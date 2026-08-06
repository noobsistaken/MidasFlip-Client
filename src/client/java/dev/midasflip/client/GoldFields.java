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

    /** Shown ON a working Gold feature during the free launch period.
     *
     *  <p>Owner call 2026-08-06. The launch anchoring problem is NOT that
     *  people get things free — it is that they were never told the free
     *  part ends. "Free forever" quietly becoming paid is a takeaway and
     *  people punish it; a promotion with a stated end date is a deadline
     *  and they do not. The whole difference is saying it out loud on day
     *  one, and it costs a line of text rather than an enforcement layer we
     *  could not collect on anyway (there is no checkout until September).
     *
     *  <p>Deliberately rendered on the feature that WORKS, not on absent
     *  data — that is what {@link #locked} is for, and the two must not be
     *  confused: one says "you have this, for now", the other says "this
     *  is not yours". */
    static String tempFree() {
        return "§6temporary free Gold§8 · until September§r";
    }

    /** A FREE field that simply has no value right now.
     *
     *  <p>Not the same thing as {@link #locked}. `locked` says "this is
     *  Gold"; this says "there is nothing to show". Using the wrong one at a
     *  free launch tells a user that data they already have is behind a
     *  paywall — and for the risk flags it is worse than that, because
     *  absence there is itself the answer: no manipulation detected, not
     *  falling. Charging for a clean verdict would be absurd.
     *
     *  <p>Free per owner 2026-08-06: manipulation risk, falling knife, the
     *  hover estimate, hold. Gold keeps the bands, comps, exits, sell-side
     *  numbers, slow-case tail, sell-through and the modifier breakdown. */
    static String unknown(String label) {
        return "§8" + label + " · not available";
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
