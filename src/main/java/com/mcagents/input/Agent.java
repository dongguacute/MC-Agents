package com.mcagents.input;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * 玩家侧 AI 对话入口：配置加载、/agent ask 与 Wiki 搜索编排。
 */
public class Agent {
    public static void initializeConfig(MinecraftServer server) {
        AgentConfigLoader.initializeConfig(server);
    }

    public static void reloadConfig(MinecraftServer server) throws IOException {
        AgentConfigLoader.reloadConfig(server);
    }

    public static Path getAgentDataDirectory(MinecraftServer server) {
        return AgentConfigLoader.getAgentDataDirectory(server);
    }

    public static void resetConversation(ServerPlayer player) {
        AgentPromptHandlers.getConversationState(player).reset();
    }

    private static final int COMPOUND_TASK_LIST_MAX_CHARS = 240;

    /** 复合指令拆段后的列表展示：单行、过长截断，避免把整段提示词塞进聊天。 */
    private static String compoundTaskLineForDisplay(String segment) {
        if (segment == null) {
            return "";
        }
        String oneLine = segment.replaceAll("\\s+", " ").trim();
        if (oneLine.length() <= COMPOUND_TASK_LIST_MAX_CHARS) {
            return oneLine;
        }
        return oneLine.substring(0, COMPOUND_TASK_LIST_MAX_CHARS - 1) + "…";
    }

    public static void handleAgentPrompt(ServerPlayer player, String prompt) {
        if (prompt == null || prompt.trim().isEmpty()) {
            player.sendSystemMessage(AgentMessaging.i18n(player,"command.modid.agent.chat.prompt.empty", "请输入提示词，例如：/agent ask 帮我总结今天任务").withStyle(ChatFormatting.YELLOW));
            return;
        }

        AgentConfig currentConfig = AgentState.config;
        if (currentConfig.apiKey().isBlank()) {
            player.sendSystemMessage(AgentMessaging.i18n(player,"command.modid.agent.chat.api_key.missing", "未配置 API Key，请编辑 %s 中的 api_key", AgentConstants.CONFIG_FILE_NAME).withStyle(ChatFormatting.RED));
            return;
        }
        String safePrompt = prompt.trim();
        String runtimeSystemPrompt = AgentPromptHandlers.buildRuntimeSystemPrompt(player);
        List<String> parts = AgentPromptHandlers.splitCompoundAskPrompt(safePrompt);
        if (parts.size() == 1) {
            if (MinecraftWiki.looksLikeWikiKnowledgeQuestion(safePrompt)) {
                player.sendSystemMessage(
                        AgentMessaging.i18n(player,
                                "command.modid.agent.wiki.searching_before_ask",
                                "[%s] 资料类：先由 AI 分析并提取 Wiki 关键词，再检索，最后生成回答…",
                                AgentConstants.AGENT_DISPLAY_NAME
                        ).withStyle(ChatFormatting.DARK_GRAY)
                );
            } else {
                player.sendSystemMessage(AgentMessaging.i18n(player,"command.modid.agent.chat.requesting", "[%s] 思考中...", AgentConstants.AGENT_DISPLAY_NAME).withStyle(ChatFormatting.DARK_GRAY));
            }
        } else {
            player.sendSystemMessage(
                    AgentMessaging.i18n(player,
                            "command.modid.agent.chat.compound_split",
                            "[%s] 本条指令已拆成 %s 个子任务，将依次执行。",
                            AgentConstants.AGENT_DISPLAY_NAME,
                            parts.size()
                    ).withStyle(ChatFormatting.DARK_GRAY)
            );
            String header = String.format(Locale.ROOT,
                    AgentLang.translate(player, "command.modid.agent.chat.compound_list_header", "[%s] 子任务列表："),
                    AgentConstants.AGENT_DISPLAY_NAME);
            StringBuilder listBody = new StringBuilder(header);
            for (int i = 0; i < parts.size(); i++) {
                listBody.append('\n').append(i + 1).append(". ").append(compoundTaskLineForDisplay(parts.get(i)));
            }
            player.sendSystemMessage(Component.literal(listBody.toString()).withStyle(ChatFormatting.DARK_GRAY));
        }

        for (int i = 0; i < parts.size(); i++) {
            final String partPrompt = parts.get(i);
            final int partIndex = i;
            final int taskIndex = i + 1;
            final int taskTotal = parts.size();
            AgentPromptHandlers.enqueuePlayerAiTask(player, () -> {
                if (taskTotal > 1) {
                    AgentMessaging.sendToMainThread(
                            player,
                            AgentMessaging.i18n(player,
                                    "command.modid.agent.chat.compound_progress",
                                    "[%s] 子任务 %s/%s…",
                                    AgentConstants.AGENT_DISPLAY_NAME,
                                    taskIndex,
                                    taskTotal
                            ).withStyle(ChatFormatting.DARK_GRAY)
                    );
                }
                if (MinecraftWiki.looksLikeWikiKnowledgeQuestion(partPrompt)) {
                    if (taskTotal > 1) {
                        AgentMessaging.sendToMainThread(
                                player,
                                AgentMessaging.i18n(player,
                                        "command.modid.agent.wiki.searching_before_ask.compound",
                                        "[%s] 资料类：先分析关键词并检索 Wiki，再生成回答…",
                                        AgentConstants.AGENT_DISPLAY_NAME
                                ).withStyle(ChatFormatting.DARK_GRAY)
                        );
                    }
                } else if (taskTotal > 1) {
                    AgentMessaging.sendToMainThread(
                            player,
                            AgentMessaging.i18n(player,"command.modid.agent.chat.requesting", "[%s] 思考中...", AgentConstants.AGENT_DISPLAY_NAME).withStyle(ChatFormatting.DARK_GRAY)
                    );
                }
                String apiPrompt = AgentPromptHandlers.buildApiPromptWithWiki(player, partPrompt, currentConfig);
                if (MinecraftWiki.looksLikeWikiKnowledgeQuestion(partPrompt)) {
                    AgentMessaging.sendToMainThread(
                            player,
                            AgentMessaging.i18n(player,
                                    "command.modid.agent.wiki.generating_answer",
                                    "[%s] 正在根据检索结果生成回答…",
                                    AgentConstants.AGENT_DISPLAY_NAME
                            ).withStyle(ChatFormatting.DARK_GRAY)
                    );
                }
                ConversationPrepareResult prepareResult = AgentPromptHandlers.prepareConversation(player, apiPrompt, currentConfig.maxContextTokens(), runtimeSystemPrompt);
                if (!prepareResult.allowed()) {
                    AgentMessaging.sendToMainThread(player, prepareResult.blockMessage().withStyle(ChatFormatting.YELLOW));
                    return;
                }
                ApiResult apiResult = AgentHttpClient.callOpenAICompatibleApi(player, prepareResult.requestMessages(), currentConfig);
                AgentPromptHandlers.handleAiReply(
                        player,
                        partPrompt,
                        apiResult,
                        prepareResult.version(),
                        currentConfig.maxContextTokens(),
                        prepareResult.systemTokens(),
                        partIndex,
                        taskTotal
                );
            });
        }
    }

    /**
     * 在 Minecraft Wiki 上检索关键词，整理摘要后通过 AI 回复（不写入「用户原话」以外的额外对话语义，仍以本次检索主题为对话轮次）。
     */
    public static void handleWikiSearch(ServerPlayer player, String query) {
        if (query == null || query.trim().isEmpty()) {
            player.sendSystemMessage(AgentMessaging.i18n(player,"command.modid.agent.search.prompt.empty", "请输入搜索关键词，例如：/agent search 末影龙").withStyle(ChatFormatting.YELLOW));
            return;
        }
        AgentConfig currentConfig = AgentState.config;
        if (currentConfig.apiKey().isBlank()) {
            player.sendSystemMessage(AgentMessaging.i18n(player,"command.modid.agent.chat.api_key.missing", "未配置 API Key，请编辑 %s 中的 api_key", AgentConstants.CONFIG_FILE_NAME).withStyle(ChatFormatting.RED));
            return;
        }
        String safeQuery = query.trim();
        String runtimeSystemPrompt = AgentPromptHandlers.buildRuntimeSystemPrompt(player);

        AgentPromptHandlers.enqueuePlayerAiTask(player, () -> {
            AgentMessaging.sendToMainThread(
                    player,
                    AgentMessaging.i18n(player,
                            "command.modid.agent.wiki.keyword_extracting",
                            "[%s] 正在分析并提取 Wiki 检索关键词…",
                            AgentConstants.AGENT_DISPLAY_NAME
                    ).withStyle(ChatFormatting.DARK_GRAY)
            );
            String aiQuery = AgentPromptHandlers.extractWikiSearchKeywordsWithAi(safeQuery, currentConfig);
            String searchQuery = aiQuery.isBlank() ? safeQuery : aiQuery;
            if (searchQuery.length() > AgentConstants.MAX_WIKI_SEARCH_QUERY_CHARS) {
                searchQuery = searchQuery.substring(0, AgentConstants.MAX_WIKI_SEARCH_QUERY_CHARS).trim();
            }
            AgentMessaging.sendToMainThread(
                    player,
                    AgentMessaging.i18n(player,
                            "command.modid.agent.wiki.searching_with_query",
                            "[%s] 正在搜索 Minecraft Wiki：%s",
                            AgentConstants.AGENT_DISPLAY_NAME,
                            AgentPromptHandlers.truncateForWikiQueryDisplay(searchQuery)
                    ).withStyle(ChatFormatting.DARK_GRAY)
            );
            String excerpt = MinecraftWiki.fetchExcerptContext(searchQuery, 4, 8000);
            if (excerpt == null || excerpt.isBlank()) {
                AgentMessaging.sendToMainThread(player, AgentMessaging.i18n(player,"command.modid.agent.wiki.empty", "[%s] 未在 Wiki 找到可用摘要，请换关键词重试。", AgentConstants.AGENT_DISPLAY_NAME).withStyle(ChatFormatting.RED));
                return;
            }
            AgentMessaging.sendToMainThread(
                    player,
                    AgentMessaging.i18n(player,
                            "command.modid.agent.wiki.generating_answer",
                            "[%s] 正在根据检索结果生成回答…",
                            AgentConstants.AGENT_DISPLAY_NAME
                    ).withStyle(ChatFormatting.DARK_GRAY)
            );
            String apiPrompt = "[Minecraft Wiki search · source https://minecraft.wiki/]\n"
                    + "Search query: " + searchQuery + "\n\n"
                    + "Summarize the following excerpts concisely in the same language as the user's original search input. "
                    + "If excerpts are insufficient, say so. Mention that facts come from Minecraft Wiki when appropriate. "
                    + "Do NOT output [MC_WIKI_SEARCH] lines (ignored).\n\n"
                    + excerpt;
            ConversationPrepareResult prepareResult = AgentPromptHandlers.prepareConversation(player, apiPrompt, currentConfig.maxContextTokens(), runtimeSystemPrompt);
            if (!prepareResult.allowed()) {
                AgentMessaging.sendToMainThread(player, prepareResult.blockMessage().withStyle(ChatFormatting.YELLOW));
                return;
            }
            ApiResult apiResult = AgentHttpClient.callOpenAICompatibleApi(player, prepareResult.requestMessages(), currentConfig);
            AgentPromptHandlers.handleAiReply(
                    player,
                    safeQuery,
                    apiResult,
                    prepareResult.version(),
                    currentConfig.maxContextTokens(),
                    prepareResult.systemTokens(),
                    0,
                    1
            );
        });
    }
}
