package dev.midasflip.client;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pet display-name parsing for by-name pricing. Pets render as
 * "[Lvl 87] Ender Dragon" with the tier encoded in the NAME COLOR —
 * both are recoverable client-side without NBT.
 *
 * Display level is only a fallback when Hypixel stripped petInfo. Exact
 * pricing sends raw NBT EXP to the backend so the collector/backend own the
 * canonical bucket thresholds.
 */
public final class Pets {
    private Pets() {}

    private static final Pattern LVL = Pattern.compile("^\\[Lvl (\\d+)] (.+)$");

    public record PetName(String type, int level, String tier) {}

    /** Parse "[Lvl N] Name" (+ tier from the name's color); null if not a pet. */
    public static PetName parse(Component hoverName) {
        String plain = hoverName.getString().strip();
        Matcher m = LVL.matcher(plain);
        if (!m.matches()) {
            return null;
        }
        return new PetName(m.group(2), Integer.parseInt(m.group(1)), tierOf(hoverName));
    }

    /** Approximate bucket for a pet whose NBT EXP is unavailable. Rule ON
     * preserves the legacy owner setting; rule OFF uses the old level
     * heuristic. Callers must label this approximate and keep action
     * conveniences such as clipboard pricing disarmed. */
    public static String approximateExpBucket(int level, MidasflipConfig config) {
        if (config.petLevelRule) {
            return level < config.petLevelPremiumFloor ? "x0" : "x2";
        }
        return level < 60 ? "x0" : level < 90 ? "x1" : "x2";
    }

    /** Collector-compatible effective tier for tier-boosted pets. */
    public static String effectiveTier(String naturalTier, boolean tierBoosted) {
        String tier = naturalTier == null ? "" : naturalTier.toUpperCase(Locale.ROOT);
        if (!tierBoosted) {
            return tier;
        }
        return switch (tier) {
            case "COMMON" -> "UNCOMMON";
            case "UNCOMMON" -> "RARE";
            case "RARE" -> "EPIC";
            case "EPIC" -> "LEGENDARY";
            case "LEGENDARY" -> "MYTHIC";
            default -> tier;
        };
    }

    /** Tier from the colored segment after "] " — Hypixel colors pet names
     *  by rarity. Empty string when indeterminate (server then tries the
     *  most-traded tiers in order). */
    private static String tierOf(Component name) {
        final String[] found = {""};
        name.visit((style, text) -> {
            if (found[0].isEmpty() && style.getColor() != null && !text.isBlank()
                    && !text.contains("[") && !text.contains("Lvl")) {
                found[0] = mapColor(style.getColor());
            }
            return Optional.empty();
        }, net.minecraft.network.chat.Style.EMPTY);
        return found[0];
    }

    private static String mapColor(TextColor c) {
        String n = c.toString().toLowerCase(Locale.ROOT);
        return switch (n) {
            case "gold" -> "LEGENDARY";
            case "dark_purple" -> "EPIC";
            case "blue" -> "RARE";
            case "green" -> "UNCOMMON";
            case "white", "gray" -> "COMMON";
            case "light_purple" -> "MYTHIC";
            default -> "";
        };
    }
}
