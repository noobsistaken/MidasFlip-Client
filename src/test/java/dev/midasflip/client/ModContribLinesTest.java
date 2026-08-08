package dev.midasflip.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
        // One priced row, plus the one-line hint that something is hidden.
        // What matters is that "shiny" never renders AS A NUMBER.
        assertTrue(out.get(0).contains("Ult. Wise 5"), out.get(0));
        assertFalse(String.join("", out).matches(".*[Ss]hiny[^\\n]*[+-]\\d.*"),
                "an unlearned atom must never carry a figure: " + out);
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
        // A row missing its `learned` flag is MALFORMED, not "unpriced", so it
        // must not produce a hint either: exactly one line, the good row.
        assertEquals(1, out.size(), out.toString());
        assertTrue(out.get(0).contains("Ult. Wise 5"), out.get(0));
    }

    @Test
    void anEmptyOrNullListYieldsNoLines() {
        assertEquals(List.of(), ItemTooltip.modContribLines(
                resp("{\"mod_contributions\": []}"), 1));
        assertEquals(List.of(), ItemTooltip.modContribLines(
                resp("{\"mod_contributions\": null}"), 1));
    }

    // ---- hold SHIFT to expand ---------------------------------------------
    // The cap kept the tooltip a sane height but made the hidden rows
    // unreachable forever: "+3 more" named evidence for the price with no way
    // to ever read it. The expanded flag is key state the render site reads;
    // these drive it directly, without a game window.

    private static final String SIX_ROWS = """
            {"mod_contributions": {"a": 9, "b": 8, "c": 7,
                                   "d": 6, "e": 5, "f": 4}}""";

    @Test
    void theHintAppearsOnlyWhenRowsAreActuallyHidden() {
        List<String> hidden = ItemTooltip.modContribLines(resp(SIX_ROWS), 1, false);
        assertTrue(hidden.get(hidden.size() - 1).contains("hold SHIFT"),
                "the way out of the cap has to be on screen: " + hidden);

        List<String> short3 = ItemTooltip.modContribLines(resp("""
                {"mod_contributions": {"a": 9, "b": 8, "c": 7}}"""), 1, false);
        assertEquals(3, short3.size());
        assertFalse(String.join("", short3).contains("hold SHIFT"),
                "nothing is hidden, so the hint would be an instruction to nowhere");
    }

    @Test
    void shiftShowsEveryRowAndNothingIsLeftCounted() {
        List<String> out = ItemTooltip.modContribLines(resp(SIX_ROWS), 1, true);
        assertEquals(6, out.size(), "every contributor, no remainder line");
        assertFalse(String.join("", out).contains("more"), out.toString());
        assertFalse(String.join("", out).contains("hold SHIFT"),
                "already expanded — the hint has nothing left to offer");
    }

    @Test
    void collapsedStillCapsAtFour() {
        // The cap is why the tooltip fits at all; expanding must be the
        // exception the user asks for, never the new default.
        List<String> out = ItemTooltip.modContribLines(resp(SIX_ROWS), 1, false);
        assertEquals(5, out.size(), "4 rows plus the remainder line");
        assertTrue(out.get(4).contains("2 more"), out.get(4));
        assertEquals(out, ItemTooltip.modContribLines(resp(SIX_ROWS), 1),
                "the two-arg form is the collapsed form, unchanged");
    }

    @Test
    void aShortListLooksTheSameInBothModes() {
        JsonObject r = resp("""
                {"mod_contributions": {"ult:wise_5": 2800000, "hpb:10": 400000}}""");
        assertEquals(ItemTooltip.modContribLines(r, 1, false),
                ItemTooltip.modContribLines(r, 1, true),
                "holding shift over an item with 2 modifiers must not change it");
        assertEquals(2, ItemTooltip.modContribLines(r, 1, true).size());
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
        // Two priced rows; the unlearned "shiny" adds the hint line, never a
        // row, and never enters the total.
        var lines = ItemTooltip.modContribLines(r, 1);
        assertEquals(2, lines.stream()
                        .filter(l -> !l.contains("more"))          // not the hint line
                        .filter(l -> l.matches(".*[+-]\\d.*")).count(),
                "exactly the two priced rows carry a figure: " + lines);
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

    // ---- shift has to DO something on a real item -------------------------
    // Measured live 2026-08-08: a fully modded Hyperion itemized 5 atoms and
    // only 3 carried a learned delta. 3 is under the 4-row cap, so nothing
    // was ever hidden, the "+N more" hint never rendered, and holding shift
    // changed nothing on screen. The owner tried it and correctly reported
    // that the feature did nothing. That exact payload is pinned here.

    private static final String LIVE_HYPERION = """
            {"mod_contributions": [
                {"feature": "ult:wise_5",              "learned_delta": 0.0,       "learned": false},
                {"feature": "ench6:3-5",               "learned_delta": 990000.0,  "learned": true},
                {"feature": "hpb:10",                  "learned_delta": 400000.0,  "learned": true},
                {"feature": "gem:PERFECT_JASPERx2",    "learned_delta": 0.0,       "learned": false},
                {"feature": "drill_parts",             "learned_delta": 6320001.0, "learned": true}]}""";

    @Test
    void theLivePayloadGivesShiftSomethingToReveal() {
        JsonObject r = resp(LIVE_HYPERION);
        List<String> collapsed = ItemTooltip.modContribLines(r, 1, false);
        List<String> expanded = ItemTooltip.modContribLines(r, 1, true);

        assertNotEquals(collapsed, expanded,
                "holding shift must change the tooltip on the real production payload");
        assertTrue(expanded.size() > collapsed.size(), expanded.toString());
    }

    @Test
    void recognisedButUnpricedAtomsAreNamedNotHidden() {
        List<String> expanded = ItemTooltip.modContribLines(resp(LIVE_HYPERION), 1, true);
        String all = String.join("\n", expanded);

        assertTrue(all.contains("Ult. Wise 5"), all);
        assertTrue(all.contains("Perfect jasper x2"), all);
        assertTrue(all.contains("no value learned yet"), all);
    }

    @Test
    void anUnpricedAtomNeverShowsANumber() {
        // The server sends 0.0 for these. Rendering it would state we measured
        // the modifier to be worth nothing, which we did not.
        for (String line : ItemTooltip.modContribLines(resp(LIVE_HYPERION), 1, true)) {
            if (line.contains("no value learned yet")) {
                assertFalse(line.contains("+0"), line);
                assertFalse(line.contains("-0"), line);
                assertFalse(line.matches(".*[+-]\\d.*"), "no figure belongs on an unpriced atom: " + line);
            }
        }
    }

    @Test
    void theCollapsedLineSaysHowManyAreWaiting() {
        List<String> collapsed = ItemTooltip.modContribLines(resp(LIVE_HYPERION), 1, false);
        String last = collapsed.get(collapsed.size() - 1);
        assertTrue(last.contains("+2 more"), last);
        assertTrue(last.contains("SHIFT"), last);
    }

    @Test
    void nothingUnpricedMeansNoExtraLine() {
        JsonObject allLearned = resp("""
                {"mod_contributions": [
                    {"feature": "hpb:10", "learned_delta": 400000.0, "learned": true}]}""");
        List<String> out = ItemTooltip.modContribLines(allLearned, 1, false);
        assertEquals(1, out.size(), out.toString());
        assertFalse(String.join("", out).contains("SHIFT"));
    }
}