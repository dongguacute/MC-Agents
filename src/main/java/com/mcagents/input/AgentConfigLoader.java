package com.mcagents.input;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mcagents.MCAgentsMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 配置文件、路径与 OpenRouter 模型上下文解析。
 */
final class AgentConfigLoader {
    static void initializeConfig(MinecraftServer server) {
        try {
            boolean created = ensureConfigFileExists(server);
            reloadConfig(server);
            if (created) {
                MCAgentsMod.LOGGER.info("Created default config file: {}", getConfigFile(server).toAbsolutePath());
            }
        } catch (IOException e) {
            MCAgentsMod.LOGGER.error("Failed to initialize config file {}: {}", AgentConstants.CONFIG_FILE_NAME, e.getMessage(), e);
        }
    }

    static void reloadConfig(MinecraftServer server) throws IOException {
        Path configFile = getConfigFile(server);
        ensureConfigFileExists(server);
        AgentState.controlBotSystemPrompt = loadControlBotPrompt(server);

        String content = Files.readString(configFile, StandardCharsets.UTF_8);
        Map<String, String> values = parseKeyValueConfig(content);

        String apiUrl = readValue(values, "api_url", AgentConstants.DEFAULT_API_URL);
        String apiKey = readValue(values, "api_key", "");
        String configuredModel = readValue(values, "model", AgentConstants.DEFAULT_MODEL);
        String model = normalizeToLatestDeepSeekModel(configuredModel);
        String openrouterApiKey = readValue(values, "openrouter_api_key", "");
        Integer configuredMaxContextTokens = readOptionalIntValue(values, "max_context_tokens", AgentConstants.MIN_CONTEXT_TOKENS);
        if (configuredMaxContextTokens != null) {
            MCAgentsMod.LOGGER.warn("Ignoring max_context_tokens from config; context window is auto-detected from API only.");
        }
        if (!model.equals(configuredModel)) {
            MCAgentsMod.LOGGER.info("Normalized DeepSeek model from '{}' to latest alias '{}'", configuredModel, model);
        }
        int maxContextTokens = resolveModelContextTokens(apiKey, openrouterApiKey, model);

        AgentState.config = new AgentConfig(apiUrl, apiKey, model, maxContextTokens);
        if (maxContextTokens > 0) {
            MCAgentsMod.LOGGER.info("Using auto-detected max_context_tokens={} for model={}", maxContextTokens, model);
        } else {
            MCAgentsMod.LOGGER.warn("OpenRouter has no context_length for model {}, using 'unkown' context display", model);
        }
        MCAgentsMod.LOGGER.info("Loaded config from {}", configFile.toAbsolutePath());
    }

    static Path getAgentDataDirectory(MinecraftServer server) {
        return getModConfigDirectory(server).resolve(AgentConstants.DATA_DIR_NAME);
    }

    private static Path getModConfigDirectory(MinecraftServer server) {
        return getServerRootDirectory(server).resolve(AgentConstants.CONFIG_DIR_NAME);
    }

    private static Path getConfigFile(MinecraftServer server) {
        return getModConfigDirectory(server).resolve(AgentConstants.CONFIG_FILE_NAME);
    }

    static Path getServerRootDirectory(MinecraftServer server) {
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
                serverRoot.resolve(AgentConstants.CONFIG_FILE_NAME),
                serverRoot.resolve("agentsdata").resolve(AgentConstants.CONFIG_FILE_NAME),
                worldParent.resolve(AgentConstants.CONFIG_FILE_NAME),
                worldRoot.resolve(AgentConstants.CONFIG_FILE_NAME)
        };
    }

    private static String loadControlBotPrompt(MinecraftServer server) throws IOException {
        Path serverRoot = getServerRootDirectory(server);
        Path parent = serverRoot.getParent();
        Path grandParent = parent != null ? parent.getParent() : null;
        Path[] candidates = new Path[]{
                serverRoot.resolve(AgentConstants.CONTROL_BOT_PROMPT_RELATIVE_PATH),
                parent != null ? parent.resolve(AgentConstants.CONTROL_BOT_PROMPT_RELATIVE_PATH) : null,
                grandParent != null ? grandParent.resolve(AgentConstants.CONTROL_BOT_PROMPT_RELATIVE_PATH) : null
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
        throw new IOException("无法加载 ControlBot 提示词文件: " + AgentConstants.CONTROL_BOT_PROMPT_RELATIVE_PATH);
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
                .uri(URI.create(AgentConstants.OPENROUTER_MODELS_URL))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + openRouterApiKey)
                .header("Content-Type", "application/json; charset=utf-8")
                .GET()
                .build();
        HttpResponse<String> response = AgentState.HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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
            return AgentConstants.DEFAULT_MODEL;
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
            return tokens >= AgentConstants.MIN_CONTEXT_TOKENS ? tokens : null;
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
                """.formatted(AgentConstants.DEFAULT_API_URL, AgentConstants.DEFAULT_MODEL);

        Files.writeString(
                configFile,
                template,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW
        );
        return true;
    }

    private AgentConfigLoader() {
    }
}
