package dev.midasflip.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.midasflip.client.ui.Phos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every render-path JSON read, against the three shapes a field can arrive
 * in: PRESENT, ABSENT, and present-but-JSON-NULL.
 *
 * <p>These paths have no try/catch above them, so a throw here is not a
 * blank line — it is the game closing (that happened on 2026-08-06). Read
 * raw, {@code o.get(k).getAsDouble()} NPEs when the key is absent and throws
 * UnsupportedOperationException when it is an explicit null, and
 * {@code has(k)} does NOT protect against the second: it returns true for a
 * JSON null.
 *
 * <p>Measured against the live API on 2026-08-09: absent keys are real and
 * common (a BAZAAR estimate carries only base/opt/pess), while JSON nulls
 * appear nowhere in /price or /flips — the server omits. So the null column
 * of this table is currently unreachable in production and becomes reachable
 * the day server-side tier shaping starts emitting nulls. Every PRESENT case
 * below therefore doubles as a regression lock: the hardening was not
 * allowed to change what today's payloads render.
 */
class NullTolerantRenderTest {

    private static JsonObject o(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static JsonElement el(String json) {
        return JsonParser.parseString(json);
    }

    @BeforeEach
    void compactCoins() {
        // Phos.coins has a global raw/compact switch and the suite shares a
        // JVM; pin it so these assertions do not depend on test order.
        Phos.setRawCoins(false);
    }

    // ---- ItemTooltip.evidenceLine ----------------------------------------

    @Test
    void evidenceLineRendersTodaysAhEstimateUnchanged() {
        assertEquals("§7conf §f0.82 §8· 14 comps · 3.2 sold/day§r",
                ItemTooltip.evidenceLine(o("{\"conf\":0.82,\"comps\":14,\"spd\":3.2}")));
    }

    @Test
    void evidenceLineWithoutVelocityDropsTheTailAsItAlwaysDid() {
        assertEquals("§7conf §f0.82 §8· 14 comps§r",
                ItemTooltip.evidenceLine(o("{\"conf\":0.82,\"comps\":14}")));
    }

    @Test
    void evidenceLineSurvivesAbsentAndNullConfidence() {
        // The bazaar-shaped estimate: base/opt/pess only. It cannot reach
        // this line today (bazaar responses take the other branch), which is
        // exactly why the shape must be safe rather than assumed away.
        String absent = assertDoesNotThrow(
                () -> ItemTooltip.evidenceLine(o("{\"base\":1,\"opt\":2,\"pess\":0.5}")));
        String nulled = assertDoesNotThrow(
                () -> ItemTooltip.evidenceLine(o("{\"conf\":null,\"comps\":null,\"spd\":null}")));
        String missing = assertDoesNotThrow(() -> ItemTooltip.evidenceLine(null));
        assertEquals(GoldFields.unknown("conf") + "§r", absent);
        assertEquals(absent, nulled);
        assertEquals(absent, missing);
    }

    @Test
    void aMissingConfidenceIsNeverSoldBackToTheUser() {
        // conf and comps are FREE fields. Rendering a paywall over data
        // nobody is gating would be a lie at a free launch.
        assertFalse(ItemTooltip.evidenceLine(o("{}")).contains("Gold"));
    }

    // ---- ItemTooltip.craftLine -------------------------------------------

    @Test
    void craftLineRendersACompleteRowUnchanged() {
        assertEquals("§7est. craft §f2.0M §8· forge§r",
                ItemTooltip.craftLine(o("{\"cost\":4000000,\"n\":2,\"kind\":\"forge\"}"), 1));
        // Stack totals: the same row on a stack of three.
        assertEquals("§7est. craft §f6.0M §8· forge§r",
                ItemTooltip.craftLine(o("{\"cost\":4000000,\"n\":2,\"kind\":\"forge\"}"), 3));
        // Absent kind keeps its existing default.
        assertEquals("§7est. craft §f2.0M §8· craft§r",
                ItemTooltip.craftLine(o("{\"cost\":4000000,\"n\":2}"), 1));
    }

    @Test
    void craftLineOmitsItselfRatherThanThrowing() {
        // /craft/costs answers 404 in production today, so craftRow returns
        // null and this never runs — which is the whole reason it has to be
        // safe before the endpoint lands.
        assertNull(assertDoesNotThrow(() -> ItemTooltip.craftLine((JsonObject) null, 1)));
        assertNull(assertDoesNotThrow(() -> ItemTooltip.craftLine(o("{\"n\":2}"), 1)));
        assertNull(assertDoesNotThrow(() -> ItemTooltip.craftLine(o("{\"cost\":4000000}"), 1)));
        assertNull(assertDoesNotThrow(
                () -> ItemTooltip.craftLine(o("{\"cost\":null,\"n\":2}"), 1)));
        assertNull(assertDoesNotThrow(
                () -> ItemTooltip.craftLine(o("{\"cost\":4000000,\"n\":null}"), 1)));
        // Unchanged: a zero yield was already refused (no division by zero).
        assertNull(ItemTooltip.craftLine(o("{\"cost\":4000000,\"n\":0}"), 1));
    }

    // ---- MidasflipShellScreen.estExitLine (comps peek) -------------------

    @Test
    void estExitLineRendersTodaysDrillDownUnchanged() {
        String line = MidasflipShellScreen.estExitLine(
                o("{\"pess\":2000000,\"conf\":0.5,\"comps\":9,\"src\":\"comps\",\"outliers\":3}"));
        assertTrue(line.startsWith("§aEST EXIT 2.0M§r §8· conf "), line);
        assertTrue(line.endsWith(" · 9 comps · 3 rejected · comps§r"), line);
    }

    @Test
    void estExitLineSurvivesAbsentAndNullEvidence() {
        String absent = assertDoesNotThrow(
                () -> MidasflipShellScreen.estExitLine(o("{\"pess\":2000000}")));
        String nulled = assertDoesNotThrow(() -> MidasflipShellScreen.estExitLine(
                o("{\"pess\":2000000,\"conf\":null,\"comps\":null,\"src\":null,\"outliers\":null}")));
        assertEquals("§aEST EXIT 2.0M§r · " + GoldFields.unknown("conf") + " · comps§r", absent);
        assertEquals(absent, nulled);
        assertDoesNotThrow(() -> MidasflipShellScreen.estExitLine(null));
    }

    @Test
    void estExitKeepsItsLockOnlyForTheWithheldPriceBand() {
        // pess IS a Gold field, so its lock stays. conf and comps are free
        // and must never acquire one.
        String line = MidasflipShellScreen.estExitLine(o("{}"));
        assertTrue(line.startsWith(GoldFields.locked("EST EXIT")), line);
        assertTrue(line.contains(GoldFields.unknown("conf")), line);
    }

    // ---- MidasflipShellScreen.saleLine (comps peek rows) -----------------

    @Test
    void saleLineRendersARecentCompUnchanged() {
        long ended = java.time.Instant.parse("2026-08-09T12:00:00Z").toEpochMilli();
        assertEquals("2.0M §8· 1h30m · exact§r", MidasflipShellScreen.saleLine(
                el("{\"unit_price\":2000000,\"ended_at\":\"2026-08-09T12:00:00\"}"),
                ended + 90 * 60_000));
        // The server's own suffixed form parses to the same instant.
        assertEquals("2.0M §8· 1h30m · exact§r", MidasflipShellScreen.saleLine(
                el("{\"unit_price\":2000000,\"ended_at\":\"2026-08-09T12:00:00Z\"}"),
                ended + 90 * 60_000));
    }

    @Test
    void anUnreadableCompIsDroppedNotGuessedAt() {
        long now = System.currentTimeMillis();
        assertNull(assertDoesNotThrow(() -> MidasflipShellScreen.saleLine(
                el("{\"ended_at\":\"2026-08-09T12:00:00Z\"}"), now)));
        assertNull(assertDoesNotThrow(() -> MidasflipShellScreen.saleLine(
                el("{\"unit_price\":2000000}"), now)));
        assertNull(assertDoesNotThrow(() -> MidasflipShellScreen.saleLine(
                el("{\"unit_price\":null,\"ended_at\":null}"), now)));
        assertNull(assertDoesNotThrow(() -> MidasflipShellScreen.saleLine(
                el("{\"unit_price\":2000000,\"ended_at\":\"not a date\"}"), now)));
        assertNull(assertDoesNotThrow(() -> MidasflipShellScreen.saleLine(el("\"row\""), now)));
        assertNull(assertDoesNotThrow(() -> MidasflipShellScreen.saleLine(null, now)));
    }

    // ---- MidasflipShellScreen.bidDetailLine (auctions board) -------------

    @Test
    void bidDetailLineRendersTodaysAuctionRowUnchanged() {
        assertEquals("§7est exit 2.0M §8· conf 0.50 · 9 comps · 4/d§r",
                MidasflipShellScreen.bidDetailLine(o(
                        "{\"est_pess\":2000000,\"conf\":0.5,\"comps\":9,\"sales_per_day\":4}")));
    }

    @Test
    void bidDetailLineKeepsItsZeroDefaultsForAbsentAndNull() {
        String absent = assertDoesNotThrow(
                () -> MidasflipShellScreen.bidDetailLine(o("{\"est_pess\":2000000}")));
        String nulled = assertDoesNotThrow(() -> MidasflipShellScreen.bidDetailLine(o(
                "{\"est_pess\":2000000,\"conf\":null,\"comps\":null,\"sales_per_day\":null}")));
        assertEquals("§7est exit 2.0M §8· conf 0.00 · 0 comps · 0/d§r", absent);
        assertEquals(absent, nulled);
        assertTrue(assertDoesNotThrow(() -> MidasflipShellScreen.bidDetailLine(o("{}")))
                .startsWith(GoldFields.locked("est exit")));
    }

    // ---- MidasflipShellScreen.eventChip ----------------------------------

    @Test
    void eventChipRendersTodaysWindowsUnchanged() {
        assertEquals("§e◆ Dark Auction · active§r", MidasflipShellScreen.eventChip(
                el("{\"windows\":[{\"name\":\"Dark Auction\",\"active\":true}]}"), 0L));
        long starts = java.time.Instant.parse("2026-08-09T12:00:00Z").toEpochMilli();
        assertEquals("§8◇ Spooky Festival in 1h30m§r", MidasflipShellScreen.eventChip(
                el("{\"windows\":[{\"name\":\"Spooky Festival\",\"active\":false,"
                        + "\"starts\":\"2026-08-09T12:00:00Z\"}]}"),
                starts - 90 * 60_000));
    }

    @Test
    void anUnreadableEventWindowJustMeansNoChip() {
        // "" is what an empty windows array already produced, so the header
        // loses an ornament rather than the player losing the game.
        assertEquals("", assertDoesNotThrow(
                () -> MidasflipShellScreen.eventChip(el("{\"windows\":null}"), 0L)));
        assertEquals("", assertDoesNotThrow(
                () -> MidasflipShellScreen.eventChip(el("{}"), 0L)));
        assertEquals("", assertDoesNotThrow(
                () -> MidasflipShellScreen.eventChip(el("{\"windows\":[{}]}"), 0L)));
        assertEquals("", assertDoesNotThrow(() -> MidasflipShellScreen.eventChip(
                el("{\"windows\":[{\"name\":null,\"active\":null}]}"), 0L)));
        assertEquals("", assertDoesNotThrow(() -> MidasflipShellScreen.eventChip(
                el("{\"windows\":[{\"name\":\"X\",\"starts\":null}]}"), 0L)));
        assertEquals("", assertDoesNotThrow(() -> MidasflipShellScreen.eventChip(
                el("{\"windows\":[{\"name\":\"X\",\"starts\":\"soonish\"}]}"), 0L)));
        assertEquals("", assertDoesNotThrow(
                () -> MidasflipShellScreen.eventChip(el("{\"windows\":[\"X\"]}"), 0L)));
        assertEquals("", assertDoesNotThrow(() -> MidasflipShellScreen.eventChip(null, 0L)));
    }

    // ---- SellOverlay.gearNote --------------------------------------------

    @Test
    void gearNoteRendersTodaysOverlayUnchanged() {
        assertEquals("§8recomb bucket · 12 comps · conf 0.77 · 3.0/day§r",
                SellOverlay.gearNote(o("{\"comp_key\":\"v1|SWORD|s0|r1|x\"}"),
                        o("{\"comps\":12,\"conf\":0.77}"), " · 3.0/day"));
        assertEquals("§8starred bucket · 12 comps · conf 0.77§r",
                SellOverlay.gearNote(o("{\"comp_key\":\"v1|SWORD|s5|r0|x\"}"),
                        o("{\"comps\":12,\"conf\":0.77}"), ""));
        assertEquals("§8clean bucket · 12 comps · conf 0.77§r",
                SellOverlay.gearNote(o("{\"comp_key\":\"v1|SWORD|s0|r0|x\"}"),
                        o("{\"comps\":12,\"conf\":0.77}"), ""));
    }

    @Test
    void gearNoteSurvivesAbsentAndNullEvidenceAndKey() {
        String absent = assertDoesNotThrow(
                () -> SellOverlay.gearNote(o("{}"), o("{\"base\":1}"), ""));
        String nulled = assertDoesNotThrow(() -> SellOverlay.gearNote(
                o("{\"comp_key\":null}"), o("{\"comps\":null,\"conf\":null}"), ""));
        assertEquals("§8clean bucket · comps · not available§r", absent);
        assertEquals(absent, nulled);
        assertDoesNotThrow(() -> SellOverlay.gearNote(null, null, ""));
        assertFalse(absent.contains("Gold"));
    }

    // ---- ListingsWatch row parsing ---------------------------------------

    @Test
    void parseMineReadsTodaysListingUnchanged() {
        ListingsWatch.Mine m = ListingsWatch.parseMine(el("{\"auction_uuid\":\"abc\","
                + "\"item_id\":\"HYPERION\",\"comp_key\":\"v1|X\",\"price\":1000,"
                + "\"unit_price\":500.5,\"status\":\"undercut\",\"floor_unit\":490,"
                + "\"hold_med_s\":1200,\"listed_s\":60}"));
        assertNotNull(m);
        assertEquals("abc", m.auctionUuid());
        assertEquals("HYPERION", m.itemId());
        assertEquals("v1|X", m.compKey());
        assertEquals(1000L, m.price());
        assertEquals(500.5, m.unitPrice());
        assertTrue(m.undercut());
        assertEquals(490.0, m.floorUnit());
        assertEquals(1200L, m.holdMedS());
        assertEquals(60L, m.listedS());
        // Absent optionals keep the defaults they always had.
        assertNull(m.suggestUnit());
        assertNull(m.holdP90S());
    }

    @Test
    void parseMineDropsARowItCannotJudge() {
        // No price, unit price or status means it cannot be called undercut
        // or stale and cannot be compared to the floor — the only things the
        // row exists to say.
        assertNull(assertDoesNotThrow(() -> ListingsWatch.parseMine(
                el("{\"item_id\":\"X\",\"price\":1,\"unit_price\":1,\"status\":\"ok\"}"))));
        assertNull(assertDoesNotThrow(() -> ListingsWatch.parseMine(
                el("{\"auction_uuid\":\"a\",\"unit_price\":1,\"status\":\"ok\"}"))));
        assertNull(assertDoesNotThrow(() -> ListingsWatch.parseMine(
                el("{\"auction_uuid\":\"a\",\"price\":null,\"unit_price\":null,"
                        + "\"status\":null}"))));
        assertNull(assertDoesNotThrow(() -> ListingsWatch.parseMine(el("\"row\""))));
        assertNull(assertDoesNotThrow(() -> ListingsWatch.parseMine(null)));
        // A null item_id or comp_key is not fatal — those already had
        // fallbacks and keep them.
        ListingsWatch.Mine m = ListingsWatch.parseMine(el("{\"auction_uuid\":\"a\","
                + "\"item_id\":null,\"comp_key\":null,\"price\":1,\"unit_price\":1,"
                + "\"status\":\"ok\",\"hold_med_s\":null,\"listed_s\":null}"));
        assertNotNull(m);
        assertEquals("?", m.itemId());
        assertEquals("", m.compKey());
        assertNull(m.holdMedS());
        assertEquals(0L, m.listedS());
    }

    @Test
    void parseSoldReadsTodaysSaleAndDropsUnreadableOnes() {
        ListingsWatch.Sold s = ListingsWatch.parseSold(el("{\"auction_uuid\":\"abc\","
                + "\"item_id\":\"HYPERION\",\"price\":7000,\"net\":6800,"
                + "\"ended_at\":\"2026-08-09T12:00:00Z\"}"));
        assertNotNull(s);
        assertEquals("abc", s.auctionUuid());
        assertEquals(7000L, s.price());
        assertEquals(6800.0, s.net());
        assertEquals(java.time.Instant.parse("2026-08-09T12:00:00Z").toEpochMilli(),
                s.endedAtMs());
        assertNull(assertDoesNotThrow(() -> ListingsWatch.parseSold(
                el("{\"auction_uuid\":\"a\",\"price\":null}"))));
        assertNull(assertDoesNotThrow(() -> ListingsWatch.parseSold(el("{\"price\":1}"))));
        assertNull(assertDoesNotThrow(() -> ListingsWatch.parseSold(el("[]"))));
        // An unreadable timestamp already fell back to "now" rather than 0.
        assertNotNull(assertDoesNotThrow(() -> ListingsWatch.parseSold(
                el("{\"auction_uuid\":\"a\",\"price\":1,\"ended_at\":null}"))));
    }

    // ---- FinderValuation -------------------------------------------------

    @Test
    void finderValuationBlocksRatherThanThrowingOnAnEstimatelessResponse() {
        // ItemTooltip and SellOverlay both hand this an `estimate` that may
        // now legitimately be null. A blocked result is the honest answer:
        // no evidence means no recommendation.
        FinderValuation.Result r = assertDoesNotThrow(
                () -> FinderValuation.from(null, o("{}")));
        assertFalse(r.backed());
        assertEquals("confidence < finder gate", r.blockedReason());
        // A null family must not throw where a string comparison used to be.
        assertDoesNotThrow(() -> FinderValuation.from(
                o("{\"family\":null,\"conf\":null,\"spd\":null,\"pess\":null,\"base\":null}"),
                o("{}")));
        // Today's exotic gate is unchanged.
        assertEquals("exotic · manual price",
                FinderValuation.from(o("{\"family\":\"exotic\"}"), o("{}")).blockedReason());
    }

    // ---- PositionLedger revaluation --------------------------------------

    @Test
    void positionRevaluationKeepsTheLastKnownPriceInsteadOfCrashing() {
        // PositionLedger.revalue() reads exactly this expression from each
        // /value response, inside the HUD loop. `has("pess")` is true for a
        // JSON null and the getAsDouble that followed it threw.
        assertEquals(2000000.0, pess("{\"estimate\":{\"pess\":2000000}}"));
        assertNull(assertDoesNotThrow(() -> pess("{\"estimate\":{\"pess\":null}}")));
        assertNull(assertDoesNotThrow(() -> pess("{\"estimate\":{}}")));
        assertNull(assertDoesNotThrow(() -> pess("{\"estimate\":null}")));
        assertNull(assertDoesNotThrow(() -> pess("{}")));
    }

    private static Double pess(String json) {
        return GoldFields.optNum(GoldFields.optObj(o(json), "estimate"), "pess");
    }
}
