package dev.midasflip.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pet-identity parsing core. These strings mirror REAL Hypixel petInfo
 * payloads (a JSON string inside ExtraAttributes), including the traps the
 * collector documents: scientific-notation exp, whitespace wobble, and the
 * level-89 Mithril Golem regression that motivated exact-EXP identity
 * (10.3M exp = x2 bucket; the old display-level heuristic said x1).
 */
class PetInfoTextTest {

    private static final String MITHRIL_GOLEM =
            "{\"type\":\"MITHRIL_GOLEM\",\"active\":false,\"exp\":1.0348123E7,"
            + "\"tier\":\"LEGENDARY\",\"hideInfo\":false,\"candyUsed\":0}";

    @Test
    void expParsesScientificNotation() {
        assertEquals(1.0348123E7, PetInfoText.exp(MITHRIL_GOLEM));
    }

    @Test
    void expParsesPlainAndDecimalForms() {
        assertEquals(25000.0, PetInfoText.exp("{\"exp\":25000}"));
        assertEquals(0.0, PetInfoText.exp("{\"exp\":0.0}"));
        assertEquals(1.5, PetInfoText.exp("{ \"exp\" : 1.5 }")); // whitespace wobble
    }

    @Test
    void expRejectsGarbage() {
        assertNull(PetInfoText.exp(null));
        assertNull(PetInfoText.exp(""));
        assertNull(PetInfoText.exp("{\"type\":\"PARROT\"}"));       // absent
        assertNull(PetInfoText.exp("{\"exp\":\"not-a-number\"}"));  // wrong type
    }

    @Test
    void fieldsUppercaseAndBound() {
        assertEquals("MITHRIL_GOLEM", PetInfoText.field(MITHRIL_GOLEM, "type"));
        assertEquals("LEGENDARY", PetInfoText.field(MITHRIL_GOLEM, "tier"));
        assertNull(PetInfoText.field(MITHRIL_GOLEM, "skin")); // absent = null, never guessed
        assertEquals("GOLEM_SKIN", PetInfoText.field("{\"skin\":\"golem_skin\"}", "skin"));
    }

    @Test
    void candiedOnlyWhenPositive() {
        assertFalse(PetInfoText.candied(MITHRIL_GOLEM));            // candyUsed: 0
        assertTrue(PetInfoText.candied("{\"candyUsed\": 3}"));
        assertFalse(PetInfoText.candied("{\"type\":\"TIGER\"}"));   // absent -> clean bucket
        assertFalse(PetInfoText.candied(null));
    }

    @Test
    void tierBoostDetectedWhitespaceTolerant() {
        assertTrue(PetInfoText.tierBoosted("{\"heldItem\":\"PET_ITEM_TIER_BOOST\"}"));
        assertTrue(PetInfoText.tierBoosted("{ \"heldItem\" : \"PET_ITEM_TIER_BOOST\" }"));
        assertFalse(PetInfoText.tierBoosted("{\"heldItem\":\"PET_ITEM_LUCKY_CLOVER\"}"));
        assertFalse(PetInfoText.tierBoosted(null));
    }
}
