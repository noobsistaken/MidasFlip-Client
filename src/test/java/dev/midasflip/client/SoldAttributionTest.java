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

    // ---- the double-book, reproduced ------------------------------------
    // Two open positions of one item in different BUCKETS. The chat line
    // carries no bucket, so onSold used to pick the older row, write a false
    // basis onto it, and leave the real position open. reconcileSold then
    // matched the server's sale by comp_key, did not recognise the row chat
    // had closed, and closed the real one too: ONE sale, TWO closed
    // positions. Measured 2026-08-10 as a clean 100M and a 5-star 480M
    // Hyperion sold once for 500M -> ledger reported 2 trades, +420M, for a
    // real +20M.

    /** Mirrors onSold's ambiguity test: does this chat name map to more than
     *  one open bucket? */
    private static boolean ambiguous(String chatName, String... compKeys) {
        String want = SessionTracker.norm(chatName);
        String wantBase = SessionTracker.normDereforged(chatName);
        String seen = null;
        boolean amb = false;
        for (String k : compKeys) {
            String ours = SessionTracker.norm(NameMap.pretty("HYPERION", k));
            if (ours.isEmpty() || !(ours.equals(want) || ours.equals(wantBase))) {
                continue;
            }
            if (seen == null) {
                seen = k;
            } else if (!seen.equals(k)) {
                amb = true;
            }
        }
        return amb;
    }

    @Test
    void twoBucketsOfOneItemAreAmbiguousAndMustNotBeGuessed() {
        assertEquals(true, ambiguous("Hyperion ✪✪✪✪✪",
                "v1|HYPERION|s0|r0", "v1|HYPERION|s5|r1"),
                "a clean and a starred Hyperion are indistinguishable from the chat name");
    }

    @Test
    void oneBucketIsNotAmbiguous() {
        assertEquals(false, ambiguous("Hyperion", "v1|HYPERION|s0|r0"));
        // Two rows in the SAME bucket are fine: reconcileSold absorbs them by
        // key, so closing either is correct.
        assertEquals(false, ambiguous("Hyperion",
                "v1|HYPERION|s0|r0", "v1|HYPERION|s0|r0"));
    }

    @Test
    void starsCollapseInTheChatName_whichIsWhyTheGuardIsNeeded() {
        // The root cause, pinned: norm() deliberately strips trailing stars,
        // so the chat line for a 5-star item is identical to the clean one.
        assertEquals(SessionTracker.norm("Hyperion"),
                SessionTracker.norm("Hyperion ✪✪✪✪✪"));
    }
}