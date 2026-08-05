package dev.midasflip.client;

import net.minecraft.client.Minecraft;

import java.util.regex.Pattern;

/**
 * THE safety boundary for command sends (spec §13/§14). Every chat command
 * that can ever reach the game funnels through here, and the rules are
 * structural, not configurable:
 *
 * <ul>
 *   <li>This class sends exactly THREE chat commands, all through the
 *       normal command path: {@code /viewauction <uuid>} from
 *       {@link #onOpenKeyPressed} (a physical keypress), and
 *       {@code /bz <name>} or {@code /ahs <name>} from
 *       {@link #onCraftLegClicked} (a physical click on a craft recipe's
 *       ingredient, the verb chosen by the market that ingredient trades
 *       on; owner spec §13/§14 amendment 2026-07-30). The leg sends need
 *       BOTH {@code craftLegSend} — off by default, its own row in the
 *       Safety pane — AND ASSISTED: enabling assisted mode alone is
 *       consent for {@code /viewauction} and nothing more. All of them
 *       share this class's single cooldown clock, so together they cannot
 *       exceed one action per floor. None involves mouse movement or an
 *       auto-opened GUI.
 *       The mod's ONE other game action lives in {@link PurchaseOverlay}
 *       (owner spec §13/§14 amendment, 2026-07-05): opt-in, OFF by
 *       default, ASSISTED-only, attaching only to auction GUIs this mod
 *       itself opened, forwarding one physical click as exactly one slot
 *       interaction — never auto-confirmed, never timed out into a
 *       purchase; Hypixel's final confirm GUI is never overlaid. Either
 *       way: one physical input = at most one game action; STRICT sends
 *       nothing ever.</li>
 *   <li>Both fire ONLY from a physical input: {@link #onOpenKeyPressed}
 *       is wired exclusively to a keypress in {@link MidasflipClient}, and
 *       {@link #onCraftLegClicked} only from a click zone registered while
 *       drawing a craft row in a screen the user opened. There is no timer,
 *       no packet hook, no detection-triggered variant of either.</li>
 *   <li>It FAILS SAFE: only the exact ASSISTED mode sends; every other
 *       value (including a corrupt/unknown/null config) copies to the
 *       clipboard instead. The default is STRICT.</li>
 *   <li>A cooldown rejects rapid re-fires, and every action lands in the
 *       audit log with its trigger and cooldown state.</li>
 * </ul>
 */
public final class ActionController {
    // TWO backend-supplied values now reach a game input, each with its own
    // guard. The auction UUID must be exactly 32 hex chars (dashed form
    // stripped upstream) — this regex. The ingredient NAME is free text, so
    // it gets sanitizeQuery() instead: a character allowlist, because a regex
    // that validated a name would just be the allowlist written backwards.
    // Neither can inject command bytes.
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
                Chat.local(mc, "§e[MidasFlip]§r copied §b/" + command + "§r · paste it in chat to open. "
                        + "(the mod never sends commands in this mode)");
            }
            AuditLog.record("clipboard", "keybind", command);
            return true;
        }

        long now = System.currentTimeMillis();
        long elapsed = now - lastSendMs;
        if (elapsed < config.effectiveCooldownMs()) {
            AuditLog.record("cooldown_rejected", "keybind", command + " elapsed=" + elapsed);
            Chat.local(mc, "§e[MidasFlip]§r cooldown · not sending (" + (config.effectiveCooldownMs() - elapsed) + "ms left)");
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
            Chat.local(mc, "§e[MidasFlip]§r opened §b" + flip.itemId + "§r · buy stays your click.");
        }
        return true;
    }

    /** Search-open from the craft board's ingredient list (owner spec
     *  §13/§14 amendment 2026-07-30). One physical click, at most one
     *  command, deliberately the same shape as {@link #onOpenKeyPressed} and
     *  sharing its cooldown clock so the surfaces together cannot exceed one
     *  action per floor.
     *
     *  <p>The LEG'S OWN SOURCE picks the command, because that is the
     *  market the ingredient actually trades on: a bazaar leg opens
     *  {@code /bz <name>}, an auction-house leg opens {@code /ahs <name>}.
     *  Sending the wrong one would open an empty search and read as the mod
     *  being broken. {@code via} comes from the same server field that drew
     *  the row, so the hint and the command can never disagree.
     *
     *  <p>ASSISTED sends and puts the QUANTITY on the clipboard, because the
     *  amount still has to be typed into Hypixel's order sign and the mod
     *  must never type it. STRICT sends nothing and puts the COMMAND on the
     *  clipboard, which is what STRICT means.
     *
     *  <p>Returns true when something happened (send or copy).
     */
    public boolean onCraftLegClicked(Minecraft mc, String itemName, long qty, String via) {
        if (mc == null || mc.player == null) {
            return false;
        }
        String verb = commandFor(via);
        if (verb == null) {
            return false; // unknown source: no command exists, caller copies
        }
        String safe = sanitizeQuery(itemName);
        if (safe.isEmpty()) {
            // A name we cannot vouch for is never interpolated into a command.
            AuditLog.record("rejected_bad_leg_name", "craft_click", String.valueOf(itemName));
            Chat.local(mc, "§e[MidasFlip]§r couldn't build a valid search for that item.");
            return false;
        }
        String command = verb + " " + safe;
        String qtyText = qty > 0 ? String.valueOf(qty) : "";

        // DOUBLE GATE, following the purchase overlay's precedent: ASSISTED
        // alone is not consent for these commands. Someone who enabled
        // ASSISTED agreed to /viewauction; craftLegSend is off by default so
        // an existing user cannot be upgraded into sending something new.
        //
        // FAIL SAFE: send ONLY when both hold. STRICT, null, anything
        // unrecognized, or the toggle off, all copy instead.
        boolean strict = config.safetyMode != MidasflipConfig.SafetyMode.ASSISTED;
        if (!config.craftLegSend || strict) {
            mc.keyboardHandler.setClipboard("/" + command);
            // Say WHICH gate stopped it. One string for both reasons told an
            // ASSISTED user "the mod never sends commands in this mode",
            // which is false of their mode and hides the switch they need
            // (reported live 2026-07-30).
            if (config.chatConfirmations && !repeatedClick(now0())) {
                Chat.local(mc, "§e[MidasFlip]§r copied §b/" + command + "§r"
                        + (qtyText.isEmpty() ? "" : " §7· you need §f" + qtyText + "§r")
                        + (strict
                           ? " §8(STRICT never sends; paste it in chat)§r"
                           : " §8(recipe sends are off · Safety → recipe leg sends)§r"));
            }
            AuditLog.record("clipboard", "craft_click", command + " qty=" + qtyText
                    + (strict ? " reason=strict" : " reason=toggle_off"));
            return true;
        }

        long now = System.currentTimeMillis();
        long elapsed = now - lastSendMs;
        if (elapsed < config.effectiveCooldownMs()) {
            AuditLog.record("cooldown_rejected", "craft_click", command + " elapsed=" + elapsed);
            Chat.local(mc, "§e[MidasFlip]§r cooldown · not sending ("
                    + (config.effectiveCooldownMs() - elapsed) + "ms left)");
            return false;
        }

        // Audit BEFORE the send, same as the open path: a crash mid-send must
        // never leave a sent command with no trail.
        AuditLog.record(verb + "_sending", "craft_click", command + " qty=" + qtyText + " elapsed=" + elapsed);
        lastSendMs = now;
        // Kill any live purchase-overlay token. This path never SETS one, but
        // an unconsumed /viewauction token (the auction expired, no GUI
        // opened) stays valid for ATTACH_WINDOW_MS — and an Auction View
        // reached from this /ahs search would then consume it. Identity fails
        // closed for a different item, but same-id-same-count would attach the
        // buy zone to a GUI the mod did not open, which is outside the
        // 2026-07-05 carve-out's wording (review 2026-08-05).
        lastSentFlip = null;
        mc.player.connection.sendCommand(command); // the one game input, on this physical click
        AuditLog.record(verb + "_sent", "craft_click", command);
        if (!qtyText.isEmpty()) {
            mc.keyboardHandler.setClipboard(qtyText);
        }
        if (config.chatConfirmations) {
            Chat.local(mc, "§e[MidasFlip]§r opened §b" + safe + "§r"
                    + (qtyText.isEmpty() ? "" : " §7· copied §f" + qtyText + "§r for the amount sign")
                    + " · the order stays your click.");
        }
        return true;
    }

    /** The search command for a leg's market, or null when there is none.
     *  craft.py prices every input as either "bazaar" or "ah:&lt;comp_key&gt;", so
     *  those two are the whole domain; anything else is a shape we do not
     *  recognise and must not guess a command for. */
    static String commandFor(String via) {
        if (via == null) {
            return null;
        }
        if (via.equals("bazaar")) {
            return "bz";
        }
        return via.startsWith("ah:") ? "ahs" : null;
    }

    private long lastCopyChatMs;

    private long now0() {
        return System.currentTimeMillis();
    }

    /** True when the last copy confirmation was printed very recently.
     *  Chat-noise control ONLY: the copy and the audit entry always happen,
     *  and this never gates a send. A click on a leg used to print a line
     *  every time, so five clicks meant five identical chat lines. */
    private boolean repeatedClick(long now) {
        boolean recent = now - lastCopyChatMs < 1500;
        lastCopyChatMs = now;
        return recent;
    }

    /** Everything that is not a plain item word is dropped before it can
     *  reach a command string. The names come from our own board, but an
     *  interpolated command is exactly the wrong place to trust an upstream
     *  string: a newline or a stray "/" would be a second command. Letters,
     *  digits, spaces, apostrophes and hyphens are all a bazaar search needs.
     *  Collapses runs of spaces and caps the length. */
    static String sanitizeQuery(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.replaceAll("[^A-Za-z0-9 '\\-]", " ").replaceAll("\\s+", " ").strip();
        return s.length() > 48 ? s.substring(0, 48).strip() : s;
    }
}
