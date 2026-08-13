package dev.midasflip.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers "the sell panel says list at 17.0M while the tooltip says 11.1M on
 * the same Tarantula" (owner, 2026-08-13).
 *
 * <p>Root cause: {@link Pets#approximateExpBucket} tops out at x2, so a
 * max-level pet fed past 30M EXP (bucket x3) was priced from x2 comps
 * whenever Hypixel stripped petInfo — which every menu GUI does. The exact
 * total was printed in the item's own lore the whole time.
 *
 * <p>These assertions are about the LORE READER, which is where the guess
 * gets replaced. The bucket thresholds themselves live server-side
 * (backend pet_exp_bucket) and are deliberately not duplicated here.
 */
class PetExpBucketTest {

    /** The exact lore from the owner's screenshot, colors included. */
    private static final List<String> TARANTULA_MAX = List.of(
            "§7Strength: §c+10",
            "§7Crit Chance: §9+10%",
            "",
            "§6Eight Legs",
            "§7Decreases the mana cost of Spider...",
            "",
            "§bMAX LEVEL",
            "§8▸ §736,926,226 XP",
            "",
            "§7Can be upgraded at Kat in The Hub!",
            "§6§lLEGENDARY");

    @Test
    @DisplayName("the incident: max-level total EXP is read from lore")
    void readsMaxLevelTotal() {
        assertEquals(36_926_226.0, Pets.expFromLoreLines(TARANTULA_MAX));
    }

    @Test
    @DisplayName("36.9M is above the 30M x3 boundary the guess could not reach")
    void aboveX3Boundary() {
        Double exp = Pets.expFromLoreLines(TARANTULA_MAX);
        org.junit.jupiter.api.Assertions.assertTrue(exp > 30_000_000.0,
                "this pet is x3; the level heuristic can only ever say x2");
    }

    @Test
    @DisplayName("no MAX LEVEL line -> null, never guess from a progress number")
    void belowMaxLevelRefuses() {
        // Hypixel shows EXP INTO THE CURRENT LEVEL below the cap. Reading it
        // as a total would under-bucket the pet — worse than approximating.
        assertNull(Pets.expFromLoreLines(List.of(
                "§7Progress to Level 91: §e45.2%",
                "§2§l§m    §f§l§m     §r §e1,234,567§6/§e2,345,678",
                "§6§lLEGENDARY")));
    }

    @Test
    @DisplayName("a progress line carrying two numbers cannot match the total pattern")
    void progressPairIsNotATotal() {
        assertNull(Pets.expFromLoreLines(List.of(
                "§bMAX LEVEL",
                "§71,234,567/2,345,678 XP")));
    }

    @Test
    @DisplayName("two candidate totals -> null (fail closed, do not pick one)")
    void ambiguousFailsClosed() {
        assertNull(Pets.expFromLoreLines(List.of(
                "§bMAX LEVEL",
                "§8▸ §736,926,226 XP",
                "§8▸ §712,000,000 XP")));
    }

    @Test
    @DisplayName("non-pet lore yields nothing")
    void nonPetLore() {
        assertNull(Pets.expFromLoreLines(List.of(
                "§7Gear Score: §d1035",
                "§7Damage: §c+250",
                "§6§lLEGENDARY")));
    }

    @Test
    @DisplayName("empty lore is safe")
    void emptyLore() {
        assertNull(Pets.expFromLoreLines(List.of()));
    }

    @Test
    @DisplayName("MAX LEVEL with no total line yields nothing, not zero")
    void maxLevelWithoutTotal() {
        // Must be null rather than 0.0 — a 0.0 would resolve to bucket x0 and
        // price a maxed pet off the cheapest comps in the game.
        assertNull(Pets.expFromLoreLines(List.of(
                "§bMAX LEVEL",
                "§6§lLEGENDARY")));
    }

    @Test
    @DisplayName("plain total without a bullet glyph still reads")
    void noGlyph() {
        assertEquals(25_353_230.0, Pets.expFromLoreLines(List.of(
                "MAX LEVEL",
                "25,353,230 XP")));
    }

    @Test
    @DisplayName("order does not matter: total may precede the MAX LEVEL line")
    void orderIndependent() {
        assertEquals(31_000_000.0, Pets.expFromLoreLines(List.of(
                "§8▸ §731,000,000 XP",
                "§bMAX LEVEL")));
    }
}
