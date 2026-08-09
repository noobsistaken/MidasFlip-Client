package dev.midasflip.client;

import com.google.gson.JsonElement;

import java.util.Locale;

/**
 * id → pretty display name, backed by /names (fetched once, cached 1h).
 * Falls back to Title-Casing the id until the map arrives, so the board
 * is never blank and stops showing raw FIG_LEGGINGS once data lands.
 * Pets pretty-print from their bucket key ("Ender Dragon · leg pet").
 *
 * Static singleton: one client, many render sites.
 */
public final class NameMap {
    private static MidasflipApi api;

    private NameMap() {}

    static void init(MidasflipApi a) {
        api = a;
    }

    /** Pretty name for an item id, using the comp key for pet detail.
     *  Accepts both bucket keys (v1|PET|TYPE|TIER|xN) and the value tab's
     *  kat ids (PET:TYPE:TIER:xN). */
    public static String pretty(String itemId, String compKey) {
        if (itemId == null) {
            return "?";
        }
        if (itemId.startsWith("PET:")) {
            String[] p = itemId.split(":");
            if (p.length >= 3) {
                return titleCase(p[1]) + " pet §8(" + tierAbbrev(p[2]) + ")§r";
            }
        }
        if ("PET".equals(itemId) && compKey != null) {
            String[] p = compKey.split("\\|");
            if (p.length >= 4) {
                return titleCase(p[2]) + " pet §8(" + tierAbbrev(p[3]) + ")§r";
            }
        }
        if (api != null) {
            JsonElement el = api.get("/names", 60 * 60_000);
            if (el != null && el.isJsonObject()) {
                // A null or non-string entry in /names must not throw: this
                // runs on every board row and every HUD line, none of which
                // has a try/catch above it. Falling through to the
                // title-cased id is exactly what an absent entry already
                // did, so no live payload renders differently.
                String pretty = GoldFields.optStr(el.getAsJsonObject(), itemId);
                if (pretty != null) {
                    return pretty;
                }
            }
        }
        return titleCase(itemId);
    }

    /** First three letters of a tier, or the whole thing when it is
     *  shorter. The guards above check the number of segments, not their
     *  length, so a tier segment under 3 chars threw
     *  StringIndexOutOfBoundsException — from pretty(), which runs on every
     *  HUD row, board row and tooltip, none of which has a try/catch above
     *  it (review 2026-08-10). */
    private static String tierAbbrev(String tier) {
        String t = tier == null ? "" : tier;
        return (t.length() > 3 ? t.substring(0, 3) : t).toLowerCase(Locale.ROOT);
    }

    public static String pretty(String itemId) {
        return pretty(itemId, null);
    }

    private static String titleCase(String id) {
        StringBuilder sb = new StringBuilder(id.length());
        for (String part : id.split("_")) {
            if (part.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(part.charAt(0)))
                    .append(part.substring(1).toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }
}
