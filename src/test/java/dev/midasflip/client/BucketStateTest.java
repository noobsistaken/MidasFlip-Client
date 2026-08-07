package dev.midasflip.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tooltip header naming the state that was priced.
 *
 * <p>"MidasFlip · starred bucket" told the user we used a starred bucket. It
 * never told them WHICH one, and it said nothing at all about the recomb or
 * the scrolls — which is the one question every player asks about an
 * estimate: did the thing I paid for get counted? The comp key already
 * carries that answer, so the header reads it out.
 *
 * <p>Keys below are real, sampled from the production corpus on 2026-08-07.
 */
class BucketStateTest {

    @Test
    void gearStateReadsAsAPhrase() {
        assertEquals("5✪ recombed · 3 scrolls", ItemTooltip.bucketState(
                "v1|HYPERION|s5|r1|sc:IMPLOSION_SCROLL+SHADOW_WARP_SCROLL+WITHER_SHIELD_SCROLL"));
        assertEquals("ethermerged", ItemTooltip.bucketState(
                "v1|ASPECT_OF_THE_VOID|s0|r0|em"));
        assertEquals("skin", ItemTooltip.bucketState(
                "v1|FERMENTO_HELMET|s0|r0|k:GARDEN_WARRIOR_FERMENTO"));
        assertEquals("recombed · skin", ItemTooltip.bucketState(
                "v1|WARDEN_HELMET|s0|r1|k:SENTINEL_WARDEN"));
    }

    @Test
    void zeroStarsAndNoRecombSayNothing() {
        // s0 and r0 are the ABSENCE of state. Printing "0★ not recombed"
        // would fill the line with non-facts and crowd out the real ones.
        assertNull(ItemTooltip.bucketState("v1|ASPECT_OF_THE_VOID|s0|r0"));
        assertEquals("recombed", ItemTooltip.bucketState("v1|HYPERION|s0|r1"));
        assertEquals("5✪", ItemTooltip.bucketState("v1|HYPERION|s5|r0"));
    }

    @Test
    void oneScrollIsNotThreeScrolls() {
        assertEquals("5✪ recombed · 1 scroll",
                ItemTooltip.bucketState("v1|HYPERION|s5|r1|sc:IMPLOSION_SCROLL"));
    }

    @Test
    void petStateSkipsIdentityAndNamesTheRest() {
        // v1|PET|<TYPE>|<TIER>|x<N>: type and tier are already on the item the
        // player is hovering, so the header spends its width on the parts that
        // moved the price.
        assertEquals("x2 exp bucket",
                ItemTooltip.bucketState("v1|PET|TIGER|LEGENDARY|x2"));
        assertEquals("x2 exp bucket · candied",
                ItemTooltip.bucketState("v1|PET|TIGER|LEGENDARY|x2|c1"));
        assertEquals("x0 exp bucket · tier boost",
                ItemTooltip.bucketState("v1|PET|TIGER|EPIC|x0|tb"));
        assertEquals("x3 exp bucket · candied · tier boost",
                ItemTooltip.bucketState("v1|PET|TIGER|LEGENDARY|x3|c1|tb"));
    }

    @Test
    void anUnknownSegmentSurvivesVerbatim() {
        // The rule prettyAtom() already follows, for the same reason: a
        // dropped segment tells the user their item was priced WITHOUT it,
        // which is the exact opposite of the truth. Raw reads as unfamiliar,
        // which is what it is.
        assertEquals("splash · y502",
                ItemTooltip.bucketState("v1|POTION_OF_STRENGTH|splash|y502"));
        assertEquals("5✪ recombed · shiny",
                ItemTooltip.bucketState("v1|HYPERION|s5|r1|shiny"));
        assertEquals("brand_new_segment:7",
                ItemTooltip.bucketState("v1|HYPERION|s0|r0|brand_new_segment:7"));
    }

    @Test
    void nothingToSayFallsBackToTheGenericLabel() {
        // Null is the contract for "keep saying 'clean bucket'". Anything else
        // would have the header invent a state the key never claimed.
        assertNull(ItemTooltip.bucketState(null));
        assertNull(ItemTooltip.bucketState(""));
        assertNull(ItemTooltip.bucketState("   "));
        assertNull(ItemTooltip.bucketState("v1"));
        assertNull(ItemTooltip.bucketState("v1|HYPERION"));
        assertNull(ItemTooltip.bucketState("v1|HYPERION|||"));
    }

    @Test
    void aKeyThatIsNotOurShapeIsNotPrintedIntoTheHeader() {
        // Verbatim pass-through is for segments INSIDE a key we recognise.
        // A string that is not a comp key at all has no state to report, and
        // echoing it would put raw junk on a buy-decision surface.
        assertNull(ItemTooltip.bucketState("garbage"));
        assertNull(ItemTooltip.bucketState("HYPERION|s5|r1"));
        assertNull(ItemTooltip.bucketState("|s5|r1"));
        // A future key version is still our shape and still reads.
        assertEquals("5✪", ItemTooltip.bucketState("v2|HYPERION|s5|r0"));
    }

    @Test
    void aLongPhraseIsTrimmedToWholePartsAndCounted() {
        // Tooltip lines compete for width with Hypixel's own lore, so long
        // phrases must shrink. This test previously asserted endsWith("…"),
        // which pinned the BUGGY behaviour: a raw substring cut the last part
        // mid-word, and unknown segments are appended last, so the one class
        // of segment the verbatim rule exists to protect was always the first
        // casualty (review 2026-08-07). Dropping whole parts and counting
        // them is a countable omission; a truncated word is a wrong claim
        // about what was priced.
        String s = ItemTooltip.bucketState(
                "v1|HYPERION|s5|r1|em|sc:A+B+C|k:SKIN|a_very_long_unknown_segment_name");
        assertTrue(s.startsWith("5✪ recombed"),
                "the most load-bearing state must survive: " + s);
        assertTrue(s.contains("more"), "the omission must be counted: " + s);
        assertFalse(s.endsWith("…"), "no mid-word cut: " + s);
        assertFalse(s.contains("a_very_long_unknown_segment"),
                "a part is either shown whole or counted, never half: " + s);
    }

    // ---- the render path must never throw --------------------------------
    // bucketState runs inside the ItemTooltipCallback lambda, per frame, on
    // EVERY hover carrying a comp key. There is no try/catch above it. The
    // file already carries a note about a crash on 2026-08-06 that "took the
    // whole game down from a tooltip render"; this is the same class.

    @Test
    void anAbsurdStarCountDoesNotCrashTheRender() {
        // "s99999999999" matched the old unbounded s\d+ and then overflowed
        // Integer.parseInt. Against the pre-fix code this test throws
        // NumberFormatException rather than failing an assertion.
        assertDoesNotThrow(() -> ItemTooltip.bucketState("v1|HYPERION|s99999999999|r0"));
        assertDoesNotThrow(() -> ItemTooltip.bucketState("v1|HYPERION|s2147483648|r1"));
        String out = ItemTooltip.bucketState("v1|HYPERION|s99999999999|r1");
        assertNotNull(out);
        assertTrue(out.contains("s99999999999"),
                "an unreadable star segment is shown verbatim, not guessed at: " + out);
        assertTrue(out.contains("recombed"), out);
    }

    @Test
    void garbageSegmentsNeverThrow() {
        for (String k : new String[]{
                "v1|ID|s|r", "v1|ID|s-1|r1", "v1|ID|sc:", "v1|ID|k:", "v1|ID|x999999999999",
                "v1|ID|" + "a".repeat(500), "v1|PET", "v1|", "v1", "|||", "v1|ID|s5|r1|"}) {
            assertDoesNotThrow(() -> ItemTooltip.bucketState(k), "threw on: " + k);
        }
    }

    @Test
    void elisionDropsWholePartsAndSaysHowMany() {
        // The unknown segment is appended LAST, so a naive substring cut it
        // first and mid-word: exactly the class the verbatim rule protects,
        // silently, with no way to reach it (review 2026-08-07).
        String out = ItemTooltip.bucketState(
                "v1|HYPERION|s5|r1|em|sc:A+B+C|k:SOME_SKIN|shiny");
        assertNotNull(out);
        assertFalse(out.endsWith("…"), "whole parts are dropped, never cut mid-word: " + out);
        assertTrue(out.contains("more"), "the omission must be counted: " + out);
        assertTrue(out.startsWith("5✪ recombed"), out);
        // and the count must be truthful
        var m = java.util.regex.Pattern.compile("\\+(\\d+) more").matcher(out);
        assertTrue(m.find(), out);
        assertTrue(Integer.parseInt(m.group(1)) >= 1, out);
    }

    @Test
    void aShortPhraseIsNeverElidedOrCounted() {
        String out = ItemTooltip.bucketState("v1|HYPERION|s5|r1");
        assertEquals("5✪ recombed", out);
        assertFalse(out.contains("more"));
        assertFalse(out.endsWith("…"));
    }
}