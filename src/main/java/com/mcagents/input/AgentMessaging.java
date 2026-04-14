package com.mcagents.input;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;

/**
 * 主线程消息发送与可翻译文本封装。
 */
public final class AgentMessaging {
    static void sendToMainThread(ServerPlayer player, MutableComponent msg) {
        MinecraftServer server = player.createCommandSourceStack().getServer();
        if (server == null) {
            return;
        }
        server.execute(() -> player.sendSystemMessage(msg));
    }

    /**
     * 从 {@link AgentLang} 按玩家语言解析 {@code key}，再格式化为 {@link Component#literal(String)} 发出（网络侧为纯文本，不依赖客户端语言包）。
     */
    public static MutableComponent i18n(ServerPlayer player, String key, String fallback, Object... args) {
        String template = AgentLang.translate(player, key, fallback);
        return formatLiteral(template, args);
    }

    /**
     * 根据命令来源上的玩家（若有）选语言；控制台等无玩家时与 {@link AgentLang#translate(ServerPlayer, String, String)} 无玩家分支一致。
     */
    public static MutableComponent i18n(CommandSourceStack source, String key, String fallback, Object... args) {
        String template = AgentLang.translate(source, key, fallback);
        return formatLiteral(template, args);
    }

    /**
     * 无玩家上下文时：依次尝试 en_us、zh_cn 语言表，再用 {@code fallback}。
     */
    @SuppressWarnings("unused")
    public static MutableComponent i18n(String key, String fallback, Object... args) {
        String template = AgentLang.translate((ServerPlayer) null, key, fallback);
        return formatLiteral(template, args);
    }

    private static MutableComponent formatLiteral(String template, Object[] args) {
        if (args == null || args.length == 0) {
            return Component.literal(template);
        }
        return Component.literal(String.format(Locale.ROOT, template, args));
    }

    private AgentMessaging() {
    }
}
