package com.mcagents.input;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 主线程消息发送与可翻译文本封装。
 */
final class AgentMessaging {
    static void sendToMainThread(ServerPlayer player, MutableComponent msg) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        server.execute(() -> player.sendSystemMessage(msg));
    }

    @SuppressWarnings("unused")
    static MutableComponent i18n(String key, String fallback, Object... args) {
        return Component.translatable(key, args);
    }

    private AgentMessaging() {
    }
}
