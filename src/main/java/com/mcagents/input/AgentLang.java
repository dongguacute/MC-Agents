package com.mcagents.input;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mcagents.MCAgentsMod;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 从模组 JAR 内 {@code assets/mc-agents/lang/*.json} 加载文案，供服务端按玩家语言解析键。
 */
public final class AgentLang {
    private static final Map<String, Map<String, String>> BY_LOCALE = new HashMap<>();
    private static volatile boolean loaded;

    private AgentLang() {
    }

    public static void init() {
        if (loaded) {
            return;
        }
        synchronized (AgentLang.class) {
            if (loaded) {
                return;
            }
            loadFile("en_us");
            loadFile("zh_cn");
            loaded = true;
        }
    }

    /**
     * 按 {@code player} 客户端语言选表；无玩家时依次尝试 en_us、zh_cn；均无则返回 {@code fallback}。
     */
    static String translate(ServerPlayer player, String key, String fallback) {
        init();
        if (player != null) {
            try {
                String loc = normalizeLocale(player.clientInformation().language());
                String v = get(loc, key);
                if (v != null) {
                    return v;
                }
            } catch (Throwable ignored) {
            }
        }
        String v = get("en_us", key);
        if (v != null) {
            return v;
        }
        v = get("zh_cn", key);
        if (v != null) {
            return v;
        }
        return fallback;
    }

    static String translate(CommandSourceStack source, String key, String fallback) {
        if (source != null) {
            ServerPlayer p = source.getPlayer();
            if (p != null) {
                return translate(p, key, fallback);
            }
        }
        return translate((ServerPlayer) null, key, fallback);
    }

    private static String normalizeLocale(String raw) {
        if (raw == null || raw.isBlank()) {
            return "en_us";
        }
        String t = raw.toLowerCase(Locale.ROOT).replace('-', '_');
        if (BY_LOCALE.containsKey(t)) {
            return t;
        }
        if (t.startsWith("zh")) {
            return "zh_cn";
        }
        return "en_us";
    }

    private static String get(String locale, String key) {
        Map<String, String> m = BY_LOCALE.get(locale);
        return m == null ? null : m.get(key);
    }

    private static void loadFile(String locale) {
        String path = "/assets/mc-agents/lang/" + locale + ".json";
        try (InputStream in = MCAgentsMod.class.getResourceAsStream(path)) {
            if (in == null) {
                MCAgentsMod.LOGGER.warn("MC-Agents: missing language file {}", path);
                return;
            }
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String, String> out = new HashMap<>();
            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                if (e.getValue().isJsonPrimitive()) {
                    out.put(e.getKey(), e.getValue().getAsString());
                }
            }
            BY_LOCALE.put(locale, out);
        } catch (Exception e) {
            MCAgentsMod.LOGGER.warn("MC-Agents: failed to load language {}: {}", locale, e.getMessage());
        }
    }
}
