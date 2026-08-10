package dev.midasflip.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Board columns must fit the width the game actually gives us.
 *
 * <p>Every table here was laid out against a ~590px root, which is 1080p at
 * GUI scale 3. Minecraft's Auto scale picks 4 on plenty of displays and a user
 * can pick 4 or 5 anywhere. At scale 4 the root is 480x270, the pane starts at
 * SIDEBAR_W+16=108, so 356px is usable against a flips table whose last column
 * sits at 442: AGE and the BUY/WATCH verdict chip were drawn past the edge of
 * the framebuffer and never seen (review 2026-08-10).
 */
class FitColsTest {

    private static final int[] FLIPS = {0, 145, 228, 290, 333, 369, 405, 442};
    private static final int CHIP = 44;

    @Test
    void aWideWindowKeepsTheDesignedSpacing() {
        assertArrayEquals(FLIPS, MidasflipShellScreen.fitCols(FLIPS, 516, CHIP),
                "1080p at scale 3 fits already and must not be touched");
        assertArrayEquals(FLIPS, MidasflipShellScreen.fitCols(FLIPS, 5000, CHIP),
                "never scale UP");
    }

    @Test
    void everyColumnFitsAtTheScaleThatUsedToTruncate() {
        // scale 4 on 1080p: root 480, pane origin 108 -> 356 usable
        int[] got = MidasflipShellScreen.fitCols(FLIPS, 356, CHIP);
        assertTrue(got[got.length - 1] + CHIP <= 356,
                "verdict chip still off-screen: last=" + got[got.length - 1]);
    }

    @Test
    void itSurvivesTheWorstScaleAUserCanPick() {
        // scale 5 on 1080p: root 384 -> 260 usable
        int[] got = MidasflipShellScreen.fitCols(FLIPS, 260, CHIP);
        assertTrue(got[got.length - 1] + CHIP <= 260, "last=" + got[got.length - 1]);
    }

    @Test
    void columnsStayInOrderAndStartAtZero() {
        for (int avail : new int[]{200, 260, 300, 356, 420, 516}) {
            int[] got = MidasflipShellScreen.fitCols(FLIPS, avail, CHIP);
            assertTrue(got[0] == 0, "first column must stay at the origin");
            for (int i = 1; i < got.length; i++) {
                assertTrue(got[i] > got[i - 1],
                        "columns collapsed onto each other at avail=" + avail);
            }
        }
    }

    @Test
    void aNonsenseWidthIsIgnoredRatherThanDividedBy() {
        assertArrayEquals(FLIPS, MidasflipShellScreen.fitCols(FLIPS, 0, CHIP));
        assertArrayEquals(FLIPS, MidasflipShellScreen.fitCols(FLIPS, -50, CHIP));
    }
}
