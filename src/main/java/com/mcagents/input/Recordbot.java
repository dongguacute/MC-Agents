package com.mcagents.input;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mcagents.util.CommandSourceFeedback;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

public class Recordbot {
    private static final String RECORD_FILE_NAME = "agent_records.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FB_RECORD_SUCCESS = "已记录: bot_name=%s, tag=%s";
    private static final String FB_RECORD_ERROR = "写入本地 data 文件失败: %s";
    private static final String FB_REMOVE_SUCCESS = "已删除 %s 条记录: bot_name=%s, tag=%s";
    private static final String FB_REMOVE_NONE = "未找到匹配记录: bot_name=%s, tag=%s";
    private static final String FB_REMOVE_BY_NAME_SUCCESS = "已删除 %s 条 bot_name=%s 的记录";
    private static final String FB_REMOVE_BY_NAME_NONE = "未找到 bot_name=%s 的记录";
    private static final String FB_REMOVE_ALL_SUCCESS = "已删除全部记录，共 %s 条";
    private static final String FB_REMOVE_ALL_EMPTY = "当前没有可删除的记录。";
    private static final String FB_BOTLIST_EMPTY = "当前没有已记录的 bot。";
    private static final String FB_BOTLIST_HEADER = "已记录的 bot 列表：";
    private static final String FB_BOTLIST_ITEM = "- bot_name: %s, tag: %s";
    private static final String FB_BOTLIST_ERROR = "读取 bot 列表失败: %s";
    private static final String FB_RELOAD_SUCCESS = "Agent 配置已重载";
    private static final String FB_RELOAD_ERROR = "重载 Agent 配置失败: %s";
    private static final String FB_ASK_PLAYER_ONLY = "该命令只能由玩家执行。";
    private static final String FB_NEW_SUCCESS = "已新建对话，上下文已清空。";

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(
                        Commands.literal("agent")
                                .then(Commands.literal("record")
                                        .then(Commands.literal("remove")
                                                .then(Commands.literal("all")
                                                        .executes(Recordbot::handleRemoveAllRecordsCommand))
                                                .then(Commands.argument("bot_name", StringArgumentType.word())
                                                        .executes(Recordbot::handleRemoveRecordByBotNameCommand)
                                                        .then(Commands.argument("tag", StringArgumentType.greedyString())
                                                                .executes(Recordbot::handleRemoveRecordCommand))))
                                        .then(Commands.argument("bot_name", StringArgumentType.word())
                                                .then(Commands.argument("tag", StringArgumentType.greedyString())
                                                        .executes(Recordbot::handleRecordCommand))))
                                .then(Commands.literal("botlist")
                                        .executes(Recordbot::handleBotListCommand))
                                .then(Commands.literal("ask")
                                        .then(Commands.argument("prompt", StringArgumentType.greedyString())
                                                .executes(Recordbot::handleAskCommand)))
                                .then(Commands.literal("search")
                                        .then(Commands.argument("query", StringArgumentType.greedyString())
                                                .executes(Recordbot::handleSearchCommand)))
                                .then(Commands.literal("new")
                                        .executes(Recordbot::handleNewCommand))
                                .then(Commands.literal("reload")
                                        .requires(source -> source.hasPermission(2))
                                        .executes(Recordbot::handleReloadCommand))
                                .then(Commands.argument("prompt", StringArgumentType.greedyString())
                                        .executes(Recordbot::handleAskCommand))
                )
        );
    }

    private static int handleRecordCommand(CommandContext<CommandSourceStack> context) {
        String botName = StringArgumentType.getString(context, "bot_name");
        String tag = StringArgumentType.getString(context, "tag");

        try {
            Path dataFile = getDataFile(context.getSource().getServer());
            Path parent = dataFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            JsonArray records = readRecords(dataFile);

            JsonObject record = new JsonObject();
            record.addProperty("bot_name", botName);
            record.addProperty("tag", tag);
            records.add(record);

            writeRecords(dataFile, records);

            CommandSourceFeedback.sendSuccess(
                    context.getSource(),
                    i18n("command.modid.agent.record.success", FB_RECORD_SUCCESS, botName, tag),
                    false
            );
            return 1;
        } catch (IOException e) {
            context.getSource().sendFailure(
                    i18n("command.modid.agent.record.error", FB_RECORD_ERROR, e.getMessage())
            );
            return 0;
        }
    }

    private static int handleRemoveRecordCommand(CommandContext<CommandSourceStack> context) {
        String botName = StringArgumentType.getString(context, "bot_name");
        String tag = StringArgumentType.getString(context, "tag");

        try {
            Path dataFile = getDataFile(context.getSource().getServer());
            JsonArray records = readRecords(dataFile);
            JsonArray updatedRecords = new JsonArray();
            int removedCount = 0;

            for (JsonElement element : records) {
                if (!element.isJsonObject()) {
                    updatedRecords.add(element);
                    continue;
                }

                JsonObject record = element.getAsJsonObject();
                String currentBotName = record.has("bot_name") ? record.get("bot_name").getAsString() : "";
                String currentTag = record.has("tag") ? record.get("tag").getAsString() : "";
                if (botName.equals(currentBotName) && tag.equals(currentTag)) {
                    removedCount++;
                    continue;
                }
                updatedRecords.add(record);
            }

            if (removedCount == 0) {
                context.getSource().sendFailure(
                        i18n("command.modid.agent.record.remove.none", FB_REMOVE_NONE, botName, tag)
                );
                return 0;
            }

            writeRecords(dataFile, updatedRecords);
            int finalRemovedCount = removedCount;
            CommandSourceFeedback.sendSuccess(
                    context.getSource(),
                    i18n("command.modid.agent.record.remove.success", FB_REMOVE_SUCCESS, finalRemovedCount, botName, tag),
                    false
            );
            return 1;
        } catch (IOException e) {
            context.getSource().sendFailure(
                    i18n("command.modid.agent.record.error", FB_RECORD_ERROR, e.getMessage())
            );
            return 0;
        }
    }

    private static int handleRemoveRecordByBotNameCommand(CommandContext<CommandSourceStack> context) {
        String botName = StringArgumentType.getString(context, "bot_name");

        try {
            Path dataFile = getDataFile(context.getSource().getServer());
            JsonArray records = readRecords(dataFile);
            JsonArray updatedRecords = new JsonArray();
            int removedCount = 0;

            for (JsonElement element : records) {
                if (!element.isJsonObject()) {
                    updatedRecords.add(element);
                    continue;
                }

                JsonObject record = element.getAsJsonObject();
                String currentBotName = record.has("bot_name") ? record.get("bot_name").getAsString() : "";
                if (botName.equals(currentBotName)) {
                    removedCount++;
                    continue;
                }
                updatedRecords.add(record);
            }

            if (removedCount == 0) {
                context.getSource().sendFailure(
                        i18n("command.modid.agent.record.remove.by_name.none", FB_REMOVE_BY_NAME_NONE, botName)
                );
                return 0;
            }

            writeRecords(dataFile, updatedRecords);
            int finalRemovedCount = removedCount;
            CommandSourceFeedback.sendSuccess(
                    context.getSource(),
                    i18n("command.modid.agent.record.remove.by_name.success", FB_REMOVE_BY_NAME_SUCCESS, finalRemovedCount, botName),
                    false
            );
            return 1;
        } catch (IOException e) {
            context.getSource().sendFailure(
                    i18n("command.modid.agent.record.error", FB_RECORD_ERROR, e.getMessage())
            );
            return 0;
        }
    }

    private static int handleRemoveAllRecordsCommand(CommandContext<CommandSourceStack> context) {
        try {
            Path dataFile = getDataFile(context.getSource().getServer());
            JsonArray records = readRecords(dataFile);
            int removedCount = records.size();

            if (removedCount == 0) {
                CommandSourceFeedback.sendSuccess(
                        context.getSource(),
                        i18n("command.modid.agent.record.remove.all.empty", FB_REMOVE_ALL_EMPTY),
                        false
                );
                return 1;
            }

            writeRecords(dataFile, new JsonArray());
            int finalRemovedCount = removedCount;
            CommandSourceFeedback.sendSuccess(
                    context.getSource(),
                    i18n("command.modid.agent.record.remove.all.success", FB_REMOVE_ALL_SUCCESS, finalRemovedCount),
                    false
            );
            return 1;
        } catch (IOException e) {
            context.getSource().sendFailure(
                    i18n("command.modid.agent.record.error", FB_RECORD_ERROR, e.getMessage())
            );
            return 0;
        }
    }

    private static int handleBotListCommand(CommandContext<CommandSourceStack> context) {
        try {
            JsonArray records = readRecords(getDataFile(context.getSource().getServer()));

            if (records.isEmpty()) {
                CommandSourceFeedback.sendSuccess(
                        context.getSource(),
                        i18n("command.modid.agent.botlist.empty", FB_BOTLIST_EMPTY),
                        false
                );
                return 1;
            }

            CommandSourceFeedback.sendSuccess(
                    context.getSource(),
                    i18n("command.modid.agent.botlist.header", FB_BOTLIST_HEADER),
                    false
            );

            for (JsonElement element : records) {
                if (!element.isJsonObject()) {
                    continue;
                }

                JsonObject record = element.getAsJsonObject();
                String botName = record.has("bot_name") ? record.get("bot_name").getAsString() : "unknown";
                String tag = record.has("tag") ? record.get("tag").getAsString() : "unknown";

                CommandSourceFeedback.sendSuccess(
                        context.getSource(),
                        i18n("command.modid.agent.botlist.item", FB_BOTLIST_ITEM, botName, tag),
                        false
                );
            }
            return 1;
        } catch (IOException e) {
            context.getSource().sendFailure(
                    i18n("command.modid.agent.botlist.error", FB_BOTLIST_ERROR, e.getMessage())
            );
            return 0;
        }
    }

    private static JsonArray readRecords(Path dataFile) throws IOException {
        if (!Files.exists(dataFile)) {
            return new JsonArray();
        }

        String content = Files.readString(dataFile).trim();
        if (content.isEmpty()) {
            return new JsonArray();
        }

        try {
            return JsonParser.parseString(content).getAsJsonArray();
        } catch (Exception ignored) {
            return new JsonArray();
        }
    }

    private static void writeRecords(Path dataFile, JsonArray records) throws IOException {
        Files.writeString(
                dataFile,
                GSON.toJson(records),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );
    }

    private static int handleReloadCommand(CommandContext<CommandSourceStack> context) {
        try {
            Agent.reloadConfig(context.getSource().getServer());
            CommandSourceFeedback.sendSuccess(
                    context.getSource(),
                    i18n("command.modid.agent.reload.success", FB_RELOAD_SUCCESS),
                    true
            );
            return 1;
        } catch (IOException e) {
            context.getSource().sendFailure(
                    i18n("command.modid.agent.reload.error", FB_RELOAD_ERROR, e.getMessage())
            );
            return 0;
        }
    }

    private static int handleAskCommand(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(i18n("command.modid.agent.chat.ask.player_only", FB_ASK_PLAYER_ONLY));
            return 0;
        }

        String prompt = StringArgumentType.getString(context, "prompt");
        Agent.handleAgentPrompt(player, prompt);
        return 1;
    }

    private static int handleSearchCommand(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(i18n("command.modid.agent.chat.ask.player_only", FB_ASK_PLAYER_ONLY));
            return 0;
        }

        String query = StringArgumentType.getString(context, "query");
        Agent.handleWikiSearch(player, query);
        return 1;
    }

    private static int handleNewCommand(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(i18n("command.modid.agent.chat.ask.player_only", FB_ASK_PLAYER_ONLY));
            return 0;
        }
        Agent.resetConversation(player);
        CommandSourceFeedback.sendSuccess(
                context.getSource(),
                i18n("command.modid.agent.chat.new.success", FB_NEW_SUCCESS),
                false
        );
        return 1;
    }

    @SuppressWarnings("unused")
    private static Component i18n(String key, String fallback, Object... args) {
        return Component.translatable(key, args);
    }

    private static Path getDataFile(MinecraftServer server) throws IOException {
        Path dataDir = Agent.getAgentDataDirectory(server);
        Files.createDirectories(dataDir);

        Path dataFile = dataDir.resolve(RECORD_FILE_NAME);
        if (Files.exists(dataFile)) {
            return dataFile;
        }

        Path legacyAgentsDataFile = dataDir.getParent() != null
                ? dataDir.getParent().resolve("agentsdata").resolve(RECORD_FILE_NAME)
                : dataDir.resolveSibling("agentsdata").resolve(RECORD_FILE_NAME);
        if (Files.exists(legacyAgentsDataFile)) {
            Files.copy(legacyAgentsDataFile, dataFile, StandardCopyOption.REPLACE_EXISTING);
            return dataFile;
        }

        Path legacyFile = server.getWorldPath(LevelResource.ROOT)
                .resolve("data")
                .resolve(RECORD_FILE_NAME);
        if (Files.exists(legacyFile)) {
            Files.copy(legacyFile, dataFile, StandardCopyOption.REPLACE_EXISTING);
        }

        return dataFile;
    }
}
