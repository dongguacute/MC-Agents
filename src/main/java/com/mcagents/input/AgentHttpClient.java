package com.mcagents.input;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.RequestOptions;
import com.openai.core.http.StreamResponse;
import com.openai.errors.OpenAIException;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessage;
import net.minecraft.server.level.ServerPlayer;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * 通过 OpenAI 官方 Java SDK 调用 Chat Completions（流式与非流式），自定义 baseUrl 时兼容各类 OpenAI 兼容 API。
 */
final class AgentHttpClient {
    private static final Object CLIENT_LOCK = new Object();
    private static volatile String cachedClientKey = "";
    private static volatile OpenAIClient cachedClient;

    static ApiResult callOpenAICompatibleApi(ServerPlayer player, JsonArray requestMessages, AgentConfig currentConfig) {
        try {
            return callOpenAICompatibleApiStreaming(requestMessages, currentConfig);
        } catch (AgentUserException streamError) {
            if (!streamError.isHttpStatusCode(400)) {
                throw streamError;
            }
            return callOpenAICompatibleApiFallback(requestMessages, currentConfig);
        }
    }

    static ApiResult callOpenAICompatibleApiFallback(JsonArray requestMessages, AgentConfig currentConfig) {
        try {
            OpenAIClient client = clientFor(currentConfig);
            ChatCompletionCreateParams params = buildChatParams(requestMessages, currentConfig.model());
            RequestOptions requestOptions = RequestOptions.builder()
                    .timeout(Duration.ofSeconds(60))
                    .build();
            ChatCompletion result = client.chat().completions().create(params, requestOptions);
            if (result.choices() == null || result.choices().isEmpty()) {
                throw new AgentUserException("command.modid.agent.chat.request.failed.invalid_choices");
            }
            ChatCompletionMessage message = result.choices().get(0).message();
            if (message == null) {
                throw new AgentUserException("command.modid.agent.chat.request.failed.invalid_message");
            }
            String content = message.content().map(String::trim).orElse("");
            if (content.isEmpty()) {
                content = extractTextFromMessageExtras(message);
            }
            if (content.isEmpty()) {
                throw new AgentUserException("command.modid.agent.chat.request.failed.empty");
            }
            Integer totalTokens = result.usage().map(u -> (int) Math.min(u.totalTokens(), Integer.MAX_VALUE)).orElse(null);
            return new ApiResult(content, totalTokens, "");
        } catch (AgentUserException e) {
            throw e;
        } catch (OpenAIServiceException e) {
            throw AgentUserException.httpStatus(e.statusCode());
        } catch (OpenAIException e) {
            throw new AgentUserException("command.modid.agent.chat.request.failed.network", e.getMessage() == null ? "" : e.getMessage());
        }
    }

    private static ApiResult callOpenAICompatibleApiStreaming(JsonArray requestMessages, AgentConfig currentConfig) {
        try {
            OpenAIClient client = clientFor(currentConfig);
            ChatCompletionCreateParams params = buildChatParams(requestMessages, currentConfig.model());
            RequestOptions requestOptions = RequestOptions.builder()
                    .timeout(Duration.ofSeconds(60))
                    .build();
            AgentStreamState streamState = new AgentStreamState();
            try (StreamResponse<ChatCompletionChunk> streamResponse = client.chat().completions().createStreaming(params, requestOptions)) {
                streamResponse.stream().forEach(chunk -> accumulateStreamChunk(chunk, streamState));
            }
            streamState.flushPending(true);
            String finalAnswer = streamState.answerText.toString().trim();
            if (finalAnswer.isEmpty()) {
                throw new AgentUserException("command.modid.agent.chat.request.failed.empty");
            }
            return new ApiResult(finalAnswer, streamState.totalTokens, streamState.thinkingText.toString());
        } catch (AgentUserException e) {
            throw e;
        } catch (OpenAIServiceException e) {
            throw AgentUserException.httpStatus(e.statusCode());
        } catch (OpenAIException e) {
            throw new AgentUserException("command.modid.agent.chat.request.failed.network", e.getMessage() == null ? "" : e.getMessage());
        }
    }

    private static void accumulateStreamChunk(ChatCompletionChunk chunk, AgentStreamState streamState) {
        if (chunk.usage().isPresent()) {
            long total = chunk.usage().get().totalTokens();
            streamState.totalTokens = (int) Math.min(total, Integer.MAX_VALUE);
        }
        if (chunk.choices() == null || chunk.choices().isEmpty()) {
            return;
        }
        ChatCompletionChunk.Choice first = chunk.choices().get(0);
        ChatCompletionChunk.Choice.Delta delta = first.delta();
        if (delta != null) {
            String answer = delta.content().orElse("");
            String reasoning = extraReasoningFromDelta(delta);
            appendChunkParts(streamState, reasoning, answer);
        }
    }

    private static String extraReasoningFromDelta(ChatCompletionChunk.Choice.Delta delta) {
        Map<String, com.openai.core.JsonValue> extra = delta._additionalProperties();
        if (extra == null || extra.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String key : new String[]{"reasoning_content", "reasoning", "thinking"}) {
            com.openai.core.JsonValue jv = extra.get(key);
            if (jv != null) {
                sb.append(jsonValueToPlainString(jv));
            }
        }
        return sb.toString();
    }

    private static String jsonValueToPlainString(com.openai.core.JsonValue v) {
        if (v == null) {
            return "";
        }
        try {
            String s = v.convert(String.class);
            return s == null ? "" : s;
        } catch (Exception ignored) {
        }
        try {
            Object o = v.convert(Object.class);
            return o == null ? "" : String.valueOf(o);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String extractTextFromMessageExtras(ChatCompletionMessage message) {
        Map<String, com.openai.core.JsonValue> extra = message._additionalProperties();
        if (extra == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String key : new String[]{"reasoning_content", "reasoning", "thinking", "content"}) {
            com.openai.core.JsonValue jv = extra.get(key);
            if (jv != null) {
                sb.append(jsonValueToPlainString(jv));
            }
        }
        return sb.toString().trim();
    }

    private static void appendChunkParts(AgentStreamState streamState, String reasoningText, String answerText) {
        String safeReasoning = AgentSanitize.sanitizeThinkingForDisplay(reasoningText);
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

    private static ChatCompletionCreateParams buildChatParams(JsonArray messages, String model) {
        ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder().model(model);
        for (JsonElement el : messages) {
            if (el == null || !el.isJsonObject()) {
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            String role = o.has("role") ? o.get("role").getAsString() : "user";
            String content = extractText(o.get("content"));
            String r = role.toLowerCase(Locale.ROOT);
            switch (r) {
                case "system" -> builder.addSystemMessage(content);
                case "developer" -> builder.addDeveloperMessage(content);
                case "assistant" -> builder.addAssistantMessage(content);
                case "user" -> builder.addUserMessage(content);
                default -> builder.addUserMessage(content);
            }
        }
        return builder.build();
    }

    private static String normalizeOpenAIBaseUrl(String apiUrl) {
        String u = apiUrl == null ? "" : apiUrl.trim();
        if (u.isEmpty()) {
            return "https://api.openai.com/v1";
        }
        String lower = u.toLowerCase(Locale.ROOT);
        String suffix = "/chat/completions";
        if (lower.endsWith(suffix)) {
            u = u.substring(0, u.length() - suffix.length());
        }
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u.isEmpty() ? "https://api.openai.com/v1" : u;
    }

    private static OpenAIClient clientFor(AgentConfig config) {
        String base = normalizeOpenAIBaseUrl(config.apiUrl());
        String key = base + '\0' + config.apiKey();
        if (key.equals(cachedClientKey) && cachedClient != null) {
            return cachedClient;
        }
        synchronized (CLIENT_LOCK) {
            if (key.equals(cachedClientKey) && cachedClient != null) {
                return cachedClient;
            }
            cachedClientKey = key;
            cachedClient = OpenAIOkHttpClient.builder()
                    .baseUrl(base)
                    .apiKey(config.apiKey())
                    .streamHandlerExecutor(AgentState.HTTP_EXECUTOR)
                    .build();
            return cachedClient;
        }
    }

    static String extractText(JsonElement element) {
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

    private AgentHttpClient() {
    }
}

/**
 * 流式 SSE 解析时的缓冲与「思考」展示节奏。
 */
final class AgentStreamState {
    private static final int FLUSH_SIZE = 36;
    private static final long FLUSH_INTERVAL_MS = 800;

    final StringBuilder thinkingText = new StringBuilder();
    final StringBuilder answerText = new StringBuilder();
    final StringBuilder thinkingPending = new StringBuilder();
    final StringBuilder answerPending = new StringBuilder();
    Integer totalTokens = null;
    private long lastFlushAt = System.currentTimeMillis();

    AgentStreamState() {
    }

    void flushPending(boolean force) {
        long now = System.currentTimeMillis();
        boolean shouldFlush = force
                || thinkingPending.length() >= FLUSH_SIZE
                || answerPending.length() >= FLUSH_SIZE
                || (now - lastFlushAt) >= FLUSH_INTERVAL_MS;
        if (!shouldFlush) {
            return;
        }

        if (thinkingPending.length() > 0) {
            // 不在流式过程中向玩家展示；完整思考文本在请求结束后由 ApiResult.streamingThinking 返回，由 handleAiReply 统一决定是否展示。
            thinkingPending.setLength(0);
        }
        answerPending.setLength(0);
        lastFlushAt = now;
    }
}
