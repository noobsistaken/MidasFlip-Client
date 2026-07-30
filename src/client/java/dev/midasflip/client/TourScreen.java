package dev.midasflip.client;

import dev.midasflip.client.ui.Phos;
import dev.midasflip.client.ui.PhosScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * The first-run tour (docs/plans/recovery-finder-and-onboarding.md §B):
 * five cards that explain what the mod is, what a board row means, the
 * tooltip, the two filter pages, and the local ledger.
 *
 * <p>Design rules from the plan, all load-bearing:
 * <ul>
 *   <li>It never blocks — "skip tour" sits on every card, and finishing
 *       or skipping both record {@link Tour#VERSION} in the config, so it
 *       does not reopen. A future major version bumps VERSION to offer a
 *       short "what's new" instead.</li>
 *   <li>It stays reachable from About → "replay tour". Discoverable, not
 *       forced.</li>
 *   <li>Zero gameplay interaction. This is a {@link PhosScreen}: it draws
 *       and it reads clicks on its own zones. No packets, no synthesized
 *       input, no sounds.</li>
 * </ul>
 *
 * <p>The card deck lives in the nested {@link Tour} holder on purpose.
 * {@code TourScreen} itself cannot load without Minecraft (it extends
 * Screen), but {@code TourScreen$Tour} is an ordinary class with an
 * Object superclass — the unit tests reference the deck and the seen-
 * version gate without booting the game, exactly like PetInfoText.
 */
public final class TourScreen extends PhosScreen {

    /** Pure, Minecraft-free tour model: the deck and the seen gate. */
    public static final class Tour {
        private Tour() {}

        /**
         * Bump when the deck changes enough to be worth re-showing.
         * A config that has already seen this version never sees it again.
         */
        // Bumped to 2 (2026-07-30): card 1 understated the send surface,
        // naming only /viewauction. A corrected deck nobody is re-shown
        // is not a disclosure, so existing users see it once more.
        public static final int VERSION = 2;

        /** One card: a title, an opening line, the body, and a closing note. */
        public record Card(String title, String lead, List<String> body, String foot) {}

        public static final List<Card> CARDS = List.of(
                new Card("What this is",
                        "We read the auction house and price items from sales.",
                        List.of(
                                "Every number shows its work: comps, confidence, exit.",
                                "It finds flips. You click. The mod never plays for you.",
                                "[J] opens this menu. [K] toggles the on-screen board.",
                                "[O] opens the best flip. STRICT copies the command,",
                                "ASSISTED sends /viewauction, or /bz from a recipe leg."),
                        "This is lower-risk by architecture, not a promise."),

                new Card("The board",
                        "One row is one flip. Read it left to right.",
                        List.of(
                                "Buy is the live listing. Sell is our careful estimate.",
                                "Profit is net of auction fees.",
                                "CONF is how much the past sales agree, with their count.",
                                "LIQ is sales per day. HOLD is how long the median took.",
                                "BUY means thick numbers. WATCH means thin ones."),
                        "Hold TAB over a row to see the sales behind the price."),

                new Card("Hover anything",
                        "Point at an item. The tooltip prices it where it sits.",
                        List.of(
                                "You get the estimate, confidence and comp count.",
                                "The lowest-BIN line shows the floor and the next one up.",
                                "It also says how many sellers are sitting at that floor.",
                                "Inventory, chests, the auction house: the same tooltip."),
                        "Tooltips are free, with no daily cap."),

                new Card("Filters are yours",
                        "Filters has two pages. They do different jobs.",
                        List.of(
                                "thresholds decide what the finder may surface at all.",
                                "what's shown hides rows that already qualified.",
                                "Presets swap every threshold. Start with default.",
                                "Share codes carry your setup to another account.",
                                "Read a code before you import it. It is plain data."),
                        "Per-family overrides live under the same tab."),

                new Card("Your ledger",
                        "Buys are read from chat and stored on this machine.",
                        List.of(
                                "Open positions are re-priced while you hold them.",
                                "A position under its cost turns red. Selling is yours.",
                                "Trades shows the model's exit next to what you got.",
                                "The ledger is local. The site shows public sales."),
                        "Nothing moves coins. Each buy and sell is your click.")
        );

        /** True while this install has not finished or skipped this tour. */
        public static boolean shouldOpen(int seenVersion) {
            return seenVersion < VERSION;
        }

        /** Every line of a card, in draw order — used by the copy tests. */
        public static List<String> lines(Card c) {
            return java.util.stream.Stream.concat(
                    java.util.stream.Stream.of(c.title(), c.lead()),
                    java.util.stream.Stream.concat(c.body().stream(),
                            java.util.stream.Stream.of(c.foot()))).toList();
        }
    }

    private final Screen parent;
    private final MidasflipConfig config;
    private int index;

    /** @param parent screen to return to (null on first run = back to the game). */
    public TourScreen(Screen parent, MidasflipConfig config) {
        super(Component.literal("MidasFlip · tour"));
        this.parent = parent;
        this.config = config;
    }

    @Override
    protected void drawPhos(GuiGraphicsExtractor g, int mx, int my, float delta) {
        Tour.Card card = Tour.CARDS.get(Math.min(Math.max(index, 0), Tour.CARDS.size() - 1));
        int pw = Math.min(width - 40, 380);
        int ph = 180;
        int px = (width - pw) / 2;
        int py = (height - ph) / 2;
        Phos.panel(g, px, py, pw, ph);

        int cx = px + 14;
        int y = py + 14;
        // Brand mark: FLIP rides the themeable accent (same handling as the
        // shell sidebar). No § codes here — they override the color param.
        Phos.text(g, font, "MIDAS", cx, y, Phos.CREAM);
        Phos.text(g, font, "FLIP", cx + font.width("MIDAS"), y, Phos.ACCENT);
        String step = "tour · " + (index + 1) + " of " + Tour.CARDS.size();
        Phos.text(g, font, step, px + pw - 14 - Phos.w(font, step), y, Phos.FAINT);
        y += 16;
        Phos.hline(g, cx, y, pw - 28);
        y += 12;

        Phos.text(g, font, card.title(), cx, y, Phos.CREAM);
        y += 14;
        Phos.text(g, font, "§7" + card.lead() + "§r", cx, y, Phos.DIM);
        y += 16;
        for (String line : card.body()) {
            Phos.text(g, font, "§7" + line + "§r", cx, y, Phos.DIM);
            y += 12;
        }
        Phos.text(g, font, "§8" + card.foot() + "§r", cx, py + ph - 42, Phos.FAINT);

        // Footer: skip is present on every card, never buried behind a step.
        int by = py + ph - 20;
        textButton(g, cx, by, "skip tour", this::onClose);
        boolean last = index >= Tour.CARDS.size() - 1;
        String nextLabel = last ? "done" : "next →";
        int nextW = Phos.w(font, nextLabel) + 10;
        int nextX = px + pw - 14 - nextW;
        chipButton(g, nextX, by, nextLabel, this::next);
        if (index > 0) {
            textButton(g, nextX - Phos.w(font, "back") - 16, by, "back", this::back);
        }
    }

    private void next() {
        if (index < Tour.CARDS.size() - 1) {
            index++;
        } else {
            onClose();
        }
    }

    private void back() {
        if (index > 0) {
            index--;
        }
    }

    /** Skipping and finishing are the same promise: don't ask again. */
    @Override
    public void onClose() {
        if (config.tourSeenVersion < Tour.VERSION) {
            config.tourSeenVersion = Tour.VERSION;
            // If the write fails the promise cannot be kept, so don't pretend
            // it was: roll the field back so the in-memory state matches the
            // disk. The tour reappears next launch, which is the honest
            // outcome of an unwritable config dir.
            if (!config.save()) {
                config.tourSeenVersion = 0;
            }
        }
        Minecraft.getInstance().setScreen(parent);
    }

    // ---------- controls (same idiom as the shell's helpers) ----------

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
}
