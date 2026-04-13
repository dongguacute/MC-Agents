package com.mcagents.input;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
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
    private static final Path DATA_FILE = FabricLoader.getInstance()
            .getGameDir()
            .resolve("data")
            .resolve("agent_records.txt");

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(
                        Commands.literal("agent")
                                .then(Commands.literal("record")
                                        .then(Commands.argument("bot_name", StringArgumentType.word())
                                                .then(Commands.argument("tag", StringArgumentType.word())
                                                        .executes(Recordbot::handleRecordCommand))))
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

            String line = botName + "," + tag + System.lineSeparator();
            Files.writeString(
                    DATA_FILE,
                    line,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );

            context.getSource().sendSuccess(
                    () -> Component.literal("已记录: bot_name=" + botName + ", tag=" + tag),
                    false
            );
            return 1;
        } catch (IOException e) {
            context.getSource().sendFailure(Component.literal("写入本地 data 文件失败: " + e.getMessage()));
            return 0;
        }
    }
}
