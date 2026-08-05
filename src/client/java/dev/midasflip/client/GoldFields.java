package dev.midasflip.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/** Small, shared accessors for tier-optional response fields. */
final class GoldFields {
    private GoldFields() {
    }

    static Double optNum(JsonObject o, String key) {
        JsonElement value = value(o, key);
        if (value == null || !value.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (!primitive.isNumber()) {
            return null;
        }
        try {
            double number = primitive.getAsDouble();
            return Double.isFinite(number) ? number : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    static String optStr(JsonObject o, String key) {
        JsonElement value = value(o, key);
        if (value == null || !value.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        return primitive.isString() ? primitive.getAsString() : null;
    }

    static JsonArray optArr(JsonObject o, String key) {
        JsonElement value = value(o, key);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : null;
    }

    static JsonObject optObj(JsonObject o, String key) {
        JsonElement value = value(o, key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    /** The one place a Gold-only field's absence becomes visible text.
     *
     *  <p>Launch posture (owner 2026-08-05): Aug 11 ships FREE, and checkout
     *  does not open until September. So a locked surface must read as
     *  "coming", not as "pay me" — it is never hidden and never accessible,
     *  and the label says when it opens rather than what it costs. All 27
     *  call sites go through here, so the launch wording is one edit. */
    static String locked(String label) {
        return "§8" + label + " · Gold · opens September";
    }

    private static JsonElement value(JsonObject o, String key) {
        if (o == null || key == null) {
            return null;
        }
        JsonElement value = o.get(key);
        return value == null || value.isJsonNull() ? null : value;
    }
}
