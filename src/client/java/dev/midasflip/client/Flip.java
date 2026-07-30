package dev.midasflip.client;

import com.google.gson.annotations.SerializedName;

/** One flip from the backend — the transparency fields ride along. */
public final class Flip {
    public String uuid;
    @SerializedName("item_id") public String itemId;
    @SerializedName("comp_key") public String compKey;
    public String family;
    @SerializedName("buy_price") public long buyPrice;
    public int count;
    @SerializedName("est_base") public double estBase;
    @SerializedName("est_pess") public Double estPess;
    @SerializedName("est_opt") public Double estOpt;
    @SerializedName("net_profit") public double netProfit;
    @SerializedName("net_margin_pct") public double netMarginPct;
    public double confidence;
    public int comps;
    public Double spread;   // bucket dispersion (relative MAD fraction)
    public String source;
    @SerializedName("sales_per_day") public double salesPerDay;
    @SerializedName("hold_med_s") public Long holdMedS;   // fair-cohort median
    @SerializedName("hold_p90_s") public Long holdP90S;   // honest slow tail
    @SerializedName("fill_pct") public Double fillPct;    // BIN sell-through %
    @SerializedName("falling_knife") public Boolean fallingKnife;
    /** "" / null = normal; "patient" = fast exit underwater on the
     *  pess-anchored quote, patient target still clears buy (P2-final).
     *  Display/filter only — the server already decided emission. */
    public String verdict;
    public String manip;                                  // "med" | "high" | null
    @SerializedName("manip_reasons") public String[] manipReasons;
    @SerializedName("pet_candy") public int petCandy;     // candies consumed (pets, 0-10)
    // Recommended exits. exit_fast/exit_patient are GROSS listing totals
    // (what you type in the sign). exit_*_net are NET PROFIT DELTAS vs
    // buy — buy is ALREADY subtracted server-side (flip.go attachExits,
    // pinned by TestExitNetIsAProfitDelta); render them directly, NEVER
    // subtract the buy price again (that shipped once as a fake -6.3M
    // loss on a +1.7M flip). Patient absent when converged with fast on
    // NORMAL flips only. P2-final: lines are never omitted; on a
    // "patient" verdict exit_fast_net is NEGATIVE (the explicit
    // "fast exit: -X%" line) and the patient line is always present.
    @SerializedName("exit_fast") public Double exitFast;
    @SerializedName("exit_fast_net") public Double exitFastNet;
    @SerializedName("exit_fast_hold_s") public Long exitFastHoldS;
    @SerializedName("exit_patient") public Double exitPatient;
    @SerializedName("exit_patient_net") public Double exitPatientNet;
    @SerializedName("exit_patient_hold_s") public Long exitPatientHoldS;
    public double score;
    @SerializedName("detected_at_ms") public long detectedAtMs;
    @SerializedName("auction_end_ms") public long auctionEndMs;

    /** Client receive time — staleness is measured from this, NOT the
     *  backend's clock (skew must not make the whole feed read stale). */
    public transient long receivedAtMs;

    /** Server-confirmed no-longer-listed (bought/ended), via the
     *  /flips/active reconciliation poll. Written off-thread, read on
     *  render — hence volatile. */
    public transient volatile boolean gone;

    /** When reconciliation first confirmed this flip is no longer listed
     *  (0 = still live). The HUD keeps a `gone` row visible with a "taken"
     *  marker for a short grace so a purchase is SEEN, not silently
     *  dropped (owner 2026-07-15). */
    public transient volatile long goneAtMs;

    /** Freshness states (spec §4). The age cap is a fallback for when
     *  reconciliation can't run (API unreachable) — reconciliation is
     *  what actually retires rows, within a snapshot tick of purchase. */
    public enum Freshness { FRESH, UNVERIFIED_LIVE, LIKELY_GONE }

    /** True while a reconciliation-confirmed-gone row should still be shown
     *  as "taken" (not the age-based likely-gone — that stays hidden). */
    public boolean withinTakenGrace(long graceMs) {
        return gone && goneAtMs > 0 && System.currentTimeMillis() - goneAtMs < graceMs;
    }

    public Freshness freshness() {
        if (gone) return Freshness.LIKELY_GONE;
        long age = System.currentTimeMillis() - receivedAtMs;
        if (age < 20_000) return Freshness.FRESH;
        if (age < 300_000) return Freshness.UNVERIFIED_LIVE;
        return Freshness.LIKELY_GONE;
    }

    public boolean likelyGone() {
        return freshness() == Freshness.LIKELY_GONE;
    }

    public long ageSeconds() {
        return Math.max(0, (System.currentTimeMillis() - receivedAtMs) / 1000);
    }
}
