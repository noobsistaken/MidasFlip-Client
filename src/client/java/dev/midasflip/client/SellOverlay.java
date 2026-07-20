package dev.midasflip.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.midasflip.client.ui.Phos;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Sell-assist overlay (owner request 2026-07-05): while listing an item,
 * a side panel shows the same pessimistic sell valuation the flip finder
 * scores, plus its fair / optimistic valuation band
 * — plus what you PAID when the item came through MidasFlip's ledger.
 *
 * Matching runs PER FRAME (the sell GUI opens empty and the item lands
 * in it afterwards — an open-time check sees nothing; first live test
 * proved exactly that). Two data paths:
 *  - ledger match → your exact cost + the exits recorded at buy;
 *  - otherwise → the item's clean-bucket market estimate by display name
 *    (/price/by-name), labeled as market data with no cost basis.
 *
 * DISPLAY-ONLY: reads slots, draws a panel; never clicks, never sets a
 * price, never forwards input. Safe in STRICT and ASSISTED (spec §13).
 */
public final class SellOverlay {
    private final MidasflipConfig config;
    private final PositionLedger ledger;
    private final MidasflipApi api;

    public SellOverlay(MidasflipConfig config, PositionLedger ledger, MidasflipApi api) {
        this.config = config;
        this.ledger = ledger;
        this.api = api;
    }

    public void register() {
        // Wire the shared api into the ledger's alert-only re-valuation:
        // this is the one construction site holding both (FlipHud drives
        // the periodic sweep but has no api handle of its own).
        ledger.attachApi(api);
        ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
            if (!config.sellOverlay || !(screen instanceof AbstractContainerScreen<?> cs)) {
                return;
            }
            // Owner call 2026-07-06: the sell panel opens ONLY on the
            // create-auction screen — not browsers, bids, or bazaar.
            String title = screen.getTitle().getString().toLowerCase(Locale.ROOT);
            boolean sellish = title.contains("create") && title.contains("auction");
            if (!sellish) {
                return;
            }
            // Piggyback re-valuation on the sell GUI opening: kick the
            // async current-price fetches now so they've landed by the
            // time the player hovers their item (TTL-cached, ≤10 GETs).
            ledger.refreshValuations(api);
            // Price whatever the mouse is over — the player hovers the item
            // they care about (in the sell slot OR their own inventory row,
            // whose stacks keep full NBT). Mouse position comes straight
            // from the window (the event's ints proved not to be gui-space
            // mouse coords in live testing — the vanilla tooltip showed
            // while our hit test missed).
            ScreenEvents.afterExtract(cs).register((s, gfx, mx, my, d) -> {
                var hovered = hoveredStack(cs);
                if (hovered != null && !hovered.isEmpty() && !isControl(hovered)) {
                    draw(gfx, s.width, s.height, hovered);
                } else {
                    drawHint(gfx, s.width, s.height);
                }
            });
        });
    }

    /** The stack under the cursor — read straight from the screen's own
     *  hoveredSlot (access-widened), the exact field that drives the
     *  vanilla tooltip. No GUI-geometry math (two prior attempts guessed
     *  the origin wrong on Hypixel's create-auction menu). */
    private net.minecraft.world.item.ItemStack hoveredStack(AbstractContainerScreen<?> cs) {
        Slot hovered = ((dev.midasflip.client.mixin.ContainerScreenAccessor) cs).midasflip$hoveredSlot();
        return hovered == null ? null : hovered.getItem();
    }

    /** The Component that actually NAMES the item. Hypixel's create-auction
     *  slot renames the item to be sold to the menu header "AUCTION FOR
     *  ITEM:" and strips its SkyBlock NBT id; the real, styled item name
     *  ("Jungle Pickaxe", "[Lvl 100] Ender Dragon") is the FIRST lore line
     *  (proven live 2026-07-08 via runtime logging — getHoverName returned
     *  the header, id was null, so nothing resolved). Everywhere else the
     *  display name is the item name, so fall through to it. */
    static net.minecraft.network.chat.Component effectiveName(net.minecraft.world.item.ItemStack stack) {
        String display = stack.getHoverName().getString().replaceAll("§.", "").strip().toLowerCase(Locale.ROOT);
        if (display.startsWith("auction for item")) {
            var lore = stack.get(net.minecraft.core.component.DataComponents.LORE);
            if (lore != null) {
                // The header is followed by a BLANK line, THEN the styled
                // item name ("6x Empty Chumcap Bucket") — proven live: lore0
                // is empty. Take the first NON-BLANK lore line.
                for (var line : lore.lines()) {
                    if (!line.getString().replaceAll("§.", "").strip().isEmpty()) {
                        return line;
                    }
                }
            }
        }
        return stack.getHoverName();
    }

    // Hypixel reforge prefixes. The auction lore name line carries the FULL
    // display name ("Fierce Terminator", "⚚ Withered Hyperion"); the API's
    // by-name map is keyed on the BASE name, so a reforge word must come off.
    // Only the first word is stripped, and only when it's a known reforge, so
    // a real item whose name merely starts with an adjective is never harmed.
    // A missing reforge just yields a graceful "no market data" (never a
    // wrong price). Extend as Hypixel adds reforges.
    private static final java.util.Set<String> REFORGES = java.util.Set.of(
            "gentle", "odd", "fast", "fair", "epic", "sharp", "heroic", "spicy", "legendary",
            "deadly", "fine", "grand", "hasty", "neat", "rapid", "unreal", "awkward", "rich",
            "precise", "spiritual", "headstrong", "clean", "fierce", "heavy", "light", "mythic",
            "pure", "smart", "titanic", "wise", "bizarre", "itchy", "ominous", "pleasant",
            "pretty", "shiny", "simple", "strange", "vivid", "godly", "demonic", "forceful",
            "hurtful", "keen", "strong", "superior", "unpleasant", "zealous", "ancient",
            "necrotic", "spiked", "renowned", "cubic", "warped", "reflective", "undead",
            "perfect", "fabled", "gilded", "suspicious", "bulky", "jaded", "dirty", "bloody",
            "silky", "ridiculous", "bustling", "mossy", "festive", "submerged", "jerry",
            "blessed", "bountiful", "fruitful", "magnetic", "refined", "stellar", "fleet",
            "mithraic", "auspicious", "treacherous", "dimensional", "waxed", "glistening",
            "toil", "moil", "blooming", "rooted", "snowy", "salty", "lucky", "stiff", "chomp",
            "pitchin", "fanged", "loving", "withered", "coldfusion");

    /** Item name as the API knows it: strips the auction slot's "6x " stack-
     *  count prefix and any leading non-letter symbols (star/ability glyphs)
     *  Hypixel prepends. Does NOT touch reforge words — see {@link
     *  #stripReforge} (reforge removal is a FALLBACK only, because several
     *  reforge words double as armor-set names: "Strong/Wise/Superior Dragon"
     *  resolve by their FULL name and must not be stripped). */
    static String cleanName(String raw) {
        return raw.replaceFirst("^\\s*\\d+x\\s+", "")            // "6x "
                .replaceFirst("^[^\\p{L}]+", "")                   // ✪ ⚚ ✦ etc.
                .strip();
    }

    /** Stars read from the item's display/lore NAME line — the sell GUI
     *  strips NBT, but the ✪s survive in the name ("Ancient Divan's
     *  Chestplate ✪✪✪✪✪"). Master stars render as ➊➋➌➍➎ after the ✪s and
     *  each adds one to the collector's upgrade_level. */
    static int loreStars(String cleanNameLine) {
        int n = 0;
        for (int i = 0; i < cleanNameLine.length(); i++) {
            char c = cleanNameLine.charAt(i);
            if (c == '✪' || (c >= '➊' && c <= '➎')) {
                n++;
            }
        }
        return Math.min(n, 10);
    }

    /** Recombobulated, read from LORE: Hypixel wraps an upgraded rarity
     *  line in decoration glyphs ("◆ MYTHIC LEGGINGS ◆"), while a natural
     *  rarity line starts with the tier word itself ("LEGENDARY
     *  LEGGINGS"). Conservative by construction: unreadable/odd lore
     *  reads NOT-recombed, which routes to the clean bucket + the
     *  likely-worth-more warning — never a confident wrong price. */
    static boolean loreRecombed(net.minecraft.world.item.ItemStack stack) {
        var lore = stack.get(net.minecraft.core.component.DataComponents.LORE);
        if (lore == null || lore.lines().isEmpty()) {
            return false;
        }
        for (int i = lore.lines().size() - 1; i >= 0; i--) {
            String line = lore.lines().get(i).getString().replaceAll("§.", "").strip();
            if (line.isEmpty()) {
                continue;
            }
            java.util.regex.Matcher m = RARITY_LINE.matcher(line);
            if (!m.find()) {
                return false; // bottom-most real line is not a rarity line
            }
            // Recombed = decoration glyphs BEFORE the tier word.
            return m.start() > 0 && !Character.isLetter(line.charAt(0));
        }
        return false;
    }

    private static final java.util.regex.Pattern RARITY_LINE = java.util.regex.Pattern.compile(
            "\\b(COMMON|UNCOMMON|RARE|EPIC|LEGENDARY|MYTHIC|DIVINE|VERY SPECIAL|SPECIAL)\\b");

    /** Drop a leading reforge word — used ONLY when the full name missed, so
     *  reforge-named sets keep resolving by their full name first. Returns
     *  the input unchanged when the first word isn't a known reforge. */
    static String stripReforge(String name) {
        int sp = name.indexOf(' ');
        if (sp > 0 && REFORGES.contains(name.substring(0, sp).toLowerCase(Locale.ROOT))) {
            return name.substring(sp + 1).strip();
        }
        return name;
    }

    /** GUI buttons/filler/placeholders in Hypixel's create-auction menu,
     *  by name — everything that is NOT a sellable item. */
    private static boolean isControl(net.minecraft.world.item.ItemStack stack) {
        if (ItemId.of(stack) != null) {
            return false; // has a real SkyBlock id — definitely sellable
        }
        String n = effectiveName(stack).getString().replaceAll("§.", "").strip().toLowerCase(Locale.ROOT);
        if (n.isEmpty() || n.matches("[< >←→✖✔]+")) {
            return true;
        }
        return n.contains("click") || n.contains("your inventory") || n.contains("to sell")
                || n.contains("sell it") || n.contains("select") || n.contains("choose")
                || n.contains("auction") || n.contains("buy it now") || n.contains("bid")
                || n.contains("duration") || n.contains("cancel") || n.contains("confirm")
                || n.contains("coin") || n.contains("go back") || n.contains("close")
                || n.contains("how to") || n.contains("collect");
    }

    /** Panel shown on a sell GUI before an item is in the sell slot. */
    private void drawHint(GuiGraphicsExtractor g, int screenW, int screenH) {
        int w = 158;
        int guiRight = screenW / 2 + GUI_WIDTH / 2;
        int x = guiRight + 8;
        int y = Math.max(6, (screenH - GUI_HEIGHT_6ROW) / 2);
        if (x + w > screenW - 4) {
            x = screenW - w - 6;
            y = 6;
        }
        Phos.panel(g, x, y, w, 40);
        var font = net.minecraft.client.Minecraft.getInstance().font;
        Phos.text(g, font, "§6MidasFlip §7· sell§r", x + 8, y + 8, Phos.ACCENT);
        Phos.text(g, font, "§8hover an item to price it§r", x + 8, y + 22, Phos.FAINT);
    }

    // Vanilla container GUIs are 176 wide, centered; leftPos/topPos are
    // protected without getters, so the dock position is computed from
    // standard centering (Hypixel menus are stock chest GUIs).
    private static final int GUI_WIDTH = 176;
    private static final int GUI_HEIGHT_6ROW = 222;

    private void draw(GuiGraphicsExtractor g, int screenW, int screenH,
                      net.minecraft.world.item.ItemStack stack) {
        var nameComponent = effectiveName(stack);
        String skyblockId = ItemId.of(stack);
        String itemName = cleanName(nameComponent.getString().replaceAll("§.", "").strip());
        Pets.PetName pet = Pets.parse(nameComponent);
        Double petExp = pet == null ? null : ItemId.petExp(stack);
        String petType = pet == null ? null : ItemId.petType(stack);
        String petTier = pet == null ? null : ItemId.petTier(stack);
        String petSkin = pet == null ? null : ItemId.petSkin(stack);
        boolean petTierBoosted = pet != null && ItemId.petTierBoosted(stack);
        boolean exactPetNbt = petExp != null && petType != null && petTier != null;
        PositionLedger.Position pos = findPosition(stack, skyblockId, itemName, pet);
        boolean useLedgerPetKey = pet != null && petExp == null
                && PositionLedger.hasCurrentPetKey(pos);

        // Keep the hovered position's current estimate fresh while the
        // panel is up (per-frame call, but api.get is TTL-cached — at
        // most one background GET per position per minute).
        if (pos != null) {
            ledger.refreshValuations(api);
        }
        boolean underwater = pos != null && pos.underwater();

        // Reserve a line for the decomposed marker: on the market path (no
        // ledger position) an item carrying modifier atoms will come back
        // decomposed, which needs a "incl. modifiers" line. Computing the
        // atoms here (cheap, off-render allocation is a few strings) lets
        // the panel be sized BEFORE the async price lands — decomposed text
        // must never spill past the panel border. Harmless padding if the
        // response turns out clean (bazaar / unlearned-only).
        boolean maybeDecomposed = !modAtomsFor(stack).isEmpty();
        // Lore path (NBT stripped: no id, not a pet) adds a second partial-
        // scope note under the marker — reserve for it too.
        boolean maybeLoreDecomposed = maybeDecomposed
                && ItemId.of(stack) == null && Pets.parse(effectiveName(stack)) == null;

        // Dock as a side tab on the GUI's right edge; corner fallback when
        // the window is too narrow for the panel to fit beside it.
        int w = 158;
        int h = 96 + (pos != null ? 13 : 0) + (underwater ? 22 : 0);
        if (maybeDecomposed) {
            h += 11;                       // "incl. modifiers +X · amber" line
        }
        if (maybeLoreDecomposed) {
            h += 11;                       // "menu view — enchants only" line
        }
        int guiRight = screenW / 2 + GUI_WIDTH / 2;
        int x = guiRight + 8;
        int y = Math.max(6, (screenH - GUI_HEIGHT_6ROW) / 2);
        if (x + w > screenW - 4) {
            x = screenW - w - 6;
            y = 6;
        } else {
            // Tab connector: a small notch bridging panel and GUI edge.
            g.fill(guiRight + 2, y + 10, x + 1, y + 22, Phos.PANEL);
            Phos.hline(g, guiRight + 2, y + 10, 7);
            Phos.hline(g, guiRight + 2, y + 21, 7);
        }
        Phos.panel(g, x, y, w, h);
        int cx = x + 8;
        int cy = y + 8;
        var font = net.minecraft.client.Minecraft.getInstance().font;
        Phos.text(g, font, "§6MidasFlip §7· sell§r", cx, cy, Phos.ACCENT);
        cy += 12;

        if (pos != null) {
            Phos.text(g, font, "§7you paid §f" + Phos.coins(pos.buyPrice) + "§r", cx, cy, Phos.DIM);
            cy += 13;
            if (underwater) {
                // Market moved against this hold since buy: even the CURRENT
                // pessimistic estimate is under cost. Words only — the human
                // decides whether to cut (alert-only, spec §13).
                Phos.text(g, font, "§c▼ now worth ~" + Phos.coins(pos.curPessTotal())
                        + " (pess)§r", cx, cy, Phos.RED);
                cy += 11;
                Phos.text(g, font, "§cmarket moved against you§r", cx, cy, Phos.RED);
                cy += 11;
            }
            // Pricing falls through to the current valuation row below.
            // The ledger contributes cost basis only; both the tooltip and
            // this panel use today's finder valuation for the sell target.
        }

        // Market fallback: prefer the exact NBT id (reliable); fall back
        // to the display-name map only when the stack has no id.
        // Owner 2026-07-13: send the item's modifiers so the panel shows the
        // FULL estimate, not the bare clean bucket. Full-NBT stacks read
        // exact atoms; the lore-only menu path recovers ENCHANTS ONLY.
        boolean lorePath = false;
        String path;
        if (pet != null) {
            // Pet type comes from the display name ("[Lvl N] Ender Dragon");
            // the NBT id for pets is just "PET". Exact NBT sends RAW EXP so
            // the backend resolves the collector bucket. If this menu
            // stripped NBT, a current versioned finder key is the only exact
            // fallback; otherwise the legacy level bucket is display-only.
            if (useLedgerPetKey) {
                path = "/value/" + URLEncoder.encode(pos.compKey, StandardCharsets.UTF_8);
            } else {
                String mods = ItemId.modAtoms(stack); // pet held item, if any
                path = (petType != null ? "/price/by-id/" : "/price/by-name/")
                        + URLEncoder.encode(petType != null ? petType : pet.type(),
                            StandardCharsets.UTF_8)
                        + "?pet=true"
                        + (petExp != null
                            ? "&pet_exp=" + Double.toString(petExp)
                            : "&exp_bucket=" + Pets.approximateExpBucket(pet.level(), config))
                        + (ItemId.petCandied(stack) ? "&candied=true" : "")
                        + (petTierBoosted ? "&tier_boosted=true" : "")
                        + "&tier=" + (petTier != null
                            ? Pets.effectiveTier(petTier, petTierBoosted) : pet.tier())
                        + (petSkin == null ? "" : "&skin="
                            + URLEncoder.encode(petSkin, StandardCharsets.UTF_8))
                        + ItemTooltip.modsParam(mods);
            }
        } else if (skyblockId != null) {
            // Full NBT available: exact stars/recomb from ExtraAttributes.
            String variant = ItemId.variantOf(stack);
            String mods = ItemId.modAtoms(stack);
            path = "/price/by-id/" + URLEncoder.encode(skyblockId, StandardCharsets.UTF_8)
                    + "?stars=" + ItemId.stars(stack)
                    + "&recomb=" + ItemId.recombed(stack)
                    + (variant != null ? "&variant=" + URLEncoder.encode(variant, StandardCharsets.UTF_8) : "")
                    + ItemTooltip.modsParam(mods);
        } else {
            // NBT stripped (Hypixel menus): stars/recomb read from LORE so
            // a recombed/starred item asks for ITS bucket, not the clean
            // one (owner incident 2026-07-12: recombed Divan leggings shown
            // at the clean price — half their value).
            lorePath = true;
            int loreStars = loreStars(nameComponent.getString().replaceAll("§.", ""));
            boolean loreRecomb = loreRecombed(stack);
            String mods = LoreMods.atomsFromLore(stack); // ults + ench6 only
            path = "/price/by-name/" + URLEncoder.encode(itemName, StandardCharsets.UTF_8)
                    + "?stars=" + loreStars + "&recomb=" + loreRecomb
                    + ItemTooltip.modsParam(mods);
        }
        JsonElement el = api.get(path, 60_000);
        // Reforge fallback (by-name only): the full name is tried FIRST so
        // reforge-named sets ("Strong Dragon") resolve; only when that misses
        // do we retry without the leading reforge word ("Fierce Terminator"
        // → "Terminator"). Full name first = no dragon-armor regression.
        // Carries the same stars/recomb query — the fallback must ask for
        // the same bucket the primary asked for.
        if (pet == null && skyblockId == null && el != null && el.isJsonNull()) {
            String base = stripReforge(itemName);
            if (!base.equals(itemName)) {
                String fbPath = "/price/by-name/" + URLEncoder.encode(base, StandardCharsets.UTF_8)
                        + "?stars=" + loreStars(nameComponent.getString().replaceAll("§.", ""))
                        + "&recomb=" + loreRecombed(stack)
                        + ItemTooltip.modsParam(LoreMods.atomsFromLore(stack));
                JsonElement re = api.get(fbPath, 60_000);
                if (re != null && re.isJsonObject()) {
                    el = re;
                    path = fbPath; // staleness checks must follow the entry served
                }
            }
        }
        if (el != null && el.isJsonNull()) {
            // Show what we looked up — instantly separates "genuinely no
            // sales data" from a resolution bug when debugging live.
            Phos.text(g, font, "§8no market data§r", cx, cy, Phos.FAINT);
            Phos.text(g, font, "§8" + shorten(skyblockId != null ? skyblockId : norm(itemName), 24) + "§r",
                    cx, cy + 11, Phos.FAINT);
            return;
        }
        if (el == null || !el.isJsonObject()) {
            Phos.text(g, font, api.likelyDown() ? "§coffline — start tunnel§r" : "§8pricing…§r",
                    cx, cy, Phos.FAINT);
            return;
        }
        JsonObject resp = el.getAsJsonObject();
        JsonObject est = resp.getAsJsonObject("estimate");
        // Market path: estimates arrive PER-UNIT; the clipboard gets the
        // stack total, so the displayed rows must be stack totals too —
        // display and paste must agree (×N marks the multiply).
        int units = Math.max(stack.getCount(), 1);
        // One shared valuation seam: the recommended sell target is the
        // pessimistic band the flip detector scores. Lowest BIN is context,
        // never a competing recommendation that can drag the target below
        // the engine's own fair downside value.
        FinderValuation.Result valuation = FinderValuation.from(est, resp);
        Double marketSuggest = valuation.target() == null
                ? null : valuation.target() * units;
        // The server answers with a flag when we ASKED for a gear bucket
        // (stars/recomb detected) but only the clean bucket had data. A
        // clean price on a rolled item is a LOWBALL, not an estimate —
        // warn and never arm the clipboard with it (owner incident
        // 2026-07-12: recombed Divan leggings, clean price = half value).
        boolean lowball = resp.has("fallback_from_mods");
        // Decomposed = server priced clean bucket + learned modifier deltas
        // (est.src, amber-capped conf). NEVER present it without the marker
        // below (spec transparency; the amber cap means it reads softer).
        boolean decomposed = est.has("src") && "decomposed".equals(est.get("src").getAsString());
        boolean exactPetIdentity = pet == null || useLedgerPetKey
                || (exactPetNbt && resp.has("pet_identity_source")
                    && "nbt_exact".equals(resp.get("pet_identity_source").getAsString()));
        // Acting (clipboard copy) demands a CURRENT market; displaying a
        // stale-while-unreachable price with an age marker is fine, but
        // silently arming a paste with one is not (MidasflipApi contract).
        boolean stale = api.isStale(path, 10 * 60_000);
        // Don't ARM a one-keystroke paste with a decomposed number: it's
        // amber-capped precisely because it's base + learned deltas, not a
        // direct comp. The on-screen rows still show it (with the "amber"
        // marker); the clipboard stays a direct-comp-only convenience —
        // same conservative posture as stale/lowball (safety-review NOTE
        // 2026-07-13). The human can still type the shown number by hand.
        if (!stale && !lowball && !decomposed && exactPetIdentity) {
            copyPrice(marketSuggest);
        }
        Phos.text(g, font, "§7finder valuation" + (units > 1 ? " §f×" + units : "")
                + (stale ? " §c· stale§r" : "") + " §8(current)§r", cx, cy, Phos.DIM);
        cy += 13;
        if (valuation.backed()) {
            row(g, font, cx, cy, "sell target", valuation.target() * units, null);
            cy += 11;
            row(g, font, cx, cy, "fair", valuation.fair() * units, null);
            cy += 11;
            row(g, font, cx, cy, "high", valuation.high() * units, null);
            cy += 13;
        } else {
            Phos.text(g, font, "§evalue unverified§r", cx, cy, Phos.YELLOW);
            cy += 11;
            Phos.text(g, font, "§8" + valuation.blockedReason() + "§r", cx, cy, Phos.FAINT);
            cy += 13;
        }
        if (decomposed) {
            // MANDATORY marker (spec transparency): a decomposed number is
            // base bucket + learned modifier deltas, amber-capped — the
            // sum is what the modifiers added, ×units for stack display.
            double contribSum = ItemTooltip.modContribSum(resp) * units;
            Phos.text(g, font, "§7incl. modifiers §f+" + Phos.coins(contribSum)
                    + "§8 · amber§r", cx, cy, Phos.DIM);
            cy += 11;
            if (lorePath) {
                // Lore path saw enchants only (LoreMods scope) — say so, so
                // the softer number isn't read as a complete valuation.
                Phos.text(g, font, "§8menu view — enchants only, gems/HPB unseen§r",
                        cx, cy, Phos.FAINT);
                cy += 11;
            }
        }
        // Live listing depth, mirrored from the tooltip (same /price
        // response): next-lowest is your REAL exit when you buy the floor.
        // Multiplied by units like the rows above — display consistency.
        String lbin = ItemTooltip.lbinLine(config, resp, units);
        if (lbin != null) {
            Phos.text(g, font, lbin, cx, cy, Phos.DIM);
            cy += 11;
        }
        // Bottom note: WHICH bucket priced this + how liquid it is. When
        // the gear bucket was requested but missing, the warning REPLACES
        // the note (same footprint, panel height unchanged) — a clean
        // price on a rolled item must read as a lowball, not a verdict.
        String spd = est.has("spd") && !est.get("spd").isJsonNull()
                ? " · " + String.format(Locale.ROOT, "%.1f", est.get("spd").getAsDouble()) + "/day"
                : "";
        String note;
        if (lowball) {
            note = "§e⚠ starred/recombed — clean price, likely LOW§r";
        } else if (resp.has("bazaar")) {
            note = "§8bazaar item · fast=instasell · wait=sell offer§r";
        } else if (pet != null) {
            String tier = resp.has("tier") ? resp.get("tier").getAsString().toLowerCase(Locale.ROOT) : "?";
            String bucket = resp.has("exp_bucket") ? resp.get("exp_bucket").getAsString()
                    : petBucketFromKey(resp.has("comp_key") ? resp.get("comp_key").getAsString() : null);
            if (useLedgerPetKey) {
                note = "§8" + tier + " · finder key · exact " + bucket + spd + "§r";
            } else if (exactPetIdentity && petExp != null) {
                note = "§8" + tier + " · Lvl " + pet.level() + " · "
                        + ItemTooltip.compactPetExp(petExp) + " EXP · exact " + bucket + spd + "§r";
            } else {
                note = "§e" + tier + " · Lvl " + pet.level()
                        + " · approximate " + bucket + " · clipboard off§r";
            }
        } else {
            String bucket = resp.has("comp_key") && resp.get("comp_key").getAsString().matches(".*\\|s\\d+\\|r1.*")
                    ? "recomb bucket" : resp.has("comp_key") && resp.get("comp_key").getAsString().matches(".*\\|s[1-9]\\d*\\|r\\d.*")
                    ? "starred bucket" : "clean bucket";
            note = "§8" + bucket + " · " + est.get("comps").getAsInt()
                    + " comps · conf " + String.format(Locale.ROOT, "%.2f", est.get("conf").getAsDouble())
                    + spd + "§r";
        }
        Phos.text(g, font, note, cx, cy, Phos.FAINT);
    }

    // Clipboard copy is idempotent per value — the panel redraws every
    // frame, so only re-copy when the suggested price actually changes,
    // to avoid clobbering the clipboard the player may be using.
    private long lastCopied;

    private void copyPrice(Double suggest) {
        if (!config.sellCopyPrice || suggest == null || suggest <= 0) {
            return;
        }
        long v = Math.round(suggest);
        if (v == lastCopied) {
            return;
        }
        lastCopied = v;
        net.minecraft.client.Minecraft.getInstance().keyboardHandler.setClipboard(String.valueOf(v));
    }

    private String copiedHint(Double suggest) {
        if (config.sellCopyPrice && suggest != null && suggest > 0) {
            return "§8" + Phos.coins(suggest) + " copied · paste (Ctrl+V) in the sign§r";
        }
        return "§8as-of buy · you set & confirm§r";
    }

    /** The modifier atoms this stack WILL send, by the same path choice as
     *  draw(): full-NBT (pet or id present) reads exact atoms from
     *  ExtraAttributes; the NBT-stripped menu path recovers enchants from
     *  lore. Used only to pre-size the panel for the decomposed marker —
     *  the actual request rebuilds atoms inline (cheap; strings only). */
    private String modAtomsFor(net.minecraft.world.item.ItemStack stack) {
        if (ItemId.of(stack) != null || Pets.parse(effectiveName(stack)) != null) {
            return ItemId.modAtoms(stack);
        }
        return LoreMods.atomsFromLore(stack);
    }

    /** One price row. The displayed price is the GROSS listing total (what
     *  you type in the sign); the profit parenthetical is the server-
     *  computed NET PROFIT DELTA at that exit. WIRE SEMANTICS (flip.go
     *  attachExits, pinned by TestExitNetIsAProfitDelta): exit_fast_net =
     *  netProceeds(exit) − buy — buy is ALREADY subtracted. Subtracting
     *  paid again here rendered netProceeds − 2·buy: a fake ~-6.3M loss
     *  on a real +1.7M flip (caught by review 2026-07-10). No net known
     *  (wait row, legacy ledger rows) → no parenthetical. */
    private void row(GuiGraphicsExtractor g, net.minecraft.client.gui.Font font,
                     int x, int y, String label, Double price, Double netProfitDelta) {
        if (price == null) {
            Phos.text(g, font, "§7" + label + " §8—§r", x, y, Phos.DIM);
            return;
        }
        String net = "";
        if (netProfitDelta != null) {
            long delta = Math.round(netProfitDelta);
            net = " §8(" + (delta >= 0 ? "§a+" : "§c") + Phos.coins(Math.abs(delta)) + "§8)§r";
        }
        Phos.text(g, font, "§7" + label + " §f" + Phos.coins(price) + net, x, y, Phos.DIM);
    }

    /** Match the hovered asset to a ledger position using collector identity,
     * not the rendered name alone. Pet menu copies can be display-identical
     * after Hypixel strips petInfo, so a pet match is accepted only when the
     * type/tier candidate is UNIQUE. Ambiguity returns null; item_id=PET is
     * never a fallback. */
    private PositionLedger.Position findPosition(net.minecraft.world.item.ItemStack stack,
                                                  String skyblockId, String itemName,
                                                  Pets.PetName pet) {
        if (pet != null) {
            PositionLedger.Position only = null;
            String type = norm(pet.type());
            String tier = pet.tier().toUpperCase(Locale.ROOT);
            for (PositionLedger.Position p : ledger.recent(200)) {
                if ("sold".equals(p.state) || p.compKey == null) {
                    continue;
                }
                String[] parts = p.compKey.split("\\|");
                if (parts.length < 5 || !"v1".equals(parts[0]) || !"PET".equals(parts[1])
                        || !type.equals(parts[2])
                        || (!tier.isEmpty() && !tier.equals(parts[3]))) {
                    continue;
                }
                if (only != null) {
                    return null; // two display-identical receipts: fail closed
                }
                only = p;
            }
            return only;
        }

        PositionLedger.Position best = null;
        int bestScore = 0;
        String want = norm(itemName);
        int visibleStars = loreStars(effectiveName(stack).getString().replaceAll("§.", ""));

        for (PositionLedger.Position p : ledger.recent(200)) {
            if ("sold".equals(p.state)) {
                continue;
            }
            int score = 0;
            if (skyblockId != null && norm(skyblockId).equals(norm(p.itemId))
                    && starsCompatible(p.compKey, visibleStars)) {
                // Prefer a position captured after comp-key persistence was
                // added. Old name/id-only rows can remain "open" forever when
                // historical chat attribution missed their sale; one such
                // 600k Token row beat the real recent 350k purchase.
                score = p.compKey != null ? 4 : 2;
            } else if (norm(NameMap.pretty(p.itemId, p.compKey)).equals(want)
                    && starsCompatible(p.compKey, visibleStars)) {
                // Create-auction menu copies can strip NBT. A modern bucketed
                // row is still stronger evidence than a legacy name-only row.
                score = p.compKey != null ? 3 : 1;
            }
            if (score > bestScore) {
                best = p;
                bestScore = score;
            }
        }
        return best;
    }

    private static String petBucketFromKey(String compKey) {
        if (compKey == null) {
            return "?";
        }
        String[] parts = compKey.split("\\|");
        return parts.length >= 5 && "PET".equals(parts[1]) ? parts[4] : "?";
    }

    private static boolean starsCompatible(String compKey, int visibleStars) {
        if (compKey == null) {
            return true;
        }
        var m = java.util.regex.Pattern.compile("\\|s(\\d+)(?:\\||$)").matcher(compKey);
        return !m.find() || Integer.parseInt(m.group(1)) == visibleStars;
    }

    /** Display name → SkyBlock-id shape ("Cake Soul" → CAKE_SOUL). Shared
     *  with PurchaseOverlay's name-evidence identity check. */
    static String norm(String s) {
        return s == null ? "" : s.toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    private static String shorten(String s, int n) {
        return s == null ? "?" : s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }
}
