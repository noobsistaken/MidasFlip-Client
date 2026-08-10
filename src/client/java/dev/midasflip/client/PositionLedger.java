package dev.midasflip.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The trade ledger (owner spec 2026-07-05): every tracked purchase opens
 * a position with BOTH recommended exits recorded at buy time, so the
 * model's exit advice is auditable against what actually happened.
 * States: open → listed → sold, driven purely by chat parsing (no
 * actions, ever). Local-first: one json file in the config dir, nothing
 * syncs anywhere.
 */
public final class PositionLedger {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_POSITIONS = 500;
    public static final String CURRENT_PET_KEY_VERSION = "v1.2-d3";

    public static final class Position {
        public String uuid;
        public String itemId;
        public String compKey;       // bucket key at buy — enables live re-pricing (null on legacy rows)
        public String petKeyVersion; // null on pre-version rows: never actionable as an exact pet key
        public int count;            // units bought (bucket estimates are per-unit; 0 on legacy rows)
        public long buyPrice;
        public Double exitFast;      // recorded at buy — the model's advice (GROSS listing totals)
        public Double exitPatient;
        public Double waitPrice;     // optimistic band — the "if you wait" price
        public Double exitFastNet;   // NET PROFIT DELTAS vs buy (buy ALREADY subtracted
        public Double exitPatientNet; // server-side — render directly, never re-subtract);
                                      // null on ledger rows from before this field
        public long boughtAtMs;
        public String state = "open"; // open | listed | sold
        public long listedAtMs;
        public long soldPrice;
        public long soldAtMs;
        public Double soldNet;       // fee-true proceeds (server reconcile);
        public String soldAuctionId; // null = chat-only sale, gross known only

        // Live re-valuation (alert-only): the CURRENT pessimistic per-unit
        // estimate for this bucket, refreshed while the position is open.
        // Transient — display state, never persisted; the recorded buy-time
        // exits above stay untouched (the audit trail, owner spec 2026-07-05).
        public transient Double curPess;

        /** Current pessimistic value of the whole stack; null before data. */
        public Double curPessTotal() {
            return curPess == null ? null : curPess * Math.max(count, 1);
        }

        /** Market moved against this hold: even the CURRENT pessimistic
         *  GROSS estimate is below what was paid. The /value estimate has
         *  no fee-adjusted net and the client never guesses fees — but
         *  net ≤ gross always, so gross-below-paid means the pessimistic
         *  net is certainly below cost. Flags only sure losses. */
        public boolean underwater() {
            Double total = curPessTotal();
            return total != null && Math.round(total) < buyPrice;
        }

        /** True when we can no longer honestly claim you still hold this.
         *
         *  The server hands the client only the last 24 HOURS of sale
         *  history (/listings/mine, LIMIT 10), so a sale that completes
         *  while the mod is closed is invisible to reconciliation FOREVER
         *  — the position stays "open" and nags about an item you sold
         *  last week (owner report 2026-07-27). A currently-listed
         *  position is confirmed by the live listing feed and never goes
         *  unconfirmed; an unlisted one goes quiet after the window.
         *
         *  This suppresses ALERTS only. The row stays in the ledger,
         *  labeled, because deleting it would silently rewrite your P&L. */
        public boolean unconfirmed(long nowMs) {
            return !"sold".equals(state)
                    && !"listed".equals(state)
                    && nowMs - boughtAtMs > UNCONFIRMED_AFTER_MS;
        }
    }

    /** How long an unlisted position keeps alerting before we admit we
     *  cannot confirm it. 72h comfortably covers a real hold; past that,
     *  silence beats a false alarm about an item that already sold. */
    static final long UNCONFIRMED_AFTER_MS = 72L * 60 * 60 * 1000;

    private final List<Position> positions;
    /** Set when load() could not read an EXISTING file for a reason that was
     *  not corruption. Blocks every save for the session: an empty in-memory
     *  list written back over a file we simply failed to read would destroy
     *  the whole position history over one bad second of I/O — strictly worse
     *  than the truncation this class exists to prevent (review 2026-08-06). */
    private boolean readOnly;

    public PositionLedger() {
        positions = load();
    }

    /** Called on a tracked purchase (chat-attributed). */
    public synchronized void open(Flip f, long paid) {
        Position p = new Position();
        p.uuid = f.uuid;
        p.itemId = f.itemId;
        p.compKey = f.compKey;
        if (isPetKey(f.compKey)) {
            p.petKeyVersion = CURRENT_PET_KEY_VERSION;
        }
        p.count = Math.max(f.count, 1);
        p.buyPrice = paid;
        p.exitFast = f.exitFast;
        p.exitPatient = f.exitPatient;
        p.waitPrice = f.estOpt != null && f.estOpt > 0 ? f.estOpt : null;
        p.exitFastNet = f.exitFastNet;
        p.exitPatientNet = f.exitPatientNet;
        p.boughtAtMs = System.currentTimeMillis();
        positions.add(p);
        while (positions.size() > MAX_POSITIONS) {
            positions.remove(0);
        }
        save();
    }

    /** Chat said we started a BIN auction — the newest open position is
     *  the best attribution we have (parsing only, honest heuristic). */
    public synchronized void onListed() {
        for (int i = positions.size() - 1; i >= 0; i--) {
            if ("open".equals(positions.get(i).state)) {
                positions.get(i).state = "listed";
                positions.get(i).listedAtMs = System.currentTimeMillis();
                save();
                return;
            }
        }
    }

    /** Chat reported one of our auctions sold: match by normalized item
     * name. Modern bucketed rows outrank legacy id-only rows, then FIFO
     * within that evidence tier. Otherwise old unreconciled history can
     * consume a new sale before the actual tracked position. */
    public synchronized void onSold(String displayName, long price) {
        // Compare the way the BUY side does (SessionTracker.sameItem). The
        // chat line carries the DISPLAY name; p.itemId is a SkyBlock id, and
        // no string rule bridges POWER_WITHER_CHESTPLATE to "Necron's
        // Chestplate". normalize(itemId) vs a display name silently failed to
        // match on every reforged, starred or possessive item, so the position
        // never left open/listed: lifetime P&L undercounted, the HUD kept
        // warning "underwater" about something already sold, and onListed's
        // newest-open-position heuristic could mark the stale row listed
        // instead of the real one. The buy side was fixed on 2026-08-06 and
        // the sell side was missed; no test covered onSold (review
        // 2026-08-10).
        String want = SessionTracker.norm(displayName);
        String wantBase = SessionTracker.normDereforged(displayName);
        // AMBIGUITY MUST NOT BE GUESSED THROUGH. The chat line carries no
        // identity beyond the name, and norm() deliberately collapses stars
        // and reforges, so two open positions of the same item in different
        // BUCKETS (a clean one and a 5-star one) are indistinguishable here.
        // Picking either wrote a false cost basis AND left the real position
        // open — then reconcileSold, which matches on comp_key, failed to
        // recognise the row chat had closed and closed the real one too. One
        // sale, two closed positions: reproduced 2026-08-10 as a clean 100M
        // and a 5-star 480M Hyperion, sold once for 500M, reporting 2 trades
        // and +420M for a real +20M.
        //
        // So when the name maps to more than one bucket, close NOTHING and
        // let reconcileSold do it: it matches on comp_key, carries the
        // server's fee-true net, and lands within about a minute. A position
        // that closes a minute late is a delay; one closed against the wrong
        // basis is permanent, and there is no un-sell path.
        Position match = null;
        int bestScore = 0;
        String seenKey = null;
        boolean ambiguous = false;
        for (Position p : positions) {
            if ("sold".equals(p.state)) {
                continue;
            }
            String ours = SessionTracker.norm(NameMap.pretty(p.itemId, p.compKey));
            if (ours.isEmpty() || !(ours.equals(want) || ours.equals(wantBase))) {
                continue;
            }
            String key = p.compKey == null ? "" : p.compKey;
            if (seenKey == null) {
                seenKey = key;
            } else if (!seenKey.equals(key)) {
                ambiguous = true;
            }
            // Prefer a bucketed row over a legacy id-only one, then FIFO. A
            // stale unconfirmed row must never outrank a live one: past the
            // 72h cutoff we can no longer claim the item is still held, so it
            // is the worst candidate, not an equal one (review 2026-08-10).
            long now = System.currentTimeMillis();
            int score = (p.compKey != null && !p.compKey.isEmpty() ? 2 : 1)
                    + (p.unconfirmed(now) ? -2 : 0);
            if (score > bestScore
                    || (score == bestScore && match != null && p.boughtAtMs < match.boughtAtMs)) {
                match = p;
                bestScore = score;
            }
        }
        if (match == null || ambiguous) {
            return;
        }
        match.state = "sold";
        match.soldPrice = price;
        match.soldAtMs = System.currentTimeMillis();
        AuditLog.record("position_sold", "chat_listen",
                match.itemId + " sold=" + price + " exit_fast=" + match.exitFast
                        + " exit_patient=" + match.exitPatient);
        save();
    }

    public synchronized List<Position> recent(int n) {
        int from = Math.max(0, positions.size() - n);
        return new ArrayList<>(positions.subList(from, positions.size()));
    }

    /** Server-side sale reconciliation (driven by ListingsWatch's minutely
     *  poll): the collector's sale history is the ground truth — it sees
     *  sales the chat listener missed (offline, lobby swap) and carries
     *  fee-true net proceeds. Each server sale is consumed AT MOST once
     *  (soldAuctionId); chat-marked sells get their net attached when the
     *  price matches. Never guesses: no match, no mutation. */
    public synchronized void reconcileSold(List<ListingsWatch.Sold> sales) {
        boolean changed = false;
        for (ListingsWatch.Sold s : sales) {
            boolean consumed = false;
            for (Position p : positions) {
                if (s.auctionUuid().equals(p.soldAuctionId)) {
                    consumed = true;
                    break;
                }
            }
            if (consumed) {
                continue;
            }
            // 1) A chat-marked sale missing its net, same item + exact price.
            Position match = null;
            int bestScore = 0;
            for (Position p : positions) {
                int score = saleMatchScore(p, s);
                if ("sold".equals(p.state) && p.soldNet == null && p.soldAuctionId == null
                        && p.soldPrice == s.price() && score > 0
                        && (score > bestScore
                            || (score == bestScore && match != null
                                && p.boughtAtMs < match.boughtAtMs))) {
                    match = p;
                    bestScore = score;
                }
            }
            if (match == null) {
                // 2) Chat missed the sale entirely: oldest not-sold position
                //    in the strongest identity tier (exact key before legacy
                //    id fallback), FIFO within that tier.
                bestScore = 0;
                for (Position p : positions) {
                    int score = saleMatchScore(p, s);
                    if (!"sold".equals(p.state) && score > 0
                            && (score > bestScore
                                || (score == bestScore && match != null
                                    && p.boughtAtMs < match.boughtAtMs))) {
                        match = p;
                        bestScore = score;
                    }
                }
                if (match != null) {
                    match.state = "sold";
                    match.soldPrice = s.price();
                    match.soldAtMs = s.endedAtMs();
                    AuditLog.record("position_sold", "server_reconcile",
                            match.itemId + " sold=" + s.price() + " (chat missed it)");
                }
            }
            if (match != null) {
                match.soldNet = s.net();
                match.soldAuctionId = s.auctionUuid();
                changed = true;
            }
        }
        if (changed) {
            save();
        }
    }

    /** Exact modern comp-key identity beats an id-only legacy fallback.
     * Known-but-different keys never match merely because item_id agrees. */
    private static int saleMatchScore(Position p, ListingsWatch.Sold s) {
        if (p.compKey != null && s.compKey() != null && !s.compKey().isEmpty()) {
            return p.compKey.equals(s.compKey()) ? 3 : 0;
        }
        if (!normalize(p.itemId).equals(normalize(s.itemId()))) {
            return 0;
        }
        return p.compKey != null && !p.compKey.isEmpty() ? 2 : 1;
    }

    /** Lifetime tally over the ledger (bounded at the newest 500 trades).
     *  Profit is NET of AH fees where the server reconciled the sale;
     *  chat-only rows fall back to gross and flag the total approximate. */
    public record Lifetime(int sold, long spentOnSold, long profit, boolean approx) {}

    public synchronized Lifetime lifetime() {
        int n = 0;
        long profit = 0;
        long spent = 0;
        boolean approx = false;
        for (Position p : positions) {
            if (!"sold".equals(p.state)) {
                continue;
            }
            n++;
            spent += p.buyPrice;
            if (p.soldNet != null) {
                profit += Math.round(p.soldNet) - p.buyPrice;
            } else {
                profit += p.soldPrice - p.buyPrice; // gross: fees unknown
                approx = true;
            }
        }
        return new Lifetime(n, spent, profit, approx);
    }

    /** Cost basis for an item we appear to have LISTED: oldest unsold
     *  position matching by comp key (exact) or item id (fallback for
     *  legacy rows without keys). Null = the ledger never saw this buy —
     *  callers must then omit profit math rather than guess. */
    /** Cost basis PER UNIT, or null.
     *
     *  <p>{@link #costBasisFor} returns what the whole position cost, which is
     *  the total for its `count` units. Comparing that against the proceeds of
     *  ONE listing mixes two different quantities: a 64-stack bought for 40M
     *  against a single unit listed at 700k reported a 39.3M loss (review
     *  2026-08-10). Per-unit is the only basis that can be compared to a
     *  listing of arbitrary size. */
    public synchronized Double costBasisPerUnitFor(String itemId, String compKey) {
        Long total = costBasisFor(itemId, compKey);
        if (total == null) {
            return null;
        }
        int units = unitsOfCostBasis(itemId, compKey);
        return units > 0 ? (double) total / units : null;
    }

    /** Units behind the position {@link #costBasisFor} just matched. Kept in
     *  step with it deliberately: a count taken from a DIFFERENT row than the
     *  price would be worse than no figure at all. */
    private synchronized int unitsOfCostBasis(String itemId, String compKey) {
        if (compKey != null && !compKey.isEmpty()) {
            for (Position p : positions) {
                if (!"sold".equals(p.state) && compKey.equals(p.compKey)
                        && (!isPetKey(compKey) || hasCurrentPetKey(p))) {
                    return Math.max(p.count, 1);
                }
            }
        }
        for (Position p : positions) {
            if (!"sold".equals(p.state) && p.itemId != null && p.itemId.equals(itemId)
                    && (p.compKey == null || p.compKey.isEmpty())) {
                return Math.max(p.count, 1);
            }
        }
        return 0;
    }

    public synchronized Long costBasisFor(String itemId, String compKey) {
        // Exact modern identity first, regardless of file order. Legacy rows
        // can predate comp-key persistence and remain open after missed chat.
        if (compKey != null && !compKey.isEmpty()) {
            for (Position p : positions) {
                if (!"sold".equals(p.state) && compKey.equals(p.compKey)
                        && (!isPetKey(compKey) || hasCurrentPetKey(p))) {
                    return p.buyPrice;
                }
            }
        }
        // Only when no exact position exists may an id-only legacy row act
        // as the fallback cost basis.
        for (Position p : positions) {
            if ("sold".equals(p.state)) {
                continue;
            }
            boolean idMatch = p.compKey == null && itemId != null
                    && normalize(itemId).equals(normalize(p.itemId));
            if (idMatch) {
                return p.buyPrice;
            }
        }
        return null;
    }

    // ---- live re-valuation (ALERT-ONLY, spec §13: display never acts) ----

    /** Cap on positions re-priced per sweep — keeps API load tiny. */
    private static final int MAX_REVALUED = 10;
    private static final long REVALUE_SWEEP_MS = 60_000;
    /** Cache age past which a "current" estimate is no longer current —
     *  an underwater alert from stale data would be a false alarm. */
    private static final long REVALUE_STALE_MS = 10 * 60_000;

    private MidasflipApi revalueApi; // wired once at client init (SellOverlay.register)
    private long lastSweepMs;

    /** Wire the shared API client in — FlipHud has no api handle of its
     *  own and its construction site is owned elsewhere, so the one place
     *  that holds both ledger and api (SellOverlay) attaches it here. */
    public void attachApi(MidasflipApi api) {
        this.revalueApi = api;
    }

    /** Passive driver (called from the HUD render loop): at most one
     *  refresh sweep per minute. No-op until an api is attached. */
    public synchronized void tickRevaluation() {
        long now = System.currentTimeMillis();
        if (revalueApi == null || now - lastSweepMs < REVALUE_SWEEP_MS) {
            return;
        }
        lastSweepMs = now;
        refreshValuations(revalueApi);
    }

    /**
     * Refresh the CURRENT market estimate for open positions, newest
     * first, capped at {@link #MAX_REVALUED}. api.get is async with a
     * 60s TTL cache and an in-flight guard, so however often surfaces
     * call this, the cost is at most one HTTP GET per tracked position
     * per minute — and zero network on the render thread. Legacy rows
     * without a comp key are skipped, never guessed. Updates only the
     * transient cur* fields; recorded buy-time exits are never touched.
     */
    public synchronized void refreshValuations(MidasflipApi api) {
        int tracked = 0;
        for (int i = positions.size() - 1; i >= 0 && tracked < MAX_REVALUED; i--) {
            Position p = positions.get(i);
            if ("sold".equals(p.state) || p.compKey == null || p.compKey.isEmpty()) {
                continue;
            }
            if (isPetKey(p.compKey) && !hasCurrentPetKey(p)) {
                p.curPess = null; // legacy pet identity may predate c1/tb splits
                continue;
            }
            tracked++;
            // Comp keys contain '|' (v1|...) — java.net.URI rejects it raw,
            // so the path segment must be percent-encoded.
            String path = "/value/" + URLEncoder.encode(p.compKey, StandardCharsets.UTF_8);
            JsonElement el = api.get(path, 60_000);
            if (el != null && el.isJsonNull()) {
                // Definitive 404: the bucket dropped out of the valuation
                // build. A market-drop alert without a current market is a
                // cry-wolf — go dark, don't keep rendering an old number.
                p.curPess = null;
                continue;
            }
            if (el == null || !el.isJsonObject()) {
                continue; // in flight or offline — keep the last known value
            }
            if (api.isStale(path, REVALUE_STALE_MS)) {
                // get() serves expired entries while the API is unreachable
                // (stale-while-unreachable). A market-drop alert needs a
                // CURRENT market — go silent rather than cry wolf on old data.
                p.curPess = null;
                continue;
            }
            // has("pess") is TRUE for an explicit JSON null, and the
            // getAsDouble that followed threw UnsupportedOperationException
            // — from inside the HUD render loop, which has no try/catch
            // above it. optNum collapses absent, null and non-numeric into
            // the one case this code already handled: keep the last known
            // value and move on. Production omits rather than nulls
            // (measured 2026-08-09), so no live payload changes behaviour.
            Double pess = GoldFields.optNum(
                    GoldFields.optObj(el.getAsJsonObject(), "estimate"), "pess");
            if (pess == null) {
                continue;
            }
            p.curPess = pess;
        }
    }

    private static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return s.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    public static boolean hasCurrentPetKey(Position p) {
        return p != null && isPetKey(p.compKey)
                && CURRENT_PET_KEY_VERSION.equals(p.petKeyVersion);
    }

    private static boolean isPetKey(String compKey) {
        return compKey != null && compKey.startsWith("v1|PET|");
    }

    private List<Position> load() {
        try {
            Path f = path();
            if (Files.exists(f)) {
                List<Position> got = GSON.fromJson(Files.readString(f),
                        new TypeToken<List<Position>>() {}.getType());
                if (got != null) {
                    got.removeIf(p -> p == null || p.uuid == null);
                    return got;
                }
            }
        } catch (com.google.gson.JsonParseException e) {
            // Keep the bytes. This file is the user's position history and
            // cost basis; if it ever does get mangled, those bytes are the
            // only route back. Starting fresh straight over the top would
            // destroy the evidence at exactly the moment it matters, so the
            // bad file is set aside with a .corrupt suffix instead.
            Midasflip.LOG.warn("ledger unreadable, starting fresh: {}", e.toString());
            SafeWrite.quarantine(path());
        } catch (IOException | RuntimeException e) {
            // NOT corruption — a file locked by a backup or antivirus pass, an
            // EIO, a permissions blip. The bytes are probably intact, so the
            // file is neither renamed nor written: the ledger goes read-only
            // for the session and a restart recovers everything.
            Midasflip.LOG.warn("ledger could not be read · left untouched, tracking paused this session: {}",
                    e.toString());
            readOnly = true;
        }
        return new ArrayList<>();
    }

    private void save() {
        if (readOnly) {
            return; // see the field: never overwrite a file we failed to read
        }
        // Atomic: this file IS the user's position history and their cost
        // basis. A truncated write loses money they cannot reconstruct from
        // anywhere else, and the mod writes here on every purchase.
        SafeWrite.write(path(), GSON.toJson(positions));
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("midasflip-ledger.json");
    }
}
