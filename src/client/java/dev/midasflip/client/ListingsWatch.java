package dev.midasflip.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.midasflip.client.ui.Phos;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Your open listings, monitored against the market (the landing card's
 * promise, now kept): polls /listings/mine/{uuid} — the server already
 * computes per listing whether you are LOWEST, UNDERCUT (with a
 * don't-chase reprice suggestion clamped at fair pessimistic value), or
 * STALE — and surfaces it on the HUD, the Trades pane, and as a ONE-SHOT
 * local chat line when a NEW undercut lands.
 *
 * DISPLAY-ONLY (spec §13): reads and draws; repricing stays the human's
 * clicks in the AH. Local chat messages are client-side text, never sent.
 * Shared singleton (NameMap precedent): HUD drives the poll cadence,
 * every surface reads the same snapshot, chat dedupe state is global.
 */
public final class ListingsWatch {
    /** One open listing as the server judged it. */
    public record Mine(String auctionUuid, String itemId, String compKey, long price,
                       double unitPrice, String status, Double floorUnit,
                       Double suggestUnit, Long holdMedS, Long holdP90S, long listedS,
                       Double netNow, Double suggestNet) {

        public boolean undercut() { return "undercut".equals(status); }

        public boolean stale() { return "stale".equals(status); }
    }

    /** One recent sale as the server's history recorded it — the ground
     *  truth the ledger reconciles against (fee-true net included). */
    public record Sold(String auctionUuid, String itemId, String compKey,
                       long price, long endedAtMs, Double net) {}

    /** Poll cadence in ms, from {@code config.undercutPollSec} (config-
     *  completeness 2026-07-13). The 30s floor is a HARD politeness clamp
     *  enforced in {@link MidasflipConfig#normalize()}, not just the UI —
     *  never let a hand-edited config poll faster than the API budget.
     *  Falls back to 60s if config isn't wired yet. */
    private static long pollMs() {
        return (config == null ? 60 : config.undercutPollSec) * 1000L;
    }

    private static MidasflipApi api;
    private static MidasflipConfig config;
    private static PositionLedger ledger;
    private static volatile List<Mine> current = List.of();
    private static long lastPoll;
    // One chat line per (auction, floor): a NEW lower floor re-alerts,
    // the same floor never spams.
    private static final Map<String, Double> alerted = new HashMap<>();

    private ListingsWatch() {
    }

    static void init(MidasflipApi a, MidasflipConfig c, PositionLedger l) {
        api = a;
        config = c;
        ledger = l;
    }

    /** "+3.0M -> +1.0M" profit story for an undercut listing, anchored to
     *  the LOCAL ledger's cost basis (what you paid never leaves your
     *  machine). Empty when we never saw the buy or fees are missing —
     *  omitted, never guessed. Nets are fee-true from the server: holding
     *  keeps only the claim tax (creation fee sunk); the reprice includes
     *  a fresh creation fee. */
    public static String profitShift(Mine m) {
        if (ledger == null || m.netNow() == null || m.suggestNet() == null) {
            return "";
        }
        Long paid = ledger.costBasisFor(m.itemId(), m.compKey());
        if (paid == null) {
            return "";
        }
        long now = Math.round(m.netNow() - paid);
        long after = Math.round(m.suggestNet() - paid);
        return signed(now) + " \u2192 " + signed(after);
    }

    private static String signed(long v) {
        return (v >= 0 ? "\u00a7a+" : "\u00a7c") + Phos.coins(Math.abs(v)) + "\u00a7r";
    }

    /** Current snapshot — empty until the first poll lands. */
    public static List<Mine> mine() {
        return current;
    }

    public static List<Mine> undercuts() {
        List<Mine> out = new ArrayList<>();
        for (Mine m : current) {
            if (m.undercut()) {
                out.add(m);
            }
        }
        return out;
    }

    /** Called from the HUD render loop (same pattern as the position
     *  re-valuation tick): rate-limited to one async poll per minute;
     *  api.get's TTL cache and in-flight guard absorb everything else. */
    public static void tick(Minecraft mc) {
        if (api == null || config == null || mc.player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long pollMs = pollMs();
        if (now - lastPoll < pollMs) {
            return;
        }
        lastPoll = now;
        String uuid = mc.player.getUUID().toString().replace("-", "");
        String path = "/listings/mine/" + uuid;
        JsonElement el = api.get(path, pollMs);
        if (el == null || !el.isJsonObject()) {
            return; // in flight or offline — keep the last snapshot
        }
        if (api.isStale(path, 10 * 60_000)) {
            current = List.of(); // dead API must not sustain old alerts
            return;
        }
        // optArr, not getAsJsonArray(): the latter returns null for an absent
        // key (already handled) but throws ClassCastException for an explicit
        // JSON null, from the HUD poll that has no try/catch above it.
        JsonArray open = GoldFields.optArr(el.getAsJsonObject(), "open");
        if (open == null) {
            return;
        }
        List<Mine> parsed = new ArrayList<>(open.size());
        for (JsonElement e : open) {
            Mine m = parseMine(e);
            if (m != null) {
                parsed.add(m);
            }
        }
        current = parsed;
        chatAlerts(mc, parsed);

        // Sale reconciliation: the server's history catches sells the chat
        // listener missed and carries the fee-true nets the lifetime tally
        // uses. Ledger consumes each auction id at most once.
        JsonArray soldArr = GoldFields.optArr(el.getAsJsonObject(), "sold_recent");
        if (soldArr != null && ledger != null) {
            List<Sold> sold = new ArrayList<>(soldArr.size());
            for (JsonElement e : soldArr) {
                Sold s = parseSold(e);
                if (s != null) {
                    sold.add(s);
                }
            }
            ledger.reconcileSold(sold);
        }
    }

    /** One open listing, or null when the row cannot be read.
     *
     *  <p>This whole path runs from the HUD render loop, which has no
     *  try/catch above it, and four of these fields were read unguarded:
     *  an absent key NPEs and an explicit JSON null throws
     *  UnsupportedOperationException. A listing missing its id, price, unit
     *  price or status cannot be judged undercut or stale and cannot be
     *  priced against the floor, so it is skipped rather than rendered
     *  half-read. The live API sends all four on every row and omits fields
     *  rather than nulling them (measured 2026-08-09), so no real listing
     *  is dropped by this. */
    static Mine parseMine(JsonElement e) {
        if (e == null || !e.isJsonObject()) {
            return null;
        }
        JsonObject o = e.getAsJsonObject();
        String uuid = GoldFields.optStr(o, "auction_uuid");
        Double price = GoldFields.optNum(o, "price");
        Double unitPrice = GoldFields.optNum(o, "unit_price");
        String status = GoldFields.optStr(o, "status");
        if (uuid == null || price == null || unitPrice == null || status == null) {
            return null;
        }
        String itemId = GoldFields.optStr(o, "item_id");
        String compKey = GoldFields.optStr(o, "comp_key");
        Double listedS = GoldFields.optNum(o, "listed_s");
        return new Mine(
                uuid,
                itemId == null ? "?" : itemId,
                compKey == null ? "" : compKey,
                price.longValue(),
                unitPrice,
                status,
                dbl(o, "floor_unit"), dbl(o, "suggest_unit"),
                lng(o, "hold_med_s"), lng(o, "hold_p90_s"),
                listedS == null ? 0 : listedS.longValue(),
                dbl(o, "net_now"), dbl(o, "suggest_net"));
    }

    /** One reconciled sale, or null when the row cannot be read. Same
     *  reasoning as {@link #parseMine}: id and price were guarded by has(),
     *  which passes for an explicit JSON null. */
    static Sold parseSold(JsonElement e) {
        if (e == null || !e.isJsonObject()) {
            return null;
        }
        JsonObject o = e.getAsJsonObject();
        String uuid = GoldFields.optStr(o, "auction_uuid");
        Double price = GoldFields.optNum(o, "price");
        if (uuid == null || price == null) {
            return null;
        }
        String itemId = GoldFields.optStr(o, "item_id");
        String compKey = GoldFields.optStr(o, "comp_key");
        return new Sold(
                uuid,
                itemId == null ? "?" : itemId,
                compKey == null ? "" : compKey,
                price.longValue(),
                endedMs(o),
                dbl(o, "net"));
    }

    private static long endedMs(JsonObject o) {
        try {
            return java.time.OffsetDateTime.parse(o.get("ended_at").getAsString())
                    .toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            return System.currentTimeMillis(); // unparsable timestamp: "now" beats zero
        }
    }

    /** One local line per NEW undercut (or per new lower floor). */
    private static void chatAlerts(Minecraft mc, List<Mine> parsed) {
        if (!config.undercutAlerts) {
            return;
        }
        for (Mine m : parsed) {
            if (!m.undercut() || m.floorUnit() == null) {
                continue;
            }
            Double seen = alerted.get(m.auctionUuid());
            if (seen != null && seen <= m.floorUnit()) {
                continue; // already told them about this floor (or lower)
            }
            alerted.put(m.auctionUuid(), m.floorUnit());
            String hold = m.holdMedS() != null
                    ? " or hold (~" + compact(m.holdMedS())
                    + (m.holdP90S() != null ? ", slow " + compact(m.holdP90S())
                    : ", " + GoldFields.locked("slow")) + ")"
                    : " " + GoldFields.unknown("hold");
            String shift = profitShift(m);
            Chat.local(mc, "§e[MidasFlip]§r §6▲ undercut§r on §b"
                    + NameMap.pretty(m.itemId(), m.compKey()) + "§r: yours "
                    + Phos.coins(m.unitPrice()) + ", market " + Phos.coins(m.floorUnit())
                    + (m.suggestUnit() != null
                        ? " · reprice ~" + Phos.coins(m.suggestUnit()) + " §8(Gold)§r" : "")
                    + (shift.isEmpty() ? "" : " §8(profit " + shift + "§8)§r")
                    + hold + ". Your call, your clicks.");
        }
        alerted.keySet().removeIf(id -> parsed.stream().noneMatch(m -> m.auctionUuid().equals(id)));
    }

    static String compact(long seconds) {
        if (seconds < 3600) {
            return (seconds / 60) + "m";
        }
        return String.format("%.1fh", seconds / 3600.0);
    }

    // Both already tolerated absent and null; routed through optNum so a
    // non-numeric value (a string, an object) is also "we do not have this"
    // rather than a throw on the render thread.
    private static Double dbl(JsonObject o, String k) {
        return GoldFields.optNum(o, k);
    }

    private static Long lng(JsonObject o, String k) {
        Double v = GoldFields.optNum(o, k);
        return v == null ? null : v.longValue();
    }
}
