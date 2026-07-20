package dev.midasflip.client;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Pure string-level parsing of Hypixel's petInfo payload (a JSON STRING
 * stored inside ExtraAttributes — historically string-or-compound, so we
 * parse the text form defensively with whitespace-tolerant patterns
 * rather than a strict JSON parser that a format wobble would break).
 *
 * Deliberately free of any Minecraft import: this is the unit-testable
 * core behind ItemId's pet readers, and the identity math here decides
 * which comp-key bucket a pet prices from — the highest-stakes parsing
 * in the client (a wrong bucket misprices by 30%+, measured 2026-07-08).
 */
final class PetInfoText {
    private PetInfoText() {}

    private static final Pattern EXP = Pattern.compile(
            "\"exp\"\\s*:\\s*((?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?)");
    private static final Pattern CANDY = Pattern.compile("\"candyUsed\"\\s*:\\s*(\\d+)");
    private static final Pattern TIER_BOOST = Pattern.compile(
            "\"heldItem\"\\s*:\\s*\"PET_ITEM_TIER_BOOST\"");

    /** Exact EXP, or null when absent/non-finite/negative. */
    static Double exp(String petInfo) {
        if (petInfo == null || petInfo.isEmpty()) {
            return null;
        }
        var m = EXP.matcher(petInfo);
        if (!m.find()) {
            return null;
        }
        try {
            double exp = Double.parseDouble(m.group(1));
            return Double.isFinite(exp) && exp >= 0 ? exp : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** Uppercased string field (type/tier/skin/...), or null when absent. */
    static String field(String petInfo, String field) {
        if (petInfo == null || petInfo.isEmpty()) {
            return null;
        }
        var m = Pattern.compile(
                "\"" + Pattern.quote(field) + "\"\\s*:\\s*\"([A-Za-z0-9_:.-]{1,80})\"")
                .matcher(petInfo);
        return m.find() ? m.group(1).toUpperCase(Locale.ROOT) : null;
    }

    /** candyUsed > 0 — prices from the discounted |c1 bucket. False when
     *  unreadable (defaults to the clean bucket, labeled upstream). */
    static boolean candied(String petInfo) {
        if (petInfo == null || petInfo.isEmpty()) {
            return false;
        }
        var m = CANDY.matcher(petInfo);
        if (!m.find()) {
            return false;
        }
        try {
            return Integer.parseInt(m.group(1)) > 0;
        } catch (NumberFormatException ignored) {
            return true; // 11+ digit candyUsed is still "candied", not a crash
        }
    }

    /** Tier-boost held item — the |tb bucket (natural-tier -30% median). */
    static boolean tierBoosted(String petInfo) {
        return petInfo != null && TIER_BOOST.matcher(petInfo).find();
    }
}
