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

    // ---- TIER CONSTANTS ---------------------------------------------------
    //
    // FROZEN POOL RULE — owner decision 2026-08-06, PERMANENT. Read this
    // before adding anything here.
    //
    // Early access ships every Gold surface FUNCTIONAL and FREE, labeled, and
    // they become paid in September. The temporarily-free set is FROZEN at
    // the 2026-08-11 launch scope. Anything built AFTER that date ships
    // Gold-LOCKED from its first commit and is NEVER added to the free pool —
    // no exceptions, no "it's small", no "it pairs with a free feature".
    //
    // The reason is that the pool can only ever shrink in the user's eyes.
    // Every surface added to it now is one more thing that gets taken away in
    // September, and a takeaway is punished far harder than a thing that was
    // always paid. A stated end date on a fixed set is a deadline; a set that
    // keeps growing and then contracts is a bait-and-switch, whatever the
    // intent was.
    //
    // So: new post-launch feature ⇒ it renders through locked(), it does NOT
    // get a badge(), and it is not mentioned in the early-access copy.

    /** THE early-access label. Every Gold surface and every Gold field routes
     *  through this string, so September is one edit here rather than a hunt
     *  through 30 call sites. */
    static final String EARLY_ACCESS = "Gold · free during early access";

    /** Waitlist tie-in for any Gold label that is clickable or has hover
     *  copy (owner 2026-08-06). Deliberately not on every inline field label:
     *  a price on every line of a tooltip is nagging, and the offer only
     *  means something where there is room to explain it. */
    static final String FOUNDER_LINE =
            "€40 Founder locks Gold for life — waitlist at " + MidasflipConfig.SITE_HOST;

    /** Has the server started shaping payloads by tier?
     *
     *  <p>Set the first time any response carries `gold_locked`. That marker
     *  is emitted by backend/app/web/tiershape.py, which is a SEPARATE flag
     *  from the paywall: SKYFLIP_SHAPE_TIERS controls whether Gold fields are
     *  stripped from a free caller, SKYFLIP_ENFORCE_TIERS controls 402s and
     *  billing, and they move independently.
     *
     *  <p>This is why the client must READ the marker instead of assuming.
     *  Option B says Gold ships working and free during early access — which
     *  is only true while shaping is dark. If shaping is lit, a free caller's
     *  Gold fields are genuinely withheld, and a badge claiming they are
     *  "free during early access" over an empty pane would be a straight
     *  falsehood. Deriving the copy from what the server actually did makes
     *  September a server-side flip with no client release: turn shaping on
     *  and every label in the mod stops saying free and starts saying Gold. */
    private static volatile boolean shapingSeen;

    /** Call with any API response before rendering from it. */
    static void observe(JsonObject resp) {
        if (resp != null && resp.has("gold_locked")) {
            shapingSeen = true;
        }
    }

    /** As {@link #observe}, for a response of unknown shape. Boards come back
     *  as arrays of rows, so the marker can arrive one level down. */
    static void observeAny(JsonElement el) {
        if (el == null) {
            return;
        }
        if (el.isJsonObject()) {
            observe(el.getAsJsonObject());
        } else if (el.isJsonArray()) {
            JsonArray rows = el.getAsJsonArray();
            if (!rows.isEmpty() && rows.get(0).isJsonObject()) {
                observe(rows.get(0).getAsJsonObject());
            }
        }
    }

    static boolean shaping() {
        return shapingSeen;
    }

    /** Tests only: the flag is deliberately one-way in production (a single
     *  unshaped response must not un-say what the server already told us). */
    static void resetShapingForTest() {
        shapingSeen = false;
    }

    /** Did the server WITHHOLD this field, as opposed to simply not having
     *  it? The whole three-state split rests on this being answered by data
     *  rather than inferred from absence.
     *
     *  <p>`gold_locked` is a CONSTANT set, not "what we actually removed" —
     *  listing only the fields that happened to be present would leak the
     *  datum itself (a missing `manip` entry would tell a free user the risk
     *  was low, which is the Gold information). So membership means "Gold
     *  field", and combined with the value being absent it means withheld. */
    static boolean isLocked(JsonObject resp, String field) {
        JsonArray locked = optArr(resp, "gold_locked");
        if (locked == null || field == null) {
            return false;
        }
        for (JsonElement e : locked) {
            if (e != null && e.isJsonPrimitive() && field.equals(e.getAsString())) {
                return true;
            }
        }
        return false;
    }

    /** Surface-level badge: this whole pane/section is Gold.
     *
     *  <p>Owner call 2026-08-06 (Option B). The launch anchoring problem is
     *  NOT that people get things free — it is that they were never told the
     *  free part ends. "Free forever" quietly becoming paid is a takeaway and
     *  people punish it; a promotion with a stated end date is a deadline and
     *  they do not. The whole difference is saying it out loud on day one, on
     *  every surface it applies to rather than one header.
     *
     *  <p>ONE line, badge and offer together: several panes lay out with a
     *  running y and register click zones off it, and two lines pushed the
     *  last control of the Auctions pane off the bottom of a 270-tall screen.
     *
     *  <p>Drops the "free" claim automatically once the server starts shaping
     *  — see {@link #shaping()}. Rendered on a feature that WORKS; {@link
     *  #locked} is for a field the server withheld and {@link #unknown} for
     *  one nobody has. The three must not converge into one sentence. */
    static String badge() {
        return shaping()
                ? "§6Gold §8· " + FOUNDER_LINE + "§r"
                : "§6" + EARLY_ACCESS + " §8· " + FOUNDER_LINE + "§r";
    }

    static String unknown(String label) {
        return "§8" + label + " · not available";
    }

    /** A Gold field the server withheld.
     *
     *  <p>Deliberately SHORT and deliberately NOT the badge's sentence. It
     *  renders inside table cells as narrow as 83px, where the full
     *  early-access wording overflowed through three neighbouring columns;
     *  and a lock that reads word-for-word like the badge tells the user the
     *  same thing means both "you have this" and "this is not here"
     *  (review 2026-08-06). The badge above it supplies the period context —
     *  this only has to name the tier. */
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
