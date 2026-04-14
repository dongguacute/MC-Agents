package com.mcagents.Agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mcagents.input.Agent;
import com.mcagents.util.CommandSourceFeedback;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Startbot {
    private static final String RECORD_FILE_NAME = "agent_records.json";
    private static final long RATE_LIMIT_WINDOW_MS = 10_000L;
    private static final int RATE_LIMIT_MAX_REQUESTS = 3;
    private static final long DIRECTIVE_CONFLICT_WINDOW_MS = 10_000L;
    private static final Pattern BOT_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    private static final Pattern AI_CONTROL_PATTERN = Pattern.compile("(?i)\\[CONTROL_BOT\\]\\s*(join|leave)\\s+(?:\"([^\"]{1,64})\"|([^\\s]{1,64}))");
    private static final Map<String, BotDirectiveState> RECENT_BOT_DIRECTIVES = new HashMap<>();
    private static final Map<String, ArrayDeque<Long>> REQUEST_TIMESTAMPS_BY_REQUESTER = new HashMap<>();

    public static int handleBotJoinCommand(CommandContext<CommandSourceStack> context) {
        String botName = StringArgumentType.getString(context, "bot_name");
        return handleControl(context.getSource(), "join", botName);
    }

    public static int handleBotLeaveCommand(CommandContext<CommandSourceStack> context) {
        String botName = StringArgumentType.getString(context, "bot_name");
        return handleControl(context.getSource(), "leave", botName);
    }

    public static boolean tryHandleAiDirective(ServerPlayer player, String aiReply) {
        if (aiReply == null || aiReply.isBlank()) {
            return false;
        }

        Matcher matcher = AI_CONTROL_PATTERN.matcher(aiReply);
        CommandSourceStack source = createAgentSourceFromPlayer(player);
        boolean matchedAny = false;
        boolean successAny = false;
        while (matcher.find()) {
            matchedAny = true;
            String action = matcher.group(1).toLowerCase();
            String botNameOrTag = matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
            if (botNameOrTag != null) {
                botNameOrTag = botNameOrTag.trim();
            }
            int result = handleControl(source, action, botNameOrTag);
            if (result == 1) {
                successAny = true;
            }
        }
        return matchedAny && successAny;
    }

    private static int handleControl(CommandSourceStack source, String action, String botNameOrTag) {
        String subCommand;
        if ("join".equalsIgnoreCase(action)) {
            subCommand = "rejoin";
        } else if ("leave".equalsIgnoreCase(action)) {
            subCommand = "kill";
        } else {
            source.sendFailure(i18n("command.modid.agent.control.action.invalid", "未知操作，仅支持 join / leave"));
            return 0;
        }

        RateLimitResult rateLimitResult = checkAndRecordRateLimit(source);
        if (!rateLimitResult.allowed()) {
            source.sendFailure(i18n(
                    "command.modid.agent.control.rate_limited",
                    "请求过于频繁：%s 秒内最多 %s 次控制请求，请在 %s 秒后重试",
                    rateLimitResult.windowSeconds(),
                    rateLimitResult.maxRequests(),
                    rateLimitResult.retryAfterSeconds()
            ));
            return 0;
        }

        List<String> targetBotNames = resolveBotTargets(source, botNameOrTag);
        if (targetBotNames == null || targetBotNames.isEmpty()) {
            return 0;
        }

        MinecraftServer server = source.getServer();
        ConflictCheckResult conflictCheck = filterConflictingTargets(source, action, targetBotNames);
        if (!conflictCheck.conflictingBots().isEmpty()) {
            source.sendFailure(i18n(
                    "command.modid.agent.control.conflict.detected",
                    "检测到冲突指令，已跳过 bot: %s。最近由 %s 执行了 %s 指令，请稍后重试",
                    String.join(", ", conflictCheck.conflictingBots()),
                    conflictCheck.lastRequester(),
                    conflictCheck.lastAction()
            ));
        }
        if (conflictCheck.executableBots().isEmpty()) {
            return 0;
        }

        int successCount = 0;
        List<String> failedBots = new ArrayList<>();
        for (String botName : conflictCheck.executableBots()) {
            String carpetCommand = "player " + botName + " " + subCommand;
            try {
                int executeResult = server.getCommands().getDispatcher().execute(
                        carpetCommand,
                        source.withPermission(4)
                );
                if (executeResult > 0) {
                    successCount++;
                } else {
                    failedBots.add(botName);
                }
            } catch (CommandSyntaxException e) {
                failedBots.add(botName);
                source.sendFailure(i18n("command.modid.agent.control.command.syntax_error", "Carpet 命令语法/上下文错误: /%s，%s", carpetCommand, e.getMessage()));
            } catch (Exception e) {
                failedBots.add(botName);
                source.sendFailure(i18n("command.modid.agent.control.command.failed", "执行 Carpet 命令失败: /%s，%s", carpetCommand, e.getMessage()));
            }
        }
        markRecentDirective(source, action, conflictCheck.executableBots());

        int finalSuccessCount = successCount;
        CommandSourceFeedback.sendSuccess(source, i18n("command.modid.agent.control.batch.result", "批量执行完成：成功 %s 个，失败 %s 个", finalSuccessCount, failedBots.size()), true);
        if (!failedBots.isEmpty()) {
            source.sendFailure(i18n("command.modid.agent.control.batch.failed_bots", "失败 bot: %s", String.join(", ", failedBots)));
        }
        return successCount > 0 ? 1 : 0;
    }

    private static boolean isValidBotName(String botName) {
        return botName != null && BOT_NAME_PATTERN.matcher(botName).matches();
    }

    private static List<String> resolveBotTargets(CommandSourceStack source, String input) {
        if (input == null || input.isBlank()) {
            source.sendFailure(i18n("command.modid.agent.control.target.empty", "请输入 bot 名称或已记录的 tag"));
            return null;
        }
        String normalizedInput = input.trim();

        List<String> matchedBots = new ArrayList<>();
        Path recordFile;
        try {
            recordFile = getRecordFile(source.getServer());
            JsonArray records = readRecords(recordFile);
            CommandSourceFeedback.sendSuccess(source, i18n("command.modid.agent.control.records.loaded", "已读取存储库: %s，记录数 %s", recordFile.toAbsolutePath().toString(), records.size()), false);
            for (JsonElement element : records) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject record = element.getAsJsonObject();
                String tag = record.has("tag") ? record.get("tag").getAsString().trim() : "";
                String botName = record.has("bot_name") ? record.get("bot_name").getAsString().trim() : "";
                if (!normalizedInput.equals(tag)) {
                    continue;
                }
                if (!isValidBotName(botName)) {
                    continue;
                }
                if (!matchedBots.contains(botName)) {
                    matchedBots.add(botName);
                }
            }
        } catch (IOException e) {
            source.sendFailure(i18n("command.modid.agent.control.records.load_failed", "读取存储库失败: %s", e.getMessage()));
            return null;
        }

        if (!matchedBots.isEmpty()) {
            CommandSourceFeedback.sendSuccess(source, i18n("command.modid.agent.control.tag.matched", "tag 命中 bot: %s", String.join(", ", matchedBots)), false);
            return matchedBots;
        }

        if (isValidBotName(normalizedInput)) {
            List<String> single = new ArrayList<>();
            single.add(normalizedInput);
            return single;
        }

        source.sendFailure(i18n("command.modid.agent.control.target.not_found_or_invalid", "未找到该 tag 对应的 bot，且输入也不是合法 bot_name（3-16 位字母/数字/下划线）"));
        return null;
    }

    private static Path getRecordFile(MinecraftServer server) throws IOException {
        Path dataDir = Agent.getAgentDataDirectory(server);
        Files.createDirectories(dataDir);
        return dataDir.resolve(RECORD_FILE_NAME);
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

    @SuppressWarnings("unused")
    private static MutableComponent i18n(String key, String fallback, Object... args) {
        return Component.translatable(key, args);
    }

    private static CommandSourceStack createAgentSourceFromPlayer(ServerPlayer player) {
        return player.createCommandSourceStack()
                .withPermission(4);
    }

    private static ConflictCheckResult filterConflictingTargets(CommandSourceStack source, String action, List<String> targetBotNames) {
        long now = System.currentTimeMillis();
        String requester = source.getTextName();
        List<String> executableBots = new ArrayList<>();
        List<String> conflictingBots = new ArrayList<>();
        String lastRequester = "";
        String lastAction = "";

        synchronized (RECENT_BOT_DIRECTIVES) {
            RECENT_BOT_DIRECTIVES.entrySet().removeIf(entry -> now - entry.getValue().timestampMs() > DIRECTIVE_CONFLICT_WINDOW_MS);
            for (String botName : targetBotNames) {
                BotDirectiveState previous = RECENT_BOT_DIRECTIVES.get(botName);
                if (previous == null || previous.requester().equalsIgnoreCase(requester)) {
                    executableBots.add(botName);
                    continue;
                }

                conflictingBots.add(botName);
                if (lastRequester.isEmpty()) {
                    lastRequester = previous.requester();
                    lastAction = previous.action();
                }
            }
        }
        return new ConflictCheckResult(executableBots, conflictingBots, lastRequester, lastAction);
    }

    private static void markRecentDirective(CommandSourceStack source, String action, List<String> botNames) {
        String requester = source.getTextName();
        long now = System.currentTimeMillis();
        synchronized (RECENT_BOT_DIRECTIVES) {
            for (String botName : botNames) {
                RECENT_BOT_DIRECTIVES.put(botName, new BotDirectiveState(action, requester, now));
            }
        }
    }

    private record BotDirectiveState(String action, String requester, long timestampMs) {
    }

    private static RateLimitResult checkAndRecordRateLimit(CommandSourceStack source) {
        String requester = source.getTextName();
        long now = System.currentTimeMillis();

        synchronized (REQUEST_TIMESTAMPS_BY_REQUESTER) {
            ArrayDeque<Long> timestamps = REQUEST_TIMESTAMPS_BY_REQUESTER.computeIfAbsent(requester, key -> new ArrayDeque<>());
            while (!timestamps.isEmpty() && now - timestamps.peekFirst() > RATE_LIMIT_WINDOW_MS) {
                timestamps.pollFirst();
            }

            if (timestamps.size() >= RATE_LIMIT_MAX_REQUESTS) {
                long retryAfterMs = (timestamps.peekFirst() + RATE_LIMIT_WINDOW_MS) - now;
                long retryAfterSeconds = Math.max(1L, (long) Math.ceil(retryAfterMs / 1000.0D));
                return new RateLimitResult(false, retryAfterSeconds, RATE_LIMIT_MAX_REQUESTS, RATE_LIMIT_WINDOW_MS / 1000L);
            }

            timestamps.addLast(now);
            return new RateLimitResult(true, 0L, RATE_LIMIT_MAX_REQUESTS, RATE_LIMIT_WINDOW_MS / 1000L);
        }
    }

    private record ConflictCheckResult(
            List<String> executableBots,
            List<String> conflictingBots,
            String lastRequester,
            String lastAction
    ) {
    }

    private record RateLimitResult(
            boolean allowed,
            long retryAfterSeconds,
            int maxRequests,
            long windowSeconds
    ) {
    }
}
