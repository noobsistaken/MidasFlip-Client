package dev.midasflip.client;

import net.minecraft.client.Minecraft;

import java.util.regex.Pattern;

/**
 * THE safety boundary for command sends (spec §13/§14). Every chat command
 * that can ever reach the game funnels through here, and the rules are
 * structural, not configurable:
 *
 * <ul>
 *   <li>This class's only game action is sending the
 *       {@code /viewauction <uuid>} chat command through the normal
 *       command path — never mouse movement, never auto-opened GUIs.
 *       The mod's ONE other game action lives in {@link PurchaseOverlay}
 *       (owner spec §13/§14 amendment, 2026-07-05): opt-in, OFF by
 *       default, ASSISTED-only, attaching only to auction GUIs this mod
 *       itself opened, forwarding one physical click as exactly one slot
 *       interaction — never auto-confirmed, never timed out into a
 *       purchase; Hypixel's final confirm GUI is never overlaid. Either
 *       way: one physical input = at most one game action; STRICT sends
 *       nothing ever.</li>
 *   <li>It fires ONLY from {@link #onOpenKeyPressed}, which is wired
 *       exclusively to a physical keypress in {@link MidasflipClient}. No
 *       other code path calls it; there is no timer, no packet hook, no
 *       detection-triggered variant.</li>
 *   <li>It FAILS SAFE: only the exact ASSISTED mode sends; every other
 *       value (including a corrupt/unknown/null config) copies to the
 *       clipboard instead. The default is STRICT.</li>
 *   <li>A cooldown rejects rapid re-fires, and every action lands in the
 *       audit log with its trigger and cooldown state.</li>
 * </ul>
 */
public final class ActionController {
    // The auction UUID is the only backend-supplied value that reaches a
    // game input — it must be exactly 32 hex chars (dashed form stripped
    // upstream). A malicious feed cannot inject command bytes.
    private static final Pattern UUID_RE = Pattern.compile("^[0-9a-fA-F]{32}$");

    private final MidasflipConfig config;
    private long lastSendMs;
    private volatile Flip lastSentFlip;
    private volatile long lastSentAtMs;

    public ActionController(MidasflipConfig config) {
        this.config = config;
    }

    /** One-shot trigger token for {@link PurchaseOverlay}: the flip whose
     *  /viewauction we most recently SENT (ASSISTED only), returned at
     *  most ONCE and only within {@code windowMs} of the send. Consuming
     *  on first read is structural — a second auction GUI inside the
     *  window must never inherit the overlay (it would cover the WRONG
     *  auction). Client thread only (keybind and screen-init both are). */
    public Flip consumeSentFlip(long windowMs) {
        Flip f = lastSentFlip;
        if (f == null) {
            return null;
        }
        if (System.currentTimeMillis() - lastSentAtMs > windowMs) {
            // Structural expiry: a stale token is dead regardless of what
            // window a future caller might pass.
            lastSentFlip = null;
            return null;
        }
        lastSentFlip = null;
        return f;
    }

    /** Called from the keybind handler ONLY — one physical press, one action.
     *  Returns true when an action happened (send or clipboard copy), so the
     *  caller can advance the open-cycle; false on rejection, so the same
     *  flip is retried on the next press. */
    public boolean onOpenKeyPressed(Minecraft mc, Flip flip) {
        if (flip == null || mc.player == null) {
            return false;
        }
        if (flip.uuid == null || !UUID_RE.matcher(flip.uuid).matches()) {
            AuditLog.record("rejected_bad_uuid", "keybind", String.valueOf(flip.uuid));
            Chat.local(mc, "§e[MidasFlip]§r ignored a flip with a malformed id.");
            return false;
        }
        String command = "viewauction " + flip.uuid;

        // FAIL SAFE: send ONLY in the exact ASSISTED mode. STRICT, null,
        // or any unrecognized value falls through to clipboard-only.
        if (config.safetyMode != MidasflipConfig.SafetyMode.ASSISTED) {
            mc.keyboardHandler.setClipboard("/" + command);
            // Confirmation line is gated (config-completeness 2026-07-13);
            // the clipboard copy and the audit entry always happen.
            if (config.chatConfirmations) {
                Chat.local(mc, "§e[MidasFlip]§r copied §b/" + command + "§r — paste it in chat to open. "
                        + "(the mod never sends commands in this mode)");
            }
            AuditLog.record("clipboard", "keybind", command);
            return true;
        }

        long now = System.currentTimeMillis();
        long elapsed = now - lastSendMs;
        if (elapsed < config.effectiveCooldownMs()) {
            AuditLog.record("cooldown_rejected", "keybind", command + " elapsed=" + elapsed);
            Chat.local(mc, "§e[MidasFlip]§r cooldown — not sending (" + (config.effectiveCooldownMs() - elapsed) + "ms left)");
            return false;
        }

        // Audit BEFORE the send: a crash mid-send must never leave a sent
        // command with no trail.
        AuditLog.record("viewauction_sending", "keybind", command + " elapsed=" + elapsed);
        lastSendMs = now;
        lastSentFlip = flip;
        lastSentAtMs = now;
        mc.player.connection.sendCommand(command); // the one game input, on this physical press
        AuditLog.record("viewauction_sent", "keybind", command);
        // Confirmation line is gated (config-completeness 2026-07-13); the
        // send and the audit trail always happen regardless of the toggle.
        if (config.chatConfirmations) {
            Chat.local(mc, "§e[MidasFlip]§r opened §b" + flip.itemId + "§r — buy stays your click.");
        }
        return true;
    }
}
