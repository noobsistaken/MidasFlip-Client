package dev.midasflip.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-modifier breakdown: what each thing adds to the price.
 *
 * <p>The server has always returned this itemized; the client summed it and
 * threw the parts away. A total says the modifiers are worth something. The
 * breakdown says WHICH one carries the item, which is the difference
 * between a number and an argument you can check.
 */
class ModContribLinesTest {

    private static final Gson GSON = new Gson();

    private static JsonObject resp(String json) {
        return GSON.fromJson(json, JsonObject.class);
    }

    @Test
    void atomsRenderAsSomethingAPlayerReads() {
        assertEquals("Ult. Wise 5", ItemTooltip.prettyAtom("ult:wise_5"));
        assertEquals("Ult. Chimera 4", ItemTooltip.prettyAtom("ult:chimera_4"));
        assertEquals("3-5 high enchants", ItemTooltip.prettyAtom("ench6:3-5"));
        assertEquals("Jasper x2", ItemTooltip.prettyAtom("gem:JASPER x2".replace(" ", "")));
        assertEquals("10 hot potato", ItemTooltip.prettyAtom("hpb:10"));
        assertEquals("drill parts", ItemTooltip.prettyAtom("drill_parts"));
        assertEquals("held Tier boost", ItemTooltip.prettyAtom("held:tier_boost"));
    }

    @Test
    void anUnknownAtomIsShownRawNotGuessedAt() {
        // A new atom the server starts sending must appear as itself, so it
        // reads as unfamiliar rather than silently rendering as the wrong
        // thing. Same rule the comp-key describer follows.
        assertEquals("shiny", ItemTooltip.prettyAtom("shiny"));
        assertEquals("newthing:7", ItemTooltip.prettyAtom("newthing:7"));
        assertEquals("?", ItemTooltip.prettyAtom(null));
        assertEquals("?", ItemTooltip.prettyAtom("  "));
    }

    @Test
    void biggestContributorComesFirst() {
        List<String> out = ItemTooltip.modContribLines(resp("""
                {"mod_contributions": {"hpb:10": 400000,
                                       "ult:wise_5": 2800000,
                                       "ench6:3-5": 1000000}}"""), 1);
        assertEquals(3, out.size());
        assertTrue(out.get(0).contains("Ult. Wise 5"), out.get(0));
        assertTrue(out.get(1).contains("3-5 high enchants"), out.get(1));
        assertTrue(out.get(2).contains("10 hot potato"), out.get(2));
    }

    @Test
    void negativeContributionsKeepTheirSign() {
        // Candied pets genuinely subtract. Rendering that as "+" would
        // invert the one fact the line exists to convey.
        List<String> out = ItemTooltip.modContribLines(
                resp("{\"mod_contributions\": {\"held:candy\": -1300000}}"), 1);
        assertEquals(1, out.size());
        assertTrue(out.get(0).contains("-1.3M"), out.get(0));
    }

    @Test
    void contributionsScaleWithTheStack() {
        List<String> one = ItemTooltip.modContribLines(
                resp("{\"mod_contributions\": {\"hpb:10\": 500000}}"), 1);
        List<String> four = ItemTooltip.modContribLines(
                resp("{\"mod_contributions\": {\"hpb:10\": 500000}}"), 4);
        assertTrue(one.get(0).contains("500k"), one.get(0));
        // Phos.coins keeps one decimal at M scale — "2.0M", not "2M".
        assertTrue(four.get(0).contains("2.0M"), four.get(0));
    }

    @Test
    void aLongListIsCappedAndTheRemainderIsCounted() {
        // A hidden line is worse than a shorter list: say how many were left.
        List<String> out = ItemTooltip.modContribLines(resp("""
                {"mod_contributions": {"a": 9, "b": 8, "c": 7,
                                       "d": 6, "e": 5, "f": 4}}"""), 1);
        assertEquals(5, out.size(), "4 rows plus the remainder line");
        assertTrue(out.get(4).contains("2 more"), out.get(4));
    }

    @Test
    void zeroContributionsAreDroppedAsNoise() {
        List<String> out = ItemTooltip.modContribLines(resp("""
                {"mod_contributions": {"hpb:10": 0, "ult:wise_5": 2800000}}"""), 1);
        assertEquals(1, out.size());
        assertTrue(out.get(0).contains("Ult. Wise 5"));
    }

    @Test
    void missingOrEmptyYieldsNoLines() {
        assertEquals(List.of(), ItemTooltip.modContribLines(resp("{}"), 1));
        assertEquals(List.of(), ItemTooltip.modContribLines(
                resp("{\"mod_contributions\": {}}"), 1));
    }
}
