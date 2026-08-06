package dev.midasflip.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoldFieldsTest {

    /** Colour codes off, so the tests compare the SENTENCE rather than the
     *  styling — two labels that differ only by colour still read the same. */
    private static String strip(String s) {
        return s.replaceAll("§.", "");
    }

    @Test
    void missingFieldsReturnNull() {
        JsonObject o = new JsonObject();

        assertNull(GoldFields.optNum(o, "number"));
        assertNull(GoldFields.optStr(o, "string"));
        assertNull(GoldFields.optArr(o, "array"));
        assertNull(GoldFields.optObj(o, "object"));
    }

    @Test
    void jsonNullFieldsReturnNull() {
        JsonObject o = new JsonObject();
        o.add("number", JsonNull.INSTANCE);
        o.add("string", JsonNull.INSTANCE);
        o.add("array", JsonNull.INSTANCE);
        o.add("object", JsonNull.INSTANCE);

        assertNull(GoldFields.optNum(o, "number"));
        assertNull(GoldFields.optStr(o, "string"));
        assertNull(GoldFields.optArr(o, "array"));
        assertNull(GoldFields.optObj(o, "object"));
    }

    @Test
    void nonNumericStringIsNotANumber() {
        JsonObject o = new JsonObject();
        o.addProperty("number", "not-a-number");

        assertNull(GoldFields.optNum(o, "number"));
    }

    @Test
    void validFieldsAndLockedCopyArePreserved() {
        JsonObject o = new JsonObject();
        JsonArray array = new JsonArray();
        JsonObject object = new JsonObject();
        o.addProperty("number", 12.5);
        o.addProperty("string", "high");
        o.add("array", array);
        o.add("object", object);

        assertEquals(12.5, GoldFields.optNum(o, "number"));
        assertEquals("high", GoldFields.optStr(o, "string"));
        assertSame(array, GoldFields.optArr(o, "array"));
        assertSame(object, GoldFields.optObj(o, "object"));
        // Option B, ratified 2026-08-06: Gold surfaces ship FUNCTIONAL and
        // FREE during early access, labeled, and become paid in September.
        // So no surface may claim to be withheld during the free period.
        // locked() is SHORT: it renders in table cells as narrow as 83px,
        // and the full early-access wording overflowed through three
        // neighbouring columns. The badge carries the period context.
        assertEquals("§8bands · Gold", GoldFields.locked("bands"));
        assertEquals("§8exits · Gold", GoldFields.locked("exits"));
        assertEquals("§8comps · Gold", GoldFields.locked("comps"));
    }

    @Test
    void everyGoldLabelRoutesThroughOneString() {
        assertTrue(GoldFields.badge().contains(GoldFields.EARLY_ACCESS));
        assertEquals("Gold · free during early access", GoldFields.EARLY_ACCESS);
    }

    @Test
    void theThreeStatesAreThreeDifferentSentences() {
        // The rule this file exists to hold. An earlier cut had badge() and
        // locked() emit the identical string, so a pane could draw "Gold ·
        // free during early access" 13px above "bands · Gold · free during
        // early access" — the same words meaning "you have this" and "this
        // never arrived" (review 2026-08-06).
        String badge = GoldFields.badge();
        String locked = GoldFields.locked("bands");
        String unknown = GoldFields.unknown("bands");

        assertNotEquals(strip(badge), strip(locked));
        assertNotEquals(strip(locked), strip(unknown));
        assertNotEquals(strip(badge), strip(unknown));
        assertFalse(strip(locked).contains(GoldFields.EARLY_ACCESS),
                "a withheld field must not claim to be free right now");
        assertFalse(strip(unknown).contains("Gold"),
                "a field nobody has must not be labeled as a tier");
    }

    @Test
    void aWithheldFieldIsKnownFromTheServerNotGuessedFromAbsence() {
        // gold_locked is the marker tiershape.py emits precisely so the
        // client can tell "you didn't pay for this" from "we checked and
        // there is nothing". Absence alone cannot distinguish them.
        JsonObject shaped = new JsonObject();
        JsonArray locked = new JsonArray();
        locked.add("est_opt");
        locked.add("comps");
        shaped.add("gold_locked", locked);

        assertTrue(GoldFields.isLocked(shaped, "est_opt"));
        assertTrue(GoldFields.isLocked(shaped, "comps"));
        assertFalse(GoldFields.isLocked(shaped, "manip"), "risk fields are free, never locked");
        assertFalse(GoldFields.isLocked(new JsonObject(), "est_opt"),
                "no marker means the server is not shaping — nothing is withheld");
        assertFalse(GoldFields.isLocked(null, "est_opt"));
    }

    @Test
    void theBadgeStopsSayingFreeOnceTheServerShapes() {
        // September is a server-side flip, not a client release (owner
        // requirement 5): turning SKYFLIP_SHAPE_TIERS on makes every payload
        // carry gold_locked, and the copy has to follow the server rather
        // than a date compiled into the jar.
        assertTrue(GoldFields.badge().contains(GoldFields.EARLY_ACCESS));

        JsonObject shaped = new JsonObject();
        shaped.add("gold_locked", new JsonArray());
        GoldFields.observe(shaped);
        try {
            assertTrue(GoldFields.shaping());
            assertFalse(GoldFields.badge().contains(GoldFields.EARLY_ACCESS),
                    "a shaped payload means Gold is being withheld — not free");
            assertTrue(GoldFields.badge().contains("Gold"));
            assertTrue(GoldFields.badge().contains("Founder"), "the offer survives the switch");
        } finally {
            GoldFields.resetShapingForTest();
        }
    }

    @Test
    void aBadgeSaysFreeAndALockSaysAbsent() {
        // The two must never converge into one sentence. badge() goes on a
        // feature that WORKS; locked() goes on data that did not arrive.
        // unknown() is the third state and belongs to FREE fields.
        assertFalse(GoldFields.badge().contains("not available"));
        assertTrue(GoldFields.unknown("hold").contains("not available"));
        assertFalse(GoldFields.unknown("hold").contains("Gold"),
                "a free field with no value must never be labeled Gold");
    }

    @Test
    void theFounderOfferNamesThePriceAndWhereToGo() {
        // The one place a price IS allowed, because it is attached to an
        // action the user can actually take (the waitlist), not stamped over
        // a feature they cannot buy yet.
        String offer = GoldFields.badge();
        assertTrue(offer.contains("€40"), offer);
        assertTrue(offer.contains("Founder"), offer);
        assertTrue(offer.contains("for life"), offer);
        assertTrue(offer.contains(MidasflipConfig.SITE_HOST), offer);
    }

    @Test
    void anInlineFieldLabelNeverQuotesAPrice() {
        // A price on every line of a tooltip is nagging, and quoting one for
        // something with no checkout is worse. The offer lives on surfaces
        // with room to explain it; inline labels stay quiet.
        for (String label : new String[]{"bands", "exits", "comps", "hold", "incl. modifiers"}) {
            String out = GoldFields.locked(label);
            assertFalse(out.contains("€"), out);
            assertFalse(out.contains("7.99"), out);
            assertFalse(out.contains("40"), out);
            assertFalse(out.toLowerCase(java.util.Locale.ROOT).contains("upgrade"), out);
            assertFalse(out.toLowerCase(java.util.Locale.ROOT).contains("buy"), out);
        }
    }
}
