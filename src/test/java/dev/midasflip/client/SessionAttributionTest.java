package dev.midasflip.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Which purchase belongs to which opened flip.
 *
 * <p>The chat line carries two signals — the item name and the price — and the
 * old matcher used only the price, then fell back to "whatever was opened most
 * recently" when nothing matched. Every position in the ledger, and every coin
 * of session profit, rests on this function getting it right. Booking a
 * purchase that was never ours is worse than missing one of ours: a missed
 * purchase is a gap, an invented one is a lie in the cost basis.
 */
class SessionAttributionTest {

    /** attribute() touches neither the ledger nor the config. */
    private static SessionTracker tracker() {
        return new SessionTracker(null, null);
    }

    private static Flip flip(String itemId, long buyPrice) {
        Flip f = new Flip();
        f.uuid = itemId + "-" + buyPrice;
        f.itemId = itemId;
        f.buyPrice = buyPrice;
        return f;
    }

    @Test
    void nameAndPriceTogetherPickTheRightFlip() {
        SessionTracker t = tracker();
        Flip wand = flip("INK_WAND", 1_300_000);
        Flip legs = flip("MAGMA_LORD_LEGGINGS", 42_000_000);
        t.onOpened(wand);
        t.onOpened(legs);

        assertSame(legs, t.attribute("Ancient Magma Lord Leggings ✪✪✪", 42_000_000));
        assertSame(wand, t.attribute("◆ Ink Wand ✦", 1_300_000));
    }

    @Test
    void twoFlipsAtTheSamePriceDoNotCollapseOntoOne() {
        // The bug: price alone matched, so the first-listed open absorbed
        // both purchases — one position duplicated, the other never opened,
        // and the wrong row marked gone on the board.
        SessionTracker t = tracker();
        Flip helmet = flip("SUPERIOR_DRAGON_HELMET", 5_000_000);
        Flip boots = flip("SUPERIOR_DRAGON_BOOTS", 5_000_000);
        t.onOpened(helmet);
        t.onOpened(boots);

        assertSame(boots, t.attribute("Superior Dragon Boots", 5_000_000));
        assertSame(helmet, t.attribute("Superior Dragon Helmet", 5_000_000));
    }

    @Test
    void oneOpenCannotAbsorbTwoPurchases() {
        // Matched opens were never removed, so buying the same item twice at
        // the same price booked the position — and its estimated profit —
        // twice off a single open.
        SessionTracker t = tracker();
        Flip wand = flip("INK_WAND", 1_300_000);
        t.onOpened(wand);

        assertSame(wand, t.attribute("Ink Wand", 1_300_000));
        assertNull(t.attribute("Ink Wand", 1_300_000),
                "the open was consumed by the first purchase");
    }

    @Test
    void aPurchaseWeNeverBrokeredIsNotOurs() {
        // Buying something by hand, in the same session, must not appear in
        // the ledger with an invented cost basis and profit estimate.
        SessionTracker t = tracker();
        t.onOpened(flip("INK_WAND", 1_300_000));

        assertNull(t.attribute("Hyperion", 900_000_000));
        assertNull(t.attribute("Necron's Blade", 1_300_001),
                "neither the name nor the price line up with anything we opened");
    }

    @Test
    void withNothingOpenedNothingIsAttributed() {
        assertNull(tracker().attribute("Ink Wand", 1_300_000));
    }

    @Test
    void anExactPriceStillWinsWhenTheNameLooksDifferent() {
        // Deliberate: Hypixel's chat name and our own rendering disagree
        // often (pets, ability glyphs, names we have no /names entry for), so
        // a name mismatch alone must not throw away a real purchase. The
        // price is the strong signal and it matched to the coin.
        SessionTracker t = tracker();
        Flip pet = flip("PET", 12_500_000);
        pet.compKey = "v1|PET|TIGER|LEGENDARY|x2";
        t.onOpened(pet);

        assertSame(pet, t.attribute("[Lvl 87] Tiger", 12_500_000));
    }

    @Test
    void theNameCarriesTheMatchWhenThePriceDoesNot() {
        // Tier 3: the price failed to line up (a misparse, or Hypixel
        // reporting a different figure) but the item is unmistakably the one
        // we just opened, seconds ago.
        SessionTracker t = tracker();
        Flip legs = flip("MAGMA_LORD_LEGGINGS", 42_000_000);
        t.onOpened(legs);

        assertSame(legs, t.attribute("Magma Lord Leggings", 41_999_999));
    }

    @Test
    void aBlankNameNeverMatchesEverything() {
        // norm("") is empty, and an empty want must not silently equal an
        // empty rendering — that would make every purchase match the first
        // open, which is the bug this function replaced.
        SessionTracker t = tracker();
        Flip f = flip("INK_WAND", 1_300_000);
        t.onOpened(f);

        assertNull(t.attribute("✦✦✦", 999));
        assertSame(f, t.attribute("Ink Wand", 1_300_000), "the open survived the non-match");
    }

    @Test
    void namesNormalizeAcrossColourStarsAndStackPrefixes() {
        assertEquals("ANCIENT_MAGMA_LORD_LEGGINGS",
                SessionTracker.norm("§6Ancient Magma Lord Leggings ✪✪✪✪✪"));
        assertEquals("ENCHANTED_DIAMOND", SessionTracker.norm("64x Enchanted Diamond"));
        assertEquals("INK_WAND", SessionTracker.norm("◆ Ink Wand ✦"));
        assertEquals("", SessionTracker.norm(null));
        // The de-reforged form is the SECOND thing tried, never the only one.
        assertEquals("MAGMA_LORD_LEGGINGS",
                SessionTracker.normDereforged("§6Ancient Magma Lord Leggings ✪✪✪✪✪"));
        assertEquals("", SessionTracker.normDereforged(null));
    }

    @Test
    void theFullChatLineIsWhatDrivesIt() {
        // The PRODUCTION pattern, not a copy of it. An earlier version of this
        // test compiled its own regex and would have stayed green through any
        // change to the real one (review 2026-08-06).
        var m = SessionTracker.PURCHASED.matcher("You purchased ◆ Ink Wand ✦ for 1,300,000 coins!");
        assertNotNull(m);
        org.junit.jupiter.api.Assertions.assertTrue(m.matches());
        assertEquals("◆ Ink Wand ✦", m.group(1));
        assertEquals("1,300,000", m.group(2));
    }

    // ---- reforge words that are also set names ----------------------------
    // `wise`, `strong`, `superior` and `ancient` are all in SellOverlay's
    // REFORGES set AND are literal Dragon-armor set names. Stripping them
    // from both sides collapsed three distinct, differently-priced armor sets
    // onto one key. Every test below fails against that version.

    @Test
    void theDragonSetsDoNotCollapseIntoEachOther() {
        assertEquals("WISE_DRAGON_BOOTS", SessionTracker.norm("Wise Dragon Boots"));
        assertEquals("SUPERIOR_DRAGON_BOOTS", SessionTracker.norm("Superior Dragon Boots"));
        assertEquals("STRONG_DRAGON_BOOTS", SessionTracker.norm("Strong Dragon Boots"));
    }

    @Test
    void buyingASuperiorIsNotBookedAgainstAnOpenWise() {
        // The verified misbooking: one open Wise flip, a manual Superior
        // purchase at a different price within the window. Tiers 1-2 miss on
        // price; tier 3 used to match on a shared DRAGON_BOOTS key and write
        // a position with the Wise flip's item id and profit estimate.
        SessionTracker t = tracker();
        t.onOpened(flip("WISE_DRAGON_BOOTS", 3_000_000));

        assertNull(t.attribute("Superior Dragon Boots", 5_000_000));
    }

    @Test
    void twoDragonSetsAtOnePriceAreToldApart() {
        SessionTracker t = tracker();
        Flip superior = flip("SUPERIOR_DRAGON_BOOTS", 5_000_000);
        Flip wise = flip("WISE_DRAGON_BOOTS", 5_000_000);
        t.onOpened(superior);
        t.onOpened(wise); // most recent — what the old code always returned

        assertSame(superior, t.attribute("Superior Dragon Boots", 5_000_000));
        assertSame(wise, t.attribute("Wise Dragon Boots", 5_000_000));
    }

    @Test
    void theReforgeFallbackBreaksATieAtASharedPrice() {
        // The test that gives the fallback something to DO. With two opens at
        // the same price, tier 2 would take whichever sits first in the deque
        // — the Wise one, since onOpened pushes to the front. Only the
        // reforge-stripped comparison in tier 1 can pick the Superior out,
        // so deleting the fallback turns this red (the previous tests all
        // stayed green without it, review 2026-08-06).
        SessionTracker t = tracker();
        Flip superior = flip("SUPERIOR_DRAGON_HELMET", 8_000_000);
        Flip wise = flip("WISE_DRAGON_HELMET", 8_000_000);
        t.onOpened(superior);
        t.onOpened(wise);

        assertSame(superior, t.attribute("Fierce Superior Dragon Helmet", 8_000_000));
        assertSame(wise, t.attribute("Ancient Wise Dragon Helmet", 8_000_000));
    }

    @Test
    void aReforgedSetNameStillMatchesItsFlip() {
        // Hypixel prefixes the reforge onto the full name, so the fallback
        // has to strip the CHAT side only — our own name never carries one.
        // Stripping both sides turned "Fierce Superior Dragon Helmet" into
        // SUPERIOR_DRAGON_HELMET on one side and DRAGON_HELMET on the other,
        // which could never match. (With a single open this would also pass
        // via tier 2 on the price alone; the tiebreaker test above is the one
        // that pins the fallback itself.)
        SessionTracker t = tracker();
        Flip helm = flip("SUPERIOR_DRAGON_HELMET", 8_000_000);
        t.onOpened(helm);

        assertSame(helm, t.attribute("Fierce Superior Dragon Helmet", 8_000_000));
    }

    @Test
    void theReforgeFallbackNeedsThePriceToCorroborateIt() {
        // Name-only (tier 3) writes a position off the name alone, so it
        // takes the exact form only. Looseness scales with corroboration.
        SessionTracker t = tracker();
        t.onOpened(flip("MAGMA_LORD_LEGGINGS", 42_000_000));

        // price matches → the reforge fallback is allowed
        assertNotNull(t.attribute("Ancient Magma Lord Leggings", 42_000_000));

        SessionTracker u = tracker();
        u.onOpened(flip("MAGMA_LORD_LEGGINGS", 42_000_000));
        // price does NOT match → only the exact name would do, and this isn't
        assertNull(u.attribute("Ancient Magma Lord Leggings", 41_000_000));
        // ...but the unreforged name still is
        assertNotNull(u.attribute("Magma Lord Leggings", 41_000_000));
    }
}
