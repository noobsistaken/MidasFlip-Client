package dev.midasflip.client;

import net.minecraft.network.chat.Component;

import java.util.ArrayDeque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Session purchase tracking — READ-ONLY (spec §13): it listens to incoming
 * chat and never produces any game input. When Hypixel confirms a purchase
 * that matches a flip we recently opened, the flip retires from the board
 * instantly (no waiting on the next snapshot) and the session tally updates.
 *
 * Numbers shown are ESTIMATES (the flip's projected net profit at open
 * time); realized P&L comes from the server's sale history, which only
 * knows once the resale actually happens.
 */
public final class SessionTracker {
    // "You purchased ◆ Ink Wand ✦ for 1,300,000 coins!" — tolerate any item
    // text; the price is the disambiguator.
    static final Pattern PURCHASED =
            Pattern.compile("^You purchased (.+?) for ([\\d,]+) coins!?$");
    // Ledger state transitions, parse-only (owner spec): our listing
    // started; one of our auctions sold.
    private static final Pattern LISTED =
            Pattern.compile("^BIN Auction started!?$");
    private static final Pattern SOLD =
            Pattern.compile("^\\[Auction] (?:.+) bought (.+?) for ([\\d,]+) coins?");
    private static final long OPEN_ATTRIBUTION_MS = 90_000;
    private static final int MAX_TRACKED_OPENS = 10;

    private final PositionLedger ledger;
    private final MidasflipConfig config;
    private final ArrayDeque<Opened> recentOpens = new ArrayDeque<>();

    private int bought;
    private long coinsSpent;
    private double estProfit;
    private volatile String lastToast = "";
    private volatile long lastToastAtMs;

    public SessionTracker(PositionLedger ledger, MidasflipConfig config) {
        this.ledger = ledger;
        this.config = config;
    }

    public PositionLedger ledger() {
        return ledger;
    }

    private record Opened(Flip flip, long atMs) {}

    /** Called by the keybind path right after an open action fires. */
    public synchronized void onOpened(Flip flip) {
        recentOpens.addFirst(new Opened(flip, System.currentTimeMillis()));
        while (recentOpens.size() > MAX_TRACKED_OPENS) {
            recentOpens.removeLast();
        }
    }

    /** Wired to ClientReceiveMessageEvents.GAME — reads, never sends. */
    public void onGameMessage(Component message, boolean overlay) {
        if (overlay) {
            return;
        }
        String text = message.getString().strip();

        if (LISTED.matcher(text).matches()) {
            ledger.onListed();
            return;
        }
        Matcher sold = SOLD.matcher(text);
        if (sold.find()) {
            try {
                ledger.onSold(sold.group(1), Long.parseLong(sold.group(2).replace(",", "")));
            } catch (NumberFormatException ignored) {
            }
            return;
        }

        Matcher m = PURCHASED.matcher(text);
        if (!m.matches()) {
            return;
        }
        long price;
        try {
            price = Long.parseLong(m.group(2).replace(",", ""));
        } catch (NumberFormatException e) {
            return;
        }
        Flip match = attribute(m.group(1), price);
        if (match == null) {
            return; // a purchase we didn't broker — not ours to count
        }
        match.gone = true; // retire from the board instantly
        ledger.open(match, price); // position opens with both exits recorded
        synchronized (this) {
            bought++;
            coinsSpent += price;
            estProfit += match.netProfit;
        }
        // HUD toast (design 3f) — display-only feedback.
        lastToast = "✔ " + match.itemId + " bought · est +" + Math.round(match.netProfit / 1000) + "k";
        lastToastAtMs = System.currentTimeMillis();
        AuditLog.record("purchase_tracked", "chat_listen",
                match.itemId + " price=" + price + " est_profit=" + (long) match.netProfit);
    }

    /** Attribute a purchase to an opened flip, using BOTH signals the chat
     *  line carries.
     *
     *  <p>The parser has always captured the item name and the old matcher
     *  ignored it, matching on price alone and then falling back to "the most
     *  recently opened flip" regardless of what was bought. Three ways that
     *  went wrong: a manual purchase you made yourself got booked as a
     *  MidasFlip position; two different items opened at the same price
     *  collapsed onto one flip; and matched opens were never removed, so one
     *  open could absorb several purchases — duplicating positions and
     *  session profit, and marking the wrong row gone on the board (audit
     *  2026-08-06).
     *
     *  <p>Tiers, strongest first. Every tier REMOVES the open it consumed,
     *  so a second purchase cannot match the same flip again:
     *  <ol>
     *    <li>name AND price both match — unambiguous</li>
     *    <li>price matches exactly — price is highly discriminating and the
     *        display name can differ from our own rendering</li>
     *    <li>name matches inside the attribution window — covers a price we
     *        misparsed, still anchored to the right item</li>
     *  </ol>
     *  No tier matches ⇒ null ⇒ not ours, not counted. Attributing a
     *  stranger's purchase is worse than missing one of ours: it corrupts
     *  the ledger and the session numbers with data we invented. */
    synchronized Flip attribute(String purchasedName, long price) { // package-private for SessionAttributionTest
        long now = System.currentTimeMillis();
        recentOpens.removeIf(o -> now - o.atMs() > 5 * 60_000);
        String exact = norm(purchasedName);
        String base = normDereforged(purchasedName);

        Opened hit = null;
        for (Opened o : recentOpens) {          // tier 1: name + price
            if (o.flip().buyPrice == price && sameItem(exact, base, o.flip(), true)) {
                hit = o;
                break;
            }
        }
        if (hit == null) {
            for (Opened o : recentOpens) {      // tier 2: exact price
                if (o.flip().buyPrice == price) {
                    hit = o;
                    break;
                }
            }
        }
        if (hit == null && !exact.isEmpty()) {  // tier 3: name, in window
            for (Opened o : recentOpens) {
                if (now - o.atMs() <= OPEN_ATTRIBUTION_MS
                        && sameItem(exact, base, o.flip(), false)) {
                    hit = o;
                    break;
                }
            }
        }
        if (hit == null) {
            return null;
        }
        recentOpens.remove(hit); // consumed — one open cannot absorb two buys
        return hit.flip();
    }

    /** Does the chat line name the item this flip is for?
     *
     *  <p>Compares against the FULL name first and only then against the
     *  reforge-stripped form, which is {@link SellOverlay#stripReforge}'s
     *  documented contract and not optional here: several reforge words are
     *  literally armor-set names, so stripping unconditionally collapses
     *  "Wise Dragon Boots", "Strong Dragon Boots" and "Superior Dragon Boots"
     *  onto one another. Booking a manually-bought Superior against an open
     *  Wise flip is precisely the misattribution this whole function replaced,
     *  one layer further down (review 2026-08-06).
     *
     *  <p>The fallback strips the CHAT side only. Hypixel prefixes the reforge
     *  onto the display name ("Fierce Superior Dragon Helmet"); our side is
     *  the item's own name and never carries one, so stripping it too would
     *  amputate a real word from the set name and could never re-align.
     *
     *  <p>{@code corroborated} is true when the caller already matched the
     *  price to the coin. Looseness scales with corroboration: with a price
     *  match the reforge fallback is safe, but a name-only match (tier 3)
     *  writes a position off the name alone and takes the exact form only. */
    private static boolean sameItem(String exact, String base, Flip flip, boolean corroborated) {
        if (exact.isEmpty()) {
            return false;
        }
        String ours = norm(NameMap.pretty(flip.itemId, flip.compKey));
        if (ours.isEmpty()) {
            return false;
        }
        return exact.equals(ours) || (corroborated && !base.isEmpty() && base.equals(ours));
    }

    /** Display name to comparable shape — colour codes, the stack-count
     *  prefix and the star/ability glyphs Hypixel decorates names with. The
     *  reforge word is deliberately NOT touched; see {@link #sameItem}. */
    static String norm(String raw) {
        if (raw == null) {
            return "";
        }
        return SellOverlay.norm(SellOverlay.cleanName(raw.replaceAll("§.", "")));
    }

    /** As {@link #norm}, with a leading reforge word removed. Only ever the
     *  second thing tried, and only against the chat side. */
    static String normDereforged(String raw) {
        if (raw == null) {
            return "";
        }
        return SellOverlay.norm(SellOverlay.stripReforge(
                SellOverlay.cleanName(raw.replaceAll("§.", ""))));
    }

    public synchronized int boughtCount() {
        return bought;
    }

    public synchronized long spent() {
        return coinsSpent;
    }

    public synchronized double estimatedProfit() {
        return estProfit;
    }

    /** Toast text for the configured dwell after a tracked purchase; empty
     *  otherwise (config-completeness 2026-07-13: purchaseToastMs). */
    public String activeToast() {
        return System.currentTimeMillis() - lastToastAtMs < config.purchaseToastMs ? lastToast : "";
    }
}
