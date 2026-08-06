package dev.midasflip.client;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import dev.midasflip.client.ui.Phos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Purchase confirm overlay — built under the owner's explicit spec
 * §13/§14 amendment (owner decision, 2026-07-05). The hard
 * rules, enforced structurally here:
 *
 * <ul>
 *   <li>OFF by default ({@code purchaseOverlay:false}); zero code paths
 *       execute when off — the AFTER_INIT hook returns before anything
 *       else runs.</li>
 *   <li>ASSISTED mode only. STRICT renders nothing and forwards nothing.</li>
 *   <li>Attaches ONLY to an auction GUI this mod itself opened: the
 *       screen must CONSUME the single-use trigger token of our own
 *       /viewauction send (short window, first "Auction View" only — a
 *       second auction GUI in the window can never inherit it), and the
 *       shown auction must pass a best-effort identity check against the
 *       flip (ExtraAttributes id + stack count) before any buy zone
 *       renders. Unestablished identity FAILS CLOSED: nothing rendered,
 *       clicks untouched. Auctions the player browsed to themselves
 *       never trigger it.</li>
 *   <li>One physical click in the buy zone forwards as EXACTLY ONE slot
 *       interaction on the real purchase slot (single-shot latch). The
 *       overlay never auto-confirms and never times out into a purchase.
 *       Hypixel's own confirm GUI is never overlaid — this only ever
 *       attaches to the auction view.</li>
 *   <li>The cancel strip (left ~30%) closes overlay AND the GUI.</li>
 *   <li>Every forwarded click and cancel is audit-logged with the
 *       physical input that caused it.</li>
 * </ul>
 */
public final class PurchaseOverlay {
    private static final long ATTACH_WINDOW_MS = 8_000;
    private static final double CANCEL_STRIP_FRACTION = 0.30;

    // Identity of the shown auction vs the flip we sent. PENDING until the
    // container contents arrive (a tick or two after init); the buy zone
    // renders and forwards ONLY once VERIFIED — anything else fails closed.
    private static final int IDENTITY_PENDING = 0;
    private static final int IDENTITY_VERIFIED = 1;
    private static final int IDENTITY_MISMATCH = 2;
    // Populated-but-no-match hardens to MISMATCH only after this grace, so
    // Hypixel's progressive GUI fills can't false-negative a real match.
    private static final long IDENTITY_GRACE_MS = 2_000;

    private final MidasflipConfig config;
    private final ActionController actions;

    public PurchaseOverlay(MidasflipConfig config, ActionController actions) {
        this.config = config;
        this.actions = actions;
    }

    public void register() {
        ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
            if (!config.purchaseOverlay || config.safetyMode != MidasflipConfig.SafetyMode.ASSISTED) {
                return; // toggled off / STRICT: no overlay code path runs
            }
            if (!(screen instanceof AbstractContainerScreen<?> cs)) {
                return;
            }
            String title = screen.getTitle().getString();
            // Only the auction VIEW. Hypixel's confirm GUI stays raw.
            // Title-gate BEFORE consuming so an unrelated screen init
            // can't burn the token our own auction view still needs.
            if (!title.contains("Auction View") || title.contains("Confirm")) {
                return;
            }
            // Single-use: consumed on first attach, so a second auction
            // view inside the window (cancel → manual browse) can never
            // inherit this flip's overlay. Side effect accepted: a window
            // resize re-inits the screen and the overlay does NOT return —
            // fail closed, the GUI is simply raw.
            Flip flip = actions.consumeSentFlip(ATTACH_WINDOW_MS);
            if (flip == null) {
                return; // not opened by us (recently), or token already used
            }
            attach(cs, flip);
        });
    }

    private void attach(AbstractContainerScreen<?> cs, Flip flip) {
        // Per-screen state: dismissible, single-shot forward, identity gate.
        final boolean[] active = {true};
        final boolean[] armed = {true};
        final int[] identity = {IDENTITY_PENDING};
        final long attachedAtMs = System.currentTimeMillis();
        AuditLog.record("overlay_attached", "screen_init", flip.uuid + " " + flip.itemId + " identity=pending");

        ScreenEvents.afterExtract(cs).register((screen, gfx, mouseX, mouseY, delta) -> {
            if (!active[0]) {
                return;
            }
            if (identity[0] == IDENTITY_PENDING) {
                identity[0] = checkIdentity(cs, flip, attachedAtMs);
                if (identity[0] == IDENTITY_VERIFIED) {
                    AuditLog.record("overlay_identity_verified", "render",
                            flip.uuid + " item=" + flip.itemId);
                } else if (identity[0] == IDENTITY_MISMATCH) {
                    // Fail visible, exactly like the missing-buy-slot path.
                    active[0] = false;
                    AuditLog.record("overlay_identity_mismatch", "render",
                            flip.uuid + " expected=" + flip.itemId);
                    Chat.local(Minecraft.getInstance(), "§e[MidasFlip]§r auction shown doesn't match the flip"
                            + " (expected §b" + flip.itemId + "§r) · overlay off, GUI is normal.");
                    return;
                } else {
                    return; // pending — contents not in yet; nothing rendered
                }
            }
            draw(gfx, screen, flip, mouseX);
        });

        ScreenMouseEvents.allowMouseClick(cs).register((screen, event) -> {
            if (!active[0] || identity[0] != IDENTITY_VERIFIED) {
                return true; // dismissed or no buy zone drawn — GUI behaves normally
            }
            if (event.button() != 0) {
                return false; // swallow non-left clicks while covered
            }
            int cancelEdge = (int) (screen.width * CANCEL_STRIP_FRACTION);
            Minecraft mc = Minecraft.getInstance();
            if (event.x() <= cancelEdge) {
                active[0] = false;
                AuditLog.record("overlay_cancel", "mouse0", flip.uuid);
                if (mc.player != null) {
                    mc.player.closeContainer();
                }
                return false;
            }
            if (!armed[0]) {
                return false;
            }
            Slot buy = findBuySlot(cs);
            if (buy == null) {
                // Fail visible, never guess a slot.
                active[0] = false;
                AuditLog.record("overlay_no_buy_slot", "mouse0", flip.uuid);
                Chat.local(mc, "§e[MidasFlip]§r couldn't find the buy slot · overlay off, GUI is normal.");
                return false;
            }
            // Click-time identity re-check (defense-in-depth): if Hypixel
            // mutated this container in place since verification (auction
            // ended, state swap), the forward must die, not fire at
            // whatever now sits in the menu. Grace anchor 0 = strict: only
            // a container matching RIGHT NOW passes.
            if (checkIdentity(cs, flip, 0L) != IDENTITY_VERIFIED) {
                active[0] = false;
                AuditLog.record("overlay_identity_lost_at_click", "mouse0", flip.uuid);
                Chat.local(mc, "§e[MidasFlip]§r auction changed under the overlay · off, GUI is normal.");
                return false;
            }
            armed[0] = false;
            active[0] = false;
            // Audit BEFORE the forward, same discipline as command sends.
            AuditLog.record("overlay_buy_forwarded", "mouse0",
                    flip.uuid + " slot=" + buy.index + " item=" + flip.itemId + " identity=verified");
            mc.gameMode.handleContainerInput(cs.getMenu().containerId, buy.index, 0,
                    ContainerInput.PICKUP, mc.player);
            // Hypixel now opens its own confirm GUI — raw, never overlaid;
            // the final click before coins move is on unmodified UI.
            return false;
        });
    }

    /** Best-effort identity of the shown auction vs the flip we sent: some
     *  container slot must carry the flip's SkyBlock id (ExtraAttributes —
     *  GUI buttons and panes have none, so only the real auctioned item
     *  can match) with the flip's stack count. PENDING while the container
     *  is still empty (contents arrive after init); the check runs only
     *  until it resolves, then never again. The auction uuid itself is not
     *  readable client-side, so two auctions of the same item are
     *  indistinguishable here — Hypixel's raw confirm GUI (never overlaid)
     *  remains the final price/item check before any coins move. */
    private static int checkIdentity(AbstractContainerScreen<?> cs, Flip flip, long attachedAtMs) {
        if (flip.itemId == null || flip.itemId.isEmpty()) {
            return IDENTITY_MISMATCH; // nothing to verify against — fail closed
        }
        int containerSlots = cs.getMenu().slots.size() - 36; // exclude player inv
        boolean populated = false;
        for (int i = 0; i < containerSlots; i++) {
            ItemStack stack = cs.getMenu().slots.get(i).getItem();
            if (stack.isEmpty()) {
                continue;
            }
            populated = true;
            // Preferred evidence: the NBT ExtraAttributes id (exact).
            String id = ItemId.of(stack);
            if (id != null && id.equals(flip.itemId)
                    && (flip.count < 1 || stack.getCount() == flip.count)) {
                return IDENTITY_VERIFIED;
            }
            // Hypixel menus STRIP ExtraAttributes (proven live 2026-07-11:
            // every attach hardened to MISMATCH — the overlay never showed
            // again after the identity check shipped). Fall back to
            // display-name evidence — the exact same signal the vanilla
            // tooltip shows the human before they click.
            if (nameMatches(stack, flip)) {
                return IDENTITY_VERIFIED;
            }
        }
        if (populated && System.currentTimeMillis() - attachedAtMs > IDENTITY_GRACE_MS) {
            return IDENTITY_MISMATCH;
        }
        return IDENTITY_PENDING;
    }

    /** Display-name identity: pets match the comp key's pet type against
     *  the "[Lvl N] Type" name; items normalize the (reforge-stripped)
     *  name back to SkyBlock-id shape and compare exactly. */
    private static boolean nameMatches(ItemStack stack, Flip flip) {
        String raw = stack.getHoverName().getString().replaceAll("§.", "").strip();
        if (raw.isEmpty()) {
            return false;
        }
        if ("PET".equals(flip.itemId)) {
            Pets.PetName pn = Pets.parse(stack.getHoverName());
            String wantType = petTypeFromKey(flip.compKey);
            return pn != null && wantType != null
                    && SellOverlay.norm(pn.type()).equals(wantType);
        }
        String base = SellOverlay.stripReforge(SellOverlay.cleanName(raw));
        boolean countOk = flip.count < 1 || stack.getCount() == flip.count;
        if (!countOk) {
            return false;
        }
        // Identity by the server's OWN id->name map first, and only fall
        // back to guessing at the spelling.
        //
        // The old check normalized the display name and compared it to the
        // item id, which silently assumes the two look alike. For most items
        // they do. For a whole armour class they do not, and there is no
        // string rule that could ever bridge it:
        //
        //   POWER_WITHER_CHESTPLATE -> "Necron's Chestplate"
        //   TANK_WITHER_CHESTPLATE  -> "Goldor's Chestplate"
        //   WISE_WITHER_CHESTPLATE  -> "Storm's Chestplate"
        //   SPEED_WITHER_CHESTPLATE -> "Maxor's Chestplate"
        //
        // so the overlay refused every Wither-armour purchase and fell back
        // to a plain GUI (reported live 2026-07-30, after two earlier
        // attempts to patch this with apostrophe handling). NameMap is the
        // same map the tooltip and the board already trust, and it makes the
        // spelling question moot rather than answering it.
        String want = SellOverlay.norm(NameMap.pretty(flip.itemId, flip.compKey));
        if (!want.isEmpty() && want.equals(SellOverlay.norm(base))) {
            return true;
        }
        // Fallback for ids the map has not learned yet: the id itself, plus
        // the two possessive spellings Hypixel uses ("Divan's Leggings" ->
        // DIVAN_LEGGINGS drops the s, "Builder's Wand" -> BUILDERS_WAND
        // keeps it). Every candidate derives from the one display name the
        // player is already looking at, so this can only add the spelling
        // the id actually uses, never a different item.
        for (String candidate : new String[]{base, keepPossessiveS(base), dropPossessiveS(base)}) {
            if (SellOverlay.norm(candidate).equals(flip.itemId)) {
                return true;
            }
        }
        return false;
    }

    /** "Builder's Wand" -> "Builders Wand" (BUILDERS_WAND). */
    private static String keepPossessiveS(String s) {
        return s == null ? "" : s.replace("'", "").replace("’", "");
    }

    /** "Divan's Leggings" -> "Divan Leggings" (DIVAN_LEGGINGS). */
    private static String dropPossessiveS(String s) {
        return s == null ? "" : s.replace("'s ", " ").replace("’s ", " ")
                .replaceAll("['’]s$", "").replace("'", "").replace("’", "");
    }

    /** "v1|PET|SHEEP|..." → "SHEEP"; null when the key isn't a pet key. */
    private static String petTypeFromKey(String compKey) {
        if (compKey == null) {
            return null;
        }
        String[] p = compKey.split("\\|");
        return p.length >= 4 && "PET".equals(p[1]) ? p[2] : null;
    }

    /** The BIN purchase slot, located by its display name — never by a
     *  hardcoded index (Hypixel shuffles layouts). Null = don't forward. */
    private static Slot findBuySlot(AbstractContainerScreen<?> cs) {
        int containerSlots = cs.getMenu().slots.size() - 36; // exclude player inv
        for (int i = 0; i < containerSlots; i++) {
            Slot s = cs.getMenu().slots.get(i);
            String name = s.getItem().getHoverName().getString();
            if (name.contains("Buy Item Right Now")) {
                return s;
            }
        }
        return null;
    }

    private void draw(GuiGraphicsExtractor gfx, Screen screen, Flip flip, int mouseX) {
        Minecraft mc = Minecraft.getInstance();
        int w = screen.width;
        int h = screen.height;
        int cancelEdge = (int) (w * CANCEL_STRIP_FRACTION);
        boolean overCancel = mouseX <= cancelEdge;

        // Buy zone: green-tinted cover; cancel strip: recessive dark.
        gfx.fill(cancelEdge, 0, w, h, overCancel ? 0xB80D1117 : 0xC8103820);
        gfx.fill(0, 0, cancelEdge, h, overCancel ? 0xD8341414 : 0xD80D1117);

        int cx = cancelEdge + (w - cancelEdge) / 2 - 100;
        int y = h / 2 - 46;
        // User-tunable verdict cut (config-completeness 2026-07-13) — the
        // same thresholds the HUD and Phos.tierColor use.
        String tier = (flip.confidence * 100 >= config.verdictConfPct
                && flip.comps >= config.verdictMinComps) ? "§a" : "§6";
        Phos.textShadow(gfx, mc.font, tier + "▎ " + flip.itemId + "§r", cx, y, 0xFFFFFFFF);
        y += 14;
        String estimate = flip.estPess == null
                ? GoldFields.unknown("est")
                : "§7est §f" + coins(Math.round(flip.estPess));
        Phos.textShadow(gfx, mc.font, "§7buy §f" + coins(flip.buyPrice) + "§7 → " + estimate
                + "§7 · net §a+" + coins((long) flip.netProfit) + "§r", cx, y, 0xFFFFFFFF);
        y += 12;
        Phos.textShadow(gfx, mc.font, String.format("§7conf %.2f · %d comps · %.1f/day%s§r",
                flip.confidence, flip.comps, flip.salesPerDay,
                Boolean.TRUE.equals(flip.fallingKnife) ? " · §c⚠ falling§r"
                        : flip.fallingKnife == null ? "" : ""),
                cx, y, 0xFFFFFFFF);
        y += 18;
        Phos.textShadow(gfx, mc.font, "§a▶ click anywhere here to buy§r", cx, y, 0xFFFFFFFF);
        y += 12;
        Phos.textShadow(gfx, mc.font, "§8hypixel's own confirm comes next · that click is yours too§r", cx, y, 0xFFFFFFFF);

        Phos.textShadow(gfx, mc.font, "§c✕ cancel§r", cancelEdge / 2 - 20, h / 2 - 6, 0xFFFFFFFF);
        Phos.textShadow(gfx, mc.font, "§8closes auction§r", cancelEdge / 2 - 32, h / 2 + 8, 0xFFFFFFFF);
    }

    /** Canonical coin format (config-completeness 2026-07-13): routes
     *  through {@link Phos#coins} so B renders at the canonical 2dp and the
     *  raw-numbers toggle is honored — the old local copy diverged. */
    private static String coins(long v) {
        return Phos.coins(v);
    }
}
