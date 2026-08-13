package dev.midasflip.client;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;

import java.util.ArrayList;
import java.util.List;
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

    // BOUNDED before parseInt. The level is read from an item's DISPLAY
    // NAME, which is player-controllable text, and parse() runs on the
    // tooltip render path that has no try/catch above it. An 11-digit run
    // overflows Integer.parseInt and takes the game down on a hover. Golden
    // Dragon caps at 200, so three digits covers every real pet; anything
    // longer is not a pet and falls through to the market path, which is the
    // honest outcome (review 2026-08-10).
    private static final Pattern LVL = Pattern.compile("^\\[Lvl (\\d{1,3})] (.+)$");

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

    /** Approximate bucket for a pet whose EXP is unavailable from BOTH NBT
     * and lore. Rule ON preserves the legacy owner setting; rule OFF uses
     * the old level heuristic. Callers must label this approximate and keep
     * action conveniences such as clipboard pricing disarmed.
     *
     * <p>NOTE: this cannot return x3 (30M+ EXP), and that is not an
     * oversight. Display level genuinely does not determine the bucket at the
     * top of the curve: a legendary hits Lvl 100 at ~25.4M EXP (x2) and keeps
     * climbing past 30M (x3) with overflow feeding, showing the same
     * "[Lvl 100]" name the whole way. Guessing x3 here would be as wrong as
     * guessing x2. {@link #expFromLore} removes the guess instead of
     * refining it. */
    public static String approximateExpBucket(int level, MidasflipConfig config) {
        if (config.petLevelRule) {
            return level < config.petLevelPremiumFloor ? "x0" : "x2";
        }
        return level < 60 ? "x0" : level < 90 ? "x1" : "x2";
    }

    /** Exact pet EXP from the strongest source available: petInfo NBT first
     *  (authoritative), then MAX-LEVEL lore for the menus that strip it.
     *  Null only when neither is readable — callers then fall back to
     *  {@link #approximateExpBucket} and MUST label the result approximate.
     *
     *  <p>Every surface that prices a pet resolves EXP through HERE, so the
     *  tooltip and the sell panel cannot land in different buckets for the
     *  same pet. They could before, and did: one item, two prices, on screen
     *  at the same moment (owner, 2026-08-13). */
    public static Double exactExp(net.minecraft.world.item.ItemStack stack) {
        Double nbt = ItemId.petExp(stack);
        return nbt != null ? nbt : expFromLore(stack);
    }

    /** Exact total pet EXP read from LORE, for the NBT-stripped Hypixel menu
     *  GUIs (auction views, Create BIN Auction) where {@link ItemId#petExp}
     *  returns null. Hypixel prints the accumulated total under MAX LEVEL:
     *
     *  <pre>  MAX LEVEL
     *  ▸ 36,926,226 XP</pre>
     *
     *  <p>Owner incident 2026-08-13: a Lvl 100 Tarantula with 36.9M EXP
     *  (bucket x3) was priced by the tooltip in bucket x2 — sell target 11.1M
     *  against a 15.0M cost — because the menu stripped petInfo and the level
     *  heuristic above tops out at x2. The number was on screen the whole
     *  time; we just were not reading it.
     *
     *  <p>DELIBERATELY gated on MAX LEVEL. Below the cap Hypixel shows a
     *  "Progress to Level N" pair — EXP into the CURRENT level, not the
     *  total — and reading that as a total would under-bucket a valuable pet,
     *  a worse failure than the approximation it replaces. Sub-max levels
     *  also bound the total tightly enough for the heuristic; the ambiguity,
     *  and the money, live at the cap. Fails closed to null on anything
     *  unfamiliar or ambiguous. */
    public static Double expFromLore(net.minecraft.world.item.ItemStack stack) {
        var lore = stack.get(net.minecraft.core.component.DataComponents.LORE);
        if (lore == null) {
            return null;
        }
        List<String> plain = new ArrayList<>();
        for (var line : lore.lines()) {
            plain.add(line.getString());
        }
        return expFromLoreLines(plain);
    }

    /** The logic of {@link #expFromLore}, over already-extracted lore text —
     *  the seam the tests drive, since building a real ItemStack needs a
     *  running client. Color codes may still be present. */
    static Double expFromLoreLines(List<String> lore) {
        boolean maxLevel = false;
        Double found = null;
        for (String raw : lore) {
            String plain = raw.replaceAll("§.", "").strip();
            if (plain.equalsIgnoreCase("MAX LEVEL")) {
                maxLevel = true;
                continue;
            }
            Matcher m = LORE_EXP.matcher(plain);
            if (!m.matches()) {
                continue;
            }
            if (found != null) {
                return null; // two candidate totals: fail closed, don't pick
            }
            try {
                double exp = Double.parseDouble(m.group(1).replace(",", ""));
                if (!Double.isFinite(exp) || exp < 0) {
                    return null;
                }
                found = exp;
            } catch (NumberFormatException ignored) {
                return null; // absurd digit runs; not a real pet
            }
        }
        return maxLevel ? found : null;
    }

    /** A total-EXP lore line: an optional bullet glyph, grouped digits, "XP".
     *  Anchored at BOTH ends so a progress line carrying a slash or a second
     *  number ("1,234/5,678 XP") cannot match — only a bare total qualifies. */
    private static final Pattern LORE_EXP =
            Pattern.compile("^[^0-9]{0,4}([\\d,]+)\\s*XP$", Pattern.CASE_INSENSITIVE);

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
