package dev.midasflip.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

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
        // Pet EXP, strongest source first — the SAME resolution the sell panel
        // uses, so the two surfaces cannot land in different buckets for one
        // pet. NBT carries candy/tier-boost state; lore EXP does not, so lore
        // outranks only the level guess and never claims exact IDENTITY.
        Double petExpNbt = pet == null ? null : ItemId.petExp(stack);
        Double petExpLore = pet == null || petExpNbt != null
                ? null : Pets.expFromLore(stack);
        Double petExp = petExpNbt != null ? petExpNbt : petExpLore;
        String petType = pet == null ? null : ItemId.petType(stack);
        String petTier = pet == null ? null : ItemId.petTier(stack);
        String petSkin = pet == null ? null : ItemId.petSkin(stack);
        boolean petTierBoosted = pet != null && ItemId.petTierBoosted(stack);
        boolean exactPetNbt = petExpNbt != null && petType != null && petTier != null;
        // Tier + displayed level bracket the total EXP between cumulative(L)
        // and cumulative(L+1). When both ends land in the same bucket that
        // bucket is CERTAIN — no guess involved. When they do not, there is no
        // honest bucket to ask for, and asking for the wrong one is not a
        // small error: a Lvl 88 legendary Wolf sent as x1 priced at 5.0M
        // against a real x2 value of 16.9M (owner, 2026-08-14).
        String petLevelBucket = pet == null || petExp != null ? null
                : Pets.bucketFromLevel(
                    petTier != null ? Pets.effectiveTier(petTier, petTierBoosted) : pet.tier(),
                    pet.level(), petType != null ? petType : pet.type());
        // Nothing to ask with. The endpoint DEFAULTS exp_bucket to x0 — the
        // cheapest bucket in the game — so omitting it would lowball silently
        // rather than fail. Say we do not know instead.
        boolean petBucketUnknown = pet != null && petExp == null && petLevelBucket == null;
        String skyblockId = ItemId.of(stack);
        // Owner 2026-07-13: send the item's modifiers so the server prices
        // the FULL estimate, not the bare clean bucket. Full-NBT surfaces
        // (inventory hovers, incl. pets) read exact atoms from ExtraAttrs;
        // the lore-only menu path recovers ENCHANTS ONLY (see LoreMods).
        boolean lorePath = false; // NBT stripped → atoms are partial
        String path;
        if (petBucketUnknown) {
            // Refuse rather than price the wrong pet. Silence would read as a
            // broken feature (KNOWN-ISSUES 0.1.0 #2), so name the reason and
            // the one action that fixes it.
            lines.add(Component.literal(""));
            lines.add(Component.literal("§6§lMidasFlip §8· pet"));
            lines.add(Component.literal("§evalue unverified §8· EXP not readable here§r"));
            lines.add(Component.literal("§8Lvl " + pet.level()
                    + " spans two price buckets — hover it in your inventory§r"));
            return;
        }
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
                        : "&exp_bucket=" + petLevelBucket)
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
        // optObj, not getAsJsonObject(): the latter returns null for an
        // absent key (NPE on the next read) and throws ClassCastException
        // for an explicit JSON null. Every read below is null-tolerant, so
        // an estimate-less response now renders the honest lines instead of
        // killing the render thread — the tooltip path has no try/catch
        // above it (crash 2026-08-06).
        var est = GoldFields.optObj(resp, "estimate");
        // /price values are per unit; the flip finder values the complete
        // listing stack. Every number below therefore uses the same total.
        int units = Math.max(stack.getCount(), 1);
        boolean bazaar = resp.has("bazaar");
        String bucket = "clean bucket";
        if (bazaar) {
            bucket = "bazaar";
        } else if (pet != null) {
            bucket = "pet bucket";
        }
        if (!bazaar && resp.has("comp_key") && resp.get("comp_key").isJsonPrimitive()) {
            // Name the state that was priced, not the bucket FAMILY. "starred
            // bucket" never said WHICH star count, and "were my stars, my
            // recomb, my scrolls counted?" is the commonest doubt about the
            // estimate (owner 2026-08-07). The comp key is the server's own
            // answer, so it is read rather than re-derived here. Null means
            // the key carries nothing beyond identity, and the generic label
            // above stands.
            String state = bucketState(resp.get("comp_key").getAsString());
            if (state != null) {
                bucket = state;
            }
        }
        boolean lowball = resp.has("fallback_from_mods");
        boolean decomposed = "decomposed".equals(GoldFields.optStr(est, "src"));
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
            lines.add(Component.literal(evidenceLine(est)));
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
                for (String line : modContribLines(resp, units, shiftHeld())) {
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
            // has()+getAsString throws on an explicit JSON null; optStr
            // gives the same string for every payload the server sends and
            // falls back to the approximation when the field is absent,
            // which is exactly what the old has() check did.
            String served = GoldFields.optStr(resp, "exp_bucket");
            String b = served != null ? served : petLevelBucket;
            boolean exact = "nbt_exact".equals(GoldFields.optStr(resp, "pet_identity_source"))
                    && exactPetNbt;
            if (exact && petExp != null) {
                lines.add(Component.literal("§8Lvl " + pet.level() + " · "
                        + compactPetExp(petExp) + " EXP · exact " + b + " bucket§r"));
            } else if (petExpLore != null) {
                // Bucket exact (lore EXP), identity partial: this menu stripped
                // petInfo, so candy and tier-boost read as absent and BOTH of
                // those defaults overstate the pet. Do not let "exact bucket"
                // be read as "exact valuation".
                lines.add(Component.literal("§8Lvl " + pet.level() + " · "
                        + compactPetExp(petExpLore) + " EXP · " + b + " bucket§r"));
                lines.add(Component.literal(
                        "§emenu view — candy / tier-boost not visible§r"));
            } else {
                // Level-derived: tier + level bracket the EXP inside ONE
                // bucket, so this is exact for bucketing even though the raw
                // number is unknown. It is no longer "approximate" — that word
                // belonged to the tier-blind guess this replaced. Candy and
                // tier-boost are still unreadable in a menu, and both defaults
                // overstate, so that caveat stays.
                lines.add(Component.literal("§8Lvl " + pet.level() + " · " + b
                        + " bucket from level§r"));
                lines.add(Component.literal(
                        "§emenu view — candy / tier-boost not visible§r"));
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
        var m = java.util.regex.Pattern.compile("\\|s(\\d{1,3})(?:\\||$)").matcher(compKey);
        return !m.find() || Integer.parseInt(m.group(1)) == visibleStars;
    }

    /** Recombobulator segment of a comp key against what the LORE says.
     *
     *  Exactly the same hole starsMatch was added to close, one field over.
     *  Name plus stars is not identity: holding a 5✪ RECOMBED Juju and
     *  hovering a 5✪ non-recombed one matched on both, so the listing was
     *  priced from the recombed bucket — 35.0M against the sell overlay's
     *  honest 15.5M on the same bow (owner, 2026-08-11). The error direction
     *  is overstatement, i.e. it can talk you into overpaying.
     *
     *  Recomb is readable from lore even when NBT is stripped, which is how
     *  the sell overlay got it right on the same item, so there is no reason
     *  to guess here.
     *
     *  A key with no r segment stays compatible with anything, matching
     *  starsMatch: absence is not a claim. The dangerous direction is caught
     *  because that key carries an explicit r1. */
    // NOT WIRED UP. Wiring this to SellOverlay.loreRecombed would have made
    // things WORSE: that function returned false for a genuinely recombed
    // 5-star Juju (proved by the access log — the sell overlay asked for
    // recomb=false while the ledger key said r1 and the full-NBT value was
    // right). Using it as a guard would reject a CORRECT ledger key and drop
    // the tooltip from 35.0M to the coarse 15.5M, i.e. cause the very bug it
    // was meant to prevent, on the one surface that was still accurate.
    // Fix loreRecombed FIRST, then reconsider this. 2026-08-11.
    @SuppressWarnings("unused")
    private static boolean recombMatch(String compKey, boolean visibleRecomb) {
        var m = java.util.regex.Pattern.compile("\\|r([01])(?:\\||$)").matcher(compKey);
        return !m.find() || ("1".equals(m.group(1)) == visibleRecomb);
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
        return craftLine(craftRow(itemId), units);
    }

    /** The line itself, split out from the lookup so it is testable and so
     *  the reads go through the null-tolerant accessors: has("cost") is
     *  TRUE for an explicit JSON null and the getAsDouble that followed it
     *  threw on the render thread. Absent stays absent — a row we cannot
     *  read returns null and the tooltip simply omits the line, which is
     *  what it already did for a row with no cost. (/craft/costs is not
     *  deployed today: production answers 404, so craftRow returns null and
     *  this never runs. That is precisely why it needs to be safe before
     *  the endpoint lands.) */
    static String craftLine(JsonObject row, int units) {
        Double cost = GoldFields.optNum(row, "cost");
        Double yield = GoldFields.optNum(row, "n");
        if (cost == null || yield == null || yield <= 0) {
            return null;
        }
        double perUnit = cost / yield;
        String kind = GoldFields.optStr(row, "kind");
        return "§7est. craft §f" + coins(perUnit * units) + " §8· "
                + (kind == null ? "craft" : kind) + "§r";
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

    /** The tooltip's evidence line: model confidence, comp count, and how
     *  fast the bucket turns over when the server measured it.
     *
     *  <p>Read through optNum because {@code est.get("conf").getAsDouble()}
     *  NPEs on an absent key and throws UnsupportedOperationException on an
     *  explicit JSON null, and a throw here takes the whole game down — the
     *  tooltip render path has no try/catch above it (crash 2026-08-06).
     *
     *  <p>conf and comps are FREE fields (owner tier split 2026-08-06), so
     *  a missing one is "we do not have this", never a paywall: it renders
     *  through {@link GoldFields#unknown} and never through locked().
     *
     *  <p>Production sends both on every AH estimate and omits fields
     *  rather than nulling them (measured against the live API 2026-08-09),
     *  so every payload the server sends today renders byte-identically to
     *  before. A BAZAAR estimate carries only base/opt/pess, but bazaar
     *  responses take the other branch and never reach this line. */
    static String evidenceLine(JsonObject est) {
        Double spd = GoldFields.optNum(est, "spd");
        String spdPart = spd == null ? ""
                : " · " + String.format(Locale.ROOT, "%.1f", spd) + " sold/day";
        Double conf = GoldFields.optNum(est, "conf");
        Double comps = GoldFields.optNum(est, "comps");
        if (conf == null || comps == null) {
            return GoldFields.unknown("conf") + spdPart + "§r";
        }
        return "§7conf §f" + String.format(Locale.ROOT, "%.2f", conf)
                + " §8· " + comps.intValue() + " comps" + spdPart + "§r";
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

    /** Atoms the server RECOGNISED on this item but has no learned value for
     *  (learned:false). Measured live 2026-08-08 on a fully modded Hyperion:
     *  the server itemized 5 atoms and only 3 carried a delta, so these are
     *  the common case, not an edge.
     *
     *  <p>They are hidden by default because a number is what the collapsed
     *  line is for and these have none. But hiding them entirely means a
     *  player cannot tell whether the model even SAW their ultimate enchant,
     *  which is worse than saying "seen, not priced yet" — and saying that is
     *  the only honest option, since printing their 0.0 as a market fact
     *  would claim we measured a modifier to be worthless. */
    static java.util.List<String> unlearnedAtoms(JsonObject resp) {
        var out = new java.util.ArrayList<String>();
        JsonElement raw = resp == null ? null : resp.get("mod_contributions");
        if (raw == null || !raw.isJsonArray()) {
            return out;
        }
        for (JsonElement e : raw.getAsJsonArray()) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject o = e.getAsJsonObject();
            String feature = GoldFields.optStr(o, "feature");
            // Require an EXPLICIT learned:false. A row that simply omits the
            // flag is malformed, not "recognised but unpriced", and counting
            // it here would put a server bug in front of the user as if it
            // were a fact about their item.
            boolean saysUnlearned = o.has("learned") && o.get("learned").isJsonPrimitive()
                    && !o.get("learned").getAsBoolean();
            if (feature != null && saysUnlearned) {
                out.add(feature);
            }
        }
        return out;
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

    /** Biggest contributors first, as display lines, collapsed to the cap.
     *  Kept as the plain entry point for callers with no key state to read. */
    static java.util.List<String> modContribLines(JsonObject resp, int units) {
        return modContribLines(resp, units, false);
    }

    /** Biggest contributors first, as display lines. Capped so a heavily
     *  modified item cannot push a tooltip off the screen; the remainder is
     *  counted rather than silently dropped, because a hidden line is worse
     *  than a shorter one.
     *
     *  <p>Counted was still not reachable: "+3 more" named rows the user had
     *  no way to ever see, on the one surface that explains where the price
     *  came from. {@code expanded} lifts the cap entirely, and the remainder
     *  line carries the hint that lifts it — the hint rides that line instead
     *  of taking one of its own precisely because it is only ever needed when
     *  that line already exists, and tooltip rows are the scarce thing here.
     *
     *  @param expanded caller-read key state (see {@link #shiftHeld()}), never
     *      captured input. */
    static java.util.List<String> modContribLines(JsonObject resp, int units, boolean expanded) {
        var rows = new java.util.ArrayList<>(modContribs(resp));
        // NO early return on an empty priced list. It used to bail here, which
        // skipped the recognised-but-unpriced block below entirely — so on any
        // item where nothing has been learned yet (the common case, and
        // exactly the item a player most wants explained) the tooltip showed
        // no rows AND no hint, and holding shift did nothing with no way to
        // tell whether the feature was broken or the data was absent. Owner
        // hit this on live items twice; the second time was this bug rather
        // than the cap (2026-08-10).
        rows.sort((x, y) -> Double.compare(Math.abs(y.getValue()), Math.abs(x.getValue())));
        java.util.List<String> out = new java.util.ArrayList<>();
        int shown = expanded ? rows.size() : Math.min(rows.size(), MAX_CONTRIB_LINES);
        for (int i = 0; i < shown; i++) {
            var r = rows.get(i);
            double coins = r.getValue() * units;
            String sign = coins >= 0 ? "+" : "-";
            out.add("§8  " + prettyAtom(r.getKey()) + " §7" + sign + coins(Math.abs(coins)) + "§r");
        }
        int hiddenRows = rows.size() - shown;
        // Shift also surfaces the atoms we RECOGNISED but cannot price. In
        // production these outnumber the priced ones often enough that
        // without them shift does nothing at all on a typical item: measured
        // 2026-08-08, a fully modded Hyperion itemized 5 atoms of which 3
        // carried a delta, i.e. fewer than the 4-row cap, so the "+N more"
        // hint never appeared and holding shift changed nothing on screen
        // (owner report, same day). No number is printed for these, ever: the
        // server sends 0.0 for them and rendering that would state we
        // measured the modifier to be worth nothing.
        var unlearned = unlearnedAtoms(resp);
        if (expanded) {
            for (String feature : unlearned) {
                out.add("§8  " + prettyAtom(feature) + " §8· no value learned yet§r");
            }
        } else if (hiddenRows + unlearned.size() > 0) {
            // ONE hint line, never two. Priced rows over the cap and
            // recognised-but-unpriced atoms both hide behind the same key, so
            // they are counted together; two separate "hold SHIFT" lines
            // would cost two rows of a tooltip to say one thing.
            out.add("§8  +" + (hiddenRows + unlearned.size()) + " more · hold SHIFT§r");
        }
        return out;
    }

    /** Either shift key, read the way the shell reads TAB for its comps peek
     *  (MidasflipShellScreen). READ-ONLY: this asks the window what a key is
     *  already doing so it can decide what TEXT to draw. Nothing is captured
     *  or consumed, so shift keeps doing whatever vanilla does with it. */
    private static boolean shiftHeld() {
        var window = Minecraft.getInstance().getWindow();
        return InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_SHIFT)
                || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_SHIFT);
    }

    /** Longest bucket phrase we will render. A tooltip line competes with
     *  Hypixel's own lore for width, so an item wearing a lot of state gets
     *  elided rather than wrapping the header. */
    private static final int MAX_BUCKET_STATE = 40;

    /** A comp key as the STATE it describes: "5✪ recombed · 3 scrolls".
     *  Null when the key says nothing beyond identity, which leaves the
     *  caller's generic label ("clean bucket", "bazaar", "pet bucket") in
     *  place.
     *
     *  <p>Grammar, from the live corpus: {@code v1|<ID>|s<N>|r<0|1>} then any
     *  of {@code em}, {@code sc:A+B+C}, {@code k:<SKIN>}; pets are
     *  {@code v1|PET|<TYPE>|<TIER>|x<N>} plus optional {@code c1} / {@code tb}.
     *  Identity segments are skipped because the player is already looking at
     *  the item; everything after them is state.
     *
     *  <p>An unrecognised segment is passed through VERBATIM — never dropped,
     *  never guessed at, the same rule {@link #prettyAtom} follows. Dropping
     *  one would tell the user their item was priced without it, which is the
     *  exact opposite of the truth; showing it raw reads as unfamiliar, which
     *  is what it is. A key that is not our shape at all returns null rather
     *  than printing itself into the header. */
    static String bucketState(String compKey) {
        if (compKey == null || compKey.isBlank()) {
            return null;
        }
        String[] seg = compKey.split("\\|", -1);
        if (seg.length < 2 || !seg[0].matches("v\\d+")) {
            return null;
        }
        int stars = 0;
        boolean recombed = false;
        java.util.List<String> parts = new java.util.ArrayList<>();
        // seg[1] is the item id; a pet spends two more on type and tier.
        for (int i = "PET".equals(seg[1]) ? 4 : 2; i < seg.length; i++) {
            String s = seg[i];
            if (s.isEmpty()) {
                continue;
            }
            if (s.matches("s\\d{1,3}")) {
                // Bounded BEFORE parsing, not after. "s99999999999" matched
                // the old unbounded \\d+ and then overflowed Integer.parseInt
                // — a NumberFormatException thrown inside the per-frame
                // tooltip render, which has no try/catch anywhere above it.
                // That is the same failure that took the whole game down on
                // 2026-08-06, and this runs on EVERY hover carrying a comp
                // key, so the exposure is far wider than the old starsOf.
                // A key we cannot read falls through to verbatim below, which
                // is the honest outcome: show it, do not guess, do not crash.
                stars = Integer.parseInt(s.substring(1));   // s0 says nothing
            } else if (s.equals("r0")) {
                continue;                                   // not recombed
            } else if (s.equals("r1")) {
                recombed = true;
            } else if (s.equals("em")) {
                parts.add("ethermerged");
            } else if (s.startsWith("sc:") && !s.substring(3).isBlank()) {
                int n = s.substring(3).split("\\+").length;
                parts.add(n + (n == 1 ? " scroll" : " scrolls"));
            } else if (s.startsWith("k:") && !s.substring(2).isBlank()) {
                parts.add("skin");
            } else if (s.matches("x\\d+")) {
                parts.add(s + " exp bucket");
            } else if (s.equals("c1")) {
                parts.add("candied");
            } else if (s.equals("tb")) {
                parts.add("tier boost");
            } else {
                parts.add(s);
            }
        }
        // Stars and recomb are one upgrade story, so they read as one phrase.
        // ✪ is Hypixel's own glyph and the tooltip renders directly under the
        // lore carrying it, so "5✪" matches what the player is looking at. ★
        // is spoken for in this mod: it marks a rule-starred board row.
        String core = stars > 0 ? stars + "✪" : "";
        if (recombed) {
            core = core.isEmpty() ? "recombed" : core + " recombed";
        }
        if (!core.isEmpty()) {
            parts.add(0, core);
        }
        if (parts.isEmpty()) {
            return null;
        }
        String phrase = String.join(" · ", parts);
        if (phrase.length() <= MAX_BUCKET_STATE) {
            return phrase;
        }
        // Drop WHOLE parts from the end until it fits, and say how many went.
        // A raw substring cut the last part first, and unknown segments are
        // appended last — so the one class of segment the verbatim rule
        // exists to protect was the first thing thrown away, mid-word, with
        // no way to ever see it (review 2026-08-07). Losing "+2 more" is a
        // countable omission; losing "shin…" is a lie about what was priced.
        var kept = new java.util.ArrayList<>(parts);
        while (kept.size() > 1
                && String.join(" · ", kept).length() + 8 > MAX_BUCKET_STATE) {
            kept.remove(kept.size() - 1);
        }
        int dropped = parts.size() - kept.size();
        String head = String.join(" · ", kept);
        if (dropped == 0) {
            return head.length() <= MAX_BUCKET_STATE
                    ? head : head.substring(0, MAX_BUCKET_STATE - 1).strip() + "…";
        }
        return head + " +" + dropped + " more";
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
        var m = java.util.regex.Pattern.compile("\\|s(\\d{1,3})(?:\\||$)").matcher(compKey);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /** Canonical coin format (config-completeness 2026-07-13): routes
     *  through {@link dev.midasflip.client.ui.Phos#coins} so B renders at 2dp
     *  and the raw-numbers toggle applies — the old local copy used 1dp. */
    private static String coins(double v) {
        return dev.midasflip.client.ui.Phos.coins(v);
    }
}
