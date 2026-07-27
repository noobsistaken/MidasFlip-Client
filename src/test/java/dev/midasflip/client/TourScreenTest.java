package dev.midasflip.client;

import dev.midasflip.client.TourScreen.Tour;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The first-run tour's pure core: the card deck and the seen-version gate.
 * Nothing here touches Minecraft — {@code TourScreen.Tour} is a plain
 * nested holder precisely so the copy can be linted without booting a game
 * (same arrangement as PetInfoText).
 *
 * <p>The copy assertions are not decoration. Safety wording is product law
 * (spec §13): the mod is "lower-risk", never "safe" and never "guaranteed",
 * and no surface may imply the mod plays for the user. A tour is the first
 * thing a new user reads, so it is the worst place to break that.
 */
class TourScreenTest {

    /** Fits the drawn card panel (352px usable (380px panel - 28px padding) at ~6px/char). */
    private static final int MAX_LINE = 56;

    /** Marketing register the product does not use. */
    private static final List<String> BANNED_WORDS = List.of(
            "unlock", "unleash", "seamless", "effortless", "revolutionary",
            "game-changing", "supercharge", "instantly", "guarantee", "safe",
            "automatic", "automated", "auto-buy", "risk-free", "passive income");

    private static String deckText() {
        StringBuilder sb = new StringBuilder();
        for (Tour.Card c : Tour.CARDS) {
            for (String line : Tour.lines(c)) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    @Test
    void deckIsFiveCardsInSpecOrder() {
        assertEquals(5, Tour.CARDS.size());
        assertEquals(List.of("What this is", "The board", "Hover anything",
                        "Filters are yours", "Your ledger"),
                Tour.CARDS.stream().map(Tour.Card::title).toList());
    }

    @Test
    void everyCardIsComplete() {
        for (Tour.Card c : Tour.CARDS) {
            assertFalse(c.title().isBlank(), "blank title");
            assertFalse(c.lead().isBlank(), "blank lead on " + c.title());
            assertFalse(c.foot().isBlank(), "blank foot on " + c.title());
            assertTrue(c.body().size() >= 3 && c.body().size() <= 5,
                    "body of " + c.title() + " must stay inside the panel");
            for (String line : Tour.lines(c)) {
                assertFalse(line.isBlank(), "blank line on " + c.title());
                assertTrue(line.length() <= MAX_LINE,
                        "line overflows the card: " + line);
                // § codes are applied by the renderer, not baked into copy.
                assertFalse(line.contains("§"), "raw colour code in copy: " + line);
            }
        }
    }

    @Test
    void deckIsImmutable() {
        assertThrows(UnsupportedOperationException.class,
                () -> Tour.CARDS.add(null));
        assertThrows(UnsupportedOperationException.class,
                () -> Tour.CARDS.get(0).body().add("nope"));
    }

    @Test
    void copyLawSafetyWording() {
        String text = deckText();
        assertTrue(text.contains("lower-risk"), "the safety posture is never stated");
        for (String banned : BANNED_WORDS) {
            assertFalse(text.contains(banned), "banned copy word present: " + banned);
        }
    }

    @Test
    void copyLawNeverClaimsTheModActs() {
        String text = deckText();
        // The one input promise, in the user's own words, on card one.
        assertTrue(text.contains("you click"), "the you-click promise is missing");
        assertTrue(text.contains("never plays for you"), "the never-acts promise is missing");
        assertFalse(text.contains("for you automatically"));
        assertFalse(text.contains("buys for you"));
        assertFalse(text.contains("clicks for you"));
    }

    @Test
    void copyLawShortSentences() {
        for (Tour.Card c : Tour.CARDS) {
            for (String line : Tour.lines(c)) {
                for (String sentence : line.split("(?<=[.!?])\\s+")) {
                    assertTrue(sentence.split("\\s+").length <= 12,
                            "sentence runs long: " + sentence);
                }
            }
        }
    }

    @Test
    void seenGateShowsOnceAndNeverAgain() {
        assertTrue(Tour.shouldOpen(0), "a fresh install must see the tour");
        assertTrue(Tour.shouldOpen(-1), "a corrupt negative must fail open, not skip");
        assertFalse(Tour.shouldOpen(Tour.VERSION), "finished/skipped must not reopen");
        assertFalse(Tour.shouldOpen(Tour.VERSION + 1), "a newer marker must not reopen");
    }

    @Test
    void configClampsSeenVersionToTheTourThatExists() {
        MidasflipConfig cfg = new MidasflipConfig();
        assertEquals(0, cfg.tourSeenVersion, "default must show the tour once");

        cfg.tourSeenVersion = 999; // hand-edited or imported junk
        cfg.normalize();
        assertEquals(Tour.VERSION, cfg.tourSeenVersion);
        assertFalse(Tour.shouldOpen(cfg.tourSeenVersion));

        cfg.tourSeenVersion = -5;
        cfg.normalize();
        assertEquals(0, cfg.tourSeenVersion);
        assertTrue(Tour.shouldOpen(cfg.tourSeenVersion));
    }
}
