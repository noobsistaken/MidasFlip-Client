package dev.midasflip.client;

import dev.midasflip.client.ui.Phos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.sounds.SoundEvents;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * The compact always-on overlay (spec §3): draw-only — a rendered list of
 * the current best flips. Rendering never interacts with game controls;
 * highlights are drawn, never clickable-hitbox-resized (non-goal §14).
 */
public final class FlipHud {
    /** Fixed block width for anchor mirroring (config-completeness
     *  2026-07-13): a stable estimate of the widest row, so right-anchored
     *  positioning never has to measure text per frame. */
    private static final int HUD_BLOCK_WIDTH = 260;

    private final MidasflipConfig config;
    private final FlipFeed feed;
    private final SessionTracker session;

    public FlipHud(MidasflipConfig config, FlipFeed feed, SessionTracker session) {
        this.config = config;
        this.feed = feed;
        this.session = session;
    }

    public void render(GuiGraphicsExtractor gfx) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) {
            return;
        }
        // The margin alert has its own toggle — it works even when the
        // board overlay itself is hidden.
        marginAlert(gfx, mc);
        if (!config.overlayEnabled) {
            return;
        }

        // HUD origin from the chosen anchor (config-completeness
        // 2026-07-13). The anchor positions the whole BLOCK; text still
        // draws left-to-right, so right/bottom anchors just mirror the
        // origin off a fixed ~260px column (the widest row we render) and
        // an estimated block height — no per-frame text measurement, so the
        // render budget stays flat (fabric-mod rule: format on update, not
        // on render). guiScaledWidth/Height already reflect the user's GUI
        // scale, so this tracks any scale change automatically.
        int line = mc.font.lineHeight + 2;
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();
        int blockW = HUD_BLOCK_WIDTH;
        int blockH = estimatedBlockHeight(line);
        boolean right = config.hudAnchor == MidasflipConfig.HudAnchor.TOP_RIGHT
                || config.hudAnchor == MidasflipConfig.HudAnchor.BOTTOM_RIGHT;
        boolean bottom = config.hudAnchor == MidasflipConfig.HudAnchor.BOTTOM_LEFT
                || config.hudAnchor == MidasflipConfig.HudAnchor.BOTTOM_RIGHT;
        int x = right ? Math.max(0, screenW - blockW - config.hudOffsetX) : config.hudOffsetX;
        int y = bottom ? Math.max(0, screenH - blockH - config.hudOffsetY) : config.hudOffsetY;

        // Stale-while-reconnect: a dropped socket dims the header, it never
        // blanks the board — the buffered flips are still actionable.
        List<Flip> top = feed.top(config.overlayMaxRows);
        String header;
        if (feed.isConnected()) {
            header = "§6[MidasFlip]§r " + top.size() + " live";
        } else {
            long retry = feed.secondsUntilRetry();
            header = "§6[MidasFlip]§r §creconnecting" + (retry > 0 ? " in " + retry + "s" : "…") + "§r";
        }
        Phos.textShadow(gfx, mc.font, header, x, y, 0xFFFFFFFF);
        y += line;

        for (Flip f : top) {
            boolean fresh = f.freshness() == Flip.Freshness.FRESH;
            // Verdict tiers (owner's design): GREEN = strong comps AND
            // confidence; AMBER = passed the server gates but thinner.
            // RED never reaches the stream by construction. Thresholds are
            // user-tunable (config-completeness 2026-07-13); §a/§6 mirror
            // Phos.tierColor's GREEN/YELLOW so the HUD and the peek agree.
            // A reconciliation-confirmed-taken flip dims to grey and its
            // status column reads "✗ taken" (owner 2026-07-15): you SEE the
            // purchase land instead of the row silently disappearing.
            String tier = f.gone ? "§8"
                    : (f.confidence * 100 >= config.verdictConfPct
                        && f.comps >= config.verdictMinComps) ? "§a" : "§6";
            // Candy count rides in the name (owner 2026-07-11): appended
            // after shortening so "4/10 candies" never truncates away.
            String candy = f.petCandy > 0 ? " §d" + f.petCandy + "/10 candies§r" : "";
            String status = f.gone ? "§c✗ taken" : fresh ? "new" : f.ageSeconds() + "s";
            String profitCol = f.gone ? "§8" : "§a+";
            String row = String.format("%s%s%s%s%s%s§r %s → %s%s§r (%.0f%%) §7c%.2f · %s§r",
                    Rule.starred(config.rules, f) ? "§e★§r " : "",
                    f.fallingKnife && !f.gone ? "§c⚠§r " : "",
                    !f.gone && "high".equals(f.manip) ? "§c⚑§r " : !f.gone && "med".equals(f.manip) ? "§6⚐§r " : "",
                    tier,
                    shorten(NameMap.pretty(f.itemId, f.compKey), f.petCandy > 0 ? 14 : 22),
                    candy,
                    coins(f.buyPrice),
                    profitCol,
                    coins((long) f.netProfit),
                    f.netMarginPct * 100,
                    f.confidence,
                    status);
            Phos.textShadow(gfx, mc.font, row, x, y, 0xFFFFFFFF);
            y += line;
        }
        if (!top.isEmpty()) {
            Phos.textShadow(gfx, mc.font, "§8[O] open best  [K] toggle§r", x, y, 0xFFFFFFFF);
            y += line;
        } else if (feed.isConnected()) {
            Phos.textShadow(gfx, mc.font, "§8waiting for flips…§r", x, y, 0xFFFFFFFF);
            y += line;
        }
        if (session.boughtCount() > 0 || session.ledger().lifetime().sold() > 0) {
            // "est" is honest: projected at open time; realized comes from
            // the server's sale history once the resale lands. The all-time
            // figure is net of fees where reconciled ("≈" = some gross rows).
            PositionLedger.Lifetime life = session.ledger().lifetime();
            String lifetime = life.sold() > 0
                    ? String.format(" · all-time %s%s%s (%d)",
                        life.approx() ? "≈" : "",
                        life.profit() >= 0 ? "§a+" : "§c",
                        coins(Math.abs(life.profit())) + "§7", life.sold())
                    : "";
            Phos.textShadow(gfx, mc.font, String.format("§7session: %d bought · %s spent · est §a+%s§7%s§r",
                    session.boughtCount(), coins(session.spent()),
                    coins((long) session.estimatedProfit()), lifetime), x, y, 0xFFFFFFFF);
            y += line;
        }

        // Cut-loss warning (alert-only, spec §13): open positions are
        // re-priced ~every 60s (ledger rate-limits the sweep; api.get is
        // async + TTL-cached, so this render-loop call does no network
        // work itself). If even the CURRENT pessimistic estimate is below
        // what was paid, say so — with words only, never an action.
        session.ledger().tickRevaluation();
        int warned = 0;
        for (PositionLedger.Position p : session.ledger().recent(50)) {
            if (warned >= config.hudUnderwaterMaxRows) {
                break;
            }
            if (!"sold".equals(p.state) && p.underwater()) {
                Phos.textShadow(gfx, mc.font, String.format(
                        "§c▼ %s underwater · paid %s · now ~%s§r",
                        shorten(NameMap.pretty(p.itemId), 18),
                        coins(p.buyPrice), coins(Math.round(p.curPessTotal()))),
                        x, y, 0xFFFFFFFF);
                y += line;
                warned++;
            }
        }

        // Undercut watch (landing-card promise, display-only): your open
        // listings vs the bucket floor. Same cadence discipline as the
        // re-valuation tick; chat alerts are one-shot inside the watch.
        ListingsWatch.tick(mc);
        if (config.undercutAlerts) {
            int shown = 0;
            for (ListingsWatch.Mine m : ListingsWatch.undercuts()) {
                if (shown >= config.hudUndercutMaxRows) {
                    break;
                }
                String shift = ListingsWatch.profitShift(m);
                Phos.textShadow(gfx, mc.font, String.format(
                        "§6▲ %s undercut · yours %s · market %s%s%s§r",
                        shorten(NameMap.pretty(m.itemId(), m.compKey()), 18),
                        coins(Math.round(m.unitPrice())),
                        coins(Math.round(m.floorUnit() != null ? m.floorUnit() : 0)),
                        m.suggestUnit() != null ? " · reprice ~" + coins(Math.round(m.suggestUnit())) : "",
                        shift.isEmpty() ? "" : " · " + shift + "§6"),
                        x, y, 0xFFFFFFFF);
                y += line;
                shown++;
            }
        }
    }

    /** Rough total height of the HUD block for bottom-anchor mirroring
     *  (config-completeness 2026-07-13): header + rows + hint + session +
     *  the worst-case warning lines, all in one line-height unit. An
     *  estimate on purpose — good enough to keep a bottom-anchored block on
     *  screen without measuring rendered content every frame. */
    private int estimatedBlockHeight(int line) {
        int rows = 1 // header
                + config.overlayMaxRows
                + 1 // hint / waiting line
                + 1 // session line
                + config.hudUnderwaterMaxRows
                + (config.undercutAlerts ? config.hudUndercutMaxRows : 0);
        return rows * line;
    }

    // ---- full-screen margin alert (owner 2026-07-12) ----
    // DISPLAY-ONLY by construction (spec §13): it renders ONLY when NO GUI
    // screen is open — a deliberate safety choice so it can never obscure
    // or frame a purchase GUI. It sends nothing, clicks nothing, opens
    // nothing, and hooks no input: expiry is time-based only; the only
    // path to an action is the human pressing the open key, which goes
    // through ActionController's normal checks like any other press.

    /** Cap on remembered flip uuids — hard-coded FIFO bound, never a
     *  preference (bounding memory is a correctness concern, not a knob). */
    private static final int MAX_ALERTED = 256;

    /** Flip uuids already alerted, bounded FIFO — one banner per flip. */
    private final LinkedHashSet<String> alerted = new LinkedHashSet<>();
    private Flip banner;
    private long bannerUntil;

    private void marginAlert(GuiGraphicsExtractor gfx, Minecraft mc) {
        if (!config.marginAlert) {
            return;
        }
        if (mc.screen != null) {
            // Never rendered over ANY GUI — and never ARMED under one
            // either: arming while a GUI is open burned the 5s window (and
            // chirped) invisibly (review 2026-07-13). The flip stays
            // eligible and arms the moment the GUI closes.
            return;
        }
        long now = System.currentTimeMillis();
        if (now >= bannerUntil) {
            banner = null;
            // feed.top() already applies config.passes() — only flips the
            // user's own filters vouch for can arm the banner.
            for (Flip f : feed.top(config.overlayMaxRows)) {
                if (f.netMarginPct * 100 >= config.marginAlertPct && !alerted.contains(f.uuid)) {
                    alerted.add(f.uuid);
                    while (alerted.size() > MAX_ALERTED) {
                        var it = alerted.iterator();
                        it.next();
                        it.remove();
                    }
                    banner = f;
                    bannerUntil = now + config.marginAlertBannerMs;
                    // One subtle UI note on arm — never looped. Toggleable
                    // with a tunable volume (config-completeness 2026-07-13);
                    // pitch stays fixed. Volume 0 is also effectively muted.
                    if (config.marginAlertSound && config.marginAlertVolume > 0f) {
                        mc.player.playSound(SoundEvents.UI_BUTTON_CLICK.value(),
                                config.marginAlertVolume, 1.4f);
                    }
                    break;
                }
            }
        }
        if (banner == null) {
            return;
        }
        drawBanner(gfx, mc, banner);
    }

    private void drawBanner(GuiGraphicsExtractor gfx, Minecraft mc, Flip f) {
        String name = NameMap.pretty(f.itemId, f.compKey);
        String stat = "+" + Math.round(f.netMarginPct * 100) + "% margin · +" + coins((long) f.netProfit);
        // Name the ACTUAL bound key, not a hardcoded O.
        String keyName = MidasflipClient.openBest == null ? "?"
                : MidasflipClient.openBest.getTranslatedKeyMessage().getString();
        String hint = "press [" + keyName + "] — open best flip";
        var font = mc.font;
        int boxW = Math.max(Math.max(Phos.w(font, name), Phos.w(font, stat)), Phos.w(font, hint)) + 24;
        int boxH = 50;
        // 2x pose scale: large and unmissable; coordinates below live in
        // the scaled space (half the gui dimensions).
        var pose = gfx.pose();
        pose.pushMatrix();
        pose.scale(2f, 2f);
        int cx = gfx.guiWidth() / 4;   // horizontal center of scaled space
        int top = gfx.guiHeight() / 8; // upper third — clear of crosshair
        int bx = cx - boxW / 2;
        gfx.fill(bx, top, bx + boxW, top + boxH, 0xE80D1117);
        Phos.border(gfx, bx, top, boxW, boxH, Phos.ACCENT);
        Phos.textShadow(gfx, font, name, cx - Phos.w(font, name) / 2, top + 8, Phos.CREAM);
        Phos.textShadow(gfx, font, "§a" + stat + "§r", cx - Phos.w(font, stat) / 2, top + 21, Phos.GREEN);
        Phos.textShadow(gfx, font, "§7" + hint + "§r", cx - Phos.w(font, hint) / 2, top + 35, Phos.DIM);
        pose.popMatrix();
    }

    private static String shorten(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    /** Canonical coin format (config-completeness 2026-07-13): every HUD
     *  figure now routes through {@link Phos#coins} — the old local copy
     *  rendered B at 1dp vs the canonical 2dp, a real inconsistency. This
     *  also honors the raw-numbers toggle. */
    private static String coins(long v) {
        return Phos.coins(v);
    }
}
