package com.mcagents.input;

import com.mcagents.MCAgentsMod;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Agent {
    private static final String AGENT_DISPLAY_NAME = "MCAGENT";
    private static final String DATA_DIR_NAME = "macagent";
    private static final String CONFIG_FILE_NAME = "macagent.txt";
    private static final String DEFAULT_API_URL = "https://api.openai.com/v1/chat/completions";
    private static final String DEFAULT_MODEL = "gpt-4o-mini";

    private static final ExecutorService HTTP_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mcagents-agent-http");
        t.setDaemon(true);
        return t;
    });

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static volatile AgentConfig config = new AgentConfig(DEFAULT_API_URL, "", DEFAULT_MODEL);

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

        String content = Files.readString(configFile, StandardCharsets.UTF_8);
        Map<String, String> values = parseKeyValueConfig(content);

        String apiUrl = readValue(values, "api_url", DEFAULT_API_URL);
        String apiKey = readValue(values, "api_key", "");
        String model = readValue(values, "model", DEFAULT_MODEL);

        config = new AgentConfig(apiUrl, apiKey, model);
        MCAgentsMod.LOGGER.info("Loaded config from {}", configFile.toAbsolutePath());
    }

    public static void handleAgentPrompt(ServerPlayer player, String prompt) {
        if (prompt == null || prompt.trim().isEmpty()) {
            player.sendSystemMessage(Component.literal("请输入提示词，例如：#agent 帮我总结今天任务").withStyle(ChatFormatting.YELLOW));
            return;
        }

        AgentConfig currentConfig = config;
        if (currentConfig.apiKey().isBlank()) {
            player.sendSystemMessage(Component.literal("未配置 API Key，请编辑 " + CONFIG_FILE_NAME + " 中的 api_key").withStyle(ChatFormatting.RED));
            return;
        }

        String safePrompt = prompt.trim();
        player.sendSystemMessage(Component.literal("正在请求 AI...").withStyle(ChatFormatting.GRAY));

        CompletableFuture
                .supplyAsync(() -> callOpenAICompatibleApi(player, safePrompt, currentConfig), HTTP_EXECUTOR)
                .thenAccept(reply -> sendToMainThread(
                        player,
                        Component.literal(AGENT_DISPLAY_NAME + ": " + reply).withStyle(ChatFormatting.AQUA)
                ))
                .exceptionally(ex -> {
                    Throwable root = unwrap(ex);
                    if (root instanceof AgentUserException agentError) {
                        sendToMainThread(player, Component.literal(agentError.message).withStyle(ChatFormatting.RED));
                    } else {
                        sendToMainThread(player, Component.literal("AI 请求失败：未知错误").withStyle(ChatFormatting.RED));
                    }
                    return null;
                });
    }

    private static String callOpenAICompatibleApi(ServerPlayer player, String prompt, AgentConfig currentConfig) {
        try {
            return callOpenAICompatibleApiStreaming(player, prompt, currentConfig);
        } catch (AgentUserException streamError) {
            if (!streamError.message.contains("HTTP 状态码 400")) {
                throw streamError;
            }
            return callOpenAICompatibleApiFallback(prompt, currentConfig);
        }
    }

    private static String callOpenAICompatibleApiStreaming(ServerPlayer player, String prompt, AgentConfig currentConfig) {
        try {
            JsonObject requestBody = buildRequestBody(prompt, currentConfig.model(), true);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(currentConfig.apiUrl()))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + currentConfig.apiKey())
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString(), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<java.util.stream.Stream<String>> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new AgentUserException("AI 请求失败：HTTP 状态码 " + response.statusCode());
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
                throw new AgentUserException("AI 响应为空");
            }
            return finalAnswer;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new AgentUserException("AI 网络请求失败：" + (e.getMessage() == null ? "" : e.getMessage()));
        }
    }

    private static String callOpenAICompatibleApiFallback(String prompt, AgentConfig currentConfig) {
        try {
            JsonObject requestBody = buildRequestBody(prompt, currentConfig.model(), false);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(currentConfig.apiUrl()))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + currentConfig.apiKey())
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString(), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new AgentUserException("AI 请求失败：HTTP 状态码 " + response.statusCode());
            }

            JsonObject result = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonArray choices = result.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                throw new AgentUserException("AI 响应格式错误：缺少 choices 字段");
            }

            JsonObject firstChoice = choices.get(0).getAsJsonObject();
            JsonObject message = firstChoice.getAsJsonObject("message");
            if (message == null) {
                throw new AgentUserException("AI 响应格式错误：缺少 message 字段");
            }

            String content = extractText(message.get("content")).trim();
            if (content.isEmpty()) {
                throw new AgentUserException("AI 响应为空");
            }
            return content;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new AgentUserException("AI 网络请求失败：" + (e.getMessage() == null ? "" : e.getMessage()));
        }
    }

    private static JsonObject buildRequestBody(String prompt, String model, boolean stream) {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", model);
        requestBody.addProperty("stream", stream);

        JsonArray messages = new JsonArray();
        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", prompt);
        messages.add(userMessage);
        requestBody.add("messages", messages);
        return requestBody;
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

        if (!reasoningText.isEmpty()) {
            streamState.thinkingPending.append(reasoningText);
            streamState.thinkingText.append(reasoningText);
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

    private static void sendToMainThread(ServerPlayer player, Component msg) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        server.execute(() -> player.sendSystemMessage(msg));
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
        private final String message;

        private AgentUserException(String message) {
            super(message);
            this.message = message;
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
        private long lastFlushAt = System.currentTimeMillis();

        private StreamState(ServerPlayer player) {
            this.player = player;
        }

        private void flushPending(boolean force) {
            long now = System.currentTimeMillis();
            boolean shouldFlush = force
                    || thinkingPending.length() >= FLUSH_SIZE
                    || (now - lastFlushAt) >= FLUSH_INTERVAL_MS;
            if (!shouldFlush) {
                return;
            }

            if (thinkingPending.length() > 0) {
                sendToMainThread(
                        player,
                        Component.literal(AGENT_DISPLAY_NAME + "[思考]: " + thinkingPending).withStyle(ChatFormatting.DARK_GRAY)
                );
                thinkingPending.setLength(0);
            }
            answerPending.setLength(0);
            lastFlushAt = now;
        }
    }

    private record AgentConfig(String apiUrl, String apiKey, String model) {
    }
}
