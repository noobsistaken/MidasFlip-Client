package dev.midasflip.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoldFieldsTest {

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
        // Launch posture (owner 2026-08-05): Aug 11 ships FREE and checkout
        // does not open until September, so a locked surface says WHEN it
        // opens, never what it costs.
        assertEquals("§8bands · Gold · opens September", GoldFields.locked("bands"));
        assertEquals("§8exits · Gold · opens September", GoldFields.locked("exits"));
        assertEquals("§8comps · Gold · opens September", GoldFields.locked("comps"));
        assertEquals("§8hold · Gold · opens September", GoldFields.locked("hold"));

        // The invariant behind the wording, not just the wording: a locked
        // label must name a DATE and never a price. A store page and a mod
        // that quote a price for something nobody can buy yet are the same
        // defect, and this is the single function all 27 call sites use.
        for (String label : new String[]{"bands", "exits", "comps", "hold"}) {
            String out = GoldFields.locked(label);
            assertTrue(out.contains("September"), out);
            assertFalse(out.contains("€"), out);
            assertFalse(out.contains("7.99"), out);
            assertFalse(out.toLowerCase(java.util.Locale.ROOT).contains("upgrade"), out);
            assertFalse(out.toLowerCase(java.util.Locale.ROOT).contains("buy"), out);
        }
    }
}
