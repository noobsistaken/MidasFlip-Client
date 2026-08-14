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
     * @deprecated superseded by {@link #bucketFromLevel}, which is exact
     *     wherever it answers at all. This is TIER-BLIND, and tier is the
     *     dominant term: level 88 is 10,032,830 EXP for a legendary (x2) and
     *     2,380,385 for a common (x1). It also gets the legendary boundaries
     *     wrong in both directions — it calls 57-59 x0 (really x1) and 88-89
     *     x1 (really x2), which underpriced a Lvl 88 Wolf at 5.0M against a
     *     real x2 value of 16.9M (owner, 2026-08-14). Retained only so an
     *     older config path keeps compiling; do not add callers.
     */
    @Deprecated
    public static String approximateExpBucket(int level, MidasflipConfig config) {
        if (config.petLevelRule) {
            return level < config.petLevelPremiumFloor ? "x0" : "x2";
        }
        return level < 60 ? "x0" : level < 90 ? "x1" : "x2";
    }

    // ---- Hypixel's pet EXP ladder -----------------------------------------
    //
    // One shared per-level cost array; RARITY SELECTS A STARTING OFFSET into
    // it. Verified against two independent open-source implementations that
    // agree byte-for-byte (NotEnoughUpdates-REPO constants/pets.json and
    // SkyCrypt src/constants/pets.js), and anchored on a live screenshot: a
    // legendary's level 88 -> 89 costs PET_LEVELS[107] = 791,700, exactly the
    // "324,577.4/791.7k" the game printed.
    //
    // PetLevelsTest reproduces all six published level-100 totals from this
    // array. That is the point of those assertions: a mistyped digit anywhere
    // in 119 numbers fails a test instead of silently mispricing a pet.

    /** Cost of the level-up at index i; a pet of rarity offset O pays
     *  PET_LEVELS[O + L - 1] to go from level L to L+1. 119 entries =
     *  20 (max offset) + 99 (level-ups to 100). */
    private static final int[] PET_LEVELS = {
        100, 110, 120, 130, 145, 160, 175, 190, 210, 230,
        250, 275, 300, 330, 360, 400, 440, 490, 540, 600,
        660, 730, 800, 880, 960, 1050, 1150, 1260, 1380, 1510,
        1650, 1800, 1960, 2130, 2310, 2500, 2700, 2920, 3160, 3420,
        3700, 4000, 4350, 4750, 5200, 5700, 6300, 7000, 7800, 8700,
        9700, 10800, 12000, 13300, 14700, 16200, 17800, 19500, 21300, 23200,
        25200, 27400, 29800, 32400, 35200, 38200, 41400, 44800, 48400, 52200,
        56200, 60400, 64800, 69400, 74200, 79200, 84700, 90700, 97200, 104200,
        111700, 119700, 128200, 137200, 146700, 156700, 167700, 179700, 192700, 206700,
        221700, 237700, 254700, 272700, 291700, 311700, 333700, 357700, 383700, 411700,
        441700, 476700, 516700, 561700, 611700, 666700, 726700, 791700, 861700, 936700,
        1016700, 1101700, 1191700, 1286700, 1386700, 1496700, 1616700, 1746700, 1886700,
    };

    /** Levels 101-200 on the 200-level dragons cost a flat amount each. */
    private static final long DRAGON_TAIL_COST = 1_886_700L;
    private static final int DRAGON_MAX_LEVEL = 200;

    /** Rarity -> starting offset into {@link #PET_LEVELS}. MYTHIC shares
     *  LEGENDARY's offset, which is why a mythic and a legendary at the same
     *  level hold identical EXP. */
    private static int rarityOffset(String tier) {
        return switch (tier == null ? "" : tier.toUpperCase(Locale.ROOT)) {
            case "COMMON" -> 0;
            case "UNCOMMON" -> 6;
            case "RARE" -> 11;
            case "EPIC" -> 16;
            case "LEGENDARY", "MYTHIC" -> 20;
            default -> -1; // indeterminate tier: refuse rather than assume
        };
    }

    private static boolean isDragon(String petType) {
        String t = petType == null ? "" : petType.toUpperCase(Locale.ROOT).replace(' ', '_');
        return t.endsWith("DRAGON") && (t.contains("GOLDEN") || t.contains("JADE") || t.contains("ROSE"));
    }

    static int maxLevel(String petType) {
        return isDragon(petType) ? DRAGON_MAX_LEVEL : 100;
    }

    /** Total EXP accumulated on reaching {@code level}; -1 when the tier is
     *  indeterminate or the level is out of range. Level 1 costs nothing. */
    static long cumulativeExp(String tier, int level, String petType) {
        int off = rarityOffset(tier);
        if (off < 0 || level < 1 || level > maxLevel(petType)) {
            return -1;
        }
        long total = 0;
        int ladder = Math.min(level, 100);
        for (int k = 1; k < ladder; k++) {
            total += PET_LEVELS[off + k - 1];
        }
        if (level > 100) { // dragon tail
            total += (long) (level - 100) * DRAGON_TAIL_COST;
        }
        return total;
    }

    /** The comp bucket implied by tier + displayed level ALONE, or null when
     *  the level cannot decide it.
     *
     *  <p>A displayed level pins the total EXP to the half-open interval
     *  [cumulative(level), cumulative(level+1)). When BOTH ends fall in the
     *  same bucket the answer is certain and needs nothing else — which is the
     *  ordinary case, and is why this replaces a guess rather than refining
     *  one. Null means genuinely undecidable, and callers must degrade rather
     *  than substitute a guess:
     *
     *  <ul>
     *    <li>the level straddles a boundary (legendary 56 and 87, epic 60 and
     *        91, rare 65 and 96) — the interval spans two buckets;
     *    <li>the pet is at MAX LEVEL — the ladder is exhausted but feeding
     *        continues, so the total is unbounded above. The owner's Lvl 100
     *        Tarantula held 36.9M against a 25.35M ladder. Read the printed
     *        total ({@link #expFromLore}) instead;
     *    <li>the tier could not be read from the name colour.
     *  </ul>
     */
    public static String bucketFromLevel(String tier, int level, String petType) {
        long low = cumulativeExp(tier, level, petType);
        if (low < 0) {
            return null;
        }
        if (level >= maxLevel(petType)) {
            return null; // overflow feeding: unbounded above, cannot decide
        }
        long highExclusive = cumulativeExp(tier, level + 1, petType);
        if (highExclusive < 0) {
            return null;
        }
        String lowBucket = bucketOf(low);
        String highBucket = bucketOf(highExclusive - 1);
        return lowBucket.equals(highBucket) ? lowBucket : null;
    }

    /** The collector's EXP thresholds. Kept identical to the server's
     *  pet_exp_bucket; the server stays authoritative whenever we can send it
     *  a real number, and this is only used to decide what we already know. */
    static String bucketOf(long exp) {
        if (exp < 1_000_000L) {
            return "x0";
        }
        if (exp < 10_000_000L) {
            return "x1";
        }
        if (exp < 30_000_000L) {
            return "x2";
        }
        return "x3";
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
