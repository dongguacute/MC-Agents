package com.mcagents.input;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mcagents.Agent.Startbot;
import com.mcagents.MCAgentsMod;
import net.minecraft.ChatFormatting;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.regex.Matcher;

/**
 * 玩家提示、Wiki 增强、AI 回复与串行任务队列。
 */
final class AgentPromptHandlers {
    static String buildRuntimeSystemPrompt(ServerPlayer player) {
        String basePrompt = AgentSanitize.getControlBotSystemPrompt();
        MinecraftServer server = player.createCommandSourceStack().getServer();
        if (server == null) {
            return basePrompt;
        }
        String recordsJson = loadAgentRecordsJson(server);
        return basePrompt
                + "\n\nCurrent bot record library (JSON):\n"
                + recordsJson
                + "\nYou MUST read this library before any control decision. Keep normal replies concise.";
    }

    private static String loadAgentRecordsJson(MinecraftServer server) {
        try {
            Path dataFile = AgentConfigLoader.getAgentDataDirectory(server).resolve(AgentConstants.RECORD_FILE_NAME);
            if (!Files.exists(dataFile)) {
                return "[]";
            }
            String content = Files.readString(dataFile, StandardCharsets.UTF_8).trim();
            if (content.isEmpty()) {
                return "[]";
            }
            JsonElement parsed = JsonParser.parseString(content);
            String normalized = parsed.toString();
            int maxChars = 5000;
            if (normalized.length() <= maxChars) {
                return normalized;
            }
            return normalized.substring(0, maxChars) + "...(truncated)";
        } catch (Exception ignored) {
            return "[]";
        }
    }

    /**
     * 将一条 ask 拆成多段（换行、连接词、分号、加号等）。无法拆分时返回仅含原文的一条列表。
     */
    static List<String> splitCompoundAskPrompt(String raw) {
        String t = raw.trim();
        if (t.isEmpty()) {
            return new ArrayList<>();
        }
        if (t.indexOf('\n') >= 0 || t.indexOf('\r') >= 0) {
            String[] lines = t.split("\\R+");
            List<String> acc = new ArrayList<>();
            for (String line : lines) {
                String s = line.trim();
                if (s.length() >= AgentConstants.COMPOUND_ASK_MIN_SEGMENT_LEN) {
                    acc.add(s);
                }
            }
            if (acc.size() >= 2) {
                return acc;
            }
        }
        String[] pieces = AgentConstants.COMPOUND_ASK_SPLIT.split(t);
        List<String> out = new ArrayList<>();
        for (String p : pieces) {
            String s = p.trim();
            if (s.length() >= AgentConstants.COMPOUND_ASK_MIN_SEGMENT_LEN) {
                out.add(s);
            }
        }
        if (out.size() >= 2) {
            return out;
        }
        return List.of(t);
    }

    /**
     * 资料类 ask：① AI 分析并提取检索关键词（独立一轮，不写入对话历史）② Wiki 搜索 ③ 将摘录与用户问题一并交给模型作答。
     */
    static String buildApiPromptWithWiki(ServerPlayer player, String safePrompt, AgentConfig config) {
        if (!MinecraftWiki.looksLikeWikiKnowledgeQuestion(safePrompt)) {
            return safePrompt;
        }
        AgentMessaging.sendToMainThread(
                player,
                AgentMessaging.i18n(player,"command.modid.agent.wiki.keyword_extracting", "[%s] 正在分析并提取 Wiki 检索关键词…", AgentConstants.AGENT_DISPLAY_NAME).withStyle(ChatFormatting.DARK_GRAY)
        );
        String aiQuery = extractWikiSearchKeywordsWithAi(safePrompt, config);
        String searchQuery = aiQuery.isBlank() ? safePrompt.trim() : aiQuery;
        if (searchQuery.length() > AgentConstants.MAX_WIKI_SEARCH_QUERY_CHARS) {
            searchQuery = searchQuery.substring(0, AgentConstants.MAX_WIKI_SEARCH_QUERY_CHARS).trim();
        }
        AgentMessaging.sendToMainThread(
                player,
                AgentMessaging.i18n(player,
                        "command.modid.agent.wiki.searching_with_query",
                        "[%s] 正在搜索 Minecraft Wiki：%s",
                        AgentConstants.AGENT_DISPLAY_NAME,
                        truncateForWikiQueryDisplay(searchQuery)
                ).withStyle(ChatFormatting.DARK_GRAY)
        );
        String excerpt = MinecraftWiki.fetchExcerptContext(searchQuery, 3, 6000);
        if (excerpt == null || excerpt.isBlank()) {
            return "[Minecraft Wiki · knowledge question · search completed with no excerpts]\n"
                    + "Step 1 (keyword extraction): AI wiki search query was: \"" + searchQuery + "\" (fallback: full user text if extraction failed). "
                    + "Step 2 (wiki search): no usable excerpts. Step 3: Answer in the user's language; say wiki had no match and avoid inventing stats.\n"
                    + "Do NOT output [MC_WIKI_SEARCH] lines (ignored).\n\n"
                    + "---\nUser question:\n"
                    + safePrompt;
        }
        return "[Minecraft Wiki · knowledge question · search completed]\n"
                + "Step 1: AI analyzed the question and chose wiki search query: \"" + searchQuery + "\". "
                + "Step 2: Excerpts below are from that wiki search (EN/zh auto). Step 3: Answer ONLY using these excerpts for factual claims; if excerpts miss the answer, say so. Same language as the user.\n"
                + "Do NOT answer from memory alone for stats/mechanics/recipes. Do NOT output [MC_WIKI_SEARCH] lines (ignored).\n\n"
                + excerpt
                + "\n\n---\nUser question (answer only after reading excerpts above):\n"
                + safePrompt;
    }

    /**
     * 独立、非流式调用：不经过会话 history，仅用于得到 WIKI_QUERY 行。
     */
    static String extractWikiSearchKeywordsWithAi(String userQuestion, AgentConfig config) {
        try {
            JsonArray messages = new JsonArray();
            JsonObject system = new JsonObject();
            system.addProperty("role", "system");
            system.addProperty("content", AgentConstants.WIKI_KEYWORD_EXTRACTION_SYSTEM);
            messages.add(system);
            JsonObject user = new JsonObject();
            user.addProperty("role", "user");
            user.addProperty("content", userQuestion);
            messages.add(user);
            ApiResult result = AgentHttpClient.callOpenAICompatibleApiFallback(messages, config);
            return parseWikiQueryLine(result.reply());
        } catch (AgentUserException e) {
            MCAgentsMod.LOGGER.warn("Wiki keyword extraction failed: {}", e.getMessage());
            return "";
        }
    }

    static String parseWikiQueryLine(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        for (String line : raw.replace("\r\n", "\n").split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }
            Matcher m = AgentConstants.WIKI_QUERY_LINE_PATTERN.matcher(t);
            if (m.matches()) {
                return m.group(1).trim().replace("`", "");
            }
        }
        return "";
    }

    static String truncateForWikiQueryDisplay(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        if (s.length() <= AgentConstants.MAX_WIKI_QUERY_DISPLAY_CHARS) {
            return s;
        }
        return s.substring(0, AgentConstants.MAX_WIKI_QUERY_DISPLAY_CHARS - 1) + "…";
    }

    static void enqueuePlayerAiTask(ServerPlayer player, Runnable task) {
        UUID id = player.getUUID();
        AgentState.PLAYER_AI_TASK_CHAIN.compute(id, (key, previous) -> {
            CompletableFuture<Void> prev = previous == null ? CompletableFuture.completedFuture(null) : previous;
            return prev.thenComposeAsync(
                    ignored -> CompletableFuture.runAsync(task, AgentState.HTTP_EXECUTOR),
                    AgentState.HTTP_EXECUTOR
            ).exceptionally(ex -> {
                Throwable root = unwrap(ex);
                if (root instanceof AgentUserException agentError) {
                    AgentMessaging.sendToMainThread(player, agentError.toComponent().withStyle(ChatFormatting.RED));
                } else {
                    AgentMessaging.sendToMainThread(player, AgentMessaging.i18n(player,"command.modid.agent.chat.request.failed.unknown", "AI 请求失败：未知错误").withStyle(ChatFormatting.RED));
                }
                return null;
            });
        });
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof CompletionException && throwable.getCause() != null) {
            return throwable.getCause();
        }
        return throwable;
    }

    /**
     * @param compoundPartIndex 复合 ask 中当前子任务下标（从 0 起）；单条 ask 为 0）
     * @param compoundPartTotal 复合 ask 子任务总数（单条 ask 为 1）
     * @param allowWikiFollowUp 若为 true 且回复中含 {@code [MC_WIKI_SEARCH]}：不向玩家展示本轮、不写入历史，先执行 Wiki 再请求模型用一轮**完整**回答；补答轮传入 false，且不再触发 Wiki 链式调用
     */
    static void handleAiReply(
            ServerPlayer player,
            String prompt,
            ApiResult result,
            long requestVersion,
            int maxContextTokens,
            int systemTokens,
            int compoundPartIndex,
            int compoundPartTotal
    ) {
        handleAiReply(player, prompt, result, requestVersion, maxContextTokens, systemTokens, compoundPartIndex, compoundPartTotal, true);
    }

    static void handleAiReply(
            ServerPlayer player,
            String prompt,
            ApiResult result,
            long requestVersion,
            int maxContextTokens,
            int systemTokens,
            int compoundPartIndex,
            int compoundPartTotal,
            boolean allowWikiFollowUp
    ) {
        String reply = result.reply();
        String thinking = result.streamingThinking();
        if (thinking == null) {
            thinking = "";
        }

        boolean hasDirective = AgentSanitize.hasControlDirective(reply);
        MinecraftServer server = player.createCommandSourceStack().getServer();
        if (server != null) {
            server.execute(() -> Startbot.tryHandleAiDirective(player, reply));
        }

        List<String> wikiQueries = AgentSanitize.extractWikiSearchQueries(reply);
        if (allowWikiFollowUp && !wikiQueries.isEmpty()) {
            List<String> capped = new ArrayList<>();
            for (String q : wikiQueries) {
                if (capped.size() >= AgentConstants.MAX_WIKI_FOLLOWUP_QUERIES_PER_REPLY) {
                    break;
                }
                capped.add(q);
            }
            enqueuePlayerAiTask(
                    player,
                    () -> handleWikiSearchFollowUp(
                            player,
                            capped,
                            prompt,
                            maxContextTokens,
                            compoundPartIndex,
                            compoundPartTotal,
                            reply
                    )
            );
            return;
        }

        if (!thinking.isBlank()) {
            String showThinking = AgentSanitize.sanitizeThinkingForDisplay(thinking);
            if (!showThinking.isBlank()) {
                AgentMessaging.sendToMainThread(
                        player,
                        AgentMessaging.i18n(player,"command.modid.agent.chat.thinking", "│ %s 思考: %s", AgentConstants.AGENT_DISPLAY_NAME, showThinking).withStyle(ChatFormatting.DARK_GRAY)
                );
            }
        }

        String replyForCommit = AgentSanitize.stripWikiSearchDirectives(reply);
        String displayReply = AgentSanitize.sanitizeReplyForDisplay(reply);
        if (displayReply.isEmpty()) {
            if (hasDirective) {
                displayReply = "已执行控制指令。";
            } else {
                displayReply = "已收到响应，但无可展示文本。";
            }
        }
        AgentMessaging.sendToMainThread(
                player,
                AgentMessaging.i18n(player,"command.modid.agent.chat.reply", "┌─ %s\n└─ %s", AgentConstants.AGENT_DISPLAY_NAME, displayReply).withStyle(ChatFormatting.AQUA)
        );

        ContextStatus contextStatus = getConversationState(player).commitTurn(prompt, replyForCommit, requestVersion, maxContextTokens, systemTokens);
        int remainingTokens = contextStatus.remainingTokens();

        boolean taskFullyDone = compoundPartTotal >= 1
                && compoundPartIndex >= 0
                && compoundPartIndex == compoundPartTotal - 1;
        if (!taskFullyDone) {
            return;
        }

        sendTaskDoneMessage(player);

        if (maxContextTokens <= 0) {
            AgentMessaging.sendToMainThread(
                    player,
                    AgentMessaging.i18n(player,
                            "command.modid.agent.chat.context.remaining.unknown",
                            "[%s] 上下文剩余: unkown",
                            AgentConstants.AGENT_DISPLAY_NAME
                    ).withStyle(ChatFormatting.DARK_GRAY)
            );
            return;
        }

        int usedTokens = Math.max(0, maxContextTokens - remainingTokens);
        String progressBar = buildProgressBar(usedTokens, maxContextTokens, 24);
        String usedPercent = formatPercent(usedTokens, maxContextTokens);

        if (remainingTokens <= 0 || contextStatus.full()) {
            AgentMessaging.sendToMainThread(
                    player,
                    AgentMessaging.i18n(player,
                            "command.modid.agent.chat.context.remaining.full",
                            "[%s] 上下文进度 %s 已用 %s%%（%s/%s tokens）；剩余 %s tokens（已满，请使用 /agent new 新建对话）",
                            AgentConstants.AGENT_DISPLAY_NAME,
                            progressBar,
                            usedPercent,
                            usedTokens,
                            maxContextTokens,
                            remainingTokens
                    ).withStyle(ChatFormatting.GOLD)
            );
            return;
        }
        AgentMessaging.sendToMainThread(
                player,
                AgentMessaging.i18n(player,
                        "command.modid.agent.chat.context.remaining",
                        "[%s] 上下文进度 %s 已用 %s%%（%s/%s tokens）；剩余 %s tokens",
                        AgentConstants.AGENT_DISPLAY_NAME,
                        progressBar,
                        usedPercent,
                        usedTokens,
                        maxContextTokens,
                        remainingTokens
                ).withStyle(ChatFormatting.DARK_GRAY)
        );
    }

    private static void sendTaskDoneMessage(ServerPlayer player) {
        AgentMessaging.sendToMainThread(
                player,
                AgentMessaging.i18n(player,"command.modid.agent.chat.task.done", "[%s] 对话完成", AgentConstants.AGENT_DISPLAY_NAME).withStyle(ChatFormatting.GREEN)
        );
    }

    private static String buildProgressBar(int usedTokens, int totalTokens, int width) {
        if (totalTokens <= 0 || width <= 0) {
            return "[unknown]";
        }
        double ratio = Math.max(0.0D, Math.min(1.0D, (double) usedTokens / (double) totalTokens));
        int filled = (int) Math.round(ratio * width);
        StringBuilder sb = new StringBuilder(width + 2);
        sb.append('[');
        for (int i = 0; i < width; i++) {
            sb.append(i < filled ? '#' : '-');
        }
        sb.append(']');
        return sb.toString();
    }

    private static String formatPercent(int usedTokens, int totalTokens) {
        if (totalTokens <= 0) {
            return "0.00";
        }
        double percent = ((double) usedTokens * 100.0D) / (double) totalTokens;
        return String.format(Locale.ROOT, "%.2f", percent);
    }

    static ConversationPrepareResult prepareConversation(ServerPlayer player, String prompt, int maxContextTokens, String systemPrompt) {
        return getConversationState(player).prepareRequest(player, prompt, maxContextTokens, systemPrompt);
    }

    static ConversationState getConversationState(ServerPlayer player) {
        return AgentState.CONVERSATIONS.computeIfAbsent(player.getUUID(), key -> new ConversationState());
    }

    /**
     * 首轮仅含 {@code [MC_WIKI_SEARCH]} 时不在此轮展示；在此检索 Wiki 后发起第二轮，由模型一次性总结并输出，并以 {@code originalUserPrompt} 提交对话历史。
     */
    private static void handleWikiSearchFollowUp(
            ServerPlayer player,
            List<String> queries,
            String originalUserPrompt,
            int maxContextTokens,
            int compoundPartIndex,
            int compoundPartTotal,
            String firstRoundReply
    ) {
        List<String> normalized = new ArrayList<>();
        for (String q : queries) {
            String t = q == null ? "" : q.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (t.length() > AgentConstants.MAX_WIKI_SEARCH_QUERY_CHARS) {
                t = t.substring(0, AgentConstants.MAX_WIKI_SEARCH_QUERY_CHARS).trim();
            }
            normalized.add(t);
        }
        if (normalized.isEmpty()) {
            return;
        }

        AgentMessaging.sendToMainThread(
                player,
                AgentMessaging.i18n(player,
                        "command.modid.agent.wiki.followup",
                        "[%s] 按模型请求检索 Minecraft Wiki…",
                        AgentConstants.AGENT_DISPLAY_NAME
                ).withStyle(ChatFormatting.DARK_GRAY)
        );

        StringBuilder excerptBlocks = new StringBuilder();
        for (String q : normalized) {
            String excerpt = MinecraftWiki.fetchExcerptContext(q, 2, 4500);
            excerptBlocks.append("\n--- Wiki search: ").append(q).append(" ---\n");
            if (excerpt == null || excerpt.isBlank()) {
                excerptBlocks.append("(no excerpts returned)\n");
            } else {
                excerptBlocks.append(excerpt).append('\n');
            }
        }

        String draft = AgentSanitize.naturalLanguageAfterToolLines(firstRoundReply);
        StringBuilder combined = new StringBuilder();
        combined.append("[MC-Agent · tool result: minecraft_wiki_search]\n");
        combined.append("The model chose wiki search before answering; the player was not shown the first assistant message.\n");
        if (!draft.isEmpty()) {
            combined.append("\nInternal draft (not shown to player):\n").append(draft).append('\n');
        }
        combined.append("\nExcerpts from Minecraft Wiki:\n").append(excerptBlocks);
        combined.append("\n---\n");
        combined.append("Instructions: Write ONE complete answer to the user's message below. Use excerpts for factual claims only; be concise. Match the user's language. ");
        combined.append("Do NOT output [MC_WIKI_SEARCH] lines. Do NOT output [CONTROL_BOT] unless still required.\n\n");
        combined.append("User message:\n").append(originalUserPrompt);

        String supplementalPrompt = combined.toString();
        String runtimeSystemPrompt = buildRuntimeSystemPrompt(player);
        AgentConfig config = AgentState.config;
        ConversationPrepareResult prepareResult = prepareConversation(player, supplementalPrompt, maxContextTokens, runtimeSystemPrompt);
        if (!prepareResult.allowed()) {
            AgentMessaging.sendToMainThread(player, prepareResult.blockMessage().withStyle(ChatFormatting.YELLOW));
            return;
        }
        ApiResult apiResult = AgentHttpClient.callOpenAICompatibleApi(player, prepareResult.requestMessages(), config);
        handleAiReply(
                player,
                originalUserPrompt,
                apiResult,
                prepareResult.version(),
                maxContextTokens,
                prepareResult.systemTokens(),
                compoundPartIndex,
                compoundPartTotal,
                false
        );
    }

    private AgentPromptHandlers() {
    }
}
