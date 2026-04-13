package com.mcagents.input;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class Recordbot {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path DATA_FILE = FabricLoader.getInstance()
            .getGameDir()
            .resolve("data")
            .resolve("agent_records.json");

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(
                        Commands.literal("agent")
                                .then(Commands.literal("record")
                                        .then(Commands.argument("bot_name", StringArgumentType.word())
                                                .then(Commands.argument("tag", StringArgumentType.word())
                                                        .executes(Recordbot::handleRecordCommand))))
                                .then(Commands.literal("botlist")
                                        .executes(Recordbot::handleBotListCommand))
                )
        );
    }

    private static int handleRecordCommand(CommandContext<CommandSourceStack> context) {
        String botName = StringArgumentType.getString(context, "bot_name");
        String tag = StringArgumentType.getString(context, "tag");

        try {
            Path parent = DATA_FILE.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            JsonArray records = readRecords();

            JsonObject record = new JsonObject();
            record.addProperty("bot_name", botName);
            record.addProperty("tag", tag);
            records.add(record);

            Files.writeString(
                    DATA_FILE,
                    GSON.toJson(records),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );

            context.getSource().sendSuccess(
                    () -> Component.translatable("command.modid.agent.record.success", botName, tag),
                    false
            );
            return 1;
        } catch (IOException e) {
            context.getSource().sendFailure(
                    Component.translatable("command.modid.agent.record.error", String.valueOf(e.getMessage()))
            );
            return 0;
        }
    }

    private static int handleBotListCommand(CommandContext<CommandSourceStack> context) {
        try {
            JsonArray records = readRecords();

            if (records.isEmpty()) {
                context.getSource().sendSuccess(
                        () -> Component.literal("当前没有已记录的 bot。"),
                        false
                );
                return 1;
            }

            context.getSource().sendSuccess(
                    () -> Component.literal("已记录的 bot 列表："),
                    false
            );

            for (JsonElement element : records) {
                if (!element.isJsonObject()) {
                    continue;
                }

                JsonObject record = element.getAsJsonObject();
                String botName = record.has("bot_name") ? record.get("bot_name").getAsString() : "unknown";
                String tag = record.has("tag") ? record.get("tag").getAsString() : "unknown";

                context.getSource().sendSuccess(
                        () -> Component.literal("- bot_name: " + botName + ", tag: " + tag),
                        false
                );
            }
            return 1;
        } catch (IOException e) {
            context.getSource().sendFailure(
                    Component.literal("读取 bot 列表失败: " + e.getMessage())
            );
            return 0;
        }
    }

    private static JsonArray readRecords() throws IOException {
        if (!Files.exists(DATA_FILE)) {
            return new JsonArray();
        }

        String content = Files.readString(DATA_FILE).trim();
        if (content.isEmpty()) {
            return new JsonArray();
        }

        try {
            return JsonParser.parseString(content).getAsJsonArray();
        } catch (Exception ignored) {
            return new JsonArray();
        }
    }
}
