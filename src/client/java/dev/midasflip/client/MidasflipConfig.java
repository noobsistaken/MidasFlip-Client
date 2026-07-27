package dev.midasflip.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * MidasFlip client config, persisted as JSON in the Fabric config dir.
 *
 * <p>Safety mode is the load-bearing setting (spec §13):
 * <ul>
 *   <li>STRICT — overlay only; the open-keybind copies the /viewauction
 *       command to the clipboard, the player pastes it themselves.</li>
 *   <li>ASSISTED — one physical keypress sends /viewauction once; with
 *       the opt-in purchase confirm overlay (owner amendment 2026-07-05),
 *       one physical click in the buy zone forwards as exactly one slot
 *       interaction on auction GUIs the mod itself opened. One physical
 *       input = at most one game action, always.</li>
 * </ul>
 * There is no mode that automates anything, and there never will be
 * (explicit non-goals, spec §14). Hypixel's final confirm GUI is never
 * overlaid.
 */
public final class MidasflipConfig {
    public enum SafetyMode { STRICT, ASSISTED }

    /** Board ranking: the score blend is the default,
     *  but %-margin flippers and absolute-coins whales read the same
     *  board differently. Display-order only — server gates unchanged. */
    public enum SortMode {
        SCORE("score"), MARGIN("% margin"), PROFIT("coins");
        public final String label;
        SortMode(String label) { this.label = label; }
        public java.util.Comparator<Flip> comparator() {
            return switch (this) {
                case SCORE -> java.util.Comparator.comparingDouble((Flip f) -> f.score).reversed();
                case MARGIN -> java.util.Comparator.comparingDouble((Flip f) -> f.netMarginPct).reversed();
                case PROFIT -> java.util.Comparator.comparingDouble((Flip f) -> f.netProfit).reversed();
            };
        }
    }

    /** Liquidity floor tiers (design: "min liquidity low/med/high"). */
    public enum Liquidity {
        LOW(2.0), MED(5.0), HIGH(10.0);
        public final double salesPerDay;
        Liquidity(double spd) { this.salesPerDay = spd; }
    }

    /** The ONE place hostnames live (born-clean rule, owner 2026-07-19).
     *  midasflip.com verified serving 2026-07-19 (TLS + app + headers).
     *  Existing installs keep whatever apiBase their config saved — only
     *  new installs read this default. SITE_HOST is the public brand
     *  domain used in user-facing copy. */
    public static final String DEFAULT_API_BASE = "https://midasflip.com";
    public static final String SITE_HOST = "midasflip.com";

    /** Default = the live production API (skyflip.xyz went live 2026-07);
     *  existing saved configs keep their own value — only the default for
     *  fresh installs changed from the dev localhost:8000. */
    public String apiBase = DEFAULT_API_BASE;
    // In-memory only (transient = Gson never writes OR reads it). The
    // token lives in TokenStore (OS keychain, else owner-only obfuscated
    // file) — it stopped being part of midasflip.json in the GPL release:
    // users paste this file into support channels, and plaintext tokens
    // in it were a credential leak waiting to happen.
    public transient String apiToken = "";
    public SafetyMode safetyMode = SafetyMode.STRICT; // safe default
    public boolean overlayEnabled = true;
    public int overlayMaxRows = 5;
    public double minScore = 0.0;
    public SortMode sortMode = SortMode.SCORE;
    /** Cooldown between command sends — a rapid-fire keybind must not look botlike (self-safety, spec §10). */
    public long commandCooldownMs = 2000;

    // Thresholds (design "default" preset: mp 250k · cf 70 · liq med).
    // Client-side display filters ON TOP of the server's hard gates.
    public long minProfit = 250_000;
    public long maxCost = 0; // 0 = unlimited
    public double minConfidence = 0.70;
    public Liquidity minLiquidity = Liquidity.MED;
    public int maxHoldMin = 60; // 0 = off
    public boolean showFallingKnife = true; // shown flagged; false hides them
    /** Hide candied-pet flips (owner 2026-07-11). The comp key carries the
     *  candied split (|c1), so the filter is server-truth, not name
     *  parsing. Default shows them — candied buckets price against their
     *  own comps since v1.1, so they are honest flips, just less liquid. */
    public boolean hideCandied = false;

    // ---- "What's shown" (owner 2026-07-11): every field the algorithm
    // surfaces is filterable in-game. All defaults = off = today's board;
    // these are CLIENT display filters on top of the server's hard gates,
    // never a way around them. ----
    public enum ManipFilter {
        SHOW_ALL("show all"), HIDE_HIGH("hide ⚑ high"), HIDE_MED_HIGH("hide ⚐ med+");
        public final String label;
        ManipFilter(String label) { this.label = label; }
    }

    // Verdict tier thresholds (config-completeness 2026-07-13): the
    // GREEN-vs-AMBER cut was hard-coded as conf>=0.80 && comps>=8 in three
    // places (FlipHud row tier, PurchaseOverlay header, Phos.tierColor).
    // Now one pair of tunables routes all of them. Display-only — this
    // colors the verdict chip, it never gates what the server ships.
    public int verdictConfPct = 80;     // GREEN needs conf ≥ this %, clamp [50,99]
    public int verdictMinComps = 8;     // GREEN needs comps ≥ this, clamp [1,50]

    public int minMarginPct = 0;        // net margin %, 0 = off
    public int minComps = 0;            // comps behind the estimate, 0 = off
    public long minCost = 0;            // hide cheap flips (whale mode), 0 = off
    public int minFillPct = 0;          // sell-through floor %, 0 = off; unknown fill passes
    public int maxSpreadPct = 0;        // bucket dispersion cap (relative MAD %), 0 = off
    public int maxHoldP90Min = 0;       // slow-tail cap (p90 hold, minutes), 0 = off
    public boolean hideDerived = false; // only direct-comps estimates
    public boolean hideTierBoosted = false; // |tb pet buckets
    public ManipFilter manipFilter = ManipFilter.SHOW_ALL;

    /** Purchase confirm overlay (owner spec §13/§14 amendment 2026-07-05):
     *  OFF by default; only effective in ASSISTED mode; only on auction
     *  GUIs this mod itself opened. */
    public boolean purchaseOverlay = false;

    // ---- Auctions tab (bid-flip candidates from /auctions/bids). OFF by
    // default (owner: "should also be toggleable" — when off the sidebar
    // hides the tab entirely). auctionMinMarginPct / auctionMaxEndMin are
    // CLIENT display filters over the server's bid board, never a way past
    // the server's pro gate. TODO(config-wiring): carry these in Preset
    // share codes once the follow-up config pass lands. ----
    public boolean auctionTab = false;      // sidebar tab hidden until enabled
    public int auctionMaxRows = 8;          // clamp [3,15]
    public int auctionMinMarginPct = 10;    // margin_at_next floor %, clamp [0,100]
    public int auctionMaxEndMin = 45;       // hide auctions ending further out, clamp [5,45]

    // ---- HUD position (config-completeness 2026-07-13). LOCAL-ONLY: this
    // is personal to the user's monitor, so it is deliberately NOT part of
    // a Preset/SF1 code — a shared code must never yank the HUD across
    // someone else's screen. Anchor positions the whole block; right/bottom
    // anchors mirror the origin, text still draws left-to-right. ----
    public enum HudAnchor { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }
    public HudAnchor hudAnchor = HudAnchor.TOP_LEFT;
    public int hudOffsetX = 4;              // inset from the anchored edge(s), clamp [0,200]
    public int hudOffsetY = 4;

    // ---- Row caps (config-completeness 2026-07-13): the magic 3/12/5/6/14/4
    // literals scattered across the HUD and shell panes are now tunable.
    // Pure display counts — none of these change what the server vouches
    // for. shellTableRows is the shared cap for the flips/craft/bz/npc
    // tables (the auctions pane keeps its own auctionMaxRows). ----
    public int hudUnderwaterMaxRows = 3;    // clamp [0,10]
    public int hudUndercutMaxRows = 3;      // clamp [0,10]
    public int shellTableRows = 12;         // shared table density, clamp [5,30]
    public int dashboardListingsMax = 5;    // dashboard "your open auctions", clamp [0,20]
    public int tradesListingsMax = 6;       // trades "my listings", clamp [0,20]
    public int tradesPositionsMax = 14;     // trades positions ledger, clamp [5,50]
    public int compsPeekSales = 4;          // sale lines in the comps peek, clamp [1,10]

    // ---- Margin-alert tuning (config-completeness 2026-07-13). The banner
    // is display-only (never over a GUI; see FlipHud) — these just change
    // its dwell and the one-shot arm chirp. pitch stays fixed. ----
    public int marginAlertBannerMs = 5000;  // banner dwell, clamp [1000,15000]
    public boolean marginAlertSound = true; // play the one-shot arm chirp
    public boolean tabSound = true;         // faint click when the menu changes tab
    public float marginAlertVolume = 0.4f;  // chirp volume, clamp [0,1]

    // ---- Coin formatting (config-completeness 2026-07-13). false = the
    // canonical compact form (Phos.coins: 1.23B / 45.6M / 789k); true =
    // grouped integers ("83,421,556") for people who want exact numbers. ----
    public boolean rawCoinNumbers = false;

    // ---- Chat verbosity (config-completeness 2026-07-13). Gates only the
    // success/confirmation lines ("opened X", "copied /viewauction"); the
    // cooldown-rejected and malformed-uuid lines are troubleshooting
    // signals and stay on regardless. ----
    public boolean chatConfirmations = true;

    // ---- Undercut poll cadence (config-completeness 2026-07-13). HARD
    // clamp [30,300]: the 30s floor is politeness (the API poll budget), not
    // a preference — it is enforced in normalize(), not just the UI. ----
    public int undercutPollSec = 60;

    // ---- Purchase toast dwell (config-completeness 2026-07-13). How long
    // the "bought X" toast lingers. clamp [1000,10000]. ----
    public int purchaseToastMs = 4000;

    /** UI accent color (RGB, no alpha). Default = the shipped amber; the
     *  Theme tab writes this. Presets: amber D9944A, mint 45D48A. */
    public int accentColor = 0xD9944A;

    /** Append MidasFlip's data under matching items' hover tooltips. */
    public boolean itemTooltip = true;

    /** Sell-assist side panel (display-only) when listing an item.
     *  Always on by default (owner 2026-07-05). */
    public boolean sellOverlay = true;

    /** Undercut alerts (owner 2026-07-12): your open listings monitored
     *  vs the bucket floor; HUD lines + one-shot LOCAL chat when someone
     *  lists below you. Display-only — repricing stays your clicks. */
    public boolean undercutAlerts = true;

    /** Full-screen margin alert (owner 2026-07-12): when a flip whose net
     *  margin %% clears the threshold lands on the board, flash a centered
     *  banner naming the open-best keybind. DISPLAY-ONLY: never rendered
     *  while any GUI screen is open, never sends or clicks anything —
     *  the only path to an action is the human pressing the key, which
     *  goes through ActionController's normal checks. */
    public boolean marginAlert = false;
    public int marginAlertPct = 30;

    /** Show the current-lowest-BIN line in item tooltips (depth-aware:
     *  lbin + next listing + how many sit at the floor). */
    public boolean lbinTooltip = true;

    /** Keep a flip on the HUD for a few seconds after reconciliation
     *  confirms it was bought, marked "taken", so you SEE it leave rather
     *  than have the row silently vanish (owner 2026-07-15). */
    public boolean showTakenFlips = true;

    /** Copy the suggested sell price to the clipboard when the sell panel
     *  shows it, so a physical Cmd/Ctrl+V fills the sign (owner 2026-07-06,
     *  "copy and paste the price"). The mod NEVER writes the sign buffer:
     *  in MC, closing a sign submits its contents, so only a human paste
     *  is safe. */
    public boolean sellCopyPrice = true;

    /** Pet level rule (owner 2026-07-05, OFF by default): when on, a pet
     *  BELOW the floor prices as fresh (lvl-1 bucket) and at/above as
     *  near-max. When off, the bucket follows a plain level heuristic. */
    public boolean petLevelRule = false;
    public int petLevelPremiumFloor = 95;

    /** Named custom presets (insertion-ordered for stable menu layout). */
    public java.util.LinkedHashMap<String, Preset> customPresets = new java.util.LinkedHashMap<>();

    /** Per-family threshold overrides (Phase 4 category tuning). Null
     *  fields inherit the globals; hide=true drops the family outright. */
    public static final class FamilyTune {
        public Long minProfit;
        public Double minConfidence;
        public Double minSalesPerDay;
        public Boolean hide;
    }

    public java.util.LinkedHashMap<String, FamilyTune> familyTuning = new java.util.LinkedHashMap<>();

    /** Raw rules (Phase 4) — plain-data predicates, see {@link Rule}. */
    public java.util.ArrayList<Rule> rules = new java.util.ArrayList<>();

    /** The display filter — every board row and cycle pick passes this. */
    public boolean passes(Flip f) {
        FamilyTune t = f.family == null ? null : familyTuning.get(f.family);
        if (t != null && Boolean.TRUE.equals(t.hide)) return false;
        long effMinProfit = t != null && t.minProfit != null ? t.minProfit : minProfit;
        double effMinConf = t != null && t.minConfidence != null ? t.minConfidence : minConfidence;
        double effMinSpd = t != null && t.minSalesPerDay != null ? t.minSalesPerDay : minLiquidity.salesPerDay;

        if (f.score < minScore || f.netProfit < effMinProfit) return false;
        if (maxCost > 0 && f.buyPrice > maxCost) return false;
        if (f.confidence < effMinConf) return false;
        if (f.salesPerDay < effMinSpd) return false;
        if (maxHoldMin > 0 && f.holdMedS != null && f.holdMedS > maxHoldMin * 60L) return false;
        if (!showFallingKnife && f.fallingKnife) return false;
        if (hideCandied && f.compKey != null && f.compKey.contains("|c1")) return false;

        // "What's shown" filters — 0/SHOW_ALL = off. Unknown values pass
        // (a missing fill/p90 must not hide a flip the server vouched for).
        if (minMarginPct > 0 && f.netMarginPct * 100 < minMarginPct) return false;
        if (minComps > 0 && f.comps < minComps) return false;
        if (minCost > 0 && f.buyPrice < minCost) return false;
        if (minFillPct > 0 && f.fillPct != null && f.fillPct < minFillPct) return false;
        if (maxSpreadPct > 0 && f.spread * 100 > maxSpreadPct) return false;
        if (maxHoldP90Min > 0 && f.holdP90S != null && f.holdP90S > maxHoldP90Min * 60L) return false;
        if (hideDerived && f.source != null && !"comps".equals(f.source)) return false;
        if (hideTierBoosted && f.compKey != null && f.compKey.contains("|tb")) return false;
        if (manipFilter == ManipFilter.HIDE_HIGH && "high".equals(f.manip)) return false;
        if (manipFilter == ManipFilter.HIDE_MED_HIGH
                && ("high".equals(f.manip) || "med".equals(f.manip))) return false;
        return Rule.rulesAllow(rules, f);
    }

    /** Hard floor: user-edited config can't disable the anti-spam cooldown. */
    private static final long MIN_COOLDOWN_MS = 500;

    public long effectiveCooldownMs() {
        return Math.max(commandCooldownMs, MIN_COOLDOWN_MS);
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static MidasflipConfig load() {
        Path file = path();
        try {
            if (!Files.exists(file)) {
                // Rename migration: a pre-rename install keeps every
                // setting. Read-once from the old filename; the save at
                // the end of load() writes the new one. The old file is
                // left in place (harmless, token already stripped by the
                // token-store migration on its first post-update load).
                Path legacy = FabricLoader.getInstance().getConfigDir().resolve("skyflip.json");
                if (Files.exists(legacy)) {
                    file = legacy;
                }
            }
            boolean fromLegacy = !file.equals(path());
            if (Files.exists(file)) {
                String raw = Files.readString(file);
                MidasflipConfig cfg = GSON.fromJson(raw, MidasflipConfig.class);
                if (cfg != null) {
                    cfg.normalize();
                    cfg.apiToken = TokenStore.load();
                    // One-time migration: configs written before the token
                    // store carried the token in plaintext right here.
                    // Move it out, then rewrite the file (save() can no
                    // longer serialize the transient field) so the
                    // plaintext leaves the disk.
                    String legacy = legacyToken(raw);
                    if (!legacy.isEmpty()) {
                        if (cfg.apiToken.isEmpty()) {
                            if (TokenStore.save(legacy)) {
                                cfg.apiToken = legacy;
                                Midasflip.LOG.info("api token migrated out of midasflip.json into the token store");
                            } else {
                                // Keep working this session, but say so plainly.
                                cfg.apiToken = legacy;
                                Midasflip.LOG.warn("token store unavailable — token kept in memory only this session");
                            }
                        }
                        // The point of the migration is that plaintext LEAVES
                        // the disk — rewrite whenever the file still carries
                        // it, even if the store already had a (newer) token.
                        // Only skip when we would lose the sole copy.
                        if (!cfg.apiToken.isEmpty() && !cfg.save()) {
                            Midasflip.LOG.warn("could not rewrite midasflip.json — legacy plaintext token still on disk");
                        }
                    }
                    if (fromLegacy) {
                        // Complete the rename migration: write the config
                        // under the new filename so the fallback read is
                        // one-time, not forever.
                        cfg.save();
                    }
                    return cfg;
                }
                Midasflip.LOG.warn("config was empty/blank — using safe defaults");
            }
        } catch (IOException | RuntimeException e) {
            Midasflip.LOG.warn("config unreadable, using defaults: {}", e.toString());
        }
        MidasflipConfig def = new MidasflipConfig();
        def.save();
        return def;
    }

    /** Fail safe: any unrecognized/absent safety mode becomes STRICT (the
     *  never-send mode), so a corrupt config can never enable sending.
     *  Thresholds clamp to sane ranges — a hand-edited or imported config
     *  must not produce a nonsense board. */
    void normalize() {
        if (safetyMode == null) {
            Midasflip.LOG.warn("config safetyMode unrecognized — forcing STRICT (never-send)");
            safetyMode = SafetyMode.STRICT;
        }
        if (minLiquidity == null) minLiquidity = Liquidity.MED;
        if (sortMode == null) sortMode = SortMode.SCORE;
        if (manipFilter == null) manipFilter = ManipFilter.SHOW_ALL;
        if (hudAnchor == null) hudAnchor = HudAnchor.TOP_LEFT;
        verdictConfPct = Math.min(Math.max(verdictConfPct, 50), 99);
        verdictMinComps = Math.min(Math.max(verdictMinComps, 1), 50);
        hudOffsetX = Math.min(Math.max(hudOffsetX, 0), 200);
        hudOffsetY = Math.min(Math.max(hudOffsetY, 0), 200);
        hudUnderwaterMaxRows = Math.min(Math.max(hudUnderwaterMaxRows, 0), 10);
        hudUndercutMaxRows = Math.min(Math.max(hudUndercutMaxRows, 0), 10);
        shellTableRows = Math.min(Math.max(shellTableRows, 5), 30);
        dashboardListingsMax = Math.min(Math.max(dashboardListingsMax, 0), 20);
        tradesListingsMax = Math.min(Math.max(tradesListingsMax, 0), 20);
        tradesPositionsMax = Math.min(Math.max(tradesPositionsMax, 5), 50);
        compsPeekSales = Math.min(Math.max(compsPeekSales, 1), 10);
        marginAlertBannerMs = Math.min(Math.max(marginAlertBannerMs, 1000), 15_000);
        marginAlertVolume = Math.min(Math.max(marginAlertVolume, 0f), 1f);
        // The 30s floor is politeness (API poll budget), not a preference.
        undercutPollSec = Math.min(Math.max(undercutPollSec, 30), 300);
        purchaseToastMs = Math.min(Math.max(purchaseToastMs, 1000), 10_000);
        minMarginPct = Math.min(Math.max(minMarginPct, 0), 100);
        minComps = Math.min(Math.max(minComps, 0), 100);
        minCost = Math.max(0, minCost);
        minFillPct = Math.min(Math.max(minFillPct, 0), 100);
        maxSpreadPct = Math.min(Math.max(maxSpreadPct, 0), 100);
        maxHoldP90Min = Math.min(Math.max(maxHoldP90Min, 0), 24 * 60);
        minProfit = Math.max(0, minProfit);
        maxCost = Math.max(0, maxCost);
        minConfidence = Math.min(Math.max(minConfidence, 0.0), 0.99);
        maxHoldMin = Math.min(Math.max(maxHoldMin, 0), 24 * 60);
        overlayMaxRows = Math.min(Math.max(overlayMaxRows, 1), 15);
        auctionMaxRows = Math.min(Math.max(auctionMaxRows, 3), 15);
        auctionMinMarginPct = Math.min(Math.max(auctionMinMarginPct, 0), 100);
        auctionMaxEndMin = Math.min(Math.max(auctionMaxEndMin, 5), 45);
        // The 500 floor mirrors MIN_COOLDOWN_MS (effectiveCooldownMs() is
        // still the hard clamp); the cap keeps a hand-edited config sane.
        commandCooldownMs = Math.min(Math.max(commandCooldownMs, 500), 60_000);
        marginAlertPct = Math.min(Math.max(marginAlertPct, 5), 200);
        petLevelPremiumFloor = Math.min(Math.max(petLevelPremiumFloor, 1), 200);
        if (customPresets == null) customPresets = new java.util.LinkedHashMap<>();
        customPresets.values().removeIf(java.util.Objects::isNull);
        if (familyTuning == null) familyTuning = new java.util.LinkedHashMap<>();
        familyTuning.values().removeIf(java.util.Objects::isNull);
        if (rules == null) rules = new java.util.ArrayList<>();
        rules.removeIf(r -> r == null || !r.valid());
        if (rules.size() > 20) rules.subList(20, rules.size()).clear();
        accentColor &= 0xFFFFFF;
        dev.midasflip.client.ui.Phos.applyAccent(accentColor);
        dev.midasflip.client.ui.Phos.setRawCoins(rawCoinNumbers);
    }

    /**
     * Persist the complete client config. Callers that only change display
     * preferences may ignore the result; credential hand-off must check it
     * before acknowledging a one-shot secret to the server.
     */
    public boolean save() {
        try {
            Path p = path();
            Files.writeString(p, GSON.toJson(this));
            // Owner-only where supported: the config still holds pairing
            // metadata and preferences nobody else's processes need.
            TokenStore.restrictToOwner(p);
            return true;
        } catch (IOException e) {
            Midasflip.LOG.warn("config save failed: {}", e.toString());
            return false;
        }
    }

    /** Pure extraction of the legacy plaintext token from a raw config
     *  JSON string (pre-token-store files). Testable without Fabric. */
    static String legacyToken(String rawJson) {
        try {
            var o = com.google.gson.JsonParser.parseString(rawJson);
            if (o.isJsonObject() && o.getAsJsonObject().has("apiToken")) {
                var t = o.getAsJsonObject().get("apiToken");
                if (t.isJsonPrimitive()) {
                    String v = t.getAsString();
                    return v == null ? "" : v;
                }
            }
        } catch (RuntimeException ignored) {
        }
        return "";
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("midasflip.json");
    }
}
