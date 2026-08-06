package dev.midasflip.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    // ---- the shape the server actually sends -------------------------------
    // decompose.py returns a LIST of rows, not an object:
    //   {"feature": a, "learned_delta": round(delta, 1), "learned": a in contribs}
    // The client only ever accepted an object, so every array was rejected and
    // the breakdown NEVER rendered once in production (audit 2026-08-06). These
    // pin the real contract; the object cases above stay as the defensive path.

    @Test
    void theServersListFormRenders() {
        List<String> out = ItemTooltip.modContribLines(resp("""
                {"mod_contributions": [
                    {"feature": "hpb:10",     "learned_delta": 400000.0,  "learned": true},
                    {"feature": "ult:wise_5", "learned_delta": 2800000.0, "learned": true},
                    {"feature": "ench6:3-5",  "learned_delta": 1000000.0, "learned": true}]}"""), 1);
        assertEquals(3, out.size(), "an array must not be silently dropped");
        assertTrue(out.get(0).contains("Ult. Wise 5"), out.get(0));
        assertTrue(out.get(0).contains("2.8M"), out.get(0));
        assertTrue(out.get(1).contains("3-5 high enchants"), out.get(1));
        assertTrue(out.get(2).contains("10 hot potato"), out.get(2));
    }

    @Test
    void anUnlearnedModifierIsNotShownAsWorthNothing() {
        // learned:false carries learned_delta 0.0 and means "no delta learned
        // for this yet" — NOT "this modifier is worth zero". Printing it as a
        // zero row would state a market fact we never measured.
        List<String> out = ItemTooltip.modContribLines(resp("""
                {"mod_contributions": [
                    {"feature": "ult:wise_5", "learned_delta": 2800000.0, "learned": true},
                    {"feature": "shiny",      "learned_delta": 0.0,       "learned": false}]}"""), 1);
        assertEquals(1, out.size());
        assertTrue(out.get(0).contains("Ult. Wise 5"), out.get(0));
    }

    @Test
    void listRowsScaleAndKeepTheirSign() {
        // Note: decompose.py clamps negative medians to 0, so a negative
        // learned_delta cannot currently reach us on this endpoint. Kept
        // because the renderer must not invert a sign if that ever changes —
        // candied pets genuinely subtract.
        List<String> four = ItemTooltip.modContribLines(resp("""
                {"mod_contributions": [
                    {"feature": "hpb:10", "learned_delta": 500000.0, "learned": true}]}"""), 4);
        assertTrue(four.get(0).contains("2.0M"), four.get(0));

        List<String> candy = ItemTooltip.modContribLines(resp("""
                {"mod_contributions": [
                    {"feature": "held:candy", "learned_delta": -1300000.0, "learned": true}]}"""), 1);
        assertTrue(candy.get(0).contains("-1.3M"), candy.get(0));
    }

    @Test
    void malformedRowsAreSkippedNotCrashedOn() {
        // A row missing either field, or the wrong type entirely, must cost
        // that row only — a tooltip render happens every frame and must never
        // throw on a payload the server changes under us.
        List<String> out = ItemTooltip.modContribLines(resp("""
                {"mod_contributions": [
                    {"feature": "ult:wise_5", "learned_delta": 2800000.0, "learned": true},
                    {"feature": "no_delta"},
                    {"learned_delta": 5000.0},
                    "not an object",
                    null]}"""), 1);
        assertEquals(1, out.size());
        assertTrue(out.get(0).contains("Ult. Wise 5"), out.get(0));
    }

    @Test
    void anEmptyOrNullListYieldsNoLines() {
        assertEquals(List.of(), ItemTooltip.modContribLines(
                resp("{\"mod_contributions\": []}"), 1));
        assertEquals(List.of(), ItemTooltip.modContribLines(
                resp("{\"mod_contributions\": null}"), 1));
    }

    // ---- the "+X" total ----------------------------------------------------
    // This number sits next to a price, so it fails CLOSED where the
    // breakdown fails soft: showing fewer lines is a smaller list, showing a
    // partial sum is a wrong number that looks complete.

    @Test
    void theTotalRefusesToBePartial() {
        // One unreadable row previously cost only that row, and the marker
        // then presented the remaining sum as the whole uplift.
        assertNull(ItemTooltip.modContribSum(resp("""
                {"mod_contributions": [
                    {"feature": "ult:wise_5", "learned_delta": 2800000.0, "learned": true},
                    {"feature": "hpb:10",     "learned_delta": "?",       "learned": true}]}""")),
                "a row we could not read must void the total, not shrink it");
    }

    @Test
    void theTotalIsTheSumOfTheRowsShown() {
        // Total and breakdown must never disagree about which rows count:
        // zeros and unlearned rows are dropped in ONE place, for both.
        JsonObject r = resp("""
                {"mod_contributions": [
                    {"feature": "ult:wise_5", "learned_delta": 2800000.0, "learned": true},
                    {"feature": "hpb:10",     "learned_delta": 400000.0,  "learned": true},
                    {"feature": "ench6:3-5",  "learned_delta": 0.0,       "learned": true},
                    {"feature": "shiny",      "learned_delta": 0.0,       "learned": false}]}""");
        assertEquals(3200000.0, ItemTooltip.modContribSum(r));
        assertEquals(2, ItemTooltip.modContribLines(r, 1).size());
    }

    @Test
    void nothingLearnedIsNotAPaywall() {
        // The distinction that matters at a free launch, now decided by the
        // server's gold_locked marker instead of by the field being missing.
        // Absence alone cannot tell "you did not pay for this" from "we have
        // no measurement", and guessing picks the paywall exactly when the
        // truth is a data gap.
        JsonObject unlearned = resp("""
                {"mod_contributions": [
                    {"feature": "shiny", "learned_delta": 0.0, "learned": false}]}""");
        assertNull(ItemTooltip.modContribSum(unlearned));
        assertFalse(GoldFields.isLocked(unlearned, "mod_contributions"),
                "no marker → nothing was withheld → unknown(), not locked()");

        JsonObject shaped = resp("{\"gold_locked\": [\"mod_contributions\"]}");
        assertTrue(GoldFields.isLocked(shaped, "mod_contributions"),
                "the server said it withheld this → locked()");
    }
}
