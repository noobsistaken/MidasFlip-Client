package dev.midasflip.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Local-only chat lines (never sent to the server). */
final class Chat {
    private Chat() {}

    static void local(Minecraft mc, String message) {
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal(message));
        }
    }
}
