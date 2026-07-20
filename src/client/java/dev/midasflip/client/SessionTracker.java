package dev.midasflip.client;

import net.minecraft.network.chat.Component;

import java.util.ArrayDeque;
import java.util.Iterator;
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
    private static final Pattern PURCHASED =
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
        Flip match = attribute(price);
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

    /** Exact-price match against recent opens first (strong signal), then
     *  most-recent-open within the attribution window as fallback. */
    private synchronized Flip attribute(long price) {
        long now = System.currentTimeMillis();
        for (Iterator<Opened> it = recentOpens.iterator(); it.hasNext(); ) {
            if (now - it.next().atMs > 5 * 60_000) {
                it.remove();
            }
        }
        for (Opened o : recentOpens) {
            if (o.flip.buyPrice == price) {
                return o.flip;
            }
        }
        Opened newest = recentOpens.peekFirst();
        if (newest != null && now - newest.atMs <= OPEN_ATTRIBUTION_MS) {
            return newest.flip;
        }
        return null;
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
