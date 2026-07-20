package dev.midasflip.client;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LORE path for valuation modifier atoms (owner 2026-07-13: the tooltip
 * and sell overlay give the FULL estimate, not the bare bucket). Hypixel
 * strips a listed item's SkyBlock NBT in its create-auction / menu GUIs,
 * so {@link ItemId#modAtoms} (which reads ExtraAttributes) returns nothing
 * there. This helper recovers what the client can still SEE — the rendered
 * tooltip lore — and emits the same whitelisted atoms.
 *
 * <p>v1 SCOPE — deliberately partial, and the display MUST say so:
 * <ul>
 *   <li>ENCHANTS are visible and parsed:
 *     <ul>
 *       <li>Ultimate enchants render pink+bold (§d§l) — "Ultimate Wise V",
 *           or the short form "Legion V". They map to
 *           {@code ult:ultimate_<name>_<lvl>} (the "ultimate_" prefix is
 *           PREPENDED when the display name omits it).</li>
 *       <li>Regular enchants render in a blue (§9) comma-separated list —
 *           "Growth V, Protection VI, ...". Each at level &gt;= 6 counts
 *           toward the single bucketed {@code ench6:...} atom.</li>
 *     </ul>
 *   </li>
 *   <li>GEMS, HOT-POTATO-BOOKS, DRILL PARTS, and PET HELD ITEM are NOT
 *       reliably parseable from lore (gem quality lives in a hover the
 *       client can't see here; HPB shows only as a merged +stat; held item
 *       is a separate line format that varies) — so the lore path emits
 *       ONLY {@code ult:*} and {@code ench6:*}. The estimate is marked
 *       "≈ partial mods" upstream so a decomposed number from lore never
 *       reads as complete.</li>
 * </ul>
 *
 * READ-ONLY: reads the stack's LORE component; no writes, no input.
 */
public final class LoreMods {
    private LoreMods() {}

    // "Ultimate Wise V" / "Ultimate Chimera IV" — the "Ultimate " word is
    // present. Captures the enchant words + a trailing roman numeral.
    private static final Pattern ULT_LABELLED = Pattern.compile(
            "^Ultimate\\s+([A-Za-z][A-Za-z '-]*?)\\s+([IVX]{1,5})$");
    // Any bold-pink enchant line without the "Ultimate" word ("Legion V",
    // "Soul Eater V"): a name (letters/spaces) + a trailing roman numeral.
    private static final Pattern ENCH_LINE = Pattern.compile(
            "^([A-Za-z][A-Za-z '-]*?)\\s+([IVX]{1,5})$");

    /**
     * Atoms recoverable from the rendered lore of {@code stack}. Returns
     * "" when the lore shows no parseable enchants. Only ever emits
     * {@code ult:*} and {@code ench6:*} (see class scope).
     */
    public static String atomsFromLore(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        var lore = stack.get(DataComponents.LORE);
        if (lore == null || lore.lines().isEmpty()) {
            return "";
        }
        List<String> ults = new ArrayList<>();
        int high6 = 0;

        for (var lineComp : lore.lines()) {
            String legacy = lineComp.getString(); // carries §-codes
            boolean ultStyled = legacy.contains("§d") && legacy.contains("§l");
            boolean regularStyled = legacy.contains("§9");
            String line = legacy.replaceAll("§.", "").strip();
            if (line.isEmpty()) {
                continue;
            }

            // Ultimate enchant line (pink+bold, or explicitly "Ultimate X").
            Matcher um = ULT_LABELLED.matcher(line);
            if (um.matches()) {
                addUlt(ults, um.group(1), um.group(2));
                continue;
            }
            if (ultStyled && !regularStyled) {
                // Bold-pink line without the "Ultimate " word ("Legion V").
                Matcher em = ENCH_LINE.matcher(line);
                if (em.matches()) {
                    addUlt(ults, em.group(1), em.group(2));
                }
                continue;
            }

            // Regular enchant list: blue, comma-separated ("Growth V,
            // Protection VI") or a single blue enchant on its own line.
            // Count each entry at level >= 6.
            if (regularStyled) {
                for (String part : line.split(",")) {
                    Matcher em = ENCH_LINE.matcher(part.strip());
                    if (em.matches() && roman(em.group(2)) >= 6) {
                        high6++;
                    }
                }
            }
        }

        List<String> all = new ArrayList<>(ults);
        if (high6 > 0) {
            all.add("ench6:" + (high6 <= 2 ? "1-2" : high6 <= 5 ? "3-5" : "6+"));
        }
        return ItemId.capAtoms(all);
    }

    /** Build ult:ultimate_<name>_<lvl>; PREPEND "ultimate_" when the
     *  display name omitted the word ("Legion" -> ultimate_legion). Drops
     *  the atom if the name sanitizes empty or the level is out of range. */
    private static void addUlt(List<String> out, String displayName, String romanLvl) {
        int lvl = roman(romanLvl);
        if (lvl < 1 || lvl > 10) {
            return;
        }
        String id = displayName.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (id.isEmpty()) {
            return;
        }
        if (!id.startsWith("ultimate_")) {
            id = "ultimate_" + id;
        }
        String tok = id + "_" + lvl;
        if (tok.length() <= 48) { // server bounds the whole post-"ult:" token
            out.add("ult:" + tok);
        }
    }

    /** Roman numeral I..X (as Hypixel renders enchant levels) -> int; 0 on
     *  anything unexpected. Handles the subtractive forms IV and IX. */
    static int roman(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        int total = 0, prev = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            int v = switch (s.charAt(i)) {
                case 'I' -> 1;
                case 'V' -> 5;
                case 'X' -> 10;
                default -> -1;
            };
            if (v < 0) {
                return 0;
            }
            if (v < prev) {
                total -= v;
            } else {
                total += v;
                prev = v;
            }
        }
        return total;
    }
}
