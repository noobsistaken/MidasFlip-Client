package dev.midasflip.client;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Config JSON round-trip + the legacy-token migration extractor. The
 * load-bearing invariant: the API token is TRANSIENT — it must never
 * appear in serialized config JSON again (users paste midasflip.json into
 * support channels; plaintext tokens there were the top leak vector).
 */
class MidasflipConfigTest {

    private static final Gson GSON = new Gson();

    @Test
    void apiTokenNeverSerializes() {
        MidasflipConfig cfg = new MidasflipConfig();
        cfg.apiToken = "sk_super_secret_value";
        String json = GSON.toJson(cfg);
        assertFalse(json.contains("sk_super_secret_value"), "token leaked into config JSON");
        assertFalse(json.contains("apiToken"), "transient field name still serialized");
    }

    @Test
    void apiTokenNeverDeserializes() {
        // A hostile/legacy file cannot inject the in-memory token via Gson.
        MidasflipConfig cfg = GSON.fromJson("{\"apiToken\":\"sk_planted\"}", MidasflipConfig.class);
        assertEquals("", cfg.apiToken);
    }

    @Test
    void roundTripPreservesRepresentativeFields() {
        MidasflipConfig cfg = new MidasflipConfig();
        cfg.verdictConfPct = 91;
        cfg.auctionMaxRows = 5;
        cfg.hudOffsetX = 42;
        MidasflipConfig back = GSON.fromJson(GSON.toJson(cfg), MidasflipConfig.class);
        assertEquals(91, back.verdictConfPct);
        assertEquals(5, back.auctionMaxRows);
        assertEquals(42, back.hudOffsetX);
    }

    @Test
    void legacyTokenExtraction() {
        assertEquals("sk_old", MidasflipConfig.legacyToken("{\"apiToken\":\"sk_old\",\"x\":1}"));
        assertEquals("", MidasflipConfig.legacyToken("{\"x\":1}"));
        assertEquals("", MidasflipConfig.legacyToken("not json at all"));
        assertEquals("", MidasflipConfig.legacyToken("[]"));
        assertEquals("", MidasflipConfig.legacyToken("{\"apiToken\":{\"nested\":true}}"));
    }

    @Test
    void unknownSafetyModeFailsClosedToStrict() {
        MidasflipConfig cfg = GSON.fromJson("{\"safetyMode\":\"TURBO_UNSAFE\"}", MidasflipConfig.class);
        cfg.normalize();
        assertEquals(MidasflipConfig.SafetyMode.STRICT, cfg.safetyMode);
        assertTrue(cfg.apiToken.isEmpty());
    }
}
