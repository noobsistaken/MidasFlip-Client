package dev.midasflip.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * apiBase decides where the bearer token is sent, so it is an authorization
 * boundary rather than a preference. External review 2026-07-30 found it
 * accepted any string: plain http to any host would have put the token on the
 * wire in clear, to a host of the editor's choosing.
 *
 * <p>Not remotely reachable — apiBase is excluded from presets and SF1 share
 * codes — so what this guards is the hand-edited config.
 */
class ApiBaseTest {

    private static final String DEF = MidasflipConfig.DEFAULT_API_BASE;

    @Test
    void httpsIsAccepted() {
        assertEquals("https://midasflip.com",
                MidasflipConfig.safeApiBase("https://midasflip.com"));
        // A self-hosted deployment on any https host is legitimate.
        assertEquals("https://my.example.net",
                MidasflipConfig.safeApiBase("https://my.example.net"));
    }

    @Test
    void plainHttpToARemoteHostIsRefused() {
        // The whole point: this would ship the token in clear.
        assertEquals(DEF, MidasflipConfig.safeApiBase("http://evil.example.com"));
        assertEquals(DEF, MidasflipConfig.safeApiBase("http://midasflip.com.evil.net"));
        assertEquals(DEF, MidasflipConfig.safeApiBase("http://10.0.0.5:8000"));
    }

    @Test
    void loopbackOverPlainHttpIsAllowedForDevelopment() {
        assertEquals("http://localhost:8000",
                MidasflipConfig.safeApiBase("http://localhost:8000"));
        assertEquals("http://127.0.0.1:8000",
                MidasflipConfig.safeApiBase("http://127.0.0.1:8000"));
        assertEquals("http://[::1]:8000",
                MidasflipConfig.safeApiBase("http://[::1]:8000"));
        assertEquals("http://localhost", MidasflipConfig.safeApiBase("http://localhost"));
    }

    @Test
    void hostnamesThatMerelyContainLoopbackAreRefused() {
        // The prefix trap: "localhost.evil.com" starts with "localhost".
        assertEquals(DEF, MidasflipConfig.safeApiBase("http://localhost.evil.com"));
        assertEquals(DEF, MidasflipConfig.safeApiBase("http://127.0.0.1.evil.com"));
        // Credentials in the authority must not smuggle a host past the check.
        assertEquals(DEF, MidasflipConfig.safeApiBase("http://localhost@evil.com"));
        // The colon form is the one that actually got through: the host split
        // took the text before the first ':', which here is the USERNAME, so
        // the authority read as loopback while the request would have gone to
        // evil.example.com carrying the bearer token in cleartext. This test
        // asserted the property before the code had it (review 2026-07-30).
        assertEquals(DEF, MidasflipConfig.safeApiBase("http://localhost:pass@evil.example.com"));
        assertEquals(DEF, MidasflipConfig.safeApiBase("http://127.0.0.1:x@evil.example.com"));
        assertEquals(DEF, MidasflipConfig.safeApiBase("http://[::1]:x@evil.example.com"));
        assertEquals(DEF, MidasflipConfig.safeApiBase("http://user:pass@localhost:8000"));
    }

    @Test
    void pathsDoNotSmuggleAHostPastTheCheck() {
        assertEquals(DEF, MidasflipConfig.safeApiBase("http://evil.com/localhost"));
        assertEquals(DEF, MidasflipConfig.safeApiBase("http://evil.com/#localhost"));
    }

    @Test
    void otherSchemesAreRefused() {
        for (String s : new String[]{"ftp://midasflip.com", "file:///etc/passwd",
                                     "javascript:alert(1)", "ws://midasflip.com",
                                     "midasflip.com", "//midasflip.com"}) {
            assertEquals(DEF, MidasflipConfig.safeApiBase(s), s);
        }
    }

    @Test
    void emptyAndNullFallBackToTheDefault() {
        assertEquals(DEF, MidasflipConfig.safeApiBase(null));
        assertEquals(DEF, MidasflipConfig.safeApiBase(""));
        assertEquals(DEF, MidasflipConfig.safeApiBase("   "));
        assertEquals(DEF, MidasflipConfig.safeApiBase("https://"));
    }

    @Test
    void trailingSlashesAreTrimmed() {
        // Every call site concatenates a path straight onto this, so a
        // trailing slash would produce "https://host//flips".
        assertEquals("https://midasflip.com",
                MidasflipConfig.safeApiBase("https://midasflip.com/"));
        assertEquals("https://midasflip.com",
                MidasflipConfig.safeApiBase("https://midasflip.com///"));
        assertEquals("https://midasflip.com",
                MidasflipConfig.safeApiBase("  https://midasflip.com/  "));
    }

    @Test
    void schemeMatchingIsCaseInsensitive() {
        assertEquals("HTTPS://midasflip.com",
                MidasflipConfig.safeApiBase("HTTPS://midasflip.com"));
        assertEquals("HTTP://LocalHost:8000",
                MidasflipConfig.safeApiBase("HTTP://LocalHost:8000"));
    }

    @Test
    void normalizeAppliesItToAHandEditedConfig() {
        MidasflipConfig c = new MidasflipConfig();
        c.apiBase = "http://evil.example.com";
        c.normalize();
        assertEquals(DEF, c.apiBase, "a hand-edited config must not redirect the token");
    }
}
