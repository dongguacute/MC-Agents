package com.mcagents.input;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Agent {
    private static final ExecutorService HTTP_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mcagents-agent-http");
        t.setDaemon(true);
        return t;
    });

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final String API_KEY = readEnv("MC_AGENT_API_KEY", "OPENAI_API_KEY");
    private static final String API_URL = readEnv("MC_AGENT_API_URL", "OPENAI_API_URL");
    private static final String MODEL = readEnv("MC_AGENT_MODEL", "OPENAI_MODEL");

    public static void handleAgentPrompt(ServerPlayer player, String prompt) {
        if (prompt == null || prompt.trim().isEmpty()) {
            player.sendSystemMessage(Component.translatable("command.modid.agent.chat.prompt.empty"));
            return;
        }

        if (API_KEY == null || API_KEY.isBlank()) {
            player.sendSystemMessage(Component.translatable("command.modid.agent.chat.api_key.missing"));
            return;
        }

        String safePrompt = prompt.trim();
        player.sendSystemMessage(Component.translatable("command.modid.agent.chat.requesting"));

        CompletableFuture
                .supplyAsync(() -> callOpenAICompatibleApi(safePrompt), HTTP_EXECUTOR)
                .thenAccept(reply -> sendToMainThread(player, Component.translatable("command.modid.agent.chat.reply", reply)))
                .exceptionally(ex -> {
                    String msg = ex.getMessage() == null ? "unknown error" : ex.getMessage();
                    sendToMainThread(player, Component.translatable("command.modid.agent.chat.request.failed", msg));
                    return null;
                });
    }

    private static String callOpenAICompatibleApi(String prompt) {
        try {
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("model", MODEL == null || MODEL.isBlank() ? "gpt-4o-mini" : MODEL);

            JsonArray messages = new JsonArray();
            JsonObject userMessage = new JsonObject();
            userMessage.addProperty("role", "user");
            userMessage.addProperty("content", prompt);
            messages.add(userMessage);
            requestBody.add("messages", messages);

            String endpoint = API_URL == null || API_URL.isBlank()
                    ? "https://api.openai.com/v1/chat/completions"
                    : API_URL;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + API_KEY)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString(), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RuntimeException("HTTP " + response.statusCode() + ": " + response.body());
            }

            JsonObject result = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonArray choices = result.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                throw new RuntimeException("响应中没有 choices 字段");
            }

            JsonObject firstChoice = choices.get(0).getAsJsonObject();
            JsonObject message = firstChoice.getAsJsonObject("message");
            if (message == null || !message.has("content")) {
                throw new RuntimeException("响应中没有 message.content 字段");
            }

            return message.get("content").getAsString().trim();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    private static void sendToMainThread(ServerPlayer player, Component msg) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        server.execute(() -> player.sendSystemMessage(msg));
    }

    private static String readEnv(String primary, String fallback) {
        String value = System.getenv(primary);
        if (value != null && !value.isBlank()) {
            return value;
        }
        return System.getenv(fallback);
    }
}
