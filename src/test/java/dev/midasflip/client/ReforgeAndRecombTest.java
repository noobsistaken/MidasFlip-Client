package dev.midasflip.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the two defects behind "the sell panel says list at 15.5M while the
 * tooltip says 35.0M on the same bow" (owner, 2026-08-11). Neither had any
 * test, which is how both survived to a public build.
 */
class ReforgeAndRecombTest {

    // --- recombobulation read from lore -------------------------------------

    @Test
    @DisplayName("inventory: rarity line last, no glyph -> not recombed")
    void inventoryPlain() {
        assertFalse(SellOverlay.recombedFromLoreLines(List.of(
                "Shortbow: Instantly shoots!",
                "",
                "LEGENDARY DUNGEON BOW")));
    }

    @Test
    @DisplayName("inventory: glyph before the tier word -> recombed")
    void inventoryRecombed() {
        assertTrue(SellOverlay.recombedFromLoreLines(List.of(
                "Shortbow: Instantly shoots!",
                "",
                "⚚ MYTHIC DUNGEON BOW")));
    }

    /**
     * The actual bug. Every auction view appends seller/price/ends-in/click
     * lines BELOW the rarity line, so the old code — which gave up as soon as
     * the bottom-most non-empty line was not a rarity line — reported "not
     * recombed" for everything in a menu, recombed or not. That is what made
     * the sell overlay ask for recomb=false on a genuinely recombed bow.
     */
    @Test
    @DisplayName("auction view: recombed is still found under the seller lines")
    void auctionViewRecombedFoundBelowExtraLines() {
        assertTrue(SellOverlay.recombedFromLoreLines(List.of(
                "Shortbow: Instantly shoots!",
                "",
                "⚚ MYTHIC DUNGEON BOW",
                "────────",
                "Seller: [MVP+] Fuessel",
                "Buy it now: 11,790,000 coins",
                "",
                "Ends in: 13d",
                "",
                "Click to inspect!")));
    }

    @Test
    @DisplayName("auction view: a plain item under the same lines stays not-recombed")
    void auctionViewPlainStaysFalse() {
        assertFalse(SellOverlay.recombedFromLoreLines(List.of(
                "EPIC SWORD",
                "────────",
                "Seller: B0raZ",
                "Buy it now: 9,700,000 coins",
                "Ends in: 18h",
                "Click to inspect!")));
    }

    @Test
    @DisplayName("colour codes and blank lines are ignored")
    void stripsFormattingAndBlanks() {
        assertTrue(SellOverlay.recombedFromLoreLines(List.of(
                "§7Some ability text",
                "   ",
                "§d⚚ §dMYTHIC SWORD",
                "",
                "§7Click to inspect!")));
    }

    @Test
    @DisplayName("no rarity line at all -> not recombed, and no exception")
    void noRarityLine() {
        assertFalse(SellOverlay.recombedFromLoreLines(List.of("Just", "some", "text")));
        assertFalse(SellOverlay.recombedFromLoreLines(List.of()));
    }

    // --- reforge-tolerant ledger matching ------------------------------------

    @Test
    @DisplayName("a reforge word is dropped so the ledger row matches")
    void stripsReforgeForMatching() {
        assertEquals("Juju Shortbow", SellOverlay.stripReforge("Juju Shortbow"));
        assertEquals("Juju Shortbow", SellOverlay.stripReforge("Hasty Juju Shortbow"));
        assertEquals("Aspect of the Void", SellOverlay.stripReforge("Heroic Aspect of the Void"));
    }

    /**
     * The guard that keeps the fix safe: an item whose REAL name begins with a
     * reforge word must still match itself rather than collapsing onto its
     * base. "Strong Dragon Helmet" is an item; "Strong" is also a reforge.
     */
    @Test
    @DisplayName("reforge-named items still match themselves")
    void reforgeNamedItemsAreNotCollapsed() {
        String want = SellOverlay.norm("Strong Dragon Helmet");
        String wantBase = SellOverlay.norm(SellOverlay.stripReforge("Strong Dragon Helmet"));
        // exact comparison must win before the stripped one is consulted
        assertEquals(SellOverlay.norm("Strong Dragon Helmet"), want);
        assertEquals(SellOverlay.norm("Dragon Helmet"), wantBase);
        assertTrue(!want.equals(wantBase), "the two forms must differ, or the test proves nothing");
    }

    @Test
    @DisplayName("stars in the display name do not defeat the name match")
    void starsDoNotBreakMatching() {
        assertEquals(SellOverlay.norm("Juju Shortbow"),
                SellOverlay.norm(SellOverlay.stripReforge("Hasty Juju Shortbow ✪✪✪✪✪")));
    }
}
