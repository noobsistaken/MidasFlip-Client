package dev.midasflip.client;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Reads the real Hypixel SkyBlock item id from an item's NBT
 * (ExtraAttributes.id) — the same field the collector decodes server-side.
 * This is the reliable identity: display names are ambiguous and often
 * differ from the id (Necron's Chestplate = POWER_WITHER_CHESTPLATE), and
 * "Create Auction" GUI buttons are vanilla items with no ExtraAttributes,
 * so this also cleanly distinguishes the real sale item from controls.
 *
 * READ-ONLY: inspects the stack the game already gave us. No writes.
 */
public final class ItemId {
    private ItemId() {}

    /** SkyBlock id (e.g. HYPERION, PET, ENCHANTED_DIAMOND) or null if the
     *  stack carries no ExtraAttributes (a vanilla item / GUI button). */
    public static String of(ItemStack stack) {
        var ea = extraAttributes(stack);
        if (ea == null) {
            return null;
        }
        String id = ea.getStringOr("id", "");
        return id.isEmpty() ? null : id;
    }

    /** Variant segment for items whose comp key carries one (mirrors
     *  normalize.go cleanKey): cake years, party hat colors/emoji,
     *  ABICASE models. Null when the item has no variant identity. */
    public static String variantOf(ItemStack stack) {
        var ea = extraAttributes(stack);
        if (ea == null) {
            return null;
        }
        int cakeYear = ea.getIntOr("new_years_cake", -1);
        if (cakeYear >= 0) {
            return "y" + cakeYear;
        }
        String hatColor = ea.getStringOr("party_hat_color", "");
        if (!hatColor.isEmpty()) {
            return "c:" + hatColor.toUpperCase(java.util.Locale.ROOT);
        }
        String hatEmoji = ea.getStringOr("party_hat_emoji", "");
        if (!hatEmoji.isEmpty()) {
            return "e:" + hatEmoji.toUpperCase(java.util.Locale.ROOT);
        }
        String model = ea.getStringOr("model", "");
        if (!model.isEmpty()) {
            return "m:" + model;
        }
        return null;
    }

    /** Exact pet EXP from petInfo, or null when Hypixel stripped/unreadable
     * NBT. This is the canonical pricing input: display level is only an
     * approximation and can cross the collector's 1M/10M/30M buckets at a
     * different point for different pet curves. */
    public static Double petExp(ItemStack stack) {
        var ea = extraAttributes(stack);
        if (ea == null) {
            return null;
        }
        return PetInfoText.exp(ea.getStringOr("petInfo", ""));
    }

    /** Exact canonical pet type from petInfo (e.g. MITHRIL_GOLEM). */
    public static String petType(ItemStack stack) {
        return petString(stack, "type");
    }

    /** Natural rarity from petInfo. Tier boost is applied separately. */
    public static String petTier(ItemStack stack) {
        return petString(stack, "tier");
    }

    /** Skin id carried in the collector's |k: segment; null when absent. */
    public static String petSkin(ItemStack stack) {
        return petString(stack, "skin");
    }

    /** Candy count from the pet's petInfo (a JSON string in EA). True =
     *  candied — prices from the discounted |c1 bucket (owner finding
     *  2026-07-06: up to 31% discount in leveled buckets). False when
     *  candy-free OR unreadable (menus strip NBT — defaults to the clean
     *  bucket, labeled upstream). */
    public static boolean petCandied(ItemStack stack) {
        var ea = extraAttributes(stack);
        if (ea == null) {
            return false;
        }
        return PetInfoText.candied(ea.getStringOr("petInfo", ""));
    }

    /** Stars from full NBT (upgrade_level counts dungeon + master stars —
     *  the same int the collector keys on). 0 when unreadable. */
    public static int stars(ItemStack stack) {
        var ea = extraAttributes(stack);
        return ea == null ? 0 : ea.getIntOr("upgrade_level", 0);
    }

    /** Recombobulated, from full NBT. False when unreadable. */
    public static boolean recombed(ItemStack stack) {
        var ea = extraAttributes(stack);
        return ea != null && ea.getIntOr("rarity_upgrades", 0) > 0;
    }

    /** Tier boost from the pet's heldItem (comp-key v1.2, measured
     *  2026-07-08: boosted pets trade median -30% vs the natural tier
     *  they display as). Same read-only petInfo surface as candy; false
     *  when unreadable — defaults to the natural bucket, labeled upstream. */
    public static boolean petTierBoosted(ItemStack stack) {
        var ea = extraAttributes(stack);
        if (ea == null) {
            return false;
        }
        return PetInfoText.tierBoosted(ea.getStringOr("petInfo", ""));
    }

    /**
     * Whitelisted valuation modifier atoms read from FULL NBT — the exact
     * grammar decompose.py features() (backend) emits and main.py's
     * _MOD_ATOM regex accepts. Comma-joined, possibly "" (no atoms / no
     * ExtraAttributes). The server prices these as learned additive deltas
     * (estimate.src == "decomposed"); unknown/unlearned atoms itemize as 0,
     * so a superset is harmless — but every atom MUST match the server
     * regex byte-for-byte or the whole request 422s, hence the per-atom
     * sanitize + drop-on-malformed discipline below.
     *
     * Order of production is the deterministic overflow/priority order
     * (ults, ench6, gems, hpb, drill, held) so the 12-atom cap and the
     * path-length cap both drop from a stable tail. READ-ONLY.
     *
     * <p>Ground truth for field shapes: collector normalize.go /
     * extraattrs.go — enchantments is an int map (ultimate_* + others),
     * gems is a compound of "&lt;TYPE&gt;_&lt;n&gt;" slots whose value is
     * either the quality STRING (legacy) or a {quality,uuid} compound
     * (modern), hot_potato_count is an int, drill parts are three string
     * keys, petInfo.heldItem lives in the petInfo JSON string.
     */
    public static String modAtoms(ItemStack stack) {
        var ea = extraAttributes(stack);
        if (ea == null) {
            return "";
        }
        java.util.List<String> ults = new java.util.ArrayList<>();
        java.util.List<String> other = new java.util.ArrayList<>(); // ench6, gems, hpb, drill, held

        // enchants: ultimate_* -> ult:<name>_<lvl>; others lvl>=6 -> one
        // bucketed ench6 count atom (mirrors features() lines 46-59).
        var ench = ea.getCompoundOrEmpty("enchantments");
        int high6 = 0;
        for (String name : ench.keySet()) {
            int lvl = ench.getIntOr(name, 0);
            if (lvl <= 0) {
                continue;
            }
            if (name.startsWith("ultimate_")) {
                String clean = sanitizeLower(name);
                String tok = clean + "_" + lvl;
                // server regex bounds the WHOLE post-"ult:" token at 48.
                if (!clean.isEmpty() && tok.length() <= 48) {
                    ults.add("ult:" + tok);
                }
            } else if (lvl >= 6) {
                high6++;
            }
        }
        if (high6 > 0) {
            other.add("ench6:" + (high6 <= 2 ? "1-2" : high6 <= 5 ? "3-5" : "6+"));
        }

        // gems: count filled slots by QUALITY -> gem:<QUALITY>x<n>, n cap 5
        // (mirrors features() lines 61-68 and asGems() in extraattrs.go —
        // the value is string-or-compound; the compound form has a
        // "quality" field; unparseable slots are skipped, never guessed).
        var gems = ea.getCompoundOrEmpty("gems");
        java.util.Map<String, Integer> byQuality = new java.util.TreeMap<>();
        for (var entry : gems.entrySet()) {
            String slot = entry.getKey();
            // Filled-slot keys are "<TYPE>_<n>" (gemSlotRe: ^[A-Z]+_[0-9]+$);
            // skip unlocked_slots list, "<SLOT>_gem" type siblings, etc.
            if (!slot.matches("[A-Z]+_[0-9]+")) {
                continue;
            }
            net.minecraft.nbt.Tag val = entry.getValue();
            String quality = "";
            var asStr = val.asString();       // legacy: value IS the quality
            if (asStr.isPresent()) {
                quality = asStr.get();
            } else {
                var asComp = val.asCompound(); // modern: {quality, uuid}
                if (asComp.isPresent()) {
                    quality = asComp.get().getStringOr("quality", "");
                }
            }
            // Gem quality atom is gem:[A-Z_]{1,24}x[1-5] — strictly no
            // digits/semicolons (unlike held), so use the quality alphabet.
            quality = (quality == null ? "" : quality.toUpperCase(java.util.Locale.ROOT))
                    .replaceAll("[^A-Z_]", "");
            if (quality.isEmpty() || quality.length() > 24) {
                continue; // unparseable slot — skip, never guess
            }
            byQuality.merge(quality, 1, Integer::sum);
        }
        for (var e : byQuality.entrySet()) {
            other.add("gem:" + e.getKey() + "x" + Math.min(e.getValue(), 5));
        }

        // hot_potato_count -> hpb:<n> (1-99; server regex is hpb:\d{1,2})
        int hpb = ea.getIntOr("hot_potato_count", 0);
        if (hpb > 0 && hpb <= 99) {
            other.add("hpb:" + hpb);
        }

        // any drill part present -> drill_parts (extraattrs.go keys)
        if (!ea.getStringOr("drill_part_engine", "").isEmpty()
                || !ea.getStringOr("drill_part_fuel_tank", "").isEmpty()
                || !ea.getStringOr("drill_part_upgrade_module", "").isEmpty()) {
            other.add("drill_parts");
        }

        // petInfo.heldItem -> held:<ID>, excluding the tier-boost (it's
        // comp-key state v1.2, never a learned feature — features() l.77-82).
        String held = petHeldItem(ea);
        if (!held.isEmpty() && !held.equals("PET_ITEM_TIER_BOOST")) {
            String clean = sanitizeUpper(held);
            if (!clean.isEmpty() && clean.length() <= 48) {
                other.add("held:" + clean);
            }
        }

        // Deterministic priority: ults first, then the rest in build order.
        java.util.List<String> all = new java.util.ArrayList<>(ults);
        all.addAll(other);
        return capAtoms(all);
    }

    /** heldItem from the pet's petInfo JSON string (same read-only surface
     *  as petCandied/petTierBoosted). "" when absent/unreadable. */
    private static String petHeldItem(net.minecraft.nbt.CompoundTag ea) {
        String petInfo = ea.getStringOr("petInfo", "");
        var m = java.util.regex.Pattern
                .compile("\"heldItem\"\\s*:\\s*\"([A-Za-z0-9_;]+)\"").matcher(petInfo);
        return m.find() ? m.group(1) : "";
    }

    private static String petString(ItemStack stack, String field) {
        var ea = extraAttributes(stack);
        if (ea == null) {
            return null;
        }
        return PetInfoText.field(ea.getStringOr("petInfo", ""), field);
    }

    /** Join at most 12 atoms, dropping from the TAIL of the given priority
     *  order, within a RAW budget that keeps the full request path under
     *  ~380 chars AFTER url-encoding. Only ':' and ',' expand (to %3A/%2C,
     *  +2 each); a fully-loaded 12-atom string has ~20 such chars (+40),
     *  so a 200-char raw budget bounds the encoded atoms to ~240 plus the
     *  ~60-char base path — comfortably < 380, and no realistic item ever
     *  loses an atom to it. The server independently enforces its own
     *  400-char mods cap and 12-atom limit; this is the CLIENT side of that
     *  contract. Shared with LoreMods so both surfaces cap identically. */
    static String capAtoms(java.util.List<String> atoms) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String a : atoms) {
            if (n >= 12) {
                break;
            }
            int add = a.length() + (sb.length() == 0 ? 0 : 1);
            if (sb.length() + add > 200) { // raw; ~240 encoded + base < 380
                break;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(a);
            n++;
        }
        return sb.toString();
    }

    /** Enchant/held ids to the server's [a-z0-9_] alphabet; anything else
     *  is dropped from the token (a name that sanitizes to empty is
     *  skipped by the caller, never sent malformed). */
    private static String sanitizeLower(String s) {
        return s == null ? "" : s.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_]", "");
    }

    /** Gem qualities / held ids to the server's [A-Z_] alphabet. */
    private static String sanitizeUpper(String s) {
        return s == null ? "" : s.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9_;]", "");
    }

    private static net.minecraft.nbt.CompoundTag extraAttributes(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        CustomData cd = stack.getComponents().get(DataComponents.CUSTOM_DATA);
        if (cd == null) {
            return null;
        }
        var ea = cd.copyTag().getCompoundOrEmpty("ExtraAttributes");
        return ea.isEmpty() ? null : ea;
    }
}
