package dev.midasflip.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Closing a position when chat reports the sale.
 *
 * <p>The chat line carries the item's DISPLAY name; a position stores a
 * SkyBlock id. `onSold` compared the two after uppercasing, which silently
 * failed for every reforged, starred or possessive item, so the position never
 * left open/listed: lifetime P&amp;L undercounted, the HUD kept warning
 * "underwater" about something already sold, and onListed's
 * newest-open-position heuristic could mark the stale row listed instead of
 * the real one. The BUY side was fixed on 2026-08-06 and this side was missed,
 * because nothing tested it (review 2026-08-10).
 *
 * <p>These assert on the normalizer pair `onSold` now uses, which is the same
 * one `SessionTracker.sameItem` uses. Driving onSold itself would need a
 * Fabric config dir for the ledger file.
 */
class SoldAttributionTest {

    /** What onSold compares: the chat name, both forms. */
    private static boolean matches(String chatName, String itemId) {
        String want = SessionTracker.norm(chatName);
        String wantBase = SessionTracker.normDereforged(chatName);
        String ours = SessionTracker.norm(NameMap.pretty(itemId, null));
        return !ours.isEmpty() && (ours.equals(want) || ours.equals(wantBase));
    }

    /** What it compared BEFORE: id vs display name, uppercased. */
    private static boolean oldMatched(String chatName, String itemId) {
        return norm(chatName).equals(norm(itemId));
    }

    private static String norm(String s) {
        return s == null ? "" : s.toUpperCase(java.util.Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    @Test
    void aReforgedSaleClosesItsPosition() {
        // The owner's own item. Sold as "Ancient Magma Lord Leggings"; the
        // position stores MAGMA_LORD_LEGGINGS.
        assertEquals(true, matches("Ancient Magma Lord Leggings ✪✪✪", "MAGMA_LORD_LEGGINGS"));
        assertEquals(false, oldMatched("Ancient Magma Lord Leggings ✪✪✪", "MAGMA_LORD_LEGGINGS"),
                "this is what silently failed before");
    }

    @Test
    void aPlainSaleStillCloses() {
        assertEquals(true, matches("Ink Wand", "INK_WAND"));
    }

    @Test
    void starsAndStackPrefixesDoNotBlockTheMatch() {
        assertEquals(true, matches("Ancient Magma Lord Leggings ✪✪✪✪✪", "MAGMA_LORD_LEGGINGS"));
        assertEquals(true, matches("64x Enchanted Diamond", "ENCHANTED_DIAMOND"));
    }

    @Test
    void aDifferentItemDoesNotCloseThePosition() {
        // The failure that matters more than a miss: closing the WRONG
        // position writes a sale price onto an item you still hold.
        assertEquals(false, matches("Hyperion", "MAGMA_LORD_LEGGINGS"));
        assertEquals(false, matches("Superior Dragon Boots", "WISE_DRAGON_BOOTS"));
    }

    @Test
    void theTwoDragonSetsStayDistinct() {
        assertEquals(true, matches("Wise Dragon Boots", "WISE_DRAGON_BOOTS"));
        assertEquals(false, matches("Wise Dragon Boots", "SUPERIOR_DRAGON_BOOTS"));
    }

    @Test
    void theNewFormIsStrictlyBetterThanTheOld() {
        // Every case the old form got right, the new one still gets right.
        for (String[] c : new String[][]{
                {"Ink Wand", "INK_WAND"},
                {"Enchanted Diamond", "ENCHANTED_DIAMOND"},
                {"Hyperion", "HYPERION"}}) {
            if (oldMatched(c[0], c[1])) {
                assertEquals(true, matches(c[0], c[1]), c[0] + " regressed");
            }
        }
        // And it fixes at least one the old form got wrong.
        assertNotEquals(oldMatched("Ancient Magma Lord Leggings", "MAGMA_LORD_LEGGINGS"),
                matches("Ancient Magma Lord Leggings", "MAGMA_LORD_LEGGINGS"));
    }
}
