package dev.midasflip.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

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
        assertEquals("§8bands · Gold", GoldFields.locked("bands"));
        assertEquals("§8exits · Gold", GoldFields.locked("exits"));
        assertEquals("§8comps · Gold", GoldFields.locked("comps"));
        assertEquals("§8hold · Gold", GoldFields.locked("hold"));
    }
}
