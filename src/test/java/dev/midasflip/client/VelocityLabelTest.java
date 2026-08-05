package dev.midasflip.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The board's velocity column: how fast a bucket actually turns over.
 *
 * <p>Two things are load-bearing. The unit follows the volume, because
 * "300/d" and "12/h" are the same fact but only one of them reads like a
 * fast item. And an unknown velocity renders as ABSENT, never as "0/d" —
 * zero is a claim that the item does not sell, which is a different and
 * much stronger statement than "we do not know".
 */
class VelocityLabelTest {

    private static String at(double spd) {
        Flip f = new Flip();
        f.salesPerDay = spd;
        return f.velocityLabel();
    }

    @Test
    void slowItemsKeepADecimal() {
        // 2.4/d and 9/d are a slow flip and a brisk one. Rounded to integers
        // they would read far more alike than they are.
        assertEquals("2.4/d", at(2.4));
        assertEquals("0.5/d", at(0.5));
        assertEquals("9.9/d", at(9.9));
    }

    @Test
    void middleVolumeRoundsToWholeDays() {
        assertEquals("10/d", at(10.0));
        assertEquals("13/d", at(13.0));
        assertEquals("23/d", at(23.4));
    }

    @Test
    void highVolumeSwitchesToHours() {
        // 24/d is exactly 1/h — the first point where the hourly figure
        // stops rounding to nothing, which is why the switch sits there.
        assertEquals("1/h", at(24.0));
        assertEquals("13/h", at(300.0));
        assertEquals("42/h", at(1000.0));
    }

    @Test
    void theSwitchHappensOnTheRawValueNotTheRoundedOne() {
        // Documented quirk rather than a bug: rounding runs independently of
        // the unit switch, so 23.9/d rounds up and reads "24/d" immediately
        // before 24.0/d becomes "1/h". Both are the same rate stated two
        // ways, and both are true. Asserted so nobody "fixes" it into
        // flooring, which would make 23.9 read as "23/d" and lose a sale.
        assertEquals("24/d", at(23.9));
        assertEquals("1/h", at(24.0));
    }

    @Test
    void unknownVelocityIsAbsentNotZero() {
        assertEquals("", at(0.0));
        assertEquals("", at(-1.0));
        assertEquals("", at(Double.NaN));
        assertEquals("", at(Double.POSITIVE_INFINITY));
    }

    @Test
    void labelNeverCarriesFormattingCodes() {
        // It is interpolated into a §-formatted row; a stray code here would
        // recolour the rest of the line.
        for (double spd : new double[]{0.5, 13, 300, 0}) {
            assertEquals(-1, at(spd).indexOf('§'), "colour code at spd=" + spd);
        }
    }
}
