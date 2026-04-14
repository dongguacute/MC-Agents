package com.mcagents.util;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Method;
import java.util.function.Supplier;

/**
 * Bridges {@link CommandSourceStack#sendSuccess} API differences: some Minecraft versions
 * use {@code sendSuccess(Component, boolean)}, others {@code sendSuccess(Supplier<Component>, boolean)}.
 */
public final class CommandSourceFeedback {
    private static final Method SEND_SUCCESS_COMPONENT;
    private static final Method SEND_SUCCESS_SUPPLIER;

    static {
        Method component = null;
        Method supplier = null;
        try {
            component = CommandSourceStack.class.getMethod("sendSuccess", Component.class, boolean.class);
        } catch (NoSuchMethodException ignored) {
        }
        try {
            supplier = CommandSourceStack.class.getMethod("sendSuccess", Supplier.class, boolean.class);
        } catch (NoSuchMethodException ignored) {
        }
        if (component == null && supplier == null) {
            throw new ExceptionInInitializerError("CommandSourceStack.sendSuccess(Component|Supplier, boolean) not found");
        }
        SEND_SUCCESS_COMPONENT = component;
        SEND_SUCCESS_SUPPLIER = supplier;
    }

    private CommandSourceFeedback() {
    }

    public static void sendSuccess(CommandSourceStack source, Component message, boolean broadcastToOps) {
        try {
            if (SEND_SUCCESS_COMPONENT != null) {
                SEND_SUCCESS_COMPONENT.invoke(source, message, broadcastToOps);
            } else {
                Supplier<Component> lazy = () -> message;
                SEND_SUCCESS_SUPPLIER.invoke(source, lazy, broadcastToOps);
            }
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
