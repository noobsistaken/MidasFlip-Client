package dev.midasflip.client.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The "warm phosphor" draw kit — palette and primitives lifted from the
 * owner's Claude Design mockups (docs/design/MidasFlip In-Game UI.dc.html).
 * Pure drawing: no widgets, no input; screens compose these and do their
 * own hit-testing (PhosScreen).
 */
public final class Phos {
    private Phos() {}

    // Palette (mockup CSS, alpha-extended). Mutable so the Theme tab can
    // recolor the whole UI live; defaults = the shipped "amber" theme.
    // Single-player client, one draw thread — global statics are fine.
    public static int BG       = 0xF806090E; // page background
    public static int PANEL    = 0xF80D1117; // card background
    public static int PANEL_HI = 0xF8131A24; // hovered card
    public static int BORDER   = 0xFF21262D;
    public static int CREAM    = 0xFFEBDBB2; // primary text
    public static int DIM      = 0xFFA89984; // secondary text
    public static int FAINT    = 0xFF5F6E85; // labels
    public static int ACCENT   = 0xFFD9944A; // the themeable accent
    public static int GREEN    = 0xFF45D48A;  // BUY verdict (semantic)
    public static int RED      = 0xFFE36565;  // loss/cancel (semantic)
    public static int YELLOW   = 0xFFE5B454;  // WATCH verdict (semantic)
    public static int SEL_BG   = 0xFF1A2230;  // selected nav item

    /** Back-compat alias for the accent. */
    public static int accent() {
        return ACCENT;
    }

    /** Recolor from an ARGB accent (opaque). Verdict colors stay semantic;
     *  the accent + selection tint follow the chosen color. */
    public static void applyAccent(int rgb) {
        ACCENT = 0xFF000000 | (rgb & 0xFFFFFF);
        // Selection tint = accent at low alpha over the panel.
        SEL_BG = 0x33000000 | (rgb & 0xFFFFFF);
    }

    public static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, PANEL);
        border(g, x, y, w, h, BORDER);
    }

    public static void border(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    public static void hline(GuiGraphicsExtractor g, int x, int y, int w) {
        g.fill(x, y, x + w, y + 1, BORDER);
    }

    /** Vanilla Minecraft font (reverted from bundled JetBrains Mono per
     *  owner preference 2026-07-05 — it reads cleaner in-game). */
    public static int w(Font f, String s) {
        return f.width(s.replaceAll("§.", ""));
    }

    public static void text(GuiGraphicsExtractor g, Font f, String s, int x, int y, int color) {
        g.text(f, s, x, y, color, false);
    }

    /** Shadowed variant for HUD text drawn over the world. */
    public static void textShadow(GuiGraphicsExtractor g, Font f, String s, int x, int y, int color) {
        g.text(f, s, x, y, color, true);
    }

    public static void label(GuiGraphicsExtractor g, Font f, String s, int x, int y) {
        text(g, f, s.toUpperCase(java.util.Locale.ROOT), x, y, FAINT);
    }

    /** Verdict chip: bordered tag in the tier color. */
    public static void chip(GuiGraphicsExtractor g, Font f, String s, int x, int y, int color) {
        int w = w(f, s) + 8;
        border(g, x, y - 2, w, 12, color);
        text(g, f, s, x + 4, y, color);
    }

    /** Filled progress/slider track. frac 0..1. */
    public static void bar(GuiGraphicsExtractor g, int x, int y, int w, int h, double frac, int color) {
        g.fill(x, y, x + w, y + h, 0xFF161D28);
        int fw = (int) Math.round(w * Math.max(0, Math.min(1, frac)));
        if (fw > 0) {
            g.fill(x, y, x + fw, y + h, color);
        }
    }

    /** Coin display mode (config-completeness 2026-07-13). false = the
     *  canonical compact form; true = grouped integers ("83,421,556").
     *  Set from {@code MidasflipConfig.normalize()} like the accent — one
     *  draw thread, single-player, so a static is fine. */
    public static boolean rawCoins = false;

    public static void setRawCoins(boolean raw) {
        rawCoins = raw;
    }

    public static String coins(double v) {
        if (rawCoins) {
            return String.format(java.util.Locale.ROOT, "%,d", (long) v);
        }
        double a = Math.abs(v);
        String s;
        if (a >= 1e9) s = String.format("%.2fB", v / 1e9);
        else if (a >= 1e6) s = String.format("%.1fM", v / 1e6);
        else if (a >= 1e3) s = String.format("%.0fk", v / 1e3);
        else s = String.valueOf((long) v);
        return s;
    }

    /** Verdict cut with user-tunable thresholds (config-completeness
     *  2026-07-13): GREEN when conf ≥ confPct% AND comps ≥ minComps, else
     *  YELLOW (AMBER). Display-only — RED never reaches the stream by
     *  construction, so it is not represented here. */
    public static int tierColor(double conf, int comps, int confPct, int minComps) {
        return (conf * 100 >= confPct && comps >= minComps) ? GREEN : YELLOW;
    }
}
