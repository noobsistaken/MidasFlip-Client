package dev.midasflip.client;

import java.util.List;
import java.util.Locale;

/**
 * The raw rule editor's data model (Phase 4, spec §17) — one predicate
 * over a flip's fields plus an action. Rules are plain data (field, op,
 * value, action) with a whitelisted vocabulary: imports and hand-edits
 * can express filters, never behavior. Nothing in a rule can reference
 * safety mode, commands, or anything outside flip display.
 *
 * Actions: "hide" (drop matching flips), "only" (if any only-rules
 * exist, a flip must match at least one), "star" (highlight on the HUD).
 * Edited in config/midasflip.json for now; rides in SF1 preset codes.
 */
public final class Rule {
    // Ordered so the in-game cycler steps through them predictably; the
    // whitelist guarantee is unchanged (contains-checks in valid()).
    public static final List<String> FIELDS = List.of(
            "net_profit", "confidence", "comps", "sales_per_day", "buy_price",
            "net_margin_pct", "score", "item_id", "family", "falling_knife");
    public static final List<String> OPS = List.of(">=", "<=", "==", "!=", "contains");
    public static final List<String> ACTIONS = List.of("star", "hide", "only");

    public String field = "";
    public String op = "";
    public String value = "";
    public String action = "";

    /** Structurally valid — unknown vocabulary never evaluates. */
    public boolean valid() {
        return field != null && FIELDS.contains(field)
                && op != null && OPS.contains(op)
                && action != null && ACTIONS.contains(action)
                && value != null && value.length() <= 48;
    }

    public boolean matches(Flip f) {
        if (!valid()) {
            return false;
        }
        Object v = extract(f);
        if (v instanceof String s) {
            String want = value.toLowerCase(Locale.ROOT);
            String have = s.toLowerCase(Locale.ROOT);
            return switch (op) {
                case "==" -> have.equals(want);
                case "!=" -> !have.equals(want);
                case "contains" -> have.contains(want);
                default -> false;
            };
        }
        double have = ((Number) v).doubleValue();
        double want;
        if (v instanceof Boolean b) { // falling_knife routed as boolean
            have = b ? 1 : 0;
        }
        try {
            want = "true".equalsIgnoreCase(value) ? 1
                    : "false".equalsIgnoreCase(value) ? 0
                    : Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return false;
        }
        return switch (op) {
            case ">=" -> have >= want;
            case "<=" -> have <= want;
            case "==" -> have == want;
            case "!=" -> have != want;
            default -> false;
        };
    }

    private Object extract(Flip f) {
        return switch (field) {
            case "buy_price" -> (double) f.buyPrice;
            case "net_profit" -> f.netProfit;
            case "net_margin_pct" -> f.netMarginPct;
            case "confidence" -> f.confidence;
            case "comps" -> (double) f.comps;
            case "sales_per_day" -> f.salesPerDay;
            case "score" -> f.score;
            case "item_id" -> f.itemId == null ? "" : f.itemId;
            case "family" -> f.family == null ? "" : f.family;
            case "falling_knife" -> f.fallingKnife ? 1.0 : 0.0;
            default -> "";
        };
    }

    /** Rule-set verdict for display filtering (hide/only). */
    public static boolean rulesAllow(List<Rule> rules, Flip f) {
        if (rules == null || rules.isEmpty()) {
            return true;
        }
        boolean anyOnly = false, matchedOnly = false;
        for (Rule r : rules) {
            if (!r.valid()) {
                continue;
            }
            if ("hide".equals(r.action) && r.matches(f)) {
                return false;
            }
            if ("only".equals(r.action)) {
                anyOnly = true;
                if (r.matches(f)) {
                    matchedOnly = true;
                }
            }
        }
        return !anyOnly || matchedOnly;
    }

    public static boolean starred(List<Rule> rules, Flip f) {
        if (rules == null) {
            return false;
        }
        for (Rule r : rules) {
            if (r.valid() && "star".equals(r.action) && r.matches(f)) {
                return true;
            }
        }
        return false;
    }
}
