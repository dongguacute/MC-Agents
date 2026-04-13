package com.mcagents.input;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mcagents.MCAgentsMod;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 从 Minecraft Wiki 通过 MediaWiki API 检索摘要文本。
 * 根据检索词自动选择 <a href="https://minecraft.wiki/">英文站</a> 或
 * <a href="https://zh.minecraft.wiki/">中文站</a>；若一侧无结果则回退另一侧。
 */
public final class MinecraftWiki {
    private static final String API_EN = "https://minecraft.wiki/api.php";
    private static final String API_ZH = "https://zh.minecraft.wiki/api.php";
    private static final int CONNECT_TIMEOUT_SEC = 10;
    private static final int REQUEST_TIMEOUT_SEC = 20;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SEC))
            .build();

    private MinecraftWiki() {
    }

    /**
     * 搜索并拉取若干条目的纯文本摘要，拼成一段供模型使用的上下文。
     *
     * @return 非空则成功；失败或无任何条目时返回 {@code null}
     */
    public static String fetchExcerptContext(String query, int maxTitles, int maxTotalChars) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String trimmed = query.trim();
        if (maxTitles <= 0) {
            maxTitles = 3;
        }
        if (maxTotalChars <= 0) {
            maxTotalChars = 6000;
        }
        try {
            String primary = pickWikiApiBase(trimmed);
            String fallback = API_EN.equals(primary) ? API_ZH : API_EN;
            String result = tryFetchExcerptContextWithApi(trimmed, maxTitles, maxTotalChars, primary);
            if (result != null) {
                return result;
            }
            if (!fallback.equals(primary)) {
                result = tryFetchExcerptContextWithApi(trimmed, maxTitles, maxTotalChars, fallback);
            }
            return result;
        } catch (Exception e) {
            MCAgentsMod.LOGGER.warn("Minecraft Wiki fetch failed: {}", e.getMessage());
            return null;
        }
    }

    private static String tryFetchExcerptContextWithApi(String query, int maxTitles, int maxTotalChars, String apiBase) {
        try {
            return fetchExcerptContextWithApi(query, maxTitles, maxTotalChars, apiBase);
        } catch (IOException e) {
            MCAgentsMod.LOGGER.debug("Minecraft Wiki {}: {}", apiBase, e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            MCAgentsMod.LOGGER.debug("Minecraft Wiki {} interrupted", apiBase);
            return null;
        }
    }

    /**
     * 拉丁字母多于 CJK 时用英文站，否则用中文站（便于「末影龙」走中文、「Ender Dragon」走英文）。
     */
    static String pickWikiApiBase(String query) {
        int cjk = 0;
        int latin = 0;
        for (int cp : query.codePoints().toArray()) {
            if (isCjkCodePoint(cp)) {
                cjk++;
            } else if (isLatinLetter(cp)) {
                latin++;
            }
        }
        if (cjk == 0 && latin == 0) {
            return API_EN;
        }
        return latin > cjk ? API_EN : API_ZH;
    }

    private static boolean isCjkCodePoint(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF)
                || (cp >= 0x3400 && cp <= 0x4DBF)
                || (cp >= 0x20000 && cp <= 0x2A6DF)
                || (cp >= 0x3040 && cp <= 0x30FF);
    }

    private static boolean isLatinLetter(int cp) {
        return (cp >= 'a' && cp <= 'z') || (cp >= 'A' && cp <= 'Z');
    }

    private static String fetchExcerptContextWithApi(String query, int maxTitles, int maxTotalChars, String apiBase)
            throws IOException, InterruptedException {
        List<String> titles = searchTitles(query, maxTitles, apiBase);
        if (titles.isEmpty()) {
            return null;
        }
        String body = fetchExtracts(titles, maxTotalChars, apiBase);
        if (body == null || body.isBlank()) {
            return null;
        }
        String label = API_ZH.equals(apiBase) ? "zh.minecraft.wiki (简体中文)" : "minecraft.wiki (English)";
        return "[Wiki source: " + label + "]\n\n" + body;
    }

    private static List<String> searchTitles(String query, int limit, String apiBase) throws IOException, InterruptedException {
        String url = apiBase + "?action=query&list=search&format=json&srlimit=" + limit
                + "&srsearch=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
        String body = httpGet(url);
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonObject queryObj = root.getAsJsonObject("query");
        if (queryObj == null || !queryObj.has("search")) {
            return List.of();
        }
        JsonArray search = queryObj.getAsJsonArray("search");
        List<String> titles = new ArrayList<>();
        for (JsonElement el : search) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            if (o.has("title")) {
                titles.add(o.get("title").getAsString());
            }
        }
        return titles;
    }

    private static String fetchExtracts(List<String> titles, int maxTotalChars, String apiBase) throws IOException, InterruptedException {
        String joined = String.join("|", titles);
        String url = apiBase + "?action=query&prop=extracts&exintro=1&explaintext=1&format=json"
                + "&titles=" + URLEncoder.encode(joined, StandardCharsets.UTF_8);
        String body = httpGet(url);
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonObject queryObj = root.getAsJsonObject("query");
        if (queryObj == null || !queryObj.has("pages")) {
            return null;
        }
        JsonObject pages = queryObj.getAsJsonObject("pages");
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, JsonElement> e : pages.entrySet()) {
            JsonObject page = e.getValue().getAsJsonObject();
            if (page.has("missing")) {
                continue;
            }
            String title = page.has("title") ? page.get("title").getAsString() : e.getKey();
            String extract = page.has("extract") ? page.get("extract").getAsString() : "";
            if (extract.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("\n\n---\n\n");
            }
            sb.append("## ").append(title).append("\n\n").append(extract.trim());
            if (sb.length() >= maxTotalChars) {
                break;
            }
        }
        String result = sb.toString().trim();
        if (result.isEmpty()) {
            return null;
        }
        if (result.length() > maxTotalChars) {
            return result.substring(0, maxTotalChars) + "\n...(truncated)";
        }
        return result;
    }

    private static String httpGet(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SEC))
                .header("Accept", "application/json")
                .header("User-Agent", "MC-Agent/1.0 (Minecraft Fabric mod; wiki client)")
                .GET()
                .build();
        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode());
        }
        return response.body();
    }

    /**
     * 启发式判断用户是否在询问 Minecraft 知识类问题（用于 ask 时自动附加 Wiki）。
     */
    public static boolean looksLikeWikiKnowledgeQuestion(String prompt) {
        if (prompt == null) {
            return false;
        }
        String t = prompt.trim();
        if (t.length() < 4) {
            return false;
        }
        String lower = t.toLowerCase(Locale.ROOT);
        if (containsAny(lower, WIKI_HINT_EN)) {
            return true;
        }
        if (containsAny(t, WIKI_HINT_ZH)) {
            return true;
        }
        if (t.length() >= 8 && (hasQuestionShape(lower, t))) {
            return true;
        }
        return false;
    }

    private static boolean hasQuestionShape(String lower, String original) {
        if (original.indexOf('？') >= 0 || original.indexOf('?') >= 0) {
            return true;
        }
        return containsAny(lower, QUESTION_EN) || containsAny(original, QUESTION_ZH);
    }

    private static boolean containsAny(String haystack, String[] needles) {
        for (String n : needles) {
            if (haystack.contains(n)) {
                return true;
            }
        }
        return false;
    }

    private static final String[] WIKI_HINT_EN = {
            "wiki", "craft", "recipe", "enchant", "drop", "mob", "spawn", "biome",
            "redstone", "village", "raid", "nether", "the end", "ender",
            "bedrock", "java edition", "snapshot", "datapack"
    };

    private static final String[] WIKI_HINT_ZH = {
            "维基", "合成", "掉落", "附魔", "生物", "红石", "版本", "更新", "机制",
            "方块", "物品", "酿造", "维度", "下界", "末地", "村民", "袭击", "进度",
            "成就", "命令", "刷怪", "农场", "生电"
    };

    private static final String[] QUESTION_EN = {
            "how ", "what ", "why ", "when ", "where ", "which ", "can i", "does ",
            "do ", "is there", "are there"
    };

    private static final String[] QUESTION_ZH = {
            "什么", "如何", "怎么", "为什么", "能否", "是不是", "有没有", "吗", "呢"
    };
}
