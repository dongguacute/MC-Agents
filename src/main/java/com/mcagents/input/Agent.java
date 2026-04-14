package com.mcagents.input;

import com.mcagents.Agent.Startbot;
import com.mcagents.MCAgentsMod;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardCopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Agent {
    private static final String AGENT_DISPLAY_NAME = "MCAGENT";
    private static final String DATA_DIR_NAME = "macagent";
    private static final String RECORD_FILE_NAME = "agent_records.json";
    private static final String CONFIG_FILE_NAME = "macagent.txt";
    private static final String CONTROL_BOT_PROMPT_RELATIVE_PATH = "src/main/java/com/mcagents/prompt/ControlBot.md";
    private static final String DEFAULT_API_URL = "https://api.openai.com/v1/chat/completions";
    private static final String DEFAULT_MODEL = "gpt-4o-mini";
    private static final String OPENROUTER_MODELS_URL = "https://openrouter.ai/api/v1/models";
    private static final int DEFAULT_MAX_CONTEXT_TOKENS = 0;
    private static final int MIN_CONTEXT_TOKENS = 512;
    private static final int MESSAGE_OVERHEAD_TOKENS = 4;
    private static final Pattern CONTROL_DIRECTIVE_LINE_PATTERN = Pattern.compile("(?im)^\\s*\\[CONTROL_BOT\\].*$");
    private static final Pattern CONTROL_DIRECTIVE_PRESENT_PATTERN = Pattern.compile("(?is)\\[CONTROL_BOT\\]\\s*(join|leave)\\s+");
    /** AI 在回复末尾声明需检索 Wiki 的指令行，例如：[MC_WIKI_SEARCH] ender dragon */
    private static final Pattern WIKI_SEARCH_DIRECTIVE_LINE_PATTERN = Pattern.compile(
            "(?i)^\\s*\\[MC_WIKI_SEARCH\\]\\s+(.+)$"
    );
    /** 资料类 ask 第一轮：模型仅输出一行 WIKI_QUERY: … 供 MediaWiki 搜索 */
    private static final Pattern WIKI_QUERY_LINE_PATTERN = Pattern.compile("(?i)^WIKI_QUERY:\\s*(.+)$");
    private static final int MAX_WIKI_SEARCH_QUERY_CHARS = 240;
    private static final int MAX_WIKI_QUERY_DISPLAY_CHARS = 96;
    private static final String WIKI_KEYWORD_EXTRACTION_SYSTEM = """
            You help search Minecraft Wiki (minecraft.wiki / zh.minecraft.wiki) via MediaWiki.
            First read the user's message, identify the main topic, then output the best 2–10 word search terms that match likely article titles.
            Output EXACTLY one line. No quotes, no markdown, no explanation, no other text.
            Format: WIKI_QUERY: <English or Chinese terms, e.g. Ender Dragon, 铁傀儡, redstone repeater>
            If the user mixes topics, pick the main one.""";
    private static final Pattern PROMPT_LEAK_PATTERN = Pattern.compile(
            "(?is)(你是\\s*minecraft\\s*服务器助手|当且仅当用户明确要求控制假人上下线时|\\[control_bot\\]|system\\s*prompt|developer\\s*message|提示词|instruction)"
    );
    /** 单条 ask 中拆成多个子任务的连接词/分隔符（多行、分号、加号等） */
    private static final Pattern COMPOUND_ASK_SPLIT = Pattern.compile(
            "\\s*(?:然后|接着|其次|另外|还有|并且|以及|顺带|顺便|之后|；|;)\\s*|\\s*\\+\\s*"
    );
    private static final int COMPOUND_ASK_MIN_SEGMENT_LEN = 4;

    private static final int HTTP_THREADS = Math.max(4, Runtime.getRuntime().availableProcessors());
    private static final ExecutorService HTTP_EXECUTOR = new ThreadPoolExecutor(
            HTTP_THREADS,
            HTTP_THREADS,
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            r -> {
                Thread t = new Thread(r, "mcagents-agent-http");
                t.setDaemon(true);
                return t;
            }
    );

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static volatile AgentConfig config = new AgentConfig(DEFAULT_API_URL, "", DEFAULT_MODEL, DEFAULT_MAX_CONTEXT_TOKENS);
    private static volatile String controlBotSystemPrompt = "";
    private static final Map<UUID, ConversationState> CONVERSATIONS = new ConcurrentHashMap<>();
    /**
     * 同一玩家上多条 ask/search 串行执行，避免并行的 prepare/commit 打乱对话状态（例如先创建假人再搜 Wiki）。
     */
    private static final ConcurrentHashMap<UUID, CompletableFuture<Void>> PLAYER_AI_TASK_CHAIN = new ConcurrentHashMap<>();

    public static void initializeConfig(MinecraftServer server) {
        try {
            boolean created = ensureConfigFileExists(server);
            reloadConfig(server);
            if (created) {
                MCAgentsMod.LOGGER.info("Created default config file: {}", getConfigFile(server).toAbsolutePath());
            }
        } catch (IOException e) {
            MCAgentsMod.LOGGER.error("Failed to initialize config file {}: {}", CONFIG_FILE_NAME, e.getMessage(), e);
        }
    }

    public static void reloadConfig(MinecraftServer server) throws IOException {
        Path configFile = getConfigFile(server);
        ensureConfigFileExists(server);
        controlBotSystemPrompt = loadControlBotPrompt(server);

        String content = Files.readString(configFile, StandardCharsets.UTF_8);
        Map<String, String> values = parseKeyValueConfig(content);

        String apiUrl = readValue(values, "api_url", DEFAULT_API_URL);
        String apiKey = readValue(values, "api_key", "");
        String configuredModel = readValue(values, "model", DEFAULT_MODEL);
        String model = normalizeToLatestDeepSeekModel(configuredModel);
        String openrouterApiKey = readValue(values, "openrouter_api_key", "");
        Integer configuredMaxContextTokens = readOptionalIntValue(values, "max_context_tokens", MIN_CONTEXT_TOKENS);
        if (configuredMaxContextTokens != null) {
            MCAgentsMod.LOGGER.warn("Ignoring max_context_tokens from config; context window is auto-detected from API only.");
        }
        if (!model.equals(configuredModel)) {
            MCAgentsMod.LOGGER.info("Normalized DeepSeek model from '{}' to latest alias '{}'", configuredModel, model);
        }
        int maxContextTokens = resolveModelContextTokens(apiKey, openrouterApiKey, model);

        config = new AgentConfig(apiUrl, apiKey, model, maxContextTokens);
        if (maxContextTokens > 0) {
            MCAgentsMod.LOGGER.info("Using auto-detected max_context_tokens={} for model={}", maxContextTokens, model);
        } else {
            MCAgentsMod.LOGGER.warn("OpenRouter has no context_length for model {}, using 'unkown' context display", model);
        }
        MCAgentsMod.LOGGER.info("Loaded config from {}", configFile.toAbsolutePath());
    }

    public static void handleAgentPrompt(ServerPlayer player, String prompt) {
        if (prompt == null || prompt.trim().isEmpty()) {
            player.sendSystemMessage(i18n("command.modid.agent.chat.prompt.empty", "请输入提示词，例如：/agent ask 帮我总结今天任务").withStyle(ChatFormatting.YELLOW));
            return;
        }

        AgentConfig currentConfig = config;
        if (currentConfig.apiKey().isBlank()) {
            player.sendSystemMessage(i18n("command.modid.agent.chat.api_key.missing", "未配置 API Key，请编辑 %s 中的 api_key", CONFIG_FILE_NAME).withStyle(ChatFormatting.RED));
            return;
        }
        String safePrompt = prompt.trim();
        String runtimeSystemPrompt = buildRuntimeSystemPrompt(player);
        List<String> parts = splitCompoundAskPrompt(safePrompt);
        if (parts.size() == 1) {
            if (MinecraftWiki.looksLikeWikiKnowledgeQuestion(safePrompt)) {
                player.sendSystemMessage(
                        i18n(
                                "command.modid.agent.wiki.searching_before_ask",
                                "[%s] 资料类：先由 AI 分析并提取 Wiki 关键词，再检索，最后生成回答…",
                                AGENT_DISPLAY_NAME
                        ).withStyle(ChatFormatting.DARK_GRAY)
                );
            } else {
                player.sendSystemMessage(i18n("command.modid.agent.chat.requesting", "[%s] 思考中...", AGENT_DISPLAY_NAME).withStyle(ChatFormatting.DARK_GRAY));
            }
        } else {
            player.sendSystemMessage(
                    i18n(
                            "command.modid.agent.chat.compound_split",
                            "[%s] 本条指令已拆成 %s 个子任务，将依次执行。",
                            AGENT_DISPLAY_NAME,
                            parts.size()
                    ).withStyle(ChatFormatting.DARK_GRAY)
            );
        }

        for (int i = 0; i < parts.size(); i++) {
            final String partPrompt = parts.get(i);
            final int partIndex = i;
            final int taskIndex = i + 1;
            final int taskTotal = parts.size();
            enqueuePlayerAiTask(player, () -> {
                if (taskTotal > 1) {
                    sendToMainThread(
                            player,
                            i18n(
                                    "command.modid.agent.chat.compound_progress",
                                    "[%s] 子任务 %s/%s…",
                                    AGENT_DISPLAY_NAME,
                                    taskIndex,
                                    taskTotal
                            ).withStyle(ChatFormatting.DARK_GRAY)
                    );
                }
                if (MinecraftWiki.looksLikeWikiKnowledgeQuestion(partPrompt)) {
                    if (taskTotal > 1) {
                        sendToMainThread(
                                player,
                                i18n(
                                        "command.modid.agent.wiki.searching_before_ask.compound",
                                        "[%s] 资料类：先分析关键词并检索 Wiki，再生成回答…",
                                        AGENT_DISPLAY_NAME
                                ).withStyle(ChatFormatting.DARK_GRAY)
                        );
                    }
                } else if (taskTotal > 1) {
                    sendToMainThread(
                            player,
                            i18n("command.modid.agent.chat.requesting", "[%s] 思考中...", AGENT_DISPLAY_NAME).withStyle(ChatFormatting.DARK_GRAY)
                    );
                }
                String apiPrompt = buildApiPromptWithWiki(player, partPrompt, currentConfig);
                if (MinecraftWiki.looksLikeWikiKnowledgeQuestion(partPrompt)) {
                    sendToMainThread(
                            player,
                            i18n(
                                    "command.modid.agent.wiki.generating_answer",
                                    "[%s] 正在根据检索结果生成回答…",
                                    AGENT_DISPLAY_NAME
                            ).withStyle(ChatFormatting.DARK_GRAY)
                    );
                }
                ConversationPrepareResult prepareResult = prepareConversation(player, apiPrompt, currentConfig.maxContextTokens(), runtimeSystemPrompt);
                if (!prepareResult.allowed()) {
                    sendToMainThread(player, prepareResult.blockMessage().withStyle(ChatFormatting.YELLOW));
                    return;
                }
                ApiResult apiResult = callOpenAICompatibleApi(player, prepareResult.requestMessages(), currentConfig);
                handleAiReply(
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
     * 将一条 ask 拆成多段（换行、连接词、分号、加号等）。无法拆分时返回仅含原文的一条列表。
     */
    private static List<String> splitCompoundAskPrompt(String raw) {
        String t = raw.trim();
        if (t.isEmpty()) {
            return new ArrayList<>();
        }
        if (t.indexOf('\n') >= 0 || t.indexOf('\r') >= 0) {
            String[] lines = t.split("\\R+");
            List<String> acc = new ArrayList<>();
            for (String line : lines) {
                String s = line.trim();
                if (s.length() >= COMPOUND_ASK_MIN_SEGMENT_LEN) {
                    acc.add(s);
                }
            }
            if (acc.size() >= 2) {
                return acc;
            }
        }
        String[] pieces = COMPOUND_ASK_SPLIT.split(t);
        List<String> out = new ArrayList<>();
        for (String p : pieces) {
            String s = p.trim();
            if (s.length() >= COMPOUND_ASK_MIN_SEGMENT_LEN) {
                out.add(s);
            }
        }
        if (out.size() >= 2) {
            return out;
        }
        return List.of(t);
    }

    /**
     * 在 Minecraft Wiki 上检索关键词，整理摘要后通过 AI 回复（不写入「用户原话」以外的额外对话语义，仍以本次检索主题为对话轮次）。
     */
    public static void handleWikiSearch(ServerPlayer player, String query) {
        if (query == null || query.trim().isEmpty()) {
            player.sendSystemMessage(i18n("command.modid.agent.search.prompt.empty", "请输入搜索关键词，例如：/agent search 末影龙").withStyle(ChatFormatting.YELLOW));
            return;
        }
        AgentConfig currentConfig = config;
        if (currentConfig.apiKey().isBlank()) {
            player.sendSystemMessage(i18n("command.modid.agent.chat.api_key.missing", "未配置 API Key，请编辑 %s 中的 api_key", CONFIG_FILE_NAME).withStyle(ChatFormatting.RED));
            return;
        }
        String safeQuery = query.trim();
        String runtimeSystemPrompt = buildRuntimeSystemPrompt(player);

        enqueuePlayerAiTask(player, () -> {
            sendToMainThread(
                    player,
                    i18n(
                            "command.modid.agent.wiki.keyword_extracting",
                            "[%s] 正在分析并提取 Wiki 检索关键词…",
                            AGENT_DISPLAY_NAME
                    ).withStyle(ChatFormatting.DARK_GRAY)
            );
            String aiQuery = extractWikiSearchKeywordsWithAi(safeQuery, currentConfig);
            String searchQuery = aiQuery.isBlank() ? safeQuery : aiQuery;
            if (searchQuery.length() > MAX_WIKI_SEARCH_QUERY_CHARS) {
                searchQuery = searchQuery.substring(0, MAX_WIKI_SEARCH_QUERY_CHARS).trim();
            }
            sendToMainThread(
                    player,
                    i18n(
                            "command.modid.agent.wiki.searching_with_query",
                            "[%s] 正在搜索 Minecraft Wiki：%s",
                            AGENT_DISPLAY_NAME,
                            truncateForWikiQueryDisplay(searchQuery)
                    ).withStyle(ChatFormatting.DARK_GRAY)
            );
            String excerpt = MinecraftWiki.fetchExcerptContext(searchQuery, 4, 8000);
            if (excerpt == null || excerpt.isBlank()) {
                sendToMainThread(player, i18n("command.modid.agent.wiki.empty", "[%s] 未在 Wiki 找到可用摘要，请换关键词重试。", AGENT_DISPLAY_NAME).withStyle(ChatFormatting.RED));
                return;
            }
            sendToMainThread(
                    player,
                    i18n(
                            "command.modid.agent.wiki.generating_answer",
                            "[%s] 正在根据检索结果生成回答…",
                            AGENT_DISPLAY_NAME
                    ).withStyle(ChatFormatting.DARK_GRAY)
            );
            String apiPrompt = "[Minecraft Wiki search · source https://minecraft.wiki/]\n"
                    + "Search query: " + searchQuery + "\n\n"
                    + "Summarize the following excerpts concisely in the same language as the user's original search input. "
                    + "If excerpts are insufficient, say so. Mention that facts come from Minecraft Wiki when appropriate. "
                    + "Do NOT output [MC_WIKI_SEARCH] lines (ignored).\n\n"
                    + excerpt;
            ConversationPrepareResult prepareResult = prepareConversation(player, apiPrompt, currentConfig.maxContextTokens(), runtimeSystemPrompt);
            if (!prepareResult.allowed()) {
                sendToMainThread(player, prepareResult.blockMessage().withStyle(ChatFormatting.YELLOW));
                return;
            }
            ApiResult apiResult = callOpenAICompatibleApi(player, prepareResult.requestMessages(), currentConfig);
            handleAiReply(
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

    /**
     * 资料类 ask：① AI 分析并提取检索关键词（独立一轮，不写入对话历史）② Wiki 搜索 ③ 将摘录与用户问题一并交给模型作答。
     */
    private static String buildApiPromptWithWiki(ServerPlayer player, String safePrompt, AgentConfig config) {
        if (!MinecraftWiki.looksLikeWikiKnowledgeQuestion(safePrompt)) {
            return safePrompt;
        }
        sendToMainThread(
                player,
                i18n("command.modid.agent.wiki.keyword_extracting", "[%s] 正在分析并提取 Wiki 检索关键词…", AGENT_DISPLAY_NAME).withStyle(ChatFormatting.DARK_GRAY)
        );
        String aiQuery = extractWikiSearchKeywordsWithAi(safePrompt, config);
        String searchQuery = aiQuery.isBlank() ? safePrompt.trim() : aiQuery;
        if (searchQuery.length() > MAX_WIKI_SEARCH_QUERY_CHARS) {
            searchQuery = searchQuery.substring(0, MAX_WIKI_SEARCH_QUERY_CHARS).trim();
        }
        sendToMainThread(
                player,
                i18n(
                        "command.modid.agent.wiki.searching_with_query",
                        "[%s] 正在搜索 Minecraft Wiki：%s",
                        AGENT_DISPLAY_NAME,
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
    private static String extractWikiSearchKeywordsWithAi(String userQuestion, AgentConfig config) {
        try {
            JsonArray messages = new JsonArray();
            JsonObject system = new JsonObject();
            system.addProperty("role", "system");
            system.addProperty("content", WIKI_KEYWORD_EXTRACTION_SYSTEM);
            messages.add(system);
            JsonObject user = new JsonObject();
            user.addProperty("role", "user");
            user.addProperty("content", userQuestion);
            messages.add(user);
            ApiResult result = callOpenAICompatibleApiFallback(messages, config);
            return parseWikiQueryLine(result.reply());
        } catch (AgentUserException e) {
            MCAgentsMod.LOGGER.warn("Wiki keyword extraction failed: {}", e.getMessage());
            return "";
        }
    }

    private static String parseWikiQueryLine(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        for (String line : raw.replace("\r\n", "\n").split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }
            Matcher m = WIKI_QUERY_LINE_PATTERN.matcher(t);
            if (m.matches()) {
                return m.group(1).trim().replace("`", "");
            }
        }
        return "";
    }

    private static void enqueuePlayerAiTask(ServerPlayer player, Runnable task) {
        UUID id = player.getUUID();
        PLAYER_AI_TASK_CHAIN.compute(id, (key, previous) -> {
            CompletableFuture<Void> prev = previous == null ? CompletableFuture.completedFuture(null) : previous;
            return prev.thenComposeAsync(
                    ignored -> CompletableFuture.runAsync(task, HTTP_EXECUTOR),
                    HTTP_EXECUTOR
            ).exceptionally(ex -> {
                Throwable root = unwrap(ex);
                if (root instanceof AgentUserException agentError) {
                    sendToMainThread(player, agentError.toComponent().withStyle(ChatFormatting.RED));
                } else {
                    sendToMainThread(player, i18n("command.modid.agent.chat.request.failed.unknown", "AI 请求失败：未知错误").withStyle(ChatFormatting.RED));
                }
                return null;
            });
        });
    }

    public static void resetConversation(ServerPlayer player) {
        getConversationState(player).reset();
    }

    /**
     * @param compoundPartIndex 复合 ask 中当前子任务下标（从 0 起）；单条 ask 为 0）
     * @param compoundPartTotal 复合 ask 子任务总数（单条 ask 为 1）
     */
    private static void handleAiReply(
            ServerPlayer player,
            String prompt,
            ApiResult result,
            long requestVersion,
            int maxContextTokens,
            int systemTokens,
            int compoundPartIndex,
            int compoundPartTotal
    ) {
        String reply = result.reply();
        boolean hasDirective = hasControlDirective(reply);
        MinecraftServer server = player.getServer();
        if (server != null) {
            server.execute(() -> Startbot.tryHandleAiDirective(player, reply));
        }
        String replyForCommit = stripWikiSearchDirectives(reply);
        String displayReply = sanitizeReplyForDisplay(reply);
        if (displayReply.isEmpty()) {
            if (hasDirective) {
                displayReply = "已执行控制指令。";
            } else {
                displayReply = "已收到响应，但无可展示文本。";
            }
        }
        sendToMainThread(
                player,
                i18n("command.modid.agent.chat.reply", "┌─ %s\n└─ %s", AGENT_DISPLAY_NAME, displayReply).withStyle(ChatFormatting.AQUA)
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
            sendToMainThread(
                    player,
                    i18n(
                            "command.modid.agent.chat.context.remaining.unknown",
                            "[%s] 上下文剩余: unkown",
                            AGENT_DISPLAY_NAME
                    ).withStyle(ChatFormatting.DARK_GRAY)
            );
            return;
        }

        int usedTokens = Math.max(0, maxContextTokens - remainingTokens);
        String progressBar = buildProgressBar(usedTokens, maxContextTokens, 24);
        String usedPercent = formatPercent(usedTokens, maxContextTokens);

        if (remainingTokens <= 0 || contextStatus.full()) {
            sendToMainThread(
                    player,
                    i18n(
                            "command.modid.agent.chat.context.remaining.full",
                            "[%s] 上下文进度 %s 已用 %s%%（%s/%s tokens）；剩余 %s tokens（已满，请使用 /agent new 新建对话）",
                            AGENT_DISPLAY_NAME,
                            progressBar,
                            usedPercent,
                            usedTokens,
                            maxContextTokens,
                            remainingTokens
                    ).withStyle(ChatFormatting.GOLD)
            );
            return;
        }
        sendToMainThread(
                player,
                i18n(
                        "command.modid.agent.chat.context.remaining",
                        "[%s] 上下文进度 %s 已用 %s%%（%s/%s tokens）；剩余 %s tokens",
                        AGENT_DISPLAY_NAME,
                        progressBar,
                        usedPercent,
                        usedTokens,
                        maxContextTokens,
                        remainingTokens
                ).withStyle(ChatFormatting.DARK_GRAY)
        );
    }

    private static void sendTaskDoneMessage(ServerPlayer player) {
        sendToMainThread(
                player,
                i18n("command.modid.agent.chat.task.done", "[%s] 对话完成", AGENT_DISPLAY_NAME).withStyle(ChatFormatting.GREEN)
        );
    }

    private static String stripWikiSearchDirectives(String reply) {
        if (reply == null || reply.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String rawLine : reply.replace("\r\n", "\n").split("\n", -1)) {
            String line = rawLine.trim();
            if (WIKI_SEARCH_DIRECTIVE_LINE_PATTERN.matcher(line).matches()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(rawLine);
        }
        return sb.toString().trim();
    }

    private static String truncateForWikiQueryDisplay(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        if (s.length() <= MAX_WIKI_QUERY_DISPLAY_CHARS) {
            return s;
        }
        return s.substring(0, MAX_WIKI_QUERY_DISPLAY_CHARS - 1) + "…";
    }

    private static ApiResult callOpenAICompatibleApi(ServerPlayer player, JsonArray requestMessages, AgentConfig currentConfig) {
        try {
            return callOpenAICompatibleApiStreaming(player, requestMessages, currentConfig);
        } catch (AgentUserException streamError) {
            if (!streamError.isHttpStatusCode(400)) {
                throw streamError;
            }
            return callOpenAICompatibleApiFallback(requestMessages, currentConfig);
        }
    }

    private static ApiResult callOpenAICompatibleApiStreaming(ServerPlayer player, JsonArray requestMessages, AgentConfig currentConfig) {
        try {
            JsonObject requestBody = buildRequestBody(requestMessages, currentConfig.model(), true);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(currentConfig.apiUrl()))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + currentConfig.apiKey())
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString(), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<java.util.stream.Stream<String>> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw AgentUserException.httpStatus(response.statusCode());
            }

            StreamState streamState = new StreamState(player);
            StringBuilder eventData = new StringBuilder();

            try (java.util.stream.Stream<String> lines = response.body()) {
                java.util.Iterator<String> iterator = lines.iterator();
                while (iterator.hasNext()) {
                    String line = iterator.next();
                    if (line == null) {
                        continue;
                    }

                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) {
                        if (consumeEventData(eventData, streamState)) {
                            break;
                        }
                        continue;
                    }

                    if (trimmed.startsWith("data:")) {
                        if (eventData.length() > 0) {
                            eventData.append('\n');
                        }
                        eventData.append(trimmed.substring(5).trim());
                    }
                }
            }

            consumeEventData(eventData, streamState);
            streamState.flushPending(true);
            String finalAnswer = streamState.answerText.toString().trim();
            if (finalAnswer.isEmpty()) {
                throw new AgentUserException("command.modid.agent.chat.request.failed.empty");
            }
            return new ApiResult(finalAnswer, streamState.totalTokens);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new AgentUserException("command.modid.agent.chat.request.failed.network", e.getMessage() == null ? "" : e.getMessage());
        }
    }

    private static ApiResult callOpenAICompatibleApiFallback(JsonArray requestMessages, AgentConfig currentConfig) {
        try {
            JsonObject requestBody = buildRequestBody(requestMessages, currentConfig.model(), false);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(currentConfig.apiUrl()))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + currentConfig.apiKey())
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString(), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw AgentUserException.httpStatus(response.statusCode());
            }

            JsonObject result = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonArray choices = result.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                throw new AgentUserException("command.modid.agent.chat.request.failed.invalid_choices");
            }

            JsonObject firstChoice = choices.get(0).getAsJsonObject();
            JsonObject message = firstChoice.getAsJsonObject("message");
            if (message == null) {
                throw new AgentUserException("command.modid.agent.chat.request.failed.invalid_message");
            }

            String content = extractText(message.get("content")).trim();
            if (content.isEmpty()) {
                throw new AgentUserException("command.modid.agent.chat.request.failed.empty");
            }
            Integer totalTokens = readUsageTotalTokens(result);
            return new ApiResult(content, totalTokens);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new AgentUserException("command.modid.agent.chat.request.failed.network", e.getMessage() == null ? "" : e.getMessage());
        }
    }

    private static JsonObject buildRequestBody(JsonArray messages, String model, boolean stream) {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", model);
        requestBody.addProperty("stream", stream);
        requestBody.add("messages", messages);
        return requestBody;
    }

    private static ConversationPrepareResult prepareConversation(ServerPlayer player, String prompt, int maxContextTokens, String systemPrompt) {
        return getConversationState(player).prepareRequest(prompt, maxContextTokens, systemPrompt);
    }

    private static ConversationState getConversationState(ServerPlayer player) {
        return CONVERSATIONS.computeIfAbsent(player.getUUID(), key -> new ConversationState());
    }

    private static boolean consumeEventData(StringBuilder eventData, StreamState streamState) {
        if (eventData.length() == 0) {
            return false;
        }
        String payload = eventData.toString().trim();
        eventData.setLength(0);

        if (payload.isEmpty()) {
            return false;
        }
        if ("[DONE]".equals(payload)) {
            return true;
        }

        try {
            JsonObject chunk = JsonParser.parseString(payload).getAsJsonObject();
            Integer totalTokens = readUsageTotalTokens(chunk);
            if (totalTokens != null) {
                streamState.totalTokens = totalTokens;
            }
            JsonArray choices = chunk.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                return false;
            }

            JsonObject firstChoice = choices.get(0).getAsJsonObject();
            JsonObject delta = firstChoice.has("delta") ? firstChoice.getAsJsonObject("delta") : null;
            JsonObject message = firstChoice.has("message") ? firstChoice.getAsJsonObject("message") : null;

            if (delta != null) {
                appendChunk(streamState, delta);
            } else if (message != null) {
                appendChunk(streamState, message);
            }
            return false;
        } catch (Exception e) {
            // 忽略无法解析的片段，继续处理后续流。
            return false;
        }
    }

    private static void appendChunk(StreamState streamState, JsonObject source) {
        String reasoningText = extractText(source.get("reasoning_content"))
                + extractText(source.get("reasoning"))
                + extractText(source.get("thinking"));
        String answerText = extractText(source.get("content"));

        String safeReasoning = sanitizeThinkingForDisplay(reasoningText);
        if (!safeReasoning.isEmpty()) {
            streamState.thinkingPending.append(safeReasoning);
            streamState.thinkingText.append(safeReasoning);
        }
        if (!answerText.isEmpty()) {
            streamState.answerPending.append(answerText);
            streamState.answerText.append(answerText);
        }

        streamState.flushPending(false);
    }

    private static String extractText(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return "";
        }
        if (element.isJsonPrimitive()) {
            return element.getAsString();
        }
        if (element.isJsonArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonElement item : element.getAsJsonArray()) {
                sb.append(extractText(item));
            }
            return sb.toString();
        }
        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            if (obj.has("text")) {
                return extractText(obj.get("text"));
            }
            if (obj.has("content")) {
                return extractText(obj.get("content"));
            }
            if (obj.has("reasoning_content")) {
                return extractText(obj.get("reasoning_content"));
            }
            if (obj.has("reasoning")) {
                return extractText(obj.get("reasoning"));
            }
        }
        return "";
    }

    private static Integer readUsageTotalTokens(JsonObject object) {
        if (object == null || !object.has("usage") || !object.get("usage").isJsonObject()) {
            return null;
        }
        JsonObject usage = object.getAsJsonObject("usage");
        if (!usage.has("total_tokens")) {
            return null;
        }
        try {
            return usage.get("total_tokens").getAsInt();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int estimateTokens(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        double tokenUnits = 0.0D;
        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (ch <= 0x7F) {
                tokenUnits += 0.25D;
            } else {
                tokenUnits += 1.0D;
            }
        }
        return Math.max(1, (int) Math.ceil(tokenUnits));
    }

    private static String getControlBotSystemPrompt() {
        return controlBotSystemPrompt == null ? "" : controlBotSystemPrompt;
    }

    private static String loadControlBotPrompt(MinecraftServer server) throws IOException {
        Path serverRoot = getServerRootDirectory(server);
        Path parent = serverRoot.getParent();
        Path grandParent = parent != null ? parent.getParent() : null;
        Path[] candidates = new Path[]{
                serverRoot.resolve(CONTROL_BOT_PROMPT_RELATIVE_PATH),
                parent != null ? parent.resolve(CONTROL_BOT_PROMPT_RELATIVE_PATH) : null,
                grandParent != null ? grandParent.resolve(CONTROL_BOT_PROMPT_RELATIVE_PATH) : null
        };
        for (Path candidate : candidates) {
            if (candidate == null || !Files.exists(candidate)) {
                continue;
            }
            String loaded = Files.readString(candidate, StandardCharsets.UTF_8).trim();
            if (!loaded.isBlank()) {
                return loaded;
            }
        }
        throw new IOException("无法加载 ControlBot 提示词文件: " + CONTROL_BOT_PROMPT_RELATIVE_PATH);
    }

    private static String buildRuntimeSystemPrompt(ServerPlayer player) {
        String basePrompt = getControlBotSystemPrompt();
        MinecraftServer server = player.getServer();
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
            Path dataFile = getAgentDataDirectory(server).resolve(RECORD_FILE_NAME);
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

    private static String sanitizeReplyForDisplay(String reply) {
        if (reply == null || reply.isBlank()) {
            return "";
        }
        String sanitized = reply.replace("\r\n", "\n").trim();
        sanitized = CONTROL_DIRECTIVE_LINE_PATTERN.matcher(sanitized).replaceAll("").trim();
        sanitized = stripWikiSearchDirectives(sanitized).trim();
        String systemPrompt = getControlBotSystemPrompt();
        if (!systemPrompt.isBlank()) {
            sanitized = sanitized.replace(systemPrompt.trim(), "").trim();
        }
        return sanitized;
    }

    private static String sanitizeThinkingForDisplay(String reasoning) {
        if (reasoning == null || reasoning.isBlank()) {
            return "";
        }
        String sanitized = reasoning.replace("\r\n", "\n").trim();
        if (sanitized.isEmpty()) {
            return "";
        }
        sanitized = CONTROL_DIRECTIVE_LINE_PATTERN.matcher(sanitized).replaceAll("").trim();
        String systemPrompt = getControlBotSystemPrompt();
        if (!systemPrompt.isBlank()) {
            sanitized = sanitized.replace(systemPrompt.trim(), "").trim();
        }
        if (PROMPT_LEAK_PATTERN.matcher(sanitized).find()) {
            return "";
        }
        return sanitized;
    }

    private static boolean hasControlDirective(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return CONTROL_DIRECTIVE_PRESENT_PATTERN.matcher(text).find();
    }

    private static void sendToMainThread(ServerPlayer player, Component msg) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        server.execute(() -> player.sendSystemMessage(msg));
    }

    private static MutableComponent i18n(String key, String fallback, Object... args) {
        return Component.translatableWithFallback(key, fallback, args);
    }

    private static Map<String, String> parseKeyValueConfig(String content) {
        Map<String, String> values = new HashMap<>();
        String[] lines = content.split("\\R");
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            int splitIndex = line.indexOf('=');
            if (splitIndex <= 0) {
                continue;
            }

            String key = line.substring(0, splitIndex).trim();
            String value = line.substring(splitIndex + 1).trim();
            values.put(key, value);
        }
        return values;
    }

    private static String readValue(Map<String, String> values, String key, String defaultValue) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value;
    }

    private static Integer readOptionalIntValue(Map<String, String> values, String key, int minValue) {
        String raw = values.get(key);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return Math.max(minValue, parsed);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static int resolveModelContextTokens(String apiKey, String openrouterApiKey, String model) throws IOException {
        String effectiveOpenRouterKey = (openrouterApiKey != null && !openrouterApiKey.isBlank()) ? openrouterApiKey : apiKey;
        if (effectiveOpenRouterKey == null || effectiveOpenRouterKey.isBlank()) {
            throw new IOException("未配置 openrouter_api_key（且 api_key 为空），无法通过 OpenRouter 检索模型总上下文");
        }
        try {
            Integer contextTokens = fetchContextTokensFromOpenRouterModels(effectiveOpenRouterKey, model);
            if (contextTokens != null) {
                return contextTokens;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("OpenRouter 模型检索被中断", e);
        } catch (Exception e) {
            throw new IOException("OpenRouter 模型检索失败: " + e.getMessage(), e);
        }
        return 0;
    }

    private static Integer fetchContextTokensFromOpenRouterModels(String openRouterApiKey, String model) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(OPENROUTER_MODELS_URL))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + openRouterApiKey)
                .header("Content-Type", "application/json; charset=utf-8")
                .GET()
                .build();
        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String body = response.body() == null ? "" : response.body();
            String brief = body.length() > 200 ? body.substring(0, 200) : body;
            throw new IOException("OpenRouter /models 请求失败，HTTP " + response.statusCode() + ": " + brief);
        }

        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonArray data = root.has("data") && root.get("data").isJsonArray() ? root.getAsJsonArray("data") : null;
        if (data == null) {
            return null;
        }

        String target = model == null ? "" : model.trim();
        String targetLower = target.toLowerCase(Locale.ROOT);
        JsonObject best = null;
        int bestScore = -1;
        for (JsonElement element : data) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject modelObj = element.getAsJsonObject();
            String id = modelObj.has("id") ? modelObj.get("id").getAsString() : "";
            String canonical = modelObj.has("canonical_slug") ? modelObj.get("canonical_slug").getAsString() : "";
            String name = modelObj.has("name") ? modelObj.get("name").getAsString() : "";
            int score = scoreModelCandidate(targetLower, id, canonical, name);
            if (score > bestScore) {
                best = modelObj;
                bestScore = score;
            }
        }
        if (best == null || bestScore < 40) {
            return null;
        }
        return extractContextTokensFromModelObject(best);
    }

    private static Integer extractContextTokensFromModelObject(JsonObject modelObj) {
        String[] candidateKeys = new String[]{
                "context_length",
                "context_window",
                "max_context_length",
                "max_context_tokens",
                "max_input_tokens",
                "max_prompt_tokens",
                "max_model_len",
                "input_token_limit",
                "context_len"
        };
        return findTokenLimitRecursively(modelObj, candidateKeys, 0);
    }

    private static int scoreModelCandidate(String target, String id, String canonical, String name) {
        String idLower = id == null ? "" : id.toLowerCase(Locale.ROOT);
        String canonicalLower = canonical == null ? "" : canonical.toLowerCase(Locale.ROOT);
        String nameLower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (matchesDeepSeekAlias(target, idLower, canonicalLower, nameLower)) {
            return 98;
        }
        if (idLower.equals(target) || canonicalLower.equals(target) || nameLower.equals(target)) {
            return 100;
        }
        if (idLower.endsWith("/" + target) || canonicalLower.endsWith("/" + target)) {
            return 95;
        }
        String normalizedTarget = normalizeModelKey(target);
        String normalizedId = normalizeModelKey(idLower);
        String normalizedCanonical = normalizeModelKey(canonicalLower);
        String normalizedName = normalizeModelKey(nameLower);
        if (normalizedId.equals(normalizedTarget) || normalizedCanonical.equals(normalizedTarget) || normalizedName.equals(normalizedTarget)) {
            return 90;
        }
        if (idLower.contains(target) || canonicalLower.contains(target) || nameLower.contains(target)) {
            return 70;
        }
        if (normalizedId.contains(normalizedTarget) || normalizedCanonical.contains(normalizedTarget) || normalizedName.contains(normalizedTarget)) {
            return 60;
        }
        return 0;
    }

    private static boolean matchesDeepSeekAlias(String target, String idLower, String canonicalLower, String nameLower) {
        if ("deepseek-reasoner".equals(target)) {
            return idLower.contains("deepseek-r1")
                    || canonicalLower.contains("deepseek-r1")
                    || nameLower.contains("deepseek-r1")
                    || idLower.contains("reasoner")
                    || canonicalLower.contains("reasoner")
                    || nameLower.contains("reasoner");
        }
        if ("deepseek-chat".equals(target)) {
            return idLower.contains("deepseek-v3")
                    || canonicalLower.contains("deepseek-v3")
                    || nameLower.contains("deepseek-v3")
                    || idLower.contains("deepseek-chat")
                    || canonicalLower.contains("deepseek-chat")
                    || nameLower.contains("deepseek-chat");
        }
        return false;
    }

    private static String normalizeModelKey(String input) {
        if (input == null) {
            return "";
        }
        return input.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String normalizeToLatestDeepSeekModel(String model) {
        if (model == null || model.isBlank()) {
            return DEFAULT_MODEL;
        }
        String lower = model.trim().toLowerCase(Locale.ROOT);
        if (!lower.contains("deepseek")) {
            return model.trim();
        }
        if (lower.contains("reasoner") || lower.contains("r1")) {
            return "deepseek-reasoner";
        }
        if (lower.contains("chat") || lower.contains("v3")) {
            return "deepseek-chat";
        }
        return model.trim();
    }

    private static Integer findTokenLimitRecursively(JsonElement element, String[] candidateKeys, int depth) {
        if (element == null || element.isJsonNull() || depth > 6) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            return parseTokenNumber(element.getAsString());
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (JsonElement item : array) {
                Integer nested = findTokenLimitRecursively(item, candidateKeys, depth + 1);
                if (nested != null) {
                    return nested;
                }
            }
            return null;
        }
        if (!element.isJsonObject()) {
            return null;
        }

        JsonObject obj = element.getAsJsonObject();
        for (String key : candidateKeys) {
            if (!obj.has(key)) {
                continue;
            }
            Integer direct = parseTokenNumber(obj.get(key).toString().replace("\"", ""));
            if (direct != null) {
                return direct;
            }
        }

        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            Integer nested = findTokenLimitRecursively(entry.getValue(), candidateKeys, depth + 1);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    private static Integer parseTokenNumber(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace("_", "");
        int multiplier = 1;
        if (normalized.endsWith("k")) {
            multiplier = 1000;
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        normalized = normalized.replaceAll("[^0-9.]", "");
        if (normalized.isEmpty()) {
            return null;
        }
        try {
            double value = Double.parseDouble(normalized);
            int tokens = (int) Math.round(value * multiplier);
            return tokens >= MIN_CONTEXT_TOKENS ? tokens : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean ensureConfigFileExists(MinecraftServer server) throws IOException {
        Path configFile = getConfigFile(server);
        if (Files.exists(configFile)) {
            return false;
        }

        for (Path legacyConfigFile : getLegacyConfigFiles(server)) {
            if (legacyConfigFile.equals(configFile) || !Files.exists(legacyConfigFile)) {
                continue;
            }

            Path parent = configFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(legacyConfigFile, configFile, StandardCopyOption.REPLACE_EXISTING);
            MCAgentsMod.LOGGER.info("Migrated config file from {} to {}", legacyConfigFile.toAbsolutePath(), configFile.toAbsolutePath());
            return false;
        }

        Path parent = configFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        String template = """
                # MC-Agent config file
                # Edit the values and run /agent reload to apply.
                api_url=%s
                api_key=
                # Optional: dedicated OpenRouter key for model context lookup.
                # If empty, will fallback to api_key.
                openrouter_api_key=
                model=%s
                """.formatted(DEFAULT_API_URL, DEFAULT_MODEL);

        Files.writeString(
                configFile,
                template,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW
        );
        return true;
    }

    public static Path getAgentDataDirectory(MinecraftServer server) {
        return getServerRootDirectory(server).resolve(DATA_DIR_NAME);
    }

    private static Path getConfigFile(MinecraftServer server) {
        return getServerRootDirectory(server).resolve(CONFIG_FILE_NAME);
    }

    private static Path getServerRootDirectory(MinecraftServer server) {
        Path worldRoot = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        Path cursor = worldRoot;

        while (cursor != null) {
            if (Files.exists(cursor.resolve("server.properties")) || Files.exists(cursor.resolve("eula.txt"))) {
                return cursor;
            }
            cursor = cursor.getParent();
        }

        Path fallback = worldRoot.getParent() != null ? worldRoot.getParent() : worldRoot;
        return fallback;
    }

    private static Path[] getLegacyConfigFiles(MinecraftServer server) {
        Path serverRoot = getServerRootDirectory(server);
        Path worldRoot = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        Path worldParent = worldRoot.getParent() != null ? worldRoot.getParent() : worldRoot;
        return new Path[]{
                serverRoot.resolve("agentsdata").resolve(CONFIG_FILE_NAME),
                worldParent.resolve(CONFIG_FILE_NAME),
                worldRoot.resolve(CONFIG_FILE_NAME)
        };
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof CompletionException && throwable.getCause() != null) {
            return throwable.getCause();
        }
        return throwable;
    }

    private static class AgentUserException extends RuntimeException {
        private final String translationKey;
        private final Object[] args;
        private final Integer httpStatusCode;

        private AgentUserException(String translationKey, Integer httpStatusCode, Object... args) {
            super(translationKey);
            this.translationKey = translationKey;
            this.args = args;
            this.httpStatusCode = httpStatusCode;
        }

        private AgentUserException(String translationKey, Object... args) {
            this(translationKey, null, args);
        }

        private static AgentUserException httpStatus(int statusCode) {
            return new AgentUserException("command.modid.agent.chat.request.failed.http", statusCode, statusCode);
        }

        private boolean isHttpStatusCode(int statusCode) {
            return httpStatusCode != null && httpStatusCode == statusCode;
        }

        private MutableComponent toComponent() {
            String fallback = switch (translationKey) {
                case "command.modid.agent.chat.request.failed.http" -> "AI 请求失败：HTTP 状态码 %s";
                case "command.modid.agent.chat.request.failed.invalid_choices" -> "AI 响应格式错误：缺少 choices 字段";
                case "command.modid.agent.chat.request.failed.invalid_message" -> "AI 响应格式错误：缺少 message 字段";
                case "command.modid.agent.chat.request.failed.empty" -> "AI 响应为空";
                case "command.modid.agent.chat.request.failed.network" -> "AI 网络请求失败：%s";
                default -> "AI 请求失败";
            };
            return i18n(translationKey, fallback, args);
        }
    }

    private static class StreamState {
        private static final int FLUSH_SIZE = 36;
        private static final long FLUSH_INTERVAL_MS = 800;

        private final ServerPlayer player;
        private final StringBuilder thinkingText = new StringBuilder();
        private final StringBuilder answerText = new StringBuilder();
        private final StringBuilder thinkingPending = new StringBuilder();
        private final StringBuilder answerPending = new StringBuilder();
        private Integer totalTokens = null;
        private long lastFlushAt = System.currentTimeMillis();

        private StreamState(ServerPlayer player) {
            this.player = player;
        }

        private void flushPending(boolean force) {
            long now = System.currentTimeMillis();
            boolean shouldFlush = force
                    || thinkingPending.length() >= FLUSH_SIZE
                    || answerPending.length() >= FLUSH_SIZE
                    || (now - lastFlushAt) >= FLUSH_INTERVAL_MS;
            if (!shouldFlush) {
                return;
            }

            if (thinkingPending.length() > 0) {
                sendToMainThread(
                        player,
                        i18n("command.modid.agent.chat.thinking", "│ %s 思考: %s", AGENT_DISPLAY_NAME, thinkingPending.toString()).withStyle(ChatFormatting.DARK_GRAY)
                );
                thinkingPending.setLength(0);
            }
            answerPending.setLength(0);
            lastFlushAt = now;
        }
    }

    private record ConversationPrepareResult(
            boolean allowed,
            JsonArray requestMessages,
            long version,
            int systemTokens,
            MutableComponent blockMessage
    ) {
    }

    private record ContextStatus(int remainingTokens, boolean full) {
    }

    private record ChatMessage(String role, String content) {
        private int tokenSize() {
            return estimateTokens(content) + MESSAGE_OVERHEAD_TOKENS;
        }
    }

    private record ApiResult(String reply, Integer totalTokens) {
    }

    private static class ConversationState {
        private final List<ChatMessage> messages = new ArrayList<>();
        private int usedTokens = 0;
        private long version = 0L;

        private synchronized ConversationPrepareResult prepareRequest(String prompt, int maxContextTokens, String systemPrompt) {
            if (maxContextTokens <= 0) {
                JsonArray payloadMessages = new JsonArray();
                JsonObject systemMessage = new JsonObject();
                systemMessage.addProperty("role", "system");
                systemMessage.addProperty("content", systemPrompt);
                payloadMessages.add(systemMessage);
                for (ChatMessage message : messages) {
                    JsonObject item = new JsonObject();
                    item.addProperty("role", message.role());
                    item.addProperty("content", message.content());
                    payloadMessages.add(item);
                }
                JsonObject currentPrompt = new JsonObject();
                currentPrompt.addProperty("role", "user");
                currentPrompt.addProperty("content", prompt);
                payloadMessages.add(currentPrompt);
                return new ConversationPrepareResult(true, payloadMessages, version, 0, null);
            }
            int systemTokens = estimateTokens(systemPrompt) + MESSAGE_OVERHEAD_TOKENS;
            if (systemTokens >= maxContextTokens || usedTokens + systemTokens >= maxContextTokens) {
                return new ConversationPrepareResult(
                        false,
                        null,
                        version,
                        systemTokens,
                        i18n(
                                "command.modid.agent.chat.context.full",
                                "[%s] 上下文已满，请先执行 /agent new 新建对话。",
                                AGENT_DISPLAY_NAME
                        )
                );
            }

            int requiredTokens = estimateTokens(prompt) + MESSAGE_OVERHEAD_TOKENS;
            int remainingTokens = maxContextTokens - systemTokens - usedTokens;
            if (requiredTokens > remainingTokens) {
                return new ConversationPrepareResult(
                        false,
                        null,
                        version,
                        systemTokens,
                        i18n(
                                "command.modid.agent.chat.context.not_enough",
                                "[%s] 当前剩余上下文为 %s tokens，本次输入预计需要 %s tokens。请执行 /agent new 新建对话。",
                                AGENT_DISPLAY_NAME,
                                remainingTokens,
                                requiredTokens
                        )
                );
            }

            JsonArray payloadMessages = new JsonArray();
            JsonObject systemMessage = new JsonObject();
            systemMessage.addProperty("role", "system");
            systemMessage.addProperty("content", systemPrompt);
            payloadMessages.add(systemMessage);
            for (ChatMessage message : messages) {
                JsonObject item = new JsonObject();
                item.addProperty("role", message.role());
                item.addProperty("content", message.content());
                payloadMessages.add(item);
            }
            JsonObject currentPrompt = new JsonObject();
            currentPrompt.addProperty("role", "user");
            currentPrompt.addProperty("content", prompt);
            payloadMessages.add(currentPrompt);
            return new ConversationPrepareResult(true, payloadMessages, version, systemTokens, null);
        }

        private synchronized ContextStatus commitTurn(String prompt, String reply, long expectedVersion, int maxContextTokens, int systemTokens) {
            if (maxContextTokens <= 0) {
                if (version == expectedVersion) {
                    appendMessage("user", prompt);
                    appendMessage("assistant", reply);
                }
                return new ContextStatus(-1, false);
            }
            if (version != expectedVersion) {
                int remainingTokens = Math.max(0, maxContextTokens - systemTokens - usedTokens);
                return new ContextStatus(remainingTokens, remainingTokens <= 0);
            }
            appendMessage("user", prompt);
            appendMessage("assistant", reply);
            int remainingTokens = Math.max(0, maxContextTokens - systemTokens - usedTokens);
            return new ContextStatus(remainingTokens, remainingTokens <= 0);
        }

        private synchronized void reset() {
            messages.clear();
            usedTokens = 0;
            version++;
        }

        private void appendMessage(String role, String content) {
            ChatMessage message = new ChatMessage(role, content == null ? "" : content);
            messages.add(message);
            usedTokens += message.tokenSize();
        }
    }

    private record AgentConfig(String apiUrl, String apiKey, String model, int maxContextTokens) {
    }
}
