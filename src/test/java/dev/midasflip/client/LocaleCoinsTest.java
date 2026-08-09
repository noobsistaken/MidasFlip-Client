package dev.midasflip.client;

import dev.midasflip.client.ui.Phos;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Coin formatting must not depend on the player's JVM locale.
 *
 * <p>Phos.coins is the one formatter every coin figure in the product routes
 * through. It used the default locale while every other format call in the
 * mod pins Locale.ROOT, so on a German or French JVM a single line rendered
 * "15,1M" next to a ROOT-formatted "2.4/day" — the two halves disagreeing
 * about the decimal separator, on numbers people screenshot and compare.
 */
class LocaleCoinsTest {

    private static String underLocale(Locale l, double v) {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(l);
            return Phos.coins(v);
        } finally {
            Locale.setDefault(before);
        }
    }

    @Test
    void coinsRenderIdenticallyInEveryLocale() {
        for (Locale l : new Locale[]{
                Locale.ROOT, Locale.US, Locale.GERMANY, Locale.FRANCE,
                Locale.forLanguageTag("pt-BR"), Locale.forLanguageTag("es-ES")}) {
            assertEquals("2.8M", underLocale(l, 2_800_000), "M scale under " + l);
            assertEquals("1.25B", underLocale(l, 1_250_000_000L), "B scale under " + l);
            assertEquals("500k", underLocale(l, 500_000), "k scale under " + l);
        }
    }

    @Test
    void aCommaDecimalLocaleDoesNotLeakIn() {
        // The concrete regression: German uses a comma decimal separator.
        assertEquals("15.1M", underLocale(Locale.GERMANY, 15_100_000));
        assertEquals("29.5M", underLocale(Locale.FRANCE, 29_500_000));
    }
}
