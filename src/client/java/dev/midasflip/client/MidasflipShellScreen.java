package dev.midasflip.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import dev.midasflip.client.ui.Phos;
import dev.midasflip.client.ui.PhosScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The MidasFlip shell — the owner's mockup as the real in-game UI: left
 * sidebar navigation, warm-phosphor panels, custom-drawn controls. All
 * behavior is ported from the earlier vanilla-widget screens unchanged;
 * this is the same product wearing the designed skin. Safety surface is
 * untouched: row clicks and controls route through the same single
 * ActionController call site.
 */
public final class MidasflipShellScreen extends PhosScreen {
    public enum Tab { DASHBOARD, FLIPS, AUCTIONS, TRADES, VALUE, FILTERS, ALERTS, DISPLAY, THEME, SAFETY, ABOUT }

    private static final int SIDEBAR_W = 92;
    private static final long[] PROFIT_STEPS =
            {0, 100_000, 250_000, 500_000, 1_000_000, 2_000_000, 5_000_000, 10_000_000};
    private static final long[] COST_STEPS =
            {0, 10_000_000, 25_000_000, 50_000_000, 75_000_000, 100_000_000, 250_000_000, 500_000_000, 1_000_000_000};
    private static final int[] HOLD_STEPS = {0, 15, 30, 45, 60, 120, 240};

    private final MidasflipConfig config;
    private final FlipFeed feed;
    private final ActionController actions;
    private final SessionTracker session;
    private final MidasflipApi api;
    private final Tab tab;

    // Flips pane state
    private List<Flip> flipRows = List.of();
    // Value pane state
    private String kindFilter = "all";
    private String expandedId;
    // Auctions pane: which bid-flip row (by auction uuid) is expanded.
    private String expandedAuctionId;
    private final List<JsonObject> valueRows = new ArrayList<>();
    /** When the current value-kind started waiting for data — drives the
     *  honest "pro feature" hint on the PRO-gated panes (see proGateOrLoading). */
    private long kindSince = System.currentTimeMillis();
    // Filters pane inputs
    private static int filtersPage; // 0 = thresholds, 1 = what's shown (sticky per session)
    private static int displayPage;  // 0 = surfaces, 1 = density & notifications (sticky per session)
    private EditBox presetName;
    private final List<EditBox> ruleValues = new ArrayList<>();
    private String flash = "";
    private long flashUntil;

    public MidasflipShellScreen(MidasflipConfig config, FlipFeed feed, ActionController actions,
                              SessionTracker session, MidasflipApi api, Tab tab) {
        super(Component.literal("MidasFlip"));
        this.config = config;
        this.feed = feed;
        this.actions = actions;
        this.session = session;
        this.api = api;
        this.tab = tab;
    }

    private void open(Tab t) {
        Minecraft mc = Minecraft.getInstance();
        // Faint UI click on tab change (owner 2026-07-27). Quiet and
        // high-pitched so it reads as interface feedback, not a game
        // event; toggleable in Display. Client-side sound only — this
        // plays nothing to the server and is not a game action.
        if (config.tabSound && t != tab && mc.player != null) {
            mc.player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.22f, 1.75f);
        }
        mc.setScreen(new MidasflipShellScreen(config, feed, actions, session, api, t));
    }

    private void refresh() {
        config.save();
        open(tab);
    }

    private void flash(String msg) {
        flash = msg;
        flashUntil = System.currentTimeMillis() + 4000;
    }

    @Override
    protected void init() {
        ruleValues.clear();
        if (tab == Tab.FILTERS) {
            int x = SIDEBAR_W + 16;
            // Created at a placeholder position; the render pass in
            // filters() sets the real X/Y each frame from the live flow.
            presetName = new EditBox(font, x + 150, 0, 90, 14, Component.literal("name"));
            presetName.setMaxLength(24);
            presetName.setBordered(false);
            addRenderableWidget(presetName);
            for (int i = 0; i < Math.min(config.rules.size(), 6); i++) {
                Rule r = config.rules.get(i);
                EditBox v = new EditBox(font, x + 168, 0, 62, 12, Component.literal("value"));
                v.setMaxLength(48);
                v.setBordered(false);
                v.setValue(r.value == null ? "" : r.value);
                v.setResponder(s -> r.value = s);
                addRenderableWidget(v);
                ruleValues.add(v);
            }
        }
    }

    @Override
    public void onClose() {
        config.normalize();
        config.save();
        super.onClose();
    }

    @Override
    protected void drawPhos(GuiGraphicsExtractor g, int mx, int my, float delta) {
        drawSidebar(g, mx, my);
        int x = SIDEBAR_W + 16;
        int w = width - x - 16;

        // Header: tab title + live status (mockup: "eu-1 · 142ms · snapshot 34s").
        Phos.text(g, font, title(tab), x, 14, Phos.CREAM);
        String status;
        if (api.likelyDown()) {
            status = "§c● offline · start tunnel§r";
        } else if (feed.isConnected()) {
            status = "§a● live§r" + (api.lastLatencyMs() >= 0 ? "  §8" + api.lastLatencyMs() + "ms§r" : "");
        } else {
            status = "§e● connecting…§r";
        }
        Phos.text(g, font, status, x + w - Phos.w(font, status) - 24, 14, Phos.DIM);
        Phos.hline(g, x, 26, w);

        switch (tab) {
            case DASHBOARD -> dashboard(g, x, w);
            case FLIPS -> flips(g, x, w, mx, my);
            case AUCTIONS -> auctions(g, x, w, mx, my);
            case TRADES -> trades(g, x, w);
            case VALUE -> value(g, x, w, mx, my);
            case FILTERS -> filters(g, x, w, mx, my);
            case ALERTS -> alerts(g, x, w);
            case DISPLAY -> display(g, x, w, mx, my);
            case THEME -> theme(g, x, w, mx, my);
            case SAFETY -> safety(g, x, w, mx, my);
            case ABOUT -> about(g, x, w);
        }

        if (System.currentTimeMillis() < flashUntil) {
            Phos.text(g, font, flash, x, height - 16, Phos.YELLOW);
        }
    }

    private void drawSidebar(GuiGraphicsExtractor g, int mx, int my) {
        g.fill(0, 0, SIDEBAR_W, height, 0xF80A0E14);
        g.fill(SIDEBAR_W - 1, 0, SIDEBAR_W, height, Phos.BORDER);
        // Brand mark: FLIP rides the themeable accent so a Theme-tab swap
        // recolors it live. No § codes — they override the color param.
        Phos.text(g, font, "MIDAS", 10, 12, Phos.CREAM);
        Phos.text(g, font, "FLIP", 10 + font.width("MIDAS"), 12, Phos.ACCENT);
        Phos.text(g, font, "0.1.0 · fabric", 10, 24, Phos.FAINT);

        int y = 44;
        for (Tab t : Tab.values()) {
            // Auctions tab is owner-toggleable: hidden from the sidebar
            // entirely until enabled in Display (auctionTab, default off).
            if (t == Tab.AUCTIONS && !config.auctionTab) {
                continue;
            }
            boolean sel = t == tab;
            boolean hov = hovered(0, y - 2, SIDEBAR_W - 1, 14, mx, my);
            if (sel) {
                g.fill(0, y - 2, SIDEBAR_W - 1, y + 12, Phos.SEL_BG);
                g.fill(0, y - 2, 2, y + 12, Phos.ACCENT);
            } else if (hov) {
                g.fill(0, y - 2, SIDEBAR_W - 1, y + 12, 0xF8101620);
            }
            Phos.text(g, font, title(t), 10, y, sel ? Phos.CREAM : Phos.DIM);
            final Tab target = t;
            zone(0, y - 2, SIDEBAR_W - 1, 14, () -> open(target));
            y += 16;
        }

        String tally = session.boughtCount() > 0
                ? "§7" + session.boughtCount() + " bought§r"
                : "§8no buys yet§r";
        Phos.text(g, font, tally, 10, height - 16, Phos.DIM);
    }

    private static String title(Tab t) {
        return switch (t) {
            case DASHBOARD -> "Dashboard";
            case FLIPS -> "Flips";
            case AUCTIONS -> "Auctions";
            case TRADES -> "Trades";
            case VALUE -> "Value";
            case FILTERS -> "Filters & Score";
            case ALERTS -> "Alerts";
            case DISPLAY -> "Display";
            case THEME -> "Theme";
            case SAFETY -> "Safety";
            case ABOUT -> "About";
        };
    }

    // ---------- panes ----------

    private void dashboard(GuiGraphicsExtractor g, int x, int w) {
        int y = 38;
        int cw = (w - 18) / 4;
        card(g, x, y, cw, 44, "SESSION", session.boughtCount() + " bought",
                Phos.coins(session.spent()) + " spent · est +" + Phos.coins(session.estimatedProfit()));
        long[] today = realized(24);
        card(g, x + cw + 6, y, cw, 44, "TODAY (SOLD)",
                (today[1] >= 0 ? "+" : "") + Phos.coins(today[1]), today[0] + " trades · gross");
        long[] week = realized(24 * 7);
        card(g, x + 2 * (cw + 6), y, cw, 44, "7 DAYS",
                (week[1] >= 0 ? "+" : "") + Phos.coins(week[1]), week[0] + " trades · gross");
        // Lifetime, net of AH fees where the server reconciled the sale;
        // "≈" marks totals still containing chat-only (gross) rows.
        PositionLedger.Lifetime life = session.ledger().lifetime();
        card(g, x + 3 * (cw + 6), y, cw, 44, "ALL TIME (MOD)",
                (life.approx() ? "≈" : "") + (life.profit() >= 0 ? "+" : "")
                        + Phos.coins(life.profit()),
                life.sold() + " flips · " + (life.approx() ? "mixed gross/net" : "net of fees"));
        y += 52;

        Phos.label(g, font, "backtest · default preset · 3d window", x, y);
        y += 12;
        JsonElement bt = api.get("/backtest/default", 10 * 60_000);
        if (bt != null && bt.isJsonObject()) {
            JsonObject b = bt.getAsJsonObject();
            Phos.text(g, font, "§7" + b.get("signals").getAsInt() + " signals · fill-adj §a+"
                    + Phos.coins(b.get("est_profit_fill_adjusted").getAsDouble())
                    + "§r §8· " + b.get("signals_sniped_by_market").getAsInt()
                    + " market-validated§r", x, y, Phos.DIM);
        } else {
            loadingOrDown(g, x, y, "backtest");
        }
        y += 18;

        // Your open auctions, live vs the market (server-judged). Same data
        // the Trades pane shows, so it carries the same label: the Dashboard
        // is free for the overall P&L (owner 2026-08-06), NOT for the
        // sell-side block underneath it. Without this a user reads the whole
        // pane as free and loses this half in September.
        ListingsWatch.tick(Minecraft.getInstance());
        List<ListingsWatch.Mine> mine = ListingsWatch.mine();
        int underc = ListingsWatch.undercuts().size();
        // The ONLY place the founder offer appears (owner 2026-08-07). The
        // user is already looking at their own P&L here, which is the one
        // context where a price is information rather than an interruption.
        Phos.text(g, font, GoldFields.badgeWithOffer(), x, y - 1, Phos.ACCENT);
        y += 12;
        Phos.label(g, font, "your open auctions" + (mine.isEmpty() ? "" : " · " + mine.size()
                + (underc > 0 ? " · §6" + underc + " undercut§8" : "")), x, y);
        y += 12;
        if (mine.isEmpty()) {
            Phos.text(g, font, "§8no open listings (or hop into the Auction House once to sync)§r", x, y, Phos.FAINT);
            y += 12;
        } else {
            int shown = 0;
            int cap = config.dashboardListingsMax;
            for (ListingsWatch.Mine m : mine) {
                if (shown++ >= cap) {
                    break;
                }
                String status = m.undercut() ? "§6▲ undercut§r" : m.stale() ? "§e◆ stale§r" : "§a● lowest§r";
                Phos.text(g, font, "§7" + shorten(NameMap.pretty(m.itemId(), m.compKey()), 22)
                        + " §f" + Phos.coins(m.unitPrice()) + "§r  " + status, x, y, Phos.DIM);
                y += 12;
            }
            if (mine.size() > cap) {
                Phos.text(g, font, "§8+" + (mine.size() - cap) + " more · see Trades§r", x, y, Phos.FAINT);
                y += 12;
            }
        }
        y += 6;

        Phos.label(g, font, "live board preview", x, y);
        y += 12;
        List<Flip> top = feed.top(3);
        for (Flip f : top) {
            Phos.text(g, font, "§7" + shorten(NameMap.pretty(f.itemId, f.compKey), 24) + "§r  " + Phos.coins(f.buyPrice)
                    + " §8→§r §a+" + Phos.coins(f.netProfit) + "§r", x, y, Phos.DIM);
            y += 12;
        }
        if (top.isEmpty()) {
            Phos.text(g, font, "§8waiting for flips…§r", x, y, Phos.FAINT);
        }
    }

    private long[] realized(int hours) {
        long n = 0;
        long sum = 0;
        long cutoff = System.currentTimeMillis() - hours * 3600_000L;
        for (PositionLedger.Position p : session.ledger().recent(500)) {
            if ("sold".equals(p.state) && p.soldAtMs >= cutoff) {
                n++;
                sum += p.soldPrice - p.buyPrice;
            }
        }
        return new long[]{n, sum};
    }

    private void card(GuiGraphicsExtractor g, int x, int y, int w, int h, String label, String big, String sub) {
        Phos.panel(g, x, y, w, h);
        Phos.label(g, font, label, x + 8, y + 6);
        Phos.text(g, font, big, x + 8, y + 17, Phos.CREAM);
        Phos.text(g, font, "§8" + sub + "§r", x + 8, y + 30, Phos.FAINT);
    }

    private void flips(GuiGraphicsExtractor g, int x, int w, int mx, int my) {
        int y = 36;
        String chips = String.format("profit ≥ %s · conf ≥ %d%% · liq ≥ %s%s",
                Phos.coins(config.minProfit), Math.round(config.minConfidence * 100),
                config.minLiquidity.name().toLowerCase(Locale.ROOT),
                (config.showFallingKnife ? "" : " · knives hidden")
                        + (config.showPatient ? "" : " · patient hidden"));
        Phos.text(g, font, "§8" + chips + "§r", x, y, Phos.FAINT);
        String evChip = eventChip();
        if (!evChip.isEmpty()) {
            Phos.text(g, font, evChip, x + w - 160, y, Phos.DIM);
        }
        y += 14;

        int[] cols = {0, 145, 228, 290, 333, 369, 405, 442};
        // The ▾ marks the active sort key so "rank by" is legible on the board.
        String profitHead = config.sortMode == MidasflipConfig.SortMode.PROFIT ? "PROFIT ▾"
                : config.sortMode == MidasflipConfig.SortMode.MARGIN ? "PROFIT%▾" : "PROFIT";
        String[] heads = {"ITEM", "BUY → SELL", profitHead, "CONF", "LIQ", "HOLD", "AGE",
                config.sortMode == MidasflipConfig.SortMode.SCORE ? "VERDICT▾" : "VERDICT"};
        for (int i = 0; i < heads.length; i++) {
            Phos.label(g, font, heads[i], x + cols[i], y);
        }
        y += 12;
        Phos.hline(g, x, y - 2, w);

        flipRows = feed.top(config.shellTableRows);
        for (Flip f : flipRows) {
            boolean hov = hovered(x, y, w, 13, mx, my);
            if (hov) {
                g.fill(x, y - 1, x + w, y + 12, Phos.PANEL_HI);
            }
            int tier = Phos.tierColor(f.confidence, f.comps, config.verdictConfPct, config.verdictMinComps);
            // Manipulation gets its own loud mark: ⚑ high (red), ⚐ med
            // (amber) — a first-class glance signal, not buried in conf.
            String manipMark = "high".equals(f.manip) ? "§c⚑§r" : "med".equals(f.manip) ? "§6⚐§r" : "";
            String marks = (Rule.starred(config.rules, f) ? "§e★§r" : "")
                    + (Boolean.TRUE.equals(f.fallingKnife) ? "§c⚠§r" : "") + manipMark;
            // Candy transparency (owner 2026-07-11): the count rides in the
            // name — "Sheep 4/10 candies" — appended AFTER shortening so it
            // never truncates away.
            String candy = f.petCandy > 0 ? " §d" + f.petCandy + "/10 candies§r" : "";
            Phos.text(g, font, marks + shorten(NameMap.pretty(f.itemId, f.compKey),
                    f.petCandy > 0 ? 12 : 22) + candy, x + cols[0], y, tier);
            Phos.text(g, font, Phos.coins(f.buyPrice) + " §8→§r "
                    + (f.estPess == null ? GoldFields.locked("SELL") : Phos.coins(f.estPess)),
                    x + cols[1], y, Phos.DIM);
            Phos.text(g, font, "+" + Phos.coins(f.netProfit) + " §8(" + Math.round(f.netMarginPct * 100) + "%)§r",
                    x + cols[2], y, Phos.GREEN);
            Phos.text(g, font, String.format("%.2f§8·%d§r", f.confidence, f.comps), x + cols[3], y, Phos.DIM);
            Phos.text(g, font, String.format("%.0f/d", f.salesPerDay), x + cols[4], y, Phos.DIM);
            Phos.text(g, font, holdStr(f), x + cols[5], y, Phos.DIM);
            Phos.text(g, font, f.ageSeconds() + "s", x + cols[6], y, Phos.FAINT);
            Phos.chip(g, font, tier == Phos.GREEN ? "BUY" : "WATCH", x + cols[7], y, tier);
            final Flip target = f;
            zone(x, y - 1, w, 13, () -> {
                if (actions.onOpenKeyPressed(Minecraft.getInstance(), target)) {
                    feed.markOpened(target);
                    session.onOpened(target);
                    onClose();
                }
            });
            y += 13;
        }
        if (flipRows.isEmpty()) {
            Phos.text(g, font, "§8waiting for flips that pass your filters…§r", x, y, Phos.FAINT);
            y += 13;
        }
        Phos.text(g, font, "§8click a row to open · hold TAB over a row for comps · [O] cycles§r",
                x, y + 6, Phos.FAINT);

        String toast = session.activeToast();
        if (!toast.isEmpty()) {
            Phos.text(g, font, "§a" + toast + "§r", x, y + 20, Phos.GREEN);
        }

        int hoverIdx = (my - (36 + 14 + 12)) / 13;
        if (InputConstants.isKeyDown(Minecraft.getInstance().getWindow(), GLFW.GLFW_KEY_TAB)
                && mx >= x && hoverIdx >= 0 && hoverIdx < flipRows.size()) {
            compsPeek(g, flipRows.get(hoverIdx), x, y + 34, w);
        }
    }

    private void compsPeek(GuiGraphicsExtractor g, Flip f, int x, int y, int w) {
        // Gold surface that RENDERS during early access, so the badge is the
        // only thing telling the user it is Gold at all — without it they
        // learn that in September, which is the takeaway the badge exists to
        // prevent.
        Phos.text(g, font, GoldFields.badge(), x, y, Phos.ACCENT);
        y += 12;
        Phos.panel(g, x, y - 4, Math.min(w, 320), 96);
        x += 8;
        // Comp keys contain '|' — illegal in a URI; unencoded it throws in
        // URI.create so the peek never loaded AND each attempt counted a
        // failure toward the false-offline banner (found 2026-07-08).
        if (f.compKey == null || f.compKey.isEmpty()) {
            Phos.text(g, font, "§8no comp key on this flip§r", x, y + 2, Phos.FAINT);
            return;
        }
        JsonElement el = api.get("/value/" + java.net.URLEncoder.encode(
                f.compKey, java.nio.charset.StandardCharsets.UTF_8), 60_000);
        if (el == null || !el.isJsonObject()) {
            Phos.text(g, font, "§8comps: loading…§r", x, y + 2, Phos.FAINT);
            return;
        }
        JsonObject d = el.getAsJsonObject();
        JsonArray sales = GoldFields.optArr(d, "recent_sales");
        JsonObject est = d.getAsJsonObject("estimate");
        if (sales == null) {
            Phos.text(g, font, GoldFields.locked("comps"), x, y + 2, Phos.FAINT);
        } else {
            Phos.label(g, font, "comps · " + Math.min(sales.size(), 25) + " recent", x, y + 2);
        }
        int yy = y + 14;
        int shown = 0;
        long now = System.currentTimeMillis();
        if (sales != null) {
            for (JsonElement se : sales) {
                JsonObject s = se.getAsJsonObject();
                String iso = s.get("ended_at").getAsString();
                long ageMin = Math.max(0, (now - Instant.parse(iso.contains("+") || iso.endsWith("Z") ? iso : iso + "Z").toEpochMilli()) / 60_000);
                Phos.text(g, font, Phos.coins(s.get("unit_price").getAsLong())
                        + " §8· " + (ageMin >= 60 ? (ageMin / 60) + "h" + ageMin % 60 + "m" : ageMin + "m") + " · exact§r", x, yy, Phos.DIM);
                yy += 10;
                if (++shown >= config.compsPeekSales) {
                    break;
                }
            }
        }
        // Show-your-work: source (direct comps vs a derived sibling) and how
        // many sales the MAD gate rejected as outliers — the "which-filtered"
        // half of the valuation drill-down.
        String src = est.has("src") ? est.get("src").getAsString() : "comps";
        String srcShort = src.startsWith("derived") ? "derived" : src;
        int rej = est.has("outliers") ? est.get("outliers").getAsInt() : 0;
        Double pess = GoldFields.optNum(est, "pess");
        String estExit = pess == null
                ? GoldFields.locked("EST EXIT")
                : "§aEST EXIT " + Phos.coins(pess) + "§r";
        Phos.text(g, font, estExit + " §8· conf " + String.format("%.2f", est.get("conf").getAsDouble())
                + " · " + est.get("comps").getAsInt() + " comps"
                + (rej > 0 ? " · " + rej + " rejected" : "")
                + " · " + srcShort + "§r", x, yy + 2, Phos.GREEN);
        if (f.exitFast != null) {
            // Signed net deltas ride each exit line (P2-final: a PATIENT
            // flip's fast line is an explicit loss, never hidden). Same
            // sign pattern as SellOverlay — abs() after the color, so a
            // negative never renders as "+-".
            boolean missingNumbers = f.exitFastNet == null || f.exitFastHoldS == null
                    || (f.exitPatient != null
                    && (f.exitPatientNet == null || f.exitPatientHoldS == null));
            if (missingNumbers) {
                // The patient VERDICT is free; only the exit NUMBERS are
                // Gold. Rendering the lock alone discarded a free field
                // along with the paid ones, so a patient flip stopped
                // announcing itself the moment exits went missing
                // (review 2026-08-05).
                String freeBadge = "patient".equals(f.verdict) ? "§6◔ patient flip§7 · " : "";
                Phos.text(g, font, freeBadge + GoldFields.locked("exits"), x, yy + 13, Phos.FAINT);
            } else {
                String fastNet = (f.exitFastNet >= 0 ? " §a+" : " §c-")
                        + Phos.coins(Math.abs(f.exitFastNet)) + "§7";
                String patientNet = f.exitPatientNet != null
                        ? (f.exitPatientNet >= 0 ? " §a+" : " §c-")
                        + Phos.coins(Math.abs(f.exitPatientNet)) + "§7" : "";
                String badge = "patient".equals(f.verdict) ? "§6◔ patient flip§7 · " : "";
                String line = badge + "exit " + Phos.coins(f.exitFast) + " fast" + fastNet
                        + " §8~" + compactDur(f.exitFastHoldS) + "§7"
                        + (f.exitPatient != null ? " · " + Phos.coins(f.exitPatient)
                        + " patient" + patientNet + " §8~" + compactDur(f.exitPatientHoldS) + "§7" : "");
                Phos.text(g, font, "§7" + line + "§r", x, yy + 13, Phos.DIM);
            }
        } else {
            Phos.text(g, font, GoldFields.locked("exits"), x, yy + 13, Phos.FAINT);
        }
        // Survivorship honesty: hold medians are conditional on selling.
        String sell = f.fillPct != null
                ? "sells " + Math.round(f.fillPct) + "% of the time"
                : GoldFields.locked("sell-through");
        String tail = f.holdP90S != null ? " · slow case ~" + compactDur(f.holdP90S)
                : " · " + GoldFields.locked("slow case");
        Phos.text(g, font, "§8" + sell + tail + "§r", x, yy + 24, Phos.FAINT);
        // Manipulation drill-down: the explicit tells
        // behind the ⚑/⚐ mark, so a whale sees WHY before committing coins.
        if (f.manip != null && f.manipReasons != null && f.manipReasons.length > 0) {
            int col = "high".equals(f.manip) ? Phos.RED : Phos.YELLOW;
            String head = ("high".equals(f.manip) ? "⚑ HIGH" : "⚐ MED") + " manipulation risk";
            Phos.text(g, font, "§c" + head + "§r", x, yy + 37, col);
            Phos.text(g, font, "§8" + String.join(" · ", f.manipReasons) + "§r",
                    x, yy + 48, Phos.FAINT);
        } else {
            // Absence IS the answer here: manip is "med"|"high"|null and null
            // means nothing was detected. A clean verdict is free, and
            // labelling it Gold sells a user something they already have
            // (owner 2026-08-06). Draw nothing.
        }
    }

    /** Auctions tab: bid-flip candidates from /auctions/bids (server
     *  refreshes per 60s snapshot, so a 60s TTL). Server order is
     *  net_at_next desc — preserved; the only client re-shaping is the
     *  hide filters (min margin, max end-time) and the row cap. The row
     *  open-action funnels through the SAME ActionController path the
     *  Flips board uses — no new send mechanism. */
    private void auctions(GuiGraphicsExtractor g, int x, int w, int mx, int my) {
        int y = 36;
        Phos.text(g, font, GoldFields.badge(), x, y, Phos.ACCENT);
        y += 13;
        String chips = String.format(Locale.ROOT, "margin ≥ %d%% · ending ≤ %dm · top %d by net",
                config.auctionMinMarginPct, config.auctionMaxEndMin, config.auctionMaxRows);
        Phos.text(g, font, "§8" + chips + "§r", x, y, Phos.FAINT);
        y += 11;
        // Unconditional risk line (audit 2026-08-06, item 6). The coin-lock
        // and staleness warnings lived only inside an EXPANDED row, so the
        // scanning view — the one people actually act on — showed a green
        // margin and nothing else. A bid is not a BIN: the coins are gone
        // until the auction ends, and losing the auction is the normal case,
        // not the failure case. That belongs above the numbers, not behind a
        // click.
        // Not "~90s": the client TTL is 60s over a 60s server snapshot with
        // publication lag on top, and MidasflipApi serves stale entries when
        // the API is unreachable. A ceiling we do not hold does not belong on
        // the line whose whole job is honesty.
        Phos.text(g, font, "§c⚠ §7bids lock coins until the auction ends · you can be outbid · "
                + "prices lag, verify in-game§r", x, y, Phos.DIM);
        y += 14;

        JsonElement el = api.get("/auctions/bids", 60_000);
        // Pro-gated server-side: on a definitive 402 show the honest hint;
        // no client entitlement logic (the mod only reflects the status).
        if (proGateOrLoading(g, x, y + 4, el, "/auctions/bids", "auctions")) {
            return;
        }

        int[] cols = {0, 150, 205, 275, 345, 415};
        String[] heads = {"ITEM", "LEFT", "CURRENT", "NEXT", "MAX", "MARGIN"};
        for (int i = 0; i < heads.length; i++) {
            Phos.label(g, font, heads[i], x + cols[i], y);
        }
        y += 12;
        Phos.hline(g, x, y - 2, w);

        long now = System.currentTimeMillis();
        int shown = 0;
        boolean any = false;
        for (JsonElement e : (JsonArray) el) {
            JsonObject r = e.getAsJsonObject();
            any = true;
            double marginNext = r.has("margin_at_next") ? r.get("margin_at_next").getAsDouble() : 0;
            long endMs = r.has("end_ms") ? r.get("end_ms").getAsLong() : 0;
            long leftMin = Math.max(0, (endMs - now) / 60_000);
            // Client display filters (hide only — never surfaces anything
            // the server didn't send): min margin, max end-time.
            if (config.auctionMinMarginPct > 0 && marginNext * 100 < config.auctionMinMarginPct) {
                continue;
            }
            if (endMs > 0 && leftMin > config.auctionMaxEndMin) {
                continue;
            }
            if (shown >= config.auctionMaxRows) {
                break;
            }
            shown++;

            String uuid = r.has("uuid") ? r.get("uuid").getAsString() : null;
            String itemId = r.has("item_id") ? r.get("item_id").getAsString() : null;
            String compKey = r.has("comp_key") && !r.get("comp_key").isJsonNull()
                    ? r.get("comp_key").getAsString() : null;
            boolean hasBids = r.has("has_bids") && r.get("has_bids").getAsBoolean();
            boolean exp = uuid != null && uuid.equals(expandedAuctionId);

            boolean hov = hovered(x, y, w, 13, mx, my);
            if (hov) {
                g.fill(x, y - 1, x + w, y + 12, Phos.PANEL_HI);
            }
            Phos.text(g, font, shorten(NameMap.pretty(itemId, compKey), 24) + (exp ? " ▾" : ""),
                    x + cols[0], y, Phos.CREAM);
            // Time left: "12m", or "3m ⚠" under 5 min (data is up to ~90s
            // stale — the footer says so).
            String left = endMs <= 0 ? "?" : leftMin < 5 ? leftMin + "m §c⚠§r" : leftMin + "m";
            Phos.text(g, font, "§7" + left + "§r", x + cols[1], y, Phos.DIM);
            Phos.text(g, font, hasBids
                    ? Phos.coins(r.get("current_bid").getAsLong())
                    : "§8no bids§r", x + cols[2], y, Phos.FAINT);
            // "next" is the number you'd actually enter (cream); "max" is
            // your ceiling (accent).
            Phos.text(g, font, Phos.coins(r.get("next_bid").getAsLong()), x + cols[3], y, Phos.CREAM);
            Phos.text(g, font, Phos.coins(r.get("max_bid").getAsLong()), x + cols[4], y, Phos.ACCENT);
            Phos.text(g, font, "+" + Math.round(marginNext * 100) + "%", x + cols[5], y, Phos.GREEN);

            final String tid = uuid;
            zone(x, y - 1, w, 13, () -> expandedAuctionId =
                    tid != null && tid.equals(expandedAuctionId) ? null : tid);
            y += 13;

            if (exp) {
                Double estPess = r.has("est_pess") ? r.get("est_pess").getAsDouble() : null;
                double conf = r.has("conf") ? r.get("conf").getAsDouble() : 0;
                int comps = r.has("comps") ? r.get("comps").getAsInt() : 0;
                double spd = r.has("sales_per_day") ? r.get("sales_per_day").getAsDouble() : 0;
                double netNext = r.has("net_at_next") ? r.get("net_at_next").getAsDouble() : 0;
                Phos.text(g, font, "   " + (estPess == null
                        ? GoldFields.locked("est exit") : "§7est exit " + Phos.coins(estPess))
                        + " §8· conf " + String.format(Locale.ROOT, "%.2f", conf)
                        + " · " + comps + " comps · " + String.format(Locale.ROOT, "%.0f/d", spd) + "§r",
                        x, y, Phos.DIM);
                y += 11;
                Phos.text(g, font, "   §anet at next bid +" + Phos.coins(netNext) + "§r", x, y, Phos.GREEN);
                y += 11;
                // The two honesty lines (staleness + coin-lock).
                Phos.text(g, font, "   §8data up to ~90s old · verify the current bid in the auction view§r",
                        x, y, Phos.FAINT);
                y += 11;
                Phos.text(g, font, "   §8bids lock coins until the auction ends; outbid refunds are claimed manually§r",
                        x, y, Phos.FAINT);
                y += 11;
                // Open-action: SAME path the Flips board uses — a synthesized
                // Flip carrying only uuid + itemId, through ActionController's
                // onOpenKeyPressed (cooldown, safety mode, UUID validation).
                // STRICT copies the command; ASSISTED sends it once.
                final String openUuid = uuid;
                final String openItemId = itemId;
                boolean strict = config.safetyMode != MidasflipConfig.SafetyMode.ASSISTED;
                textButton(g, x, y, strict ? "   copy /viewauction →" : "   open auction →", () -> {
                    Flip f = new Flip();
                    f.uuid = openUuid;
                    f.itemId = openItemId;
                    actions.onOpenKeyPressed(Minecraft.getInstance(), f);
                });
                y += 13;
            }
        }

        if (!any) {
            // Endpoint returned [] — collector not yet deployed / no
            // candidates / TTL-expired during a stall. Calm empty state.
            Phos.text(g, font, "§8no auction candidates right now · data refreshes every minute§r",
                    x, y + 2, Phos.FAINT);
            y += 14;
        } else if (shown == 0) {
            Phos.text(g, font, "§8nothing passes your margin/end-time filters right now§r", x, y + 2, Phos.FAINT);
            y += 14;
        }
        Phos.text(g, font, "§8click a row for detail · data up to ~90s stale · verify the live bid before you commit§r",
                x, y + 6, Phos.FAINT);
        y += 22;

        // In-tab settings (contSlider pattern): rows, min margin, max end.
        Phos.label(g, font, "auctions tab settings", x, y);
        y += 14;
        int half = (w - 10) / 2;
        contSlider(g, x, y, half, "max rows", 3, 15, 1, config.auctionMaxRows,
                v -> String.valueOf(v), v -> config.auctionMaxRows = (int) v);
        contSlider(g, x + half + 10, y, half, "min margin", 0, 100, 1, config.auctionMinMarginPct,
                v -> v == 0 ? "off" : v + "%", v -> config.auctionMinMarginPct = (int) v);
        y += 30;
        contSlider(g, x, y, half, "max end-time", 5, 45, 1, config.auctionMaxEndMin,
                v -> v + "m", v -> config.auctionMaxEndMin = (int) v);
    }

    private void trades(GuiGraphicsExtractor g, int x, int w) {
        int y = 32;
        // Per-trade history and the sell-side suite are Gold; the Dashboard's
        // overall P&L is free (owner 2026-08-06), which is why the badge sits
        // here and not there.
        Phos.text(g, font, GoldFields.badge(), x, y, Phos.ACCENT);
        y += 13;

        // My live listings vs the market (the undercut watch): server-
        // judged status per listing — lowest / undercut / stale — with the
        // don't-chase reprice suggestion. Display-only.
        ListingsWatch.tick(Minecraft.getInstance());
        List<ListingsWatch.Mine> mine = ListingsWatch.mine();
        if (!mine.isEmpty()) {
            Phos.label(g, font, "my listings · live vs market", x, y);
            y += 12;
            int shown = 0;
            for (ListingsWatch.Mine m : mine) {
                if (shown++ >= config.tradesListingsMax) {
                    break;
                }
                String tag;
                if (m.undercut()) {
                    String shift = ListingsWatch.profitShift(m);
                    tag = "§6▲ undercut§r §8· market " + Phos.coins(m.floorUnit() == null ? 0 : m.floorUnit())
                            + (m.suggestUnit() != null ? " · reprice ~" + Phos.coins(m.suggestUnit()) : "")
                            + (shift.isEmpty() ? "" : " · profit " + shift + "§8")
                            + (m.holdMedS() != null ? " · or hold ~" + ListingsWatch.compact(m.holdMedS())
                            : " · " + GoldFields.unknown("hold")) + "§r";
                } else if (m.stale()) {
                    tag = "§e◆ stale§r §8· est " + (m.suggestUnit() != null ? Phos.coins(m.suggestUnit()) : "?") + "§r";
                } else {
                    tag = "§a● lowest§r";
                }
                Phos.text(g, font, "§7" + shorten(NameMap.pretty(m.itemId(), m.compKey()), 20)
                        + " §f" + Phos.coins(m.unitPrice()) + "§r  " + tag, x, y, Phos.DIM);
                y += 12;
            }
            y += 8;
        }

        Phos.label(g, font, "positions · model's exit vs what happened · local ledger", x, y);
        y += 14;
        // Live re-pricing of open positions (alert-only): api.get is
        // TTL-cached, so this per-frame call costs at most one background
        // GET per open position per minute.
        session.ledger().refreshValuations(api);
        List<PositionLedger.Position> ps = session.ledger().recent(config.tradesPositionsMax);
        if (ps.isEmpty()) {
            Phos.text(g, font, "§8no tracked trades yet · buy a flip and this fills itself§r", x, y, Phos.FAINT);
            return;
        }
        for (int i = ps.size() - 1; i >= 0; i--) {
            PositionLedger.Position p = ps.get(i);
            String rec = p.exitFast == null ? GoldFields.locked("exits")
                    : Phos.coins(p.exitFast) + (p.exitPatient != null ? "/" + Phos.coins(p.exitPatient) : "");
            if ("sold".equals(p.state)) {
                long vs = p.exitFast == null ? 0 : Math.round(p.soldPrice - p.exitFast);
                Phos.text(g, font, "§a●§r " + shorten(NameMap.pretty(p.itemId), 22)
                        + "  §7buy " + Phos.coins(p.buyPrice) + " · rec " + rec
                        + " → §f" + Phos.coins(p.soldPrice) + "§r "
                        + (p.exitFast != null ? (vs >= 0 ? "§a+" : "§c") + Phos.coins(Math.abs(vs)) + "§r §8vs fast§r" : ""),
                        x, y, Phos.DIM);
            } else {
                // ▼ underwater: the CURRENT pessimistic estimate is below
                // cost — the market moved against this hold since buy.
                // Display only; cutting the loss is the human's call.
                boolean unconfirmed = p.unconfirmed(System.currentTimeMillis());
                String uw = unconfirmed ? "§8unconfirmed§r" : p.underwater()
                        ? " §c▼ underwater · now ~" + Phos.coins(p.curPessTotal()) + "§r" : "";
                Phos.text(g, font, "§8" + ("listed".equals(p.state) ? "◐" : "○") + "§r "
                        + shorten(NameMap.pretty(p.itemId), 22) + " §7" + p.state + " · buy "
                        + Phos.coins(p.buyPrice) + " · rec " + rec + "§r" + uw, x, y, Phos.DIM);
            }
            y += 13;
        }
    }

    private void value(GuiGraphicsExtractor g, int x, int w, int mx, int my) {
        int y = 36;
        Phos.text(g, font, GoldFields.badge(), x, y, Phos.ACCENT);
        y += 13;
        int bx = x;
        for (String k : new String[]{"all", "craft", "forge", "kat", "bz craft", "bz", "npc"}) {
            boolean sel = k.equals(kindFilter);
            int bw = Phos.w(font, k) + 12;
            if (sel) {
                g.fill(bx, y - 2, bx + bw, y + 10, Phos.SEL_BG);
            }
            Phos.border(g, bx, y - 2, bw, 12, sel ? Phos.ACCENT : Phos.BORDER);
            Phos.text(g, font, k, bx + 6, y, sel ? Phos.CREAM : Phos.DIM);
            final String kk = k;
            zone(bx, y - 2, bw, 12, () -> {
                kindFilter = kk;
                expandedId = null;
                kindSince = System.currentTimeMillis();
            });
            bx += bw + 6;
        }
        y += 18;

        // The bazaar-spread and NPC boards are their own endpoints/panes;
        // everything else filters the /craft/evs table client-side.
        if ("bz".equals(kindFilter)) {
            bazaarSpreads(g, x, w, y);
            return;
        }
        if ("npc".equals(kindFilter)) {
            npcFlips(g, x, w, y);
            return;
        }

        JsonElement el = api.get("/craft/evs", 5 * 60_000);
        valueRows.clear();
        // Was loadingOrDown: a free user's 402 rendered as "value board down",
        // i.e. the product looked broken instead of locked. This is the
        // largest Gold surface in the mod (review note 2026-07-30).
        if (proGateOrLoading(g, x, y, el, "/craft/evs", "value board")) {
            return;
        }
        int[] cols = {0, 175, 225, 330, 385, 425};
        String[] heads = {"RECIPE", "TYPE", "COST → OUT", "NET", "TIME", "/SLOT·HR"};
        for (int i = 0; i < heads.length; i++) {
            Phos.label(g, font, heads[i], x + cols[i], y);
        }
        y += 12;
        Phos.hline(g, x, y - 2, w);
        for (JsonElement e : (JsonArray) el) {
            JsonObject r = e.getAsJsonObject();
            if (!craftKindMatches(r)) {
                continue;
            }
            if (valueRows.size() >= config.shellTableRows) {
                break;
            }
            valueRows.add(r);
            String id = r.get("output_id").getAsString();
            boolean exp = id.equals(expandedId);
            boolean hov = hovered(x, y, w, 13, mx, my);
            if (hov) {
                g.fill(x, y - 1, x + w, y + 12, Phos.PANEL_HI);
            }
            Phos.text(g, font, shorten(NameMap.pretty(id), 26) + (exp ? " ▾" : ""), x + cols[0], y, Phos.CREAM);
            Phos.text(g, font, r.get("kind").getAsString(), x + cols[1], y, Phos.FAINT);
            Phos.text(g, font, Phos.coins(r.get("cost").getAsDouble()) + " §8→§r "
                    + Phos.coins(r.get("proceeds").getAsDouble()), x + cols[2], y, Phos.DIM);
            Phos.text(g, font, "+" + Phos.coins(r.get("profit").getAsDouble()), x + cols[3], y, Phos.GREEN);
            JsonElement dur = r.get("duration_s");
            Phos.text(g, font, dur == null || dur.isJsonNull() ? "instant"
                    : durStr(dur.getAsLong()), x + cols[4], y, Phos.DIM);
            JsonElement shr = r.get("profit_per_slot_hour");
            Phos.text(g, font, shr == null || shr.isJsonNull() ? "·" : Phos.coins(shr.getAsDouble()),
                    x + cols[5], y, Phos.DIM);
            final String tid = id;
            zone(x, y - 1, w, 13, () -> expandedId = tid.equals(expandedId) ? null : tid);
            y += 13;
            if (exp) {
                // Unlock requirement first ("Requires: Mycelium IX") — the
                // recipe is worthless to a player who can't craft it yet.
                if (r.has("req") && !r.get("req").isJsonNull()) {
                    Phos.text(g, font, "   §8⚠ " + r.get("req").getAsString() + "§r", x, y, Phos.FAINT);
                    y += 11;
                }
                for (JsonElement ie : r.getAsJsonArray("inputs")) {
                    JsonObject in = ie.getAsJsonObject();
                    String legName = NameMap.pretty(in.get("id").getAsString());
                    double need = in.get("count").getAsDouble();
                    // One run. There is no run-count control yet; when one
                    // arrives this is the single place that multiplies.
                    long qty = (long) Math.ceil(need);
                    String via = in.get("via").getAsString();
                    // Every priced leg has a market and therefore a search
                    // command: craft.py returns "bazaar" or "ah:<key>" and
                    // nothing else. A leg we cannot classify sends nothing.
                    String verb = ActionController.commandFor(via);
                    // Both markets have a search command, so both can open:
                    // /bz for a bazaar leg, /ahs for an auction-house one. A
                    // leg we cannot classify has no command and only copies.
                    // The row says which, so a click is never a surprise.
                    // The hint must match what the click will ACTUALLY do,
                    // including in STRICT and with the send toggle off, or the
                    // row promises an open and delivers a copy.
                    boolean willSend = verb != null && config.craftLegSend
                            && config.safetyMode == MidasflipConfig.SafetyMode.ASSISTED;
                    String hint = willSend ? " §8· open ⏎§r"
                            : verb != null ? " §8· copy /" + verb + " ⧉§r" : " §8· copy ⧉§r";
                    boolean legHov = hovered(x, y - 1, w, 11, mx, my);
                    if (legHov) {
                        g.fill(x, y - 1, x + w, y + 10, Phos.PANEL_HI);
                    }
                    Phos.text(g, font, "   §8" + shorten(legName, 30)
                            + " ×" + need
                            + " · " + Phos.coins(in.get("cost").getAsDouble())
                            + " · " + via + hint + "§r", x, y, Phos.FAINT);
                    zone(x, y - 1, w, 11, () -> {
                        Minecraft mc = Minecraft.getInstance();
                        if (verb != null) {
                            actions.onCraftLegClicked(mc, legName, qty, via);
                        } else {
                            // Display-only path: no command is ever built for
                            // an AH or forge leg, so there is nothing here for
                            // the cooldown or the send gate to govern.
                            // Sanitized even though nothing is sent: legName
                            // is server-chosen text, and this is the one place
                            // a hostile /craft/evs could land arbitrary bytes
                            // on an OS resource the user may paste anywhere.
                            String safeLeg = ActionController.sanitizeQuery(legName);
                            String copied = safeLeg + " x" + qty;
                            mc.keyboardHandler.setClipboard(copied);
                            AuditLog.record("clipboard", "craft_click",
                                    "leg " + copied + " via=" + via);
                            flash("copied '" + copied + "'");
                        }
                    });
                    y += 11;
                }
            }
        }
    }

    /** Craft-table kind filter. "bz craft" = a pure bazaar loop: craft
     *  kind, sold on bazaar, EVERY input bought on bazaar (input via is
     *  "bazaar" or "ah:<comp_key>", craft.py's _price_input). */
    private boolean craftKindMatches(JsonObject r) {
        if ("all".equals(kindFilter)) {
            return true;
        }
        String kind = r.get("kind").getAsString();
        if ("bz craft".equals(kindFilter)) {
            if (!"craft".equals(kind) || !r.has("sell_via")
                    || !"bazaar".equals(r.get("sell_via").getAsString())) {
                return false;
            }
            for (JsonElement ie : r.getAsJsonArray("inputs")) {
                if (!"bazaar".equals(ie.getAsJsonObject().get("via").getAsString())) {
                    return false;
                }
            }
            return true;
        }
        return kindFilter.equals(kind);
    }

    /** True when the pane can't render data yet — draws the right honest
     *  placeholder and the caller returns. The pro hint fires when the
     *  server answered 402 (these endpoints are PRO-gated server-side) or
     *  has served nothing for ~12s while otherwise reachable. The mod holds
     *  NO entitlement logic; it only reflects what the API serves. */
    private boolean proGateOrLoading(GuiGraphicsExtractor g, int x, int y, JsonElement el,
                                     String path, String what) {
        if (el != null && el.isJsonArray()) {
            return false;
        }
        // A 402 is the server SAYING so. The second clause is an inference
        // from silence, and inference must be slow: at 3s one timed-out
        // request told a free-launch user that a free board was paid, on the
        // same pane that now carries the early-access badge right above it
        // (review 2026-08-06). Widening the window only ever shows LESS
        // restriction than the server did, so it cannot become a bypass.
        boolean pro = (el != null && el.isJsonNull() && api.lastStatus(path) == 402)
                || (el == null && !api.likelyDown()
                    && System.currentTimeMillis() - kindSince > 12_000);
        boolean said402 = el != null && el.isJsonNull() && api.lastStatus(path) == 402;
        if (pro) {
            // ONLY a real 402 may render tier copy. The other branch is an
            // INFERENCE from a request that never came back, and answering a
            // timeout with "€40 Founder" puts a price tag on data we do not
            // have — the exact failure the three-state rule exists to stop
            // (review 2026-08-06). A silent request gets an honest "did not
            // load", not an upsell.
            if (said402) {
                Phos.text(g, font, GoldFields.badge(), x, y, Phos.ACCENT);
                Phos.text(g, font, "§8this board is part of Gold§r", x, y + 11, Phos.FAINT);
            } else {
                Phos.text(g, font, "§8this board did not load · retrying§r", x, y, Phos.FAINT);
            }
        } else if (el != null && el.isJsonNull()) {
            // Definitive non-402 "no data" (e.g. 404 from an older server)
            // — never a forever-"loading…".
            Phos.text(g, font, "§8no data for this board§r", x, y, Phos.FAINT);
        } else {
            loadingOrDown(g, x, y, what);
        }
        return true;
    }

    /** Bazaar order-flip board (/bazaar/spreads): buy order at buy_at,
     *  sell order at sell_at, spread minus tax per unit. The /hour figure
     *  assumes full fill of the hourly volume — an estimate, not a promise. */
    private void bazaarSpreads(GuiGraphicsExtractor g, int x, int w, int y) {
        JsonElement el = api.get("/bazaar/spreads", 5 * 60_000);
        if (proGateOrLoading(g, x, y, el, "/bazaar/spreads", "bazaar spreads")) {
            return;
        }
        int[] cols = {0, 150, 255, 325, 420};
        String[] heads = {"PRODUCT", "BUY → SELL", "/UNIT", "/HOUR", "SPREAD"};
        for (int i = 0; i < heads.length; i++) {
            Phos.label(g, font, heads[i], x + cols[i], y);
        }
        y += 12;
        Phos.hline(g, x, y - 2, w);
        int shown = 0;
        for (JsonElement e : (JsonArray) el) {
            if (shown++ >= config.shellTableRows) {
                break;
            }
            JsonObject r = e.getAsJsonObject();
            Phos.text(g, font, shorten(NameMap.pretty(r.get("product_id").getAsString()), 24),
                    x + cols[0], y, Phos.CREAM);
            Phos.text(g, font, Phos.coins(r.get("buy_at").getAsDouble()) + " §8→§r "
                    + Phos.coins(r.get("sell_at").getAsDouble()), x + cols[1], y, Phos.DIM);
            Phos.text(g, font, "+" + Phos.coins(r.get("profit_per_unit").getAsDouble()) + "/u",
                    x + cols[2], y, Phos.GREEN);
            Phos.text(g, font, Phos.coins(r.get("est_profit_per_hour").getAsDouble())
                    + "/h §8@ full fill§r", x + cols[3], y, Phos.DIM);
            // spread_pct arrives as a FRACTION (bazaar.py: profit/place_buy,
            // gate MIN_SPREAD_PCT=0.02) — ×100 like netMarginPct everywhere.
            Phos.text(g, font, Math.round(r.get("spread_pct").getAsDouble() * 100) + "%",
                    x + cols[4], y, Phos.FAINT);
            y += 13;
        }
        if (shown == 0) {
            Phos.text(g, font, "§8nothing above the gates right now§r", x, y, Phos.FAINT);
            y += 13;
        }
        Phos.text(g, font, "§8order flips: place buy + sell orders, wait for fills · /hour assumes full fill§r",
                x, y + 6, Phos.FAINT);
    }

    /** Bazaar→NPC board (/npc/flips): instabuy below the fixed NPC vendor
     *  price. NPC sales are untaxed but daily-capped — /day is a fill
     *  estimate, never a promise. */
    private void npcFlips(GuiGraphicsExtractor g, int x, int w, int y) {
        JsonElement el = api.get("/npc/flips", 5 * 60_000);
        if (proGateOrLoading(g, x, y, el, "/npc/flips", "npc flips")) {
            return;
        }
        int[] cols = {0, 150, 255, 325, 385};
        String[] heads = {"PRODUCT", "BUY → NPC", "/UNIT", "MARGIN", "/DAY"};
        for (int i = 0; i < heads.length; i++) {
            Phos.label(g, font, heads[i], x + cols[i], y);
        }
        y += 12;
        Phos.hline(g, x, y - 2, w);
        int shown = 0;
        for (JsonElement e : (JsonArray) el) {
            if (shown++ >= config.shellTableRows) {
                break;
            }
            JsonObject r = e.getAsJsonObject();
            Phos.text(g, font, shorten(NameMap.pretty(r.get("product_id").getAsString()), 24),
                    x + cols[0], y, Phos.CREAM);
            Phos.text(g, font, Phos.coins(r.get("buy_at").getAsDouble()) + " §8→§r "
                    + Phos.coins(r.get("npc_sell").getAsDouble()), x + cols[1], y, Phos.DIM);
            Phos.text(g, font, "+" + Phos.coins(r.get("profit_per_unit").getAsDouble()) + "/u",
                    x + cols[2], y, Phos.GREEN);
            // margin_pct is a FRACTION (npc.py: profit/instabuy, gate
            // MIN_MARGIN_PCT=0.03) — ×100 for display.
            Phos.text(g, font, Math.round(r.get("margin_pct").getAsDouble() * 100) + "%",
                    x + cols[3], y, Phos.DIM);
            Phos.text(g, font, Phos.coins(r.get("est_profit_per_day").getAsDouble()) + "/d",
                    x + cols[4], y, Phos.DIM);
            y += 13;
        }
        if (shown == 0) {
            Phos.text(g, font, "§8nothing above the gates right now§r", x, y, Phos.FAINT);
            y += 13;
        }
        Phos.text(g, font, "§8sell to NPC · untaxed, but hypixel daily-caps NPC-sell coins§r",
                x, y + 6, Phos.FAINT);
    }

    private void filters(GuiGraphicsExtractor g, int x, int w, int mx, int my) {
        int y = 36;
        int half = (w - 10) / 2;

        // Sub-pages: the board is filterable on ~everything the algorithm
        // surfaces (owner 2026-07-11) — too much for one column, so
        // thresholds and visibility split across two chips.
        chipButton(g, x, y + 3, (filtersPage == 0 ? "§f● " : "§8○ ") + "thresholds", () -> filtersPage = 0);
        chipButton(g, x + 92, y + 3, (filtersPage == 1 ? "§f● " : "§8○ ") + "what's shown", () -> filtersPage = 1);
        Phos.text(g, font, "§8changes apply live§r", x + w - 110, y + 3, Phos.FAINT);
        y += 20;

        if (filtersPage == 1) {
            filtersVisibility(g, x, y, half);
            return;
        }
        // Continuous sliders: drag a little, the value moves a little.
        // A MINIMUM is disabled at its LEFT end, not its right — say so.
        contSlider(g, x, y, half, "min profit", 0, 20_000_000, 50_000, config.minProfit,
                v -> v == 0 ? "off · any" : coinsFmt().format(v),
                v -> config.minProfit = v);
        contSlider(g, x + half + 10, y, half, "max cost", 0, 2_001_000_000, 1_000_000,
                config.maxCost == 0 ? 2_001_000_000L : config.maxCost,
                v -> (v >= 2_000_500_000L || v == 0) ? "∞ no cap" : coinsFmt().format(v),
                v -> config.maxCost = (v >= 2_000_500_000L) ? 0 : v);
        y += 30;
        confSlider(g, x, y, half);
        cycle(g, x + half + 10, y, half, "min liquidity: " + config.minLiquidity.name().toLowerCase(Locale.ROOT), () -> {
            MidasflipConfig.Liquidity[] vs = MidasflipConfig.Liquidity.values();
            config.minLiquidity = vs[(config.minLiquidity.ordinal() + 1) % vs.length];
        });
        y += 30;
        // Far RIGHT switches the cap off (owner 2026-07-27): 245 is a
        // sentinel past the real 240m range and stores 0, which passes()
        // already reads as "no hold filter".
        contSlider(g, x, y, half, "max hold", 0, 245, 5,
                config.maxHoldMin == 0 ? 245 : config.maxHoldMin,
                v -> (v >= 245 || v == 0) ? "∞ no cap" : v + "m",
                v -> config.maxHoldMin = (v >= 245) ? 0 : (int) v);
        cycle(g, x + half + 10, y, half, "falling knives: " + (config.showFallingKnife ? "shown ⚠" : "hidden"),
                () -> config.showFallingKnife = !config.showFallingKnife);
        y += 30;
        cycle(g, x, y, half, "patient flips: " + (config.showPatient ? "shown ◔" : "hidden"),
                () -> config.showPatient = !config.showPatient);
        y += 30;
        cycle(g, x, y, half, "pet lvl rule: " + (config.petLevelRule
                        ? "on · <" + config.petLevelPremiumFloor + " = fresh price" : "off"),
                () -> config.petLevelRule = !config.petLevelRule);
        if (config.petLevelRule) {
            contSlider(g, x + half + 10, y, half, "premium floor", 1, 200, 1, config.petLevelPremiumFloor,
                    v -> "lvl " + v, v -> config.petLevelPremiumFloor = (int) v);
        }
        y += 30;
        cycle(g, x, y, half, "rank by: " + config.sortMode.label, () -> {
            MidasflipConfig.SortMode[] vs = MidasflipConfig.SortMode.values();
            config.sortMode = vs[(config.sortMode.ordinal() + 1) % vs.length];
        });
        cycle(g, x + half + 10, y, half, "candied pets: " + (config.hideCandied ? "hidden" : "shown"),
                () -> config.hideCandied = !config.hideCandied);
        y += 34;

        Phos.label(g, font, "presets · one click swaps every threshold", x, y);
        y += 14;
        int bx = x;
        Map<String, Preset> all = new LinkedHashMap<>(Preset.builtIns());
        all.putAll(config.customPresets);
        for (Map.Entry<String, Preset> e : all.entrySet()) {
            int bw = Phos.w(font, e.getKey()) + 12;
            if (bx + bw > x + w) {
                bx = x;
                y += 16;
            }
            Phos.border(g, bx, y - 2, bw, 12, Phos.BORDER);
            Phos.text(g, font, e.getKey(), bx + 6, y, Phos.DIM);
            final Preset p = e.getValue();
            final String nm = e.getKey();
            zone(bx, y - 2, bw, 12, () -> {
                p.apply(config);
                flash("preset '" + nm + "' applied");
                refresh();
            });
            bx += bw + 6;
        }
        // Preset-name row, flow-positioned (was a stale fixed Y that
        // collided once the pet row grew the section). The EditBox tracks
        // the flow each frame so it can never drift again.
        y += 26;
        if (presetName != null) {
            presetName.setX(x + 150);
            presetName.setY(y);
        }
        Phos.border(g, x + 146, y - 3, 98, 16, Phos.BORDER);
        textButton(g, x, y, "save current as preset →", () -> {
            String name = presetName == null ? "" : presetName.getValue().strip().toLowerCase(Locale.ROOT);
            if (name.isEmpty() || Preset.builtIns().containsKey(name)) {
                flash("pick a non-builtin name first");
                return;
            }
            config.customPresets.put(name, Preset.fromConfig(config));
            flash("saved '" + name + "'");
            refresh();
        });
        textButton(g, x + 260, y, "copy code ⧉", () -> {
            Minecraft.getInstance().keyboardHandler.setClipboard(Preset.fromConfig(config).shareCode());
            flash("SF1 code copied");
        });
        textButton(g, x + 340, y, "import code", () -> {
            Preset p = Preset.fromShareCode(Minecraft.getInstance().keyboardHandler.getClipboard());
            if (p == null) {
                flash("clipboard has no SF1 code");
                return;
            }
            p.apply(config);
            flash("imported");
            refresh();
        });

        int ry = y + 26;
        Phos.label(g, font, "rules · " + config.rules.size() + " · top-down, first match wins", x, ry);
        ry += 14;
        for (int i = 0; i < Math.min(config.rules.size(), 6); i++) {
            Rule r = config.rules.get(i);
            final int idx = i;
            Phos.text(g, font, "§8" + (i + 1) + "§r WHEN", x, ry + 4, Phos.FAINT);
            chipButton(g, x + 40, ry + 4, r.field, () -> {
                r.field = next(new ArrayList<>(Rule.FIELDS), r.field);
                refresh();
            });
            chipButton(g, x + 128, ry + 4, r.op, () -> {
                r.op = next(new ArrayList<>(Rule.OPS), r.op);
                refresh();
            });
            Phos.border(g, x + 164, ry, 70, 16, Phos.BORDER); // value EditBox frame
            if (idx < ruleValues.size()) {
                ruleValues.get(idx).setX(x + 168);
                ruleValues.get(idx).setY(ry + 3);
            }
            Phos.text(g, font, "THEN", x + 242, ry + 4, Phos.FAINT);
            chipButton(g, x + 272, ry + 4, r.action, () -> {
                r.action = next(new ArrayList<>(Rule.ACTIONS), r.action);
                refresh();
            });
            textButton(g, x + 322, ry + 4, "↑", () -> {
                if (idx > 0) {
                    config.rules.add(idx - 1, config.rules.remove(idx));
                    refresh();
                }
            });
            textButton(g, x + 334, ry + 4, "↓", () -> {
                if (idx < config.rules.size() - 1) {
                    config.rules.add(idx + 1, config.rules.remove(idx));
                    refresh();
                }
            });
            textButton(g, x + 346, ry + 4, "§c✕§r", () -> {
                config.rules.remove(idx);
                refresh();
            });
            ry += 18;
        }
        textButton(g, x, ry + 4, "＋ add rule", () -> {
            if (config.rules.size() < 20) {
                Rule r = new Rule();
                r.field = "net_profit";
                r.op = ">=";
                r.value = "1000000";
                r.action = "star";
                config.rules.add(r);
                refresh();
            }
        });
        // One line so the two panes are never confused (owner 2026-07-27),
        // moved below the controls it describes (owner 2026-07-29): at the old
        // y − 12 it drew straight through the chip row above it.
        //
        // Clamped, because "below all the other text" can be below the SCREEN.
        // This pane's flow reaches ry+24 = 374 empty and 482 with six rules,
        // while Minecraft's auto GUI scale on a 1080p display gives a 270-tall
        // root — and nothing here scrolls, nor does Phos.text clip. Unclamped,
        // the fix for an overlapping line was an invisible one. height−28
        // keeps 12px clear of the flash message at height−16.
        Phos.text(g, font, "§8thresholds decide what the finder is ALLOWED to surface · "
                + "drag a slider to its open end to switch it off§r",
                x, Math.min(ry + 24, height - 28), Phos.FAINT);
    }

    private void alerts(GuiGraphicsExtractor g, int x, int w) {
        int y = 32;
        Phos.text(g, font, GoldFields.badge(), x, y, Phos.ACCENT);
        y += 13;
        Phos.panel(g, x, y, w, 40);
        Phos.label(g, font, "discord alerts", x + 8, y + 6);
        Phos.text(g, font, "§7server-side: flips ≥ 1M est net post to your webhook§r", x + 8, y + 17, Phos.DIM);
        Phos.text(g, font, "§8tune via MIDASFLIP_ALERT_MIN_PROFIT on the server§r", x + 8, y + 28, Phos.FAINT);
        y += 48;
        // NOT under the badge: quick-open has not shipped, and the frozen-pool
        // rule in GoldFields says a post-launch feature never joins the
        // early-access set. Badging an unbuilt feature as free-right-now would
        // have broken that rule on the same day it was written.
        Phos.panel(g, x, y, w, 28);
        Phos.label(g, font, "quick-open", x + 8, y + 6);
        Phos.text(g, font, "§8" + GoldFields.locked("arrives next update")
                + " §8· mouse-bindable one-press open§r", x + 8, y + 17, Phos.FAINT);
    }

    /** "What's shown": client display filters over every field the server
     *  ships on a flip — margin, comps, cost floor, sell-through, spread,
     *  slow-tail hold, estimate source, manip level, tier-boosted pets.
     *  0/off = today's board. These sit ON TOP of the server gates; they
     *  can only hide, never surface anything the server didn't vouch for. */
    private void filtersVisibility(GuiGraphicsExtractor g, int x, int y, int half) {
        contSlider(g, x, y, half, "min margin", 0, 50, 1, config.minMarginPct,
                v -> v == 0 ? "off" : v + "%", v -> config.minMarginPct = (int) v);
        contSlider(g, x + half + 10, y, half, "min comps", 0, 50, 1, config.minComps,
                v -> v == 0 ? "off" : String.valueOf(v), v -> config.minComps = (int) v);
        y += 30;
        contSlider(g, x, y, half, "min cost", 0, 500_000_000, 1_000_000, config.minCost,
                coinsFmt(), v -> config.minCost = v);
        contSlider(g, x + half + 10, y, half, "min sell-through", 0, 100, 5, config.minFillPct,
                v -> v == 0 ? "off" : v + "%", v -> config.minFillPct = (int) v);
        y += 30;
        contSlider(g, x, y, half, "max spread", 0, 60, 1, config.maxSpreadPct,
                v -> v == 0 ? "off" : v + "%", v -> config.maxSpreadPct = (int) v);
        contSlider(g, x + half + 10, y, half, "max slow-case hold", 0, 24 * 60, 15, config.maxHoldP90Min,
                v -> v == 0 ? "off" : v + "m", v -> config.maxHoldP90Min = (int) v);
        y += 30;
        cycle(g, x, y, half, "manip risk: " + config.manipFilter.label, () -> {
            MidasflipConfig.ManipFilter[] vs = MidasflipConfig.ManipFilter.values();
            config.manipFilter = vs[(config.manipFilter.ordinal() + 1) % vs.length];
        });
        cycle(g, x + half + 10, y, half,
                "estimates: " + (config.hideDerived ? "direct comps only" : "all sources"),
                () -> config.hideDerived = !config.hideDerived);
        y += 30;
        cycle(g, x, y, half, "tier-boosted pets: " + (config.hideTierBoosted ? "hidden" : "shown"),
                () -> config.hideTierBoosted = !config.hideTierBoosted);
        y += 34;
        // Verdict tier cut (config-completeness 2026-07-13): the GREEN-vs-
        // AMBER threshold, one pair of sliders for all three call sites.
        Phos.label(g, font, "verdict (green) needs", x, y);
        y += 14;
        contSlider(g, x, y, half, "min confidence", 50, 99, 1, config.verdictConfPct,
                v -> v + "%", v -> config.verdictConfPct = (int) v);
        contSlider(g, x + half + 10, y, half, "min comps", 1, 50, 1, config.verdictMinComps,
                v -> String.valueOf(v), v -> config.verdictMinComps = (int) v);
        y += 34;
        // Same defect as the thresholds pane (drawn at y−12, through the chip
        // row), so the same move — but folded into the line that already
        // lived here rather than added above it. Two stacked lines pushed this
        // one to y=270, and Minecraft's auto GUI scale on a 1080p display
        // gives a 270-tall root: it rendered exactly at the bottom edge and
        // vanished. Nothing in this shell scrolls and Phos.text does not clip,
        // so an extra 12px is not free.
        Phos.text(g, font, "§8these hide rows that already qualified · thresholds gate, "
                + "these filter · presets carry both§r", x, y, Phos.FAINT);
    }

    private void display(GuiGraphicsExtractor g, int x, int w, int mx, int my) {
        int y = 38;
        int half = (w - 10) / 2;
        // Sub-pages (mirrors the Filters tab): the display knobs outgrew one
        // column once every magic number became tunable (config-completeness
        // 2026-07-13), so surfaces and the density/notification cluster split
        // across two chips.
        chipButton(g, x, y + 3, (displayPage == 0 ? "§f● " : "§8○ ") + "surfaces", () -> displayPage = 0);
        chipButton(g, x + 92, y + 3, (displayPage == 1 ? "§f● " : "§8○ ") + "density & notifications",
                () -> displayPage = 1);
        y += 22;

        if (displayPage == 1) {
            displayDensity(g, x, y, half);
            return;
        }

        cycle(g, x, y, half, "hud overlay: " + (config.overlayEnabled ? "on" : "off"),
                () -> config.overlayEnabled = !config.overlayEnabled);
        cycle(g, x + half + 10, y, half, "item tooltips: " + (config.itemTooltip ? "on" : "off"),
                () -> config.itemTooltip = !config.itemTooltip);
        y += 26;
        cycle(g, x, y, half, "est. craft line: " + (config.craftPrice ? "on" : "off"),
                () -> config.craftPrice = !config.craftPrice);
        y += 26;
        cycle(g, x, y, half, "sell-assist overlay: " + (config.sellOverlay ? "on" : "off"),
                () -> config.sellOverlay = !config.sellOverlay);
        cycle(g, x + half + 10, y, half, "copy sell price: " + (config.sellCopyPrice ? "on" : "off"),
                () -> config.sellCopyPrice = !config.sellCopyPrice);
        y += 26;
        cycle(g, x, y, half, "show taken flips: " + (config.showTakenFlips ? "on ✗" : "off"),
                () -> config.showTakenFlips = !config.showTakenFlips);
        y += 26;
        cycle(g, x, y, half, "undercut alerts: " + (config.undercutAlerts ? "on" : "off"),
                () -> config.undercutAlerts = !config.undercutAlerts);
        // Full-screen margin alert (display-only banner; never renders
        // over any GUI — see FlipHud.marginAlert).
        cycle(g, x + half + 10, y, half, "margin alert: " + (config.marginAlert ? "on" : "off"),
                () -> config.marginAlert = !config.marginAlert);
        y += 26;
        if (config.marginAlert) {
            contSlider(g, x, y, half, "alert at margin ≥", 5, 200, 5, config.marginAlertPct,
                    v -> v + "%", v -> config.marginAlertPct = (int) v);
            y += 30;
        }
        contSlider(g, x, y, half, "board rows", 3, 15, 1, config.overlayMaxRows,
                v -> String.valueOf(v), v -> config.overlayMaxRows = (int) v);
        // Auctions tab is owner-toggleable and hidden from the sidebar until
        // enabled here — surfaced in Display so it's discoverable.
        cycle(g, x + half + 10, y, half, "auctions tab: " + (config.auctionTab ? "shown" : "hidden"),
                () -> config.auctionTab = !config.auctionTab);
        y += 34;
        Phos.label(g, font, "buy overlay (assisted only · off by default)", x, y);
        y += 12;
        cycle(g, x, y, half, "purchase confirm overlay: " + (config.purchaseOverlay ? "§aon§r" : "off"),
                () -> config.purchaseOverlay = !config.purchaseOverlay);
        y += 18;
        Phos.text(g, font, "§8covers only auctions MidasFlip opened · hypixel's final confirm stays raw§r",
                x, y, Phos.FAINT);
    }

    /** Display sub-page: HUD placement, table density, coin/chat display,
     *  and notification durations (config-completeness 2026-07-13). Local
     *  presentation only — none of this changes what the server ships.
     *  HUD position is LOCAL-ONLY (not carried in presets/SF1 codes). */
    private void displayDensity(GuiGraphicsExtractor g, int x, int y, int half) {
        // --- HUD position (local-only) ---
        Phos.label(g, font, "hud position (local · not shared in presets)", x, y);
        y += 14;
        cycle(g, x, y, half, "anchor: " + anchorLabel(config.hudAnchor), () -> {
            MidasflipConfig.HudAnchor[] vs = MidasflipConfig.HudAnchor.values();
            config.hudAnchor = vs[(config.hudAnchor.ordinal() + 1) % vs.length];
        });
        y += 26;
        contSlider(g, x, y, half, "x offset", 0, 200, 1, config.hudOffsetX,
                v -> v + "px", v -> config.hudOffsetX = (int) v);
        contSlider(g, x + half + 10, y, half, "y offset", 0, 200, 1, config.hudOffsetY,
                v -> v + "px", v -> config.hudOffsetY = (int) v);
        y += 30;

        // --- Table density ---
        Phos.label(g, font, "table density", x, y);
        y += 14;
        contSlider(g, x, y, half, "table rows", 5, 30, 1, config.shellTableRows,
                v -> String.valueOf(v), v -> config.shellTableRows = (int) v);
        contSlider(g, x + half + 10, y, half, "comps peek sales", 1, 10, 1, config.compsPeekSales,
                v -> String.valueOf(v), v -> config.compsPeekSales = (int) v);
        y += 30;
        contSlider(g, x, y, half, "hud underwater rows", 0, 10, 1, config.hudUnderwaterMaxRows,
                v -> String.valueOf(v), v -> config.hudUnderwaterMaxRows = (int) v);
        contSlider(g, x + half + 10, y, half, "hud undercut rows", 0, 10, 1, config.hudUndercutMaxRows,
                v -> String.valueOf(v), v -> config.hudUndercutMaxRows = (int) v);
        y += 30;

        // --- Coin & chat display ---
        cycle(g, x, y, half, "coin display: " + (config.rawCoinNumbers ? "raw" : "compact"), () -> {
            config.rawCoinNumbers = !config.rawCoinNumbers;
            dev.midasflip.client.ui.Phos.setRawCoins(config.rawCoinNumbers); // apply live (cycle skips normalize)
        });
        cycle(g, x + half + 10, y, half, "chat confirmations: " + (config.chatConfirmations ? "on" : "off"),
                () -> config.chatConfirmations = !config.chatConfirmations);
        y += 30;

        // --- Notification durations & margin-alert sound ---
        Phos.label(g, font, "notification durations", x, y);
        y += 14;
        contSlider(g, x, y, half, "margin banner", 1000, 15_000, 500, config.marginAlertBannerMs,
                v -> (v / 1000.0) + "s", v -> config.marginAlertBannerMs = (int) v);
        contSlider(g, x + half + 10, y, half, "purchase toast", 1000, 10_000, 500, config.purchaseToastMs,
                v -> (v / 1000.0) + "s", v -> config.purchaseToastMs = (int) v);
        y += 30;
        cycle(g, x, y, half, "margin alert sound: " + (config.marginAlertSound ? "on" : "off"),
                () -> config.marginAlertSound = !config.marginAlertSound);
        cycle(g, x + half + 10, y, half, "menu tab sound: " + (config.tabSound ? "on" : "off"),
                () -> config.tabSound = !config.tabSound);
        contSlider(g, x + half + 10, y, half, "alert volume", 0, 100, 5, Math.round(config.marginAlertVolume * 100),
                v -> v + "%", v -> config.marginAlertVolume = v / 100f);
        y += 30;
        // Undercut poll cadence (30s floor is a hard politeness clamp).
        contSlider(g, x, y, half, "undercut poll", 30, 300, 5, config.undercutPollSec,
                v -> v + "s", v -> config.undercutPollSec = (int) v);
        Phos.text(g, font, "§830s floor is hard-coded (politeness)§r", x + half + 10, y + 2, Phos.FAINT);
    }

    private static String anchorLabel(MidasflipConfig.HudAnchor a) {
        return switch (a) {
            case TOP_LEFT -> "top-left";
            case TOP_RIGHT -> "top-right";
            case BOTTOM_LEFT -> "bottom-left";
            case BOTTOM_RIGHT -> "bottom-right";
        };
    }

    private void safety(GuiGraphicsExtractor g, int x, int w, int mx, int my) {
        int y = 38;
        boolean assisted = config.safetyMode == MidasflipConfig.SafetyMode.ASSISTED;
        Phos.panel(g, x, y, w, 82);
        Phos.label(g, font, "mode", x + 8, y + 6);
        Phos.text(g, font, assisted ? "§6ASSISTED§r · one input sends /viewauction"
                : "§aSTRICT§r · one press copies, you paste", x + 8, y + 18, Phos.CREAM);
        Phos.text(g, font, "§8either way: one physical input = at most one action, never automated§r",
                x + 8, y + 31, Phos.FAINT);
        textButton(g, x + 8, y + 43, assisted ? "switch to STRICT" : "switch to ASSISTED", () -> {
            config.safetyMode = assisted ? MidasflipConfig.SafetyMode.STRICT : MidasflipConfig.SafetyMode.ASSISTED;
            config.save();
            refresh();
        });
        // The SECOND gate on recipe-leg sends, with its own row because it is
        // its own consent (spec §13/§14 amendment 2026-07-30). It shipped as a
        // config field with no control at all, while the blocked-send chat line
        // told people to come here and find it — so the only way to turn it on
        // was hand-editing midasflip.json, which is exactly the vector
        // normalize() treats as hostile (review 2026-08-03).
        Phos.text(g, font, config.craftLegSend
                        ? "§6recipe legs§r · a click sends /bz or /ahs for that ingredient"
                        : "§8recipe legs · off · a click copies the command instead§r",
                x + 8, y + 58, config.craftLegSend ? Phos.CREAM : Phos.FAINT);
        textButton(g, x + 8, y + 70,
                config.craftLegSend ? "turn recipe leg sends off" : "turn recipe leg sends on", () -> {
            config.craftLegSend = !config.craftLegSend;
            config.save();
            refresh();
        });
        y += 90;

        // Website account pairing is always recoverable from settings. Never
        // render the saved credential; this surface exposes only its presence
        // and opens the device-code flow for first pairing or replacement.
        Phos.panel(g, x, y, w, 48);
        Phos.label(g, font, "website account", x + 8, y + 6);
        boolean paired = config.apiToken != null && !config.apiToken.isEmpty();
        Phos.text(g, font, paired ? "§a● credential saved§r" : "§e● not connected§r",
                x + 8, y + 18, paired ? Phos.GREEN : Phos.YELLOW);
        textButton(g, x + 8, y + 33, paired ? "re-pair account" : "connect account", () ->
                Minecraft.getInstance().setScreen(
                        new MidasflipFirstRunScreen(this, config, api, feed)));
        y += 56;

        // Cooldown between command sends — user-tunable above the hard
        // 500ms floor (MIN_COOLDOWN_MS in the config stays authoritative:
        // effectiveCooldownMs() clamps whatever ends up in the file).
        contSlider(g, x, y, (w - 10) / 2, "cooldown", 500, 5000, 100, config.commandCooldownMs,
                v -> v + "ms", v -> config.commandCooldownMs = v);
        y += 22;
        Phos.text(g, font, "§8(500ms floor is hard-coded)§r", x, y, Phos.FAINT);
        y += 14;
        Phos.text(g, font, "§7audit log: every action recorded in config/midasflip-audit.log§r", x, y, Phos.DIM);
        y += 14;
        Phos.text(g, font, "§8midasflip is lower-risk by architecture, never \"guaranteed safe\"§r", x, y, Phos.FAINT);
    }

    private void about(GuiGraphicsExtractor g, int x, int w) {
        int y = 38;
        Phos.text(g, font, "§fMIDAS§6FLIP§r §7· auction intelligence for SkyBlock§r", x, y, Phos.CREAM);
        y += 16;
        Phos.text(g, font, "§7every flip shown with its work: comps, confidence, exits§r", x, y, Phos.DIM);
        y += 12;
        Phos.text(g, font, "§7open source · local-first ledger · human-executed§r", x, y, Phos.DIM);
        y += 20;
        // The first-run tour, on demand — it never reopens by itself.
        chipButton(g, x, y, "replay tour", () ->
                Minecraft.getInstance().setScreen(new TourScreen(this, config)));
        Phos.text(g, font, "§8five cards: the board, tooltips, filters, your ledger§r",
                x + Phos.w(font, "replay tour") + 20, y, Phos.FAINT);
        y += 22;
        Phos.text(g, font, "§8not affiliated with hypixel or mojang§r", x, y, Phos.FAINT);
    }

    // ---------- controls ----------

    private void textButton(GuiGraphicsExtractor g, int x, int y, String label, Runnable onClick) {
        Phos.text(g, font, "§7" + label + "§r", x, y, Phos.DIM);
        zone(x, y - 2, Phos.w(font, label) + 4, 12, onClick);
    }

    private void chipButton(GuiGraphicsExtractor g, int x, int y, String label, Runnable onClick) {
        int w = Phos.w(font, label) + 10;
        Phos.border(g, x, y - 3, w, 13, Phos.BORDER);
        Phos.text(g, font, label, x + 5, y, Phos.CREAM);
        zone(x, y - 3, w, 13, onClick);
    }

    private void cycle(GuiGraphicsExtractor g, int x, int y, int w, String label, Runnable onClick) {
        Phos.panel(g, x, y - 3, w, 17);
        Phos.text(g, font, label, x + 6, y + 1, Phos.CREAM);
        zone(x, y - 3, w, 17, () -> {
            onClick.run();
            config.save();
            refresh();
        });
    }

    private interface LongSink {
        void accept(long v);
    }

    private interface LongFmt {
        String format(long v);
    }

    private LongFmt coinsFmt() {
        return v -> v == 0 ? "off" : Phos.coins(v);
    }

    /** Continuous slider: the handle moves freely; the value maps linearly
     *  across [min,max] and snaps only to `round` (fine granularity), so a
     *  small drag is a small change — not a jump between fixed steps. */
    private void contSlider(GuiGraphicsExtractor g, int x, int y, int w, String label,
                            long min, long max, long round, long current, LongFmt fmt, LongSink sink) {
        double frac = (double) (current - min) / (max - min);
        Phos.text(g, font, "§7" + label + ": §f" + fmt.format(current) + "§r", x, y, Phos.DIM);
        Phos.bar(g, x, y + 11, w, 5, frac, Phos.ACCENT);
        // Handle marker so fine positions read clearly.
        int hx = x + (int) Math.round(Math.max(0, Math.min(1, frac)) * (w - 2));
        g.fill(hx, y + 8, hx + 2, y + 19, Phos.CREAM);
        dragZone(x, y + 6, w, 15, f -> {
            long raw = min + Math.round(f * (max - min));
            long snapped = Math.round(raw / (double) round) * round;
            sink.accept(Math.max(min, Math.min(max, snapped)));
            config.save();
        });
    }

    private void confSlider(GuiGraphicsExtractor g, int x, int y, int w) {
        String confLbl = config.minConfidence <= 0.355
                ? "off · any" : Math.round(config.minConfidence * 100) + "%";
        Phos.text(g, font, "§7min confidence: §f" + confLbl + "§r", x, y, Phos.DIM);
        Phos.bar(g, x, y + 11, w, 5, (config.minConfidence - 0.35) / 0.64, Phos.ACCENT);
        dragZone(x, y + 7, w, 13, frac -> {
            config.minConfidence = Math.round((0.35 + frac * 0.64) * 100) / 100.0;
            config.save();
        });
    }

    /** Honest placeholder: "loading…" only while we might still succeed;
     *  a clear tunnel hint once the API is definitively unreachable. */
    private void loadingOrDown(GuiGraphicsExtractor g, int x, int y, String what) {
        if (api.likelyDown()) {
            Phos.text(g, font, "§ccan't reach MidasFlip.§r", x, y, Phos.RED);
            // Public wording — the old dev-tunnel hint leaked internal
            // setup and meant nothing to real users.
            Phos.text(g, font, "§8check your connection · status at "
                    + MidasflipConfig.SITE_HOST + "§r", x, y + 11, Phos.FAINT);
        } else {
            Phos.text(g, font, "§8loading " + what + "…§r", x, y, Phos.FAINT);
        }
    }

    // ---------- theme pane ----------

    private static final int[][] THEME_PRESETS = {
            {0xD9944A, 0}, // amber (ships default)
            {0x45D48A, 0}, // mint / green
            {0x4AA3D9, 0}, // ocean
            {0xD94A6B, 0}, // crimson
            {0xB07CE8, 0}, // violet
    };
    private static final String[] THEME_NAMES = {"amber", "mint", "ocean", "crimson", "violet"};

    private void theme(GuiGraphicsExtractor g, int x, int w, int mx, int my) {
        int y = 38;
        Phos.label(g, font, "presets · one click recolors everything", x, y);
        y += 14;
        int bx = x;
        for (int i = 0; i < THEME_PRESETS.length; i++) {
            int col = 0xFF000000 | THEME_PRESETS[i][0];
            int bw = Phos.w(font, THEME_NAMES[i]) + 20;
            boolean cur = (config.accentColor & 0xFFFFFF) == THEME_PRESETS[i][0];
            Phos.border(g, bx, y - 2, bw, 14, cur ? Phos.CREAM : Phos.BORDER);
            g.fill(bx + 4, y + 1, bx + 10, y + 7, col); // swatch
            Phos.text(g, font, THEME_NAMES[i], bx + 13, y, cur ? Phos.CREAM : Phos.DIM);
            final int rgb = THEME_PRESETS[i][0];
            zone(bx, y - 2, bw, 14, () -> {
                config.accentColor = rgb;
                Phos.applyAccent(rgb);
                config.save();
                flash("theme: " + THEME_NAMES[(indexOf(rgb))]);
                refresh();
            });
            bx += bw + 6;
        }
        y += 26;

        Phos.label(g, font, "custom accent · drag to mix", x, y);
        y += 14;
        int r = (config.accentColor >> 16) & 0xFF;
        int gg = (config.accentColor >> 8) & 0xFF;
        int b = config.accentColor & 0xFF;
        int half = (w - 10) / 2;
        channel(g, x, y, half, "R", r, v -> setAccent((int) v, gg, b));
        y += 22;
        channel(g, x, y, half, "G", gg, v -> setAccent(r, (int) v, b));
        y += 22;
        channel(g, x, y, half, "B", b, v -> setAccent(r, gg, (int) v));
        y += 26;

        // Live preview block in the current accent.
        Phos.panel(g, x, y, half, 46);
        Phos.text(g, font, "preview", x + 8, y + 6, Phos.FAINT);
        g.fill(x + 8, y + 18, x + 8 + 40, y + 24, Phos.ACCENT);
        Phos.chip(g, font, "BUY", x + 56, y + 19, Phos.GREEN);
        Phos.chip(g, font, "WATCH", x + 86, y + 19, Phos.YELLOW);
        Phos.text(g, font, "§7Hyperion §f830M §8→§r §a+42M§r", x + 8, y + 32, Phos.DIM);
        Phos.text(g, font, String.format("§8#%06X§r", config.accentColor & 0xFFFFFF), x + half - 52, y + 6, Phos.FAINT);
    }

    private void setAccent(int r, int gg, int b) {
        config.accentColor = ((r & 0xFF) << 16) | ((gg & 0xFF) << 8) | (b & 0xFF);
        Phos.applyAccent(config.accentColor);
        config.save();
    }

    private void channel(GuiGraphicsExtractor g, int x, int y, int w, String label, int val, LongSink sink) {
        Phos.text(g, font, "§7" + label + " §f" + val + "§r", x, y, Phos.DIM);
        Phos.bar(g, x, y + 11, w, 5, val / 255.0, Phos.ACCENT);
        int hx = x + (int) Math.round(val / 255.0 * (w - 2));
        g.fill(hx, y + 8, hx + 2, y + 19, Phos.CREAM);
        dragZone(x, y + 6, w, 15, f -> sink.accept(Math.round(f * 255)));
    }

    private static int indexOf(int rgb) {
        for (int i = 0; i < THEME_PRESETS.length; i++) {
            if (THEME_PRESETS[i][0] == rgb) {
                return i;
            }
        }
        return 0;
    }

    private static <T> T next(List<T> list, T cur) {
        int i = list.indexOf(cur);
        return list.get((i + 1) % list.size());
    }

    private String eventChip() {
        JsonElement el = api.get("/events/upcoming?days=1", 10 * 60_000);
        if (el == null || !el.isJsonObject()) {
            return "";
        }
        JsonArray ws = el.getAsJsonObject().getAsJsonArray("windows");
        if (ws == null || ws.isEmpty()) {
            return "";
        }
        JsonObject wnd = ws.get(0).getAsJsonObject();
        String name = wnd.get("name").getAsString();
        if (wnd.get("active").getAsBoolean()) {
            return "§e◆ " + shorten(name, 22) + " · active§r";
        }
        long mins = (Instant.parse(wnd.get("starts").getAsString()).toEpochMilli()
                - System.currentTimeMillis()) / 60_000;
        return mins <= 240 ? "§8◇ " + shorten(name, 22) + " in "
                + (mins >= 60 ? (mins / 60) + "h" + mins % 60 + "m" : mins + "m") + "§r" : "";
    }

    private static String holdStr(Flip f) {
        if (f.holdMedS == null) {
            // hold is a FREE board column (owner 2026-08-06) — the tour
            // teaches it as one. Absent means we could not measure it, not
            // that it costs money.
            return GoldFields.unknown("hold");
        }
        // Honest range: fair-cohort median with the p90 slow tail — a
        // point estimate is exceeded half the time by construction.
        String med = compactDur(f.holdMedS);
        if (f.holdP90S == null) {
            return "~" + med + " · " + GoldFields.locked("slow case");
        }
        if (f.holdP90S != null && f.holdP90S > f.holdMedS * 2) {
            return "~" + med + "§8–" + compactDur(f.holdP90S) + "§r";
        }
        return "~" + med;
    }

    private static String compactDur(long s) {
        long m = s / 60;
        return m >= 60 ? (m / 60) + "h" + (m % 60 > 0 ? (m % 60) + "m" : "") : m + "m";
    }

    private static String durStr(long s) {
        return s >= 86400 ? String.format(Locale.ROOT, "%.1fd", s / 86400.0)
                : s >= 3600 ? (s / 3600) + "h" : (s / 60) + "m";
    }

    private static String shorten(String s, int n) {
        return s == null ? "?" : s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }
}
