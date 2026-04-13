package com.mcagents.mixin;

import com.mcagents.input.Agent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public class AgentChatMixin {
    @Shadow
    public ServerPlayer player;

    @Inject(method = "handleChat", at = @At("HEAD"), cancellable = true)
    private void mcagents$handleAgentTag(ServerboundChatPacket packet, CallbackInfo ci) {
        String chatMessage = packet.message();
        if (!chatMessage.startsWith("#agent")) {
            return;
        }

        String prompt = chatMessage.substring("#agent".length()).trim();
        if (prompt.isEmpty()) {
            player.sendSystemMessage(Component.literal("格式错误：请使用 #agent 你的提示词"));
            ci.cancel();
            return;
        }

        Agent.handleAgentPrompt(player, prompt);
        ci.cancel();
    }
}
