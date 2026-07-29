package dev.midasflip.client;

import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

/**
 * Item hover tooltip (owner request 2026-07-05): appends MidasFlip's data
 * under the item's own tooltip — the same pessimistic sell valuation the
 * finder scores, its wider valuation band, net, confidence, and comps.
 * READ-ONLY: it only reads the hovered stack and the flip buffer.
 *
 * Board matching requires auction evidence: display identity, stack count,
 * stars, AND the exact "Buy it now" price from lore. Name-only matching
 * decorated owned inventory items with an unrelated live flip of the same
 * name (and even a different stack total). Everything else falls to the
 * current market path, which recovers stars/recomb from NBT when present
 * or from LORE in NBT-stripped menu GUIs (same recovery the sell overlay
 * uses).
 */
public final class ItemTooltip {
    private final MidasflipConfig config;
    private final FlipFeed feed;
    private final MidasflipApi api;

    public ItemTooltip(MidasflipConfig config, FlipFeed feed, MidasflipApi api) {
        this.config = config;
        this.feed = feed;
        this.api = api;
    }

    public void register() {
        ItemTooltipCallback.EVENT.register((stack, ctx, flag, lines) -> {
            if (!config.itemTooltip || stack.isEmpty()) {
                return;
            }
            String want = norm(stack.getHoverName().getString());
            if (want.isEmpty()) {
                return;
            }
            // A board flip describes one AUCTION, not every owned item with
            // the same name. Require the exact BIN price carried by auction
            // lore; inventory stacks have no such line and correctly use the
            // live market path below. Count prevents a six-item board listing
            // from decorating a one-item stack with its six-item total.
            Long listingPrice = listingBuyPrice(stack);
            int hoverStars = SellOverlay.loreStars(
                    stack.getHoverName().getString().replaceAll("§.", ""));
            Flip match = null;
            if (listingPrice != null) {
                for (Flip f : feed.top(64)) { // current board buffer, best-first
                    if (f.itemId == null || f.buyPrice != listingPrice
                            || Math.max(f.count, 1) != Math.max(stack.getCount(), 1)
                            || !norm(NameMap.pretty(f.itemId, f.compKey)).equals(want)) {
                        continue;
                    }
                    int keyStars = starsOf(f.compKey);
                    if (keyStars >= 0 && keyStars != hoverStars) {
                        continue; // same item name, different star bucket
                    }
                    match = f;
                    break;
                }
            }
            if (match == null) {
                // Not on the board: clean-bucket market estimate — by exact
                // NBT id when present (reliable), else the display-name map;
                // pets go through the level rule (below the floor = fresh).
                marketLines(stack, lines);
                return;
            }
            lines.add(Component.literal(""));
            lines.add(Component.literal("§6§lMidasFlip"));
            lines.add(Component.literal("§7sell target §a" + coins(match.estPess)
                    + "  §7conf §f" + String.format(Locale.ROOT, "%.2f", match.confidence)
                    + " §8(" + match.comps + " comps)§r"));
            lines.add(Component.literal("§7fair §f" + coins(match.estBase)
                    + " §8· high §f" + coins(match.estOpt) + "§r"));
            lines.add(Component.literal("§7net §a+" + coins(match.netProfit)
                    + " §8(" + Math.round(match.netMarginPct * 100) + "%)§r"
                    + (match.fallingKnife ? " §c⚠ falling§r" : "")));
            lines.add(Component.literal("§8matched to board auction by item · count · price§r"));
        });
    }

    /** Exact BIN price from Hypixel's auction-listing lore. Owned inventory
     * items do not carry this line, which deliberately makes them ineligible
     * for board-profit decoration. Fail closed on compact/unfamiliar formats:
     * the market valuation remains available and is safer than a wrong match. */
    private static Long listingBuyPrice(net.minecraft.world.item.ItemStack stack) {
        var lore = stack.get(net.minecraft.core.component.DataComponents.LORE);
        if (lore == null) {
            return null;
        }
        var pattern = java.util.regex.Pattern.compile(
                "(?i)^\\s*Buy it now\\s*:?\\s*([\\d,]+)\\s*coins?\\s*$");
        for (var line : lore.lines()) {
            String plain = line.getString().replaceAll("§.", "").strip();
            var m = pattern.matcher(plain);
            if (!m.matches()) {
                continue;
            }
            try {
                return Long.parseLong(m.group(1).replace(",", ""));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private void marketLines(net.minecraft.world.item.ItemStack stack, List<Component> lines) {
        Pets.PetName pet = Pets.parse(stack.getHoverName());
        Double petExp = pet == null ? null : ItemId.petExp(stack);
        String petType = pet == null ? null : ItemId.petType(stack);
        String petTier = pet == null ? null : ItemId.petTier(stack);
        String petSkin = pet == null ? null : ItemId.petSkin(stack);
        boolean petTierBoosted = pet != null && ItemId.petTierBoosted(stack);
        boolean exactPetNbt = petExp != null && petType != null && petTier != null;
        String skyblockId = ItemId.of(stack);
        // Owner 2026-07-13: send the item's modifiers so the server prices
        // the FULL estimate, not the bare clean bucket. Full-NBT surfaces
        // (inventory hovers, incl. pets) read exact atoms from ExtraAttrs;
        // the lore-only menu path recovers ENCHANTS ONLY (see LoreMods).
        boolean lorePath = false; // NBT stripped → atoms are partial
        String path;
        if (pet != null) {
            String mods = ItemId.modAtoms(stack); // pet held item, if any
            path = (petType != null ? "/price/by-id/" : "/price/by-name/")
                    + java.net.URLEncoder.encode(
                        petType != null ? petType : pet.type(),
                        java.nio.charset.StandardCharsets.UTF_8)
                    + "?pet=true"
                    + (petExp != null
                        ? "&pet_exp=" + Double.toString(petExp)
                        : "&exp_bucket=" + Pets.approximateExpBucket(pet.level(), config))
                    + (ItemId.petCandied(stack) ? "&candied=true" : "")
                    + (petTierBoosted ? "&tier_boosted=true" : "")
                    + "&tier=" + (petTier != null
                        ? Pets.effectiveTier(petTier, petTierBoosted) : pet.tier())
                    + (petSkin == null ? "" : "&skin=" + java.net.URLEncoder.encode(
                        petSkin, java.nio.charset.StandardCharsets.UTF_8))
                    + modsParam(mods);
        } else if (skyblockId != null) {
            // Inventory stacks carry full NBT: ask for the exact gear
            // bucket (stars/recomb are comp-key state, not modifiers).
            String variant = ItemId.variantOf(stack);
            String mods = ItemId.modAtoms(stack);
            path = "/price/by-id/"
                    + java.net.URLEncoder.encode(skyblockId, java.nio.charset.StandardCharsets.UTF_8)
                    + "?stars=" + ItemId.stars(stack) + "&recomb=" + ItemId.recombed(stack)
                    + (variant != null ? "&variant=" + java.net.URLEncoder.encode(variant, java.nio.charset.StandardCharsets.UTF_8) : "")
                    + modsParam(mods);
        } else {
            // NBT stripped (Hypixel menu GUIs): stars/recomb from the LORE,
            // exactly like the sell overlay — without them a 5✪ item priced
            // as its clean bucket and understated by the full star value
            // (field report 2026-07-13: flipper 15.0M→+3.4M vs tooltip
            // "clean bucket est 15.1M" on the same helmet).
            lorePath = true;
            String cleanName = stack.getHoverName().getString().replaceAll("§.", "").strip();
            String mods = LoreMods.atomsFromLore(stack); // ults + ench6 only
            path = "/price/by-name/"
                    + java.net.URLEncoder.encode(cleanName, java.nio.charset.StandardCharsets.UTF_8)
                    + "?stars=" + SellOverlay.loreStars(cleanName)
                    + "&recomb=" + SellOverlay.loreRecombed(stack)
                    + modsParam(mods);
        }
        var el = api.get(path, 5 * 60_000);
        if (el == null || !el.isJsonObject()) {
            return; // unknown / no data / still loading — add nothing
        }
        var resp = el.getAsJsonObject();
        var est = resp.getAsJsonObject("estimate");
        // /price values are per unit; the flip finder values the complete
        // listing stack. Every number below therefore uses the same total.
        int units = Math.max(stack.getCount(), 1);
        boolean bazaar = resp.has("bazaar");
        String bucket = "clean bucket";
        if (bazaar) {
            bucket = "bazaar";
        } else if (pet != null) {
            bucket = "pet bucket";
        } else if (resp.has("comp_key")) {
            String ck = resp.get("comp_key").getAsString();
            if (ck.matches(".*\\|s\\d+\\|r1.*")) {
                bucket = "recomb bucket";
            } else if (ck.matches(".*\\|s[1-9]\\d*\\|r\\d.*")) {
                bucket = "starred bucket";
            }
        }
        boolean lowball = resp.has("fallback_from_mods");
        boolean decomposed = est.has("src") && "decomposed".equals(est.get("src").getAsString());
        lines.add(Component.literal(""));
        lines.add(Component.literal("§6§lMidasFlip §8· " + bucket
                + (units > 1 ? " · ×" + units : "")));
        if (bazaar) {
            FinderValuation.Result valuation = FinderValuation.from(est, resp);
            lines.add(Component.literal("§7instasell §a" + coins(valuation.target() * units)
                    + " §8· sell offer §f" + coins(valuation.high() * units) + "§r"));
            lines.add(Component.literal("§8instasell – sell offer · bazaar venue§r"));
        } else {
            String spd = est.has("spd") && !est.get("spd").isJsonNull()
                    ? " · " + String.format(Locale.ROOT, "%.1f", est.get("spd").getAsDouble()) + " sold/day"
                    : "";
            lines.add(Component.literal("§7conf §f" + String.format(Locale.ROOT, "%.2f", est.get("conf").getAsDouble())
                    + " §8· " + est.get("comps").getAsInt() + " comps" + spd + "§r"));
            // One valuation seam everywhere: this is the pessimistic band
            // the finder used to decide that an auction was a flip. Lowest
            // BIN is context below, never a replacement sell number.
            FinderValuation.Result valuation = FinderValuation.from(est, resp);
            if (valuation.backed()) {
                lines.add(Component.literal("§7sell target §a" + coins(valuation.target() * units)
                        + " §8· finder valuation§r"));
                lines.add(Component.literal("§7fair §f" + coins(valuation.fair() * units)
                        + " §8· high §f" + coins(valuation.high() * units) + "§r"));
            } else {
                lines.add(Component.literal("§evalue unverified §8· "
                        + valuation.blockedReason() + "§r"));
                lines.add(Component.literal("§8sold comps "
                        + coins(est.get("pess").getAsDouble() * units) + "–"
                        + coins(est.get("opt").getAsDouble() * units) + "§r"));
            }
            if (decomposed) {
                // NEVER show a decomposed number without this marker — the
                // amber cap exists so it reads as SOFTER than a direct comp
                // (spec transparency). The +sum is the learned modifier
                // uplift the server itemized in mod_contributions.
                lines.add(Component.literal("§7incl. modifiers §f+" + coins(modContribSum(resp) * units)
                        + "§8 · amber conf§r"));
                if (lorePath) {
                    lines.add(Component.literal(
                            "§8menu view — enchants counted, gems/HPB not visible§r"));
                }
            }
            if (lowball) {
                lines.add(Component.literal("§e⚠ starred/recombed — clean price, likely LOW§r"));
            }
        }
        // What it would cost to MAKE this instead of buying it (owner
        // 2026-07-27). Same stack-total convention as every number above.
        String craft = craftLine(skyblockId, units);
        if (craft != null) {
            lines.add(Component.literal(craft));
        }
        // Live listing depth (owner: next-lowest is your REAL exit when
        // you buy the floor). Stack totals, like the finder and estimates.
        String lbin = lbinLine(config, resp, units);
        if (lbin != null) {
            lines.add(Component.literal(lbin));
        }
        if (pet != null && !bazaar) {
            String b = resp.has("exp_bucket") ? resp.get("exp_bucket").getAsString()
                    : Pets.approximateExpBucket(pet.level(), config);
            boolean exact = resp.has("pet_identity_source")
                    && "nbt_exact".equals(resp.get("pet_identity_source").getAsString())
                    && exactPetNbt;
            if (exact && petExp != null) {
                lines.add(Component.literal("§8Lvl " + pet.level() + " · "
                        + compactPetExp(petExp) + " EXP · exact " + b + " bucket§r"));
            } else {
                lines.add(Component.literal("§eLvl " + pet.level() + " · approximate "
                        + b + " bucket · EXP unavailable§r"));
            }
        }
    }

    /** "estimated craft price" — what one unit costs to make, from the
     *  server's craft/forge EV board (/craft/evs), which prices every leg
     *  from OUR market data and skips any recipe with an unpriceable leg.
     *
     *  cost is per recipe RUN and output_count is the yield, so the honest
     *  per-unit number is cost/output_count — then scaled to the stack the
     *  same way every other line here is.
     *
     *  ABSENCE MEANS LITTLE. The server publishes only the top 100 rows by
     *  profit (build.py craft_evs[:100]) and drops anything under its
     *  profit/margin gate, so at most ~100 of 2,556 recipes can ever show a
     *  line. No line = "not on today's profitable board", NOT "cannot be
     *  crafted". The number shown is honest; the silence is not evidence.
     *
     *  Null (no line) when: no row for this item, the board is still
     *  loading, or the endpoint is not available to this account. Never a
     *  guess. */
    String craftLine(String itemId, int units) {
        if (itemId == null || itemId.isEmpty()) {
            return null;
        }
        JsonObject row = craftRow(itemId);
        if (row == null || !row.has("cost") || !row.has("output_count")) {
            return null;
        }
        double outCount = row.get("output_count").getAsDouble();
        if (outCount <= 0) {
            return null;
        }
        double perUnit = row.get("cost").getAsDouble() / outCount;
        String kind = row.has("kind") ? row.get("kind").getAsString() : "craft";
        String req = row.has("req") && !row.get("req").isJsonNull()
                ? " §8· " + row.get("req").getAsString() : "";
        return "§7est. craft §f" + coins(perUnit * units) + " §8· " + kind + req + "§r";
    }

    // Index the EV board by output id so a tooltip render is a map lookup,
    // not a linear scan every frame.
    //
    // Keyed on ARRAY IDENTITY, not size: the server publishes exactly 100
    // rows every build (build.py craft_evs[:100]), so a size comparison is
    // true once and then never again — the index would freeze on the first
    // board of the session and serve hours-old coin figures (review
    // 2026-07-27). MidasflipApi hands back the same JsonElement instance
    // until a refresh lands, so reference inequality is precisely "the
    // board changed". That also covers re-pairing: credentialsChanged()
    // empties the API cache, so the next fetch is a different instance and
    // the previous account's board can never be reused.
    //
    // Instance state, not static, so it cannot outlive this tooltip.
    private java.util.Map<String, JsonObject> craftIndex = java.util.Map.of();
    private com.google.gson.JsonArray craftIndexOf;

    private JsonObject craftRow(String itemId) {
        var el = api.get("/craft/evs", 5 * 60_000);
        if (el == null || !el.isJsonArray()) {
            return null;  // loading, unavailable, or not on this plan
        }
        var arr = el.getAsJsonArray();
        if (arr != craftIndexOf) {
            var next = new java.util.HashMap<String, JsonObject>(arr.size() * 2);
            for (var e : arr) {
                if (!e.isJsonObject()) {
                    continue;
                }
                var o = e.getAsJsonObject();
                if (!o.has("output_id") || !o.has("cost") || !o.has("output_count")) {
                    continue;
                }
                String id = o.get("output_id").getAsString();
                JsonObject prev = next.get(id);
                // The board is sorted by absolute PROFIT PER RUN, which is
                // not the same question this line answers. 35 ids appear
                // twice with different yields (ENCHANTED_DIAMOND at n=1 and
                // n=9, AMALGAMATED_CRIMSONITE_NEW at n=2 and n=40), and the
                // big-batch row usually wins on profit while costing more
                // per unit. Keep the CHEAPEST way to make one.
                if (prev == null || perUnitCost(o) < perUnitCost(prev)) {
                    next.put(id, o);
                }
            }
            craftIndex = next;
            craftIndexOf = arr;
        }
        return craftIndex.get(itemId);
    }

    /** cost per single output unit; +inf for an unusable row so it loses
     *  every comparison rather than being picked. */
    private static double perUnitCost(JsonObject row) {
        double n = row.get("output_count").getAsDouble();
        return n > 0 ? row.get("cost").getAsDouble() / n : Double.POSITIVE_INFINITY;
    }

    static String compactPetExp(double exp) {
        if (exp >= 1_000_000) {
            return String.format(Locale.ROOT, "%.1fM", exp / 1_000_000.0);
        }
        if (exp >= 1_000) {
            return String.format(Locale.ROOT, "%.1fk", exp / 1_000.0);
        }
        return String.format(Locale.ROOT, "%.0f", exp);
    }

    /** The lowest-BIN depth line from a /price response's optional "lbin"
     *  object {low, next, at_floor, n} (unit prices): "§7lbin §f2.4M §8·
     *  next 2.5M · 3 at floor§r". Null when the toggle is off, the field
     *  is absent (no active BINs / older server), or low is null. Depth
     *  matters (owner): next-lowest is your REAL exit when you buy the
     *  floor. {@code units} multiplies for surfaces that display stack
     *  totals (SellOverlay); tooltips pass 1. Shared with SellOverlay. */
    static String lbinLine(MidasflipConfig config, JsonObject resp, int units) {
        if (!config.lbinTooltip || !resp.has("lbin") || !resp.get("lbin").isJsonObject()) {
            return null;
        }
        JsonObject lb = resp.getAsJsonObject("lbin");
        if (!lb.has("low") || lb.get("low").isJsonNull()) {
            return null;
        }
        StringBuilder s = new StringBuilder("§7lbin §f")
                .append(coins((double) lb.get("low").getAsLong() * units));
        if (lb.has("next") && !lb.get("next").isJsonNull()) {
            s.append(" §8· next ").append(coins((double) lb.get("next").getAsLong() * units));
        }
        int atFloor = lb.has("at_floor") && !lb.get("at_floor").isJsonNull()
                ? lb.get("at_floor").getAsInt() : 0;
        if (atFloor > 1) {
            s.append(" §8· ").append(atFloor).append(" at floor");
        }
        return s.append("§r").toString();
    }

    /** "&mods=<url-encoded atoms>" or "" when there are no atoms. Shared
     *  shape with SellOverlay so both surfaces build the param identically. */
    static String modsParam(String atoms) {
        if (atoms == null || atoms.isEmpty()) {
            return "";
        }
        return "&mods=" + java.net.URLEncoder.encode(atoms, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Sum of the server's itemized per-modifier deltas (mod_contributions
     *  map atom->coins), for the "incl. modifiers +X" marker. 0 when the
     *  field is absent. */
    static double modContribSum(JsonObject resp) {
        if (!resp.has("mod_contributions") || !resp.get("mod_contributions").isJsonObject()) {
            return 0;
        }
        double sum = 0;
        for (var e : resp.getAsJsonObject("mod_contributions").entrySet()) {
            if (!e.getValue().isJsonNull()) {
                sum += e.getValue().getAsDouble();
            }
        }
        return sum;
    }

    private static String norm(String s) {
        return s == null ? "" : s.toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    /** Star count from a gear comp key ("v1|ID|s5|r1" -> 5), or -1 when
     *  the key carries no star segment (non-gear families). */
    private static int starsOf(String compKey) {
        if (compKey == null) {
            return -1;
        }
        var m = java.util.regex.Pattern.compile("\\|s(\\d+)(?:\\||$)").matcher(compKey);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /** Canonical coin format (config-completeness 2026-07-13): routes
     *  through {@link dev.midasflip.client.ui.Phos#coins} so B renders at 2dp
     *  and the raw-numbers toggle applies — the old local copy used 1dp. */
    private static String coins(double v) {
        return dev.midasflip.client.ui.Phos.coins(v);
    }
}
