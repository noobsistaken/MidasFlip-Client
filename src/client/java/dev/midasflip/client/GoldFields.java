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

    static String locked(String label) {
        return "§8" + label + " · Gold";
    }

    private static JsonElement value(JsonObject o, String key) {
        if (o == null || key == null) {
            return null;
        }
        JsonElement value = o.get(key);
        return value == null || value.isJsonNull() ? null : value;
    }
}
