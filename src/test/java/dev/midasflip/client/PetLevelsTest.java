package dev.midasflip.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards Hypixel's pet EXP ladder and the bucket it implies.
 *
 * <p>The point of the first block is that the 119-number cost array cannot be
 * mistyped without a test going red. Six independently published level-100
 * totals, plus a level cost read off a live screenshot, all have to fall out
 * of the same array — so one wrong digit anywhere breaks several assertions at
 * once instead of silently mispricing a pet.
 *
 * <p>Incident behind it: a Lvl 88 legendary Wolf priced from bucket x1 at 5.0M
 * when its real x2 value was 16.9M (owner, 2026-08-14). The old heuristic was
 * tier-blind and its boundaries were simply wrong.
 */
class PetLevelsTest {

    // ---- the array reproduces every published total -------------------------

    @Test
    @DisplayName("published level-100 totals, all six rarities")
    void publishedTotals() {
        assertEquals(5_624_785L, Pets.cumulativeExp("COMMON", 100, "WOLF"));
        assertEquals(8_644_220L, Pets.cumulativeExp("UNCOMMON", 100, "WOLF"));
        assertEquals(12_626_665L, Pets.cumulativeExp("RARE", 100, "WOLF"));
        assertEquals(18_608_500L, Pets.cumulativeExp("EPIC", 100, "WOLF"));
        assertEquals(25_353_230L, Pets.cumulativeExp("LEGENDARY", 100, "WOLF"));
        // MYTHIC shares LEGENDARY's offset — same ladder, same total.
        assertEquals(25_353_230L, Pets.cumulativeExp("MYTHIC", 100, "WOLF"));
    }

    @Test
    @DisplayName("live anchor: the owner's Lvl 88 Wolf")
    void ownerWolfAnchor() {
        // Lore read "Progress to Level 89: 41%  324,577.4/791.7k", so the
        // single level 88->89 costs 791,700 for a legendary.
        long at88 = Pets.cumulativeExp("LEGENDARY", 88, "WOLF");
        long at89 = Pets.cumulativeExp("LEGENDARY", 89, "WOLF");
        assertEquals(791_700L, at89 - at88, "cost of legendary 88->89");
        assertEquals(10_032_830L, at88);

        // The pet's true total, and the bucket that follows from it.
        double trueTotal = at88 + 324_577.4;
        assertEquals(10_357_407.4, trueTotal, 0.01);
        assertEquals("x2", Pets.bucketOf((long) trueTotal));
    }

    @Test
    @DisplayName("level 1 has cost nothing yet")
    void levelOneIsZero() {
        assertEquals(0L, Pets.cumulativeExp("LEGENDARY", 1, "WOLF"));
    }

    // ---- the bug this replaces ---------------------------------------------

    @Test
    @DisplayName("THE INCIDENT: Lvl 88 legendary resolves x2, not x1")
    void lvl88LegendaryIsX2() {
        assertEquals("x2", Pets.bucketFromLevel("LEGENDARY", 88, "WOLF"));
    }

    @Test
    @DisplayName("tier decides the bucket at the same level")
    void tierIsNotIgnorable() {
        // The old heuristic returned x1 for level 88 regardless of rarity.
        assertEquals("x2", Pets.bucketFromLevel("LEGENDARY", 88, "WOLF"));
        assertEquals("x1", Pets.bucketFromLevel("COMMON", 88, "WOLF"));
    }

    @Test
    @DisplayName("legendary 57-59 are x1, which the old rule called x0")
    void lowBoundaryWasWrongToo() {
        assertEquals("x1", Pets.bucketFromLevel("LEGENDARY", 57, "WOLF"));
        assertEquals("x1", Pets.bucketFromLevel("LEGENDARY", 59, "WOLF"));
    }

    // ---- refusing to answer is a feature -----------------------------------

    @Test
    @DisplayName("straddling levels return null instead of guessing")
    void straddlingLevelsRefuse() {
        // Legendary 56 spans the 1M line and 87 spans the 10M line: the level
        // genuinely does not determine the bucket, so neither should we.
        assertNull(Pets.bucketFromLevel("LEGENDARY", 56, "WOLF"));
        assertNull(Pets.bucketFromLevel("LEGENDARY", 87, "WOLF"));
        assertTrue(Pets.cumulativeExp("LEGENDARY", 87, "WOLF") < 10_000_000L);
        assertTrue(Pets.cumulativeExp("LEGENDARY", 88, "WOLF") > 10_000_000L);
    }

    @Test
    @DisplayName("MAX LEVEL refuses: overflow feeding is unbounded above")
    void maxLevelRefuses() {
        // The owner's Lvl 100 Tarantula held 36.9M against a 25.35M ladder,
        // so the ladder cannot bound a maxed pet. expFromLore handles these.
        assertNull(Pets.bucketFromLevel("LEGENDARY", 100, "WOLF"));
        assertNull(Pets.bucketFromLevel("LEGENDARY", 200, "GOLDEN_DRAGON"));
    }

    @Test
    @DisplayName("indeterminate tier refuses rather than assuming one")
    void unknownTierRefuses() {
        assertNull(Pets.bucketFromLevel("", 88, "WOLF"));
        assertNull(Pets.bucketFromLevel(null, 88, "WOLF"));
        assertEquals(-1L, Pets.cumulativeExp("NONSENSE", 50, "WOLF"));
    }

    @Test
    @DisplayName("out-of-range levels refuse")
    void outOfRangeRefuses() {
        assertEquals(-1L, Pets.cumulativeExp("LEGENDARY", 0, "WOLF"));
        assertEquals(-1L, Pets.cumulativeExp("LEGENDARY", 101, "WOLF"));
        assertNull(Pets.bucketFromLevel("LEGENDARY", 101, "WOLF"));
    }

    // ---- dragons -----------------------------------------------------------

    @Test
    @DisplayName("dragons run to 200 on a flat tail, and reach x3")
    void dragonTail() {
        assertEquals(200, Pets.maxLevel("GOLDEN_DRAGON"));
        assertEquals(100, Pets.maxLevel("WOLF"));
        // Levels 101-200 cost a flat 1,886,700 each.
        assertEquals(1_886_700L,
                Pets.cumulativeExp("LEGENDARY", 102, "GOLDEN_DRAGON")
                        - Pets.cumulativeExp("LEGENDARY", 101, "GOLDEN_DRAGON"));
        // x3 (30M) is unreachable on a 100-cap ladder but a dragon crosses it.
        assertEquals("x2", Pets.bucketOf(Pets.cumulativeExp("LEGENDARY", 100, "WOLF")));
        assertEquals("x3", Pets.bucketFromLevel("LEGENDARY", 103, "GOLDEN_DRAGON"));
        assertEquals(31_013_330L, Pets.cumulativeExp("LEGENDARY", 103, "GOLDEN_DRAGON"));
    }

    @Test
    @DisplayName("bucket thresholds match the server's pet_exp_bucket")
    void thresholds() {
        assertEquals("x0", Pets.bucketOf(0));
        assertEquals("x0", Pets.bucketOf(999_999L));
        assertEquals("x1", Pets.bucketOf(1_000_000L));
        assertEquals("x1", Pets.bucketOf(9_999_999L));
        assertEquals("x2", Pets.bucketOf(10_000_000L));
        assertEquals("x2", Pets.bucketOf(29_999_999L));
        assertEquals("x3", Pets.bucketOf(30_000_000L));
    }
}
