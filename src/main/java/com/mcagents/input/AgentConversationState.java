package com.mcagents.input;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * 单玩家对话历史与 prepare/commit 逻辑。
 */
final class ConversationState {
    private final List<ChatMessage> messages = new ArrayList<>();
    private int usedTokens = 0;
    private long version = 0L;

    synchronized ConversationPrepareResult prepareRequest(ServerPlayer player, String prompt, int maxContextTokens, String systemPrompt) {
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
        int systemTokens = AgentTokenUtils.estimateTokens(systemPrompt) + AgentConstants.MESSAGE_OVERHEAD_TOKENS;
        if (systemTokens >= maxContextTokens || usedTokens + systemTokens >= maxContextTokens) {
            return new ConversationPrepareResult(
                    false,
                    null,
                    version,
                    systemTokens,
                    AgentMessaging.i18n(
                            player,
                            "command.modid.agent.chat.context.full",
                            "[%s] 上下文已满，请先执行 /agent new 新建对话。",
                            AgentConstants.AGENT_DISPLAY_NAME
                    )
            );
        }

        int requiredTokens = AgentTokenUtils.estimateTokens(prompt) + AgentConstants.MESSAGE_OVERHEAD_TOKENS;
        int remainingTokens = maxContextTokens - systemTokens - usedTokens;
        if (requiredTokens > remainingTokens) {
            return new ConversationPrepareResult(
                    false,
                    null,
                    version,
                    systemTokens,
                    AgentMessaging.i18n(
                            player,
                            "command.modid.agent.chat.context.not_enough",
                            "[%s] 当前剩余上下文为 %s tokens，本次输入预计需要 %s tokens。请执行 /agent new 新建对话。",
                            AgentConstants.AGENT_DISPLAY_NAME,
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

    synchronized ContextStatus commitTurn(String prompt, String reply, long expectedVersion, int maxContextTokens, int systemTokens) {
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

    synchronized void reset() {
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
