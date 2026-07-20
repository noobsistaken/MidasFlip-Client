package dev.midasflip.client;

import com.google.gson.JsonObject;

/**
 * The sell-side view of the same valuation row the flip detector scores.
 *
 * <p>The finder admits an auction against the pessimistic band
 * ({@code PriceRow.Pess}); that is therefore the only honest "finder sell
 * target" for tooltips and the sell overlay. Lowest-BIN depth remains useful
 * context, but it must not replace this value: a temporary low listing can
 * sit below fair value, which is exactly the case the finder is meant to
 * identify.
 */
final class FinderValuation {
    // Mirrors the server's published finder gates.
    static final double MIN_CONFIDENCE = 0.35;
    static final double MIN_SALES_PER_DAY = 2.0;

    private FinderValuation() {}

    /** Per-unit valuation bands. A null target means this row would not
     * clear the finder's evidence gates and must not be presented as a
     * recommendation (or copied into the auction sign). */
    record Result(Double target, double fair, double high, String blockedReason) {
        boolean backed() {
            return target != null;
        }
    }

    static Result from(JsonObject est, JsonObject response) {
        double pess = number(est, "pess");
        double base = number(est, "base");
        double opt = number(est, "opt");

        // Bazaar uses direct order-book values. Its pessimistic band is the
        // instasell price, so it is already the venue's conservative exit.
        if (response.has("bazaar")) {
            return pess > 0
                    ? new Result(pess, base, opt, null)
                    : new Result(null, base, opt, "no supported valuation");
        }

        if (response.has("fallback_from_mods")) {
            return new Result(null, base, opt, "exact bucket unavailable");
        }
        if (est.has("family") && !est.get("family").isJsonNull()
                && "exotic".equals(est.get("family").getAsString())) {
            return new Result(null, base, opt, "exotic · manual price");
        }
        double confidence = number(est, "conf");
        if (confidence < MIN_CONFIDENCE) {
            return new Result(null, base, opt, "confidence < finder gate");
        }
        double spd = number(est, "spd");
        if (spd < MIN_SALES_PER_DAY) {
            return new Result(null, base, opt, "liquidity < finder gate");
        }
        if (pess <= 0 || base <= 0) {
            return new Result(null, base, opt, "no supported valuation");
        }
        return new Result(pess, base, opt, null);
    }

    private static double number(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsDouble() : 0;
    }
}
