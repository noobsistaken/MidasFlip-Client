package dev.midasflip.client;

import com.google.gson.JsonElement;
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
    private final PositionLedger ledger;

    public ItemTooltip(MidasflipConfig config, FlipFeed feed, MidasflipApi api, PositionLedger ledger) {
        this.config = config;
        this.feed = feed;
        this.api = api;
        this.ledger = ledger;
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
            lines.add(Component.literal(listAtLine(match.estPess, match.confidence, match.comps)));
            lines.add(Component.literal(match.estOpt == null
                    ? GoldFields.locked("bands")
                    : "§7fair §f" + coins(match.estBase)
                    + " §8· high §f" + coins(match.estOpt) + "§r"));
            lines.add(Component.literal("§7net §a+" + coins(match.netProfit)
                    + " §8(" + Math.round(match.netMarginPct * 100) + "%)§r"
                    + (Boolean.TRUE.equals(match.fallingKnife) ? " §c⚠ falling§r"
                    : match.fallingKnife == null ? "" : "")));
            lines.add(Component.literal("§8matched to board auction by item · count · price§r"));
            // Sell target, the bands and the comp count above are all Gold.
            // They RENDER during early access, so this line is the only thing
            // that tells the user so — without it they find out in September.
            lines.add(Component.literal("§8" + GoldFields.EARLY_ACCESS + "§r"));
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
        // Set by each Gold add site below; drives the single early-access
        // badge at the end of the block. A local rather than a field: this
        // runs per hover and must not carry state between items.
        boolean goldShown = false;
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
        // Same bucket-mismatch fix as the sell overlay (owner 2026-07-30).
        // In an auction menu Hypixel strips ExtraAttributes, so there are no
        // stars or recomb to derive from and the by-name path below resolves
        // the CLEAN bucket — undervaluing the exact item the finder scored.
        // If the ledger holds this item with the finder's own comp_key, that
        // key is strictly better evidence than a name guess, so use it.
        String ledgerKey = pet == null && skyblockId == null
                ? ledgerKeyFor(stack) : null;
        if (ledgerKey != null) {
            // Carry the lore-recovered modifiers too. Without them this path
            // returns a bucket price with no mod_contributions, so the
            // breakdown vanished on exactly the NBT-stripped menus this
            // branch exists to serve (owner 2026-08-03). LoreMods recovers
            // enchants only — gems and HPB are not in the lore — so the
            // partial-atoms warning below still applies.
            lorePath = true;
            path = "/value/" + java.net.URLEncoder.encode(
                    ledgerKey, java.nio.charset.StandardCharsets.UTF_8)
                    + modsParam(LoreMods.atomsFromLore(stack)).replaceFirst("^&", "?");
        } else if (pet != null) {
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
        Double pess = GoldFields.optNum(est, "pess");
        Double opt = GoldFields.optNum(est, "opt");
        lines.add(Component.literal(""));
        lines.add(Component.literal("§6§lMidasFlip §8· " + bucket
                + (units > 1 ? " · ×" + units : "")));
        if (bazaar) {
            FinderValuation.Result valuation = FinderValuation.from(est, resp);
            // Guard on backed() — target != null — NOT on the raw pess/opt
            // fields merely being PRESENT. A bazaar row whose pess exists but
            // is <= 0 passes a presence check, FinderValuation.from() then
            // returns target=null, and the unbox below threw NPE and took the
            // whole game down from a tooltip render (crash 2026-08-06).
            // The other two call sites already used backed(); this one
            // checked a different thing and looked correct.
            goldShown = true;   // bands
            lines.add(Component.literal(!valuation.backed() || opt == null
                    ? GoldFields.locked("bands")
                    : "§7instasell §a" + coins(valuation.target() * units)
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
                goldShown = true;   // sell target + bands
                // "list at", not "sell target" — this is a GROSS price, the
                // figure you type into the auction sign, and the board's
                // profit numbers are NET of the AH fee model. Owner hit the
                // gap on a pet: overlay said 29.5M, board said 28.6M, and
                // they are the same coin — net(29,500,000) = 28,614,000
                // after the 2% listing fee, the 1% claim tax and the
                // duration fee. Both were right and neither said which it
                // was, so the honest reading was to list at 28.6M and
                // silently give up ~900k of margin (owner report
                // 2026-08-07). The client does not compute fees — it says
                // plainly which side of them this number sits on.
                lines.add(Component.literal("§7list at §a" + coins(valuation.target() * units)
                        + " §8· before AH fees§r"));
                lines.add(Component.literal(opt == null
                        ? GoldFields.locked("bands")
                        : "§7fair §f" + coins(valuation.fair() * units)
                        + " §8· high §f" + coins(valuation.high() * units) + "§r"));
            } else {
                lines.add(Component.literal("§evalue unverified §8· "
                        + valuation.blockedReason() + "§r"));
                lines.add(Component.literal(pess == null || opt == null
                        ? GoldFields.locked("bands")
                        : "§8sold comps " + coins(pess * units) + "–"
                        + coins(opt * units) + "§r"));
            }
            if (decomposed) {
                // NEVER show a decomposed number without this marker — the
                // amber cap exists so it reads as SOFTER than a direct comp
                // (spec transparency). The +sum is the learned modifier
                // uplift the server itemized in mod_contributions.
                Double contributions = modContribSum(resp);
                goldShown = true;   // the modifier breakdown
                // Three distinct states, three distinct sentences. The field
                // missing entirely is the Gold lock. The field ARRIVING with
                // nothing learned is not a paywall — it is us having no
                // measurement yet, and printing a price tag over that during
                // a free launch tells the user their own data is being sold
                // to them (review 2026-08-06).
                lines.add(Component.literal(contributions != null
                        ? "§7incl. modifiers §f+" + coins(contributions * units) + "§8 · amber conf§r"
                        : GoldFields.isLocked(resp, "mod_contributions")
                        ? GoldFields.locked("incl. modifiers")
                        : GoldFields.unknown("incl. modifiers")));
                // Itemized: a total tells you the modifiers are worth
                // something, the breakdown tells you WHICH one carries the
                // item — which is the difference between a number and an
                // argument. The server already returns this map; the client
                // used to sum it and throw the parts away (owner 2026-08-01).
                for (String line : modContribLines(resp, units)) {
                    lines.add(Component.literal(line));
                }
                if (lorePath) {
                    lines.add(Component.literal(
                            "§8menu view · enchants counted, gems/HPB not visible§r"));
                }
            }
            if (lowball) {
                lines.add(Component.literal("§e⚠ starred/recombed · clean price, likely LOW§r"));
            }
        }
        // What it would cost to MAKE this instead of buying it (owner
        // 2026-07-27). Same stack-total convention as every number above.
        // Checked before craftLine so switching it off also stops the index
        // fetch, which is the only reason the switch exists.
        if (config.craftPrice) {
            String craft = craftLine(skyblockId, units);
            if (craft != null) {
                lines.add(Component.literal(craft));
            }
        }
        // Live listing depth (owner: next-lowest is your REAL exit when
        // you buy the floor). Stack totals, like the finder and estimates.
        String lbin = lbinLine(config, resp, units);
        if (lbin != null) {
            goldShown = true;   // lbin depth
            lines.add(Component.literal(lbin));
        }
        // One badge for the whole valuation block, and only when a Gold line
        // actually rendered — a tooltip showing nothing but free data (the
        // estimate, hold, manip risk, falling knife) must not claim to be
        // showing Gold. goldShown is set at each Gold add site above.
        if (goldShown) {
            lines.add(Component.literal("§8" + GoldFields.EARLY_ACCESS + "§r"));
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

    /** The finder's exact comp_key for a stack we are holding, when the
     *  ledger has an open position whose display name matches. Null when
     *  there is no position, no key, or no confident name match: a guessed
     *  key would price a DIFFERENT item, which is the whole failure this
     *  exists to avoid. */
    private String ledgerKeyFor(net.minecraft.world.item.ItemStack stack) {
        if (ledger == null) {
            return null;
        }
        String raw = stack.getHoverName().getString().replaceAll("§.", "");
        String want = norm(SellOverlay.stripReforge(SellOverlay.cleanName(raw)));
        if (want.isEmpty()) {
            return null;
        }
        // Stars ARE visible in the name even when NBT is stripped, so a
        // name-only match was far too weak: norm() flattens "Necron's
        // Chestplate ✪✪✪✪✪" and "Necron's Chestplate" to the same string, and
        // ledger.recent() returns oldest-first, so holding one 5-star and
        // hovering a clean one priced the clean item from the 5-star bucket
        // (review 2026-08-05, caught pre-release). Same guard the sell
        // overlay has used since 2026-07-13.
        int visibleStars = SellOverlay.loreStars(raw);
        String found = null;
        for (PositionLedger.Position p : ledger.recent(200)) {
            if ("sold".equals(p.state) || p.compKey == null || p.compKey.isEmpty()) {
                continue;
            }
            if (!want.equals(norm(NameMap.pretty(p.itemId, p.compKey)))
                    || !starsMatch(p.compKey, visibleStars)) {
                continue;
            }
            if (found != null && !found.equals(p.compKey)) {
                // Two open positions, same name, same visible stars, DIFFERENT
                // buckets. Nothing on screen distinguishes them, so picking
                // one would be a coin flip on a buy-decision surface. Fall
                // through to the lore path, which at least derives from the
                // item in hand.
                return null;
            }
            found = p.compKey;
        }
        return found;
    }

    /** Star segment of a comp key against the stars visible in the name.
     *  A key with no star segment is compatible with anything — absence is
     *  not a claim of zero. */
    private static boolean starsMatch(String compKey, int visibleStars) {
        var m = java.util.regex.Pattern.compile("\\|s(\\d+)(?:\\||$)").matcher(compKey);
        return !m.find() || Integer.parseInt(m.group(1)) == visibleStars;
    }

    /** "estimated craft price" — what one unit costs to make, from
     *  /craft/costs: every recipe whose inputs we can price, each leg valued
     *  from OUR market data.
     *
     *  Reads the COST index, not the EV board (owner 2026-07-29). The board
     *  answers "what is profitable to craft", drops anything under its
     *  profit/margin gate and then keeps only the top 100 — so it could
     *  never show a line for the other ~2,450 recipes, which is most of what
     *  a player hovers. Cost was computed on the way to that gate and thrown
     *  away; /craft/costs keeps it.
     *
     *  cost is per recipe RUN and n is the yield, so the honest per-unit
     *  figure is cost/n — then scaled to the stack like every other line
     *  here. The server has already picked the cheapest recipe per unit
     *  where several make the same item, so there is nothing to choose here.
     *
     *  No recipe REQUIREMENT is shown, deliberately (owner 2026-07-29). This
     *  line is a value anchor — what the thing would cost to make, against
     *  what you are being asked to pay — not a check on whether you can make
     *  it. The comparison holds whatever your slayer level is, and 48% of
     *  recipes carry a gate, so quoting them all would be noise against the
     *  question actually being asked.
     *
     *  ABSENCE now means something narrower: we could not price some input,
     *  so there is no honest number. It still does NOT mean "cannot be
     *  crafted". Null also while the index is loading or if the endpoint is
     *  unavailable. Never a guess. */
    String craftLine(String itemId, int units) {
        if (itemId == null || itemId.isEmpty()) {
            return null;
        }
        JsonObject row = craftRow(itemId);
        if (row == null || !row.has("cost") || !row.has("n")) {
            return null;
        }
        double yield = row.get("n").getAsDouble();
        if (yield <= 0) {
            return null;
        }
        double perUnit = row.get("cost").getAsDouble() / yield;
        String kind = row.has("kind") ? row.get("kind").getAsString() : "craft";
        return "§7est. craft §f" + coins(perUnit * units) + " §8· " + kind + "§r";
    }

    // /craft/costs is keyed BY output id, so a tooltip render is one member
    // lookup: no index to build, no cache to invalidate, and no
    // duplicate-recipe choice to make here — the server already published the
    // cheapest per unit.
    //
    // This deletes a bug class rather than fixing it. Indexing an ARRAY forced
    // a hand-rolled cache; keying that cache on the array's SIZE froze it
    // permanently, because the board was always exactly 100 rows (review
    // 2026-07-27), and holding it in a static field let one account's board
    // outlive a re-pair. A stateless lookup cannot do either.
    private JsonObject craftRow(String itemId) {
        var el = api.get("/craft/costs", 5 * 60_000);
        if (el == null || !el.isJsonObject()) {
            return null;  // loading, or the endpoint is unavailable
        }
        var row = el.getAsJsonObject().get(itemId);
        return row != null && row.isJsonObject() ? row.getAsJsonObject() : null;
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
     *  next 2.5M · 3 at floor§r". Null when the toggle is off; a missing
     *  object or low value returns the shared locked marker. Depth matters
     *  (owner): next-lowest is your REAL exit when you buy the floor.
     *  {@code units} multiplies for surfaces that display stack totals
     *  (SellOverlay); tooltips pass 1. Shared with SellOverlay. */
    static String lbinLine(MidasflipConfig config, JsonObject resp, int units) {
        if (!config.lbinTooltip) {
            return null;
        }
        JsonObject lb = GoldFields.optObj(resp, "lbin");
        Double low = GoldFields.optNum(lb, "low");
        if (low == null) {
            return GoldFields.locked("lbin");
        }
        StringBuilder s = new StringBuilder("§7lbin §f")
                .append(coins(low * units));
        Double next = GoldFields.optNum(lb, "next");
        if (next != null) {
            s.append(" §8· next ").append(coins(next * units));
        }
        Double atFloor = GoldFields.optNum(lb, "at_floor");
        if (atFloor != null && atFloor > 1) {
            s.append(" §8· ").append(atFloor.intValue()).append(" at floor");
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

    /** The server's itemized per-modifier deltas, normalised to (label,
     *  coins) pairs.
     *
     *  <p>CONTRACT: the backend sends a LIST, not an object —
     *  `decompose_value` returns `list[dict]` of
     *  {@code {"feature": atom, "learned_delta": coins, "learned": bool}}
     *  and main.py assigns it straight to `mod_contributions`. The first
     *  client read used optObj(), which rejects an array, so it returned
     *  null every single time: the total rendered as a Gold lock and the
     *  breakdown never appeared once. A shape mismatch that failed silently
     *  in the safe direction, which is why it read as "thin coverage"
     *  (audit finding, 2026-08-06).
     *
     *  <p>Both shapes are accepted. The list is the real contract; the
     *  object form costs three lines and means a future server change
     *  cannot blank this surface again.
     *
     *  <p>Unlearned atoms arrive with delta 0 and {@code learned:false} —
     *  deliberately itemized by the server rather than guessed. They are
     *  dropped here: "we know this adds nothing" and "we have not learned
     *  this yet" are different claims, and only the second is true. */
    private static final int MAX_CONTRIB_LINES = 4;

    /** Atom -> something a player reads. Grammar is ItemId.modAtoms':
     *  ult:&lt;name&gt;_&lt;lvl&gt;, ench6:&lt;bucket&gt;, gem:&lt;TYPE&gt;x&lt;n&gt;, hpb:&lt;n&gt;,
     *  drill_parts, held:&lt;item&gt;. Unknown shapes are surfaced RAW rather
     *  than guessed at, so a new atom appears as itself instead of silently
     *  rendering as something it is not. */
    static String prettyAtom(String atom) {
        if (atom == null || atom.isBlank()) {
            return "?";
        }
        if (atom.startsWith("ult:")) {
            String t = atom.substring(4);
            int u = t.lastIndexOf('_');
            String name = u > 0 ? t.substring(0, u) : t;
            String lvl = u > 0 ? t.substring(u + 1) : "";
            return "Ult. " + title(name) + (lvl.isEmpty() ? "" : " " + lvl);
        }
        if (atom.startsWith("ench6:")) {
            return atom.substring(6) + " high enchants";
        }
        if (atom.startsWith("gem:")) {
            String t = atom.substring(4);
            int x = t.lastIndexOf('x');
            return x > 0 ? title(t.substring(0, x)) + " x" + t.substring(x + 1) : title(t);
        }
        if (atom.startsWith("hpb:")) {
            return atom.substring(4) + " hot potato";
        }
        if (atom.equals("drill_parts")) {
            return "drill parts";
        }
        if (atom.startsWith("held:")) {
            return "held " + title(atom.substring(5));
        }
        return atom;
    }

    private static String title(String s) {
        // Lowercase first: gem atoms arrive SHOUTING ("JASPER") and a
        // tooltip full of capitals reads as an error message.
        String t = s.replace('_', ' ').strip().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) {
            return s;
        }
        return Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    /** The server's itemized per-modifier deltas.
     *
     *  <p>decompose.py returns a LIST of {@code {feature, learned_delta,
     *  learned}} rows. This only ever accepted a JSON object, so every array
     *  was rejected outright and the breakdown never rendered once in
     *  production (audit 2026-08-06). The object form is kept as a defensive
     *  path, not because anything sends it.
     *
     *  <p>{@code learned:false} rows carry {@code learned_delta} 0.0 and mean
     *  "no delta learned for this yet", NOT "this modifier is worth nothing".
     *  Zeros are dropped for the same reason, and dropped HERE rather than in
     *  each caller so the total and the breakdown can never disagree about
     *  which rows count.
     *
     *  @param strict return null on ANY malformed row instead of skipping it.
     *      The breakdown renders what it can read; the "+X" TOTAL must not,
     *      because a partial sum presented as a whole one understates the
     *      uplift while looking complete. */
    /** The board-matched headline: the listing price, then the evidence.
     *
     *  <p>Confidence and comp count are FREE (owner tier split 2026-08-06)
     *  but lived inside the same else-branch as the Gold price, so a
     *  withheld price took them down with it — free data discarded because
     *  it happened to sit next to paid data, which is the same defect that
     *  once hid the patient badge behind its exits. They render either way
     *  now.
     *
     *  <p>"list at", not "sell target": this is GROSS, the number typed into
     *  the auction sign, while the board's profit is NET of the AH fee
     *  model. net(29,500,000) = 28,614,000, and an owner reading the two as
     *  one figure listed at the net price would hand ~900k of margin back
     *  per flip (owner report 2026-08-07). */
    static String listAtLine(Double estPess, double confidence, int comps) {
        String evidence = "  §7conf §f" + String.format(Locale.ROOT, "%.2f", confidence)
                + " §8(" + comps + " comps)§r";
        return (estPess == null
                ? GoldFields.locked("list at")
                : "§7list at §a" + coins(estPess) + " §8(gross)§r") + evidence;
    }

    static java.util.List<java.util.Map.Entry<String, Double>> modContribs(JsonObject resp, boolean strict) {
        java.util.List<java.util.Map.Entry<String, Double>> out = new java.util.ArrayList<>();
        if (resp == null) {
            return strict ? null : out;
        }
        JsonElement raw = resp.get("mod_contributions");
        if (raw == null || raw.isJsonNull()) {
            return strict ? null : out;
        }
        if (raw.isJsonArray()) {
            for (JsonElement e : raw.getAsJsonArray()) {
                if (!e.isJsonObject()) {
                    if (strict) {
                        return null;
                    }
                    continue;
                }
                JsonObject o = e.getAsJsonObject();
                Double d = GoldFields.optNum(o, "learned_delta");
                String feature = GoldFields.optStr(o, "feature");
                if (d == null || feature == null) {
                    if (strict) {
                        return null;
                    }
                    continue;
                }
                if (o.has("learned") && o.get("learned").isJsonPrimitive()
                        && !o.get("learned").getAsBoolean()) {
                    continue;
                }
                if (d != 0) {
                    out.add(java.util.Map.entry(feature, d));
                }
            }
            return out;
        }
        if (raw.isJsonObject()) {
            JsonObject o = raw.getAsJsonObject();
            for (String k : o.keySet()) {
                Double d = GoldFields.optNum(o, k);
                if (d == null) {
                    if (strict) {
                        return null;
                    }
                    continue;
                }
                if (d != 0) {
                    out.add(java.util.Map.entry(k, d));
                }
            }
            return out;
        }
        return strict ? null : out;
    }

    static java.util.List<java.util.Map.Entry<String, Double>> modContribs(JsonObject resp) {
        return modContribs(resp, false);
    }

    /** Sum of the itemized deltas, for the "incl. modifiers +X" marker.
     *  Null when the server sent nothing usable — including when it sent a
     *  row we could not read, because this number appears next to a price and
     *  a silently partial total is a wrong one. */
    static Double modContribSum(JsonObject resp) {
        var rows = modContribs(resp, true);
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        double sum = 0;
        for (var e : rows) {
            sum += e.getValue();
        }
        return sum;
    }

    /** Biggest contributors first, as display lines. Capped so a heavily
     *  modified item cannot push a tooltip off the screen; the remainder is
     *  counted rather than silently dropped, because a hidden line is worse
     *  than a shorter one. */
    static java.util.List<String> modContribLines(JsonObject resp, int units) {
        var rows = new java.util.ArrayList<>(modContribs(resp));
        if (rows.isEmpty()) {
            return java.util.List.of();
        }
        rows.sort((x, y) -> Double.compare(Math.abs(y.getValue()), Math.abs(x.getValue())));
        java.util.List<String> out = new java.util.ArrayList<>();
        int shown = Math.min(rows.size(), MAX_CONTRIB_LINES);
        for (int i = 0; i < shown; i++) {
            var r = rows.get(i);
            double coins = r.getValue() * units;
            String sign = coins >= 0 ? "+" : "-";
            out.add("§8  " + prettyAtom(r.getKey()) + " §7" + sign + coins(Math.abs(coins)) + "§r");
        }
        if (rows.size() > shown) {
            out.add("§8  +" + (rows.size() - shown) + " more§r");
        }
        return out;
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
