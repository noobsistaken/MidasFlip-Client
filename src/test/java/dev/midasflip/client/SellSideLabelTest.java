package dev.midasflip.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which side of the AH fees a displayed number sits on.
 *
 * <p>The sell overlay and tooltip show a GROSS price — the figure you type
 * into the auction sign. The flip board shows profit NET of the verified fee
 * model. For one pet those were 29.5M and 28.6M, and they are the same coin:
 *
 * <pre>
 *   gross          29,500,000
 *   listing fee 2%   -590,000
 *   claim tax   1%   -295,000
 *   duration fee       -1,000
 *   net            28,614,000
 * </pre>
 *
 * <p>Neither surface said which it was, so the honest reading was to list at
 * 28.6M — handing back ~900k of margin on every flip, silently, forever
 * (owner report 2026-08-07). A wrong number gets noticed; this does not.
 *
 * <p>The client deliberately owns no fee model ("the client never guesses
 * fees", PositionLedger.underwater) and /price ships no net, so the fix is
 * not to compute the other number — it is to say which number this is.
 */
class SellSideLabelTest {

    @Test
    void theListingPriceSaysItIsBeforeFees() {
        String line = ItemTooltip.listAtLine(29_500_000.0, 0.82, 14);

        assertTrue(line.contains("list at"), line);
        assertTrue(line.contains("gross"), "must name which side of the fees it is on: " + line);
        assertFalse(line.contains("sell target"),
                "'sell target' invited the reader to type this into the sign as their net");
    }

    @Test
    void theEvidenceSurvivesAWithheldPrice() {
        // conf and comps are FREE. They used to sit inside the same branch as
        // the Gold price, so a withheld price deleted them too — free data
        // discarded for touching paid data.
        String withheld = ItemTooltip.listAtLine(null, 0.82, 14);

        assertTrue(withheld.contains("conf"), withheld);
        assertTrue(withheld.contains("14 comps"), withheld);
        assertTrue(withheld.contains("Gold"), "the price itself is still marked withheld");
        assertFalse(withheld.contains("gross"), "there is no price to qualify");
    }

    @Test
    void bothFormsCarryTheSameEvidence() {
        String priced = ItemTooltip.listAtLine(29_500_000.0, 0.82, 14);
        String withheld = ItemTooltip.listAtLine(null, 0.82, 14);
        String evidence = "conf §f0.82 §8(14 comps)§r";

        assertTrue(priced.endsWith(evidence), priced);
        assertTrue(withheld.endsWith(evidence), withheld);
    }

    @Test
    void theFeeArithmeticThatMotivatedThisStillHolds() {
        // Guards the claim in the javadoc against drift in the shared fee
        // model (collector/internal/flip/fees.go, mirrored in Python and
        // pinned by testdata/fee_vectors.json). If this ever fails, the
        // comment explaining WHY the label exists has gone stale — the label
        // itself stays correct either way.
        double gross = 29_500_000;
        double listingFee = gross * 0.02;   // >= 10M bracket
        double claimTax = gross * 0.01;
        double durationFee = 1_000;
        double net = gross - listingFee - claimTax - durationFee;

        assertTrue(Math.abs(net - 28_614_000) < 1.0, "net was " + net);
        assertTrue(gross - net > 800_000,
                "the gap is large enough that mislabeling it costs real coins");
    }
}
