package dev.midasflip.client;

import com.google.gson.Gson;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A named bundle of board thresholds — "one click swaps every threshold"
 * (owner's design). Share codes are {@code SF1|<base64url json>}: plain
 * data, no account, no code — import clamps everything through
 * {@link MidasflipConfig#normalize()} semantics so a hostile code can at
 * worst set weird thresholds, never anything else. Safety mode is
 * deliberately NOT part of a preset: nothing imported may ever switch the
 * mod toward sending.
 */
public final class Preset {
    private static final Gson GSON = new Gson();
    private static final String PREFIX = "SF1|";

    public long minProfit = 250_000;
    public long maxCost = 0;
    public double minConfidence = 0.70;
    public MidasflipConfig.Liquidity minLiquidity = MidasflipConfig.Liquidity.MED;
    public int maxHoldMin = 60;
    public boolean showFallingKnife = true;
    public boolean showPatient = true;
    public boolean hideCandied = false;
    /** Board ranking. Null on older codes — keeps the user's current mode. */
    public MidasflipConfig.SortMode sortMode;
    // "What's shown" filters (owner 2026-07-11). Zero/false on older codes
    // = off = unchanged board, so legacy SF1 codes stay meaningful.
    public int minMarginPct;
    public int minComps;
    public long minCost;
    public int minFillPct;
    public int maxSpreadPct;
    public int maxHoldP90Min;
    public boolean hideDerived;
    public boolean hideTierBoosted;
    /** Null on older codes — keeps the user's current setting. */
    public MidasflipConfig.ManipFilter manipFilter;
    /** Phase 4: presets carry category tuning + rules too (design:
     *  "carries thresholds · score · families · alert tiers"). Null on
     *  older codes — treated as empty. */
    public LinkedHashMap<String, MidasflipConfig.FamilyTune> familyTuning;
    public java.util.ArrayList<Rule> rules;

    // ---- config-completeness 2026-07-13: presets carry the rest of the
    // display-shaping knobs too. Every default here MIRRORS the config
    // default, so an older SF1 code (which lacks these fields) decodes to
    // exactly today's shipped behavior — legacy codes stay meaningful.
    // Deliberately EXCLUDED (never in a preset): apiToken, apiBase,
    // safetyMode, and the HUD anchor/offsets (local to the user's monitor).
    public int verdictConfPct = 80;
    public int verdictMinComps = 8;
    public int hudUnderwaterMaxRows = 3;
    public int hudUndercutMaxRows = 3;
    public int shellTableRows = 12;
    public int dashboardListingsMax = 5;
    public int tradesListingsMax = 6;
    public int tradesPositionsMax = 14;
    public int compsPeekSales = 4;
    public int marginAlertBannerMs = 5000;
    public boolean marginAlertSound = true;
    public boolean tabSound = true;
    public float marginAlertVolume = 0.4f;
    public boolean rawCoinNumbers = false;
    public boolean chatConfirmations = true;
    public int undercutPollSec = 60;
    public int purchaseToastMs = 4000;
    // Auction-tab display filters (plain clamped display data). The
    // auctionTab toggle itself is NOT carried — showing/hiding the tab is a
    // local layout choice, not a shared threshold.
    public int auctionMaxRows = 8;
    public int auctionMinMarginPct = 10;
    public int auctionMaxEndMin = 45;

    public static Preset fromConfig(MidasflipConfig c) {
        Preset p = new Preset();
        p.minProfit = c.minProfit;
        p.maxCost = c.maxCost;
        p.minConfidence = c.minConfidence;
        p.minLiquidity = c.minLiquidity;
        p.maxHoldMin = c.maxHoldMin;
        p.showFallingKnife = c.showFallingKnife;
        p.showPatient = c.showPatient;
        p.hideCandied = c.hideCandied;
        p.sortMode = c.sortMode;
        p.minMarginPct = c.minMarginPct;
        p.minComps = c.minComps;
        p.minCost = c.minCost;
        p.minFillPct = c.minFillPct;
        p.maxSpreadPct = c.maxSpreadPct;
        p.maxHoldP90Min = c.maxHoldP90Min;
        p.hideDerived = c.hideDerived;
        p.hideTierBoosted = c.hideTierBoosted;
        p.manipFilter = c.manipFilter;
        p.verdictConfPct = c.verdictConfPct;
        p.verdictMinComps = c.verdictMinComps;
        p.hudUnderwaterMaxRows = c.hudUnderwaterMaxRows;
        p.hudUndercutMaxRows = c.hudUndercutMaxRows;
        p.shellTableRows = c.shellTableRows;
        p.dashboardListingsMax = c.dashboardListingsMax;
        p.tradesListingsMax = c.tradesListingsMax;
        p.tradesPositionsMax = c.tradesPositionsMax;
        p.compsPeekSales = c.compsPeekSales;
        p.marginAlertBannerMs = c.marginAlertBannerMs;
        p.marginAlertSound = c.marginAlertSound;
        p.tabSound = c.tabSound;
        p.marginAlertVolume = c.marginAlertVolume;
        p.rawCoinNumbers = c.rawCoinNumbers;
        p.chatConfirmations = c.chatConfirmations;
        p.undercutPollSec = c.undercutPollSec;
        p.purchaseToastMs = c.purchaseToastMs;
        p.auctionMaxRows = c.auctionMaxRows;
        p.auctionMinMarginPct = c.auctionMinMarginPct;
        p.auctionMaxEndMin = c.auctionMaxEndMin;
        // Deep copy via gson: presets must not alias the live config.
        p.familyTuning = GSON.fromJson(GSON.toJson(c.familyTuning),
                new com.google.gson.reflect.TypeToken<LinkedHashMap<String, MidasflipConfig.FamilyTune>>() {}.getType());
        p.rules = GSON.fromJson(GSON.toJson(c.rules),
                new com.google.gson.reflect.TypeToken<java.util.ArrayList<Rule>>() {}.getType());
        return p;
    }

    /** Applies thresholds to the live config. Never touches safety mode;
     *  normalize() re-validates every imported rule and tune. */
    public void apply(MidasflipConfig c) {
        c.minProfit = minProfit;
        c.maxCost = maxCost;
        c.minConfidence = minConfidence;
        c.minLiquidity = minLiquidity == null ? MidasflipConfig.Liquidity.MED : minLiquidity;
        c.maxHoldMin = maxHoldMin;
        c.showFallingKnife = showFallingKnife;
        c.showPatient = showPatient;
        c.hideCandied = hideCandied;
        if (sortMode != null) c.sortMode = sortMode;
        c.minMarginPct = minMarginPct;
        c.minComps = minComps;
        c.minCost = minCost;
        c.minFillPct = minFillPct;
        c.maxSpreadPct = maxSpreadPct;
        c.maxHoldP90Min = maxHoldP90Min;
        c.hideDerived = hideDerived;
        c.hideTierBoosted = hideTierBoosted;
        if (manipFilter != null) c.manipFilter = manipFilter;
        c.verdictConfPct = verdictConfPct;
        c.verdictMinComps = verdictMinComps;
        c.hudUnderwaterMaxRows = hudUnderwaterMaxRows;
        c.hudUndercutMaxRows = hudUndercutMaxRows;
        c.shellTableRows = shellTableRows;
        c.dashboardListingsMax = dashboardListingsMax;
        c.tradesListingsMax = tradesListingsMax;
        c.tradesPositionsMax = tradesPositionsMax;
        c.compsPeekSales = compsPeekSales;
        c.marginAlertBannerMs = marginAlertBannerMs;
        c.marginAlertSound = marginAlertSound;
        c.tabSound = tabSound;
        c.marginAlertVolume = marginAlertVolume;
        c.rawCoinNumbers = rawCoinNumbers;
        c.chatConfirmations = chatConfirmations;
        c.undercutPollSec = undercutPollSec;
        c.purchaseToastMs = purchaseToastMs;
        c.auctionMaxRows = auctionMaxRows;
        c.auctionMinMarginPct = auctionMinMarginPct;
        c.auctionMaxEndMin = auctionMaxEndMin;
        c.familyTuning = familyTuning == null ? new LinkedHashMap<>() : new LinkedHashMap<>(familyTuning);
        c.rules = rules == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(rules);
        c.normalize();
        c.save();
    }

    public String shareCode() {
        return PREFIX + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(GSON.toJson(this).getBytes(StandardCharsets.UTF_8));
    }

    /** Null on anything that isn't a well-formed SF1 code. */
    public static Preset fromShareCode(String code) {
        if (code == null) {
            return null;
        }
        code = code.strip();
        if (!code.startsWith(PREFIX)) {
            return null;
        }
        try {
            String json = new String(
                    Base64.getUrlDecoder().decode(code.substring(PREFIX.length())),
                    StandardCharsets.UTF_8);
            Preset p = GSON.fromJson(json, Preset.class);
            if (p == null) {
                return null;
            }
            // Clamp: imported numbers are untrusted.
            p.minProfit = Math.max(0, p.minProfit);
            p.maxCost = Math.max(0, p.maxCost);
            p.minConfidence = Math.min(Math.max(p.minConfidence, 0.0), 0.99);
            p.maxHoldMin = Math.min(Math.max(p.maxHoldMin, 0), 24 * 60);
            p.minMarginPct = Math.min(Math.max(p.minMarginPct, 0), 100);
            p.minComps = Math.min(Math.max(p.minComps, 0), 100);
            p.minCost = Math.max(0, p.minCost);
            p.minFillPct = Math.min(Math.max(p.minFillPct, 0), 100);
            p.maxSpreadPct = Math.min(Math.max(p.maxSpreadPct, 0), 100);
            p.maxHoldP90Min = Math.min(Math.max(p.maxHoldP90Min, 0), 24 * 60);
            // config-completeness 2026-07-13: same clamp ranges as
            // MidasflipConfig.normalize() (apply() re-runs normalize anyway;
            // this is defense-in-depth on the untrusted import).
            p.verdictConfPct = Math.min(Math.max(p.verdictConfPct, 50), 99);
            p.verdictMinComps = Math.min(Math.max(p.verdictMinComps, 1), 50);
            p.hudUnderwaterMaxRows = Math.min(Math.max(p.hudUnderwaterMaxRows, 0), 10);
            p.hudUndercutMaxRows = Math.min(Math.max(p.hudUndercutMaxRows, 0), 10);
            p.shellTableRows = Math.min(Math.max(p.shellTableRows, 5), 30);
            p.dashboardListingsMax = Math.min(Math.max(p.dashboardListingsMax, 0), 20);
            p.tradesListingsMax = Math.min(Math.max(p.tradesListingsMax, 0), 20);
            p.tradesPositionsMax = Math.min(Math.max(p.tradesPositionsMax, 5), 50);
            p.compsPeekSales = Math.min(Math.max(p.compsPeekSales, 1), 10);
            p.marginAlertBannerMs = Math.min(Math.max(p.marginAlertBannerMs, 1000), 15_000);
            p.marginAlertVolume = Math.min(Math.max(p.marginAlertVolume, 0f), 1f);
            p.undercutPollSec = Math.min(Math.max(p.undercutPollSec, 30), 300);
            p.purchaseToastMs = Math.min(Math.max(p.purchaseToastMs, 1000), 10_000);
            p.auctionMaxRows = Math.min(Math.max(p.auctionMaxRows, 3), 15);
            p.auctionMinMarginPct = Math.min(Math.max(p.auctionMinMarginPct, 0), 100);
            p.auctionMaxEndMin = Math.min(Math.max(p.auctionMaxEndMin, 5), 45);
            if (p.minLiquidity == null) {
                p.minLiquidity = MidasflipConfig.Liquidity.MED;
            }
            return p;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Built-ins straight from the owner's design mockups. */
    public static Map<String, Preset> builtIns() {
        Map<String, Preset> m = new LinkedHashMap<>();
        Preset conservative = new Preset();
        conservative.minProfit = 1_000_000;
        conservative.minConfidence = 0.85;
        conservative.minLiquidity = MidasflipConfig.Liquidity.HIGH;
        conservative.showFallingKnife = false;
        conservative.showPatient = false;
        m.put("conservative", conservative);

        m.put("default", new Preset());

        Preset aggressive = new Preset();
        aggressive.minProfit = 100_000;
        aggressive.minConfidence = 0.60;
        aggressive.minLiquidity = MidasflipConfig.Liquidity.LOW;
        m.put("aggressive", aggressive);
        return m;
    }
}
