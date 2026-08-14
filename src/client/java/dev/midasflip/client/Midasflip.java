package dev.midasflip.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shared client-side constants. */
public final class Midasflip {
    public static final String MOD_ID = "midasflip";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    private static String version;

    /** The running mod's version, read from the loader's own metadata.
     *
     *  <p>That metadata comes from fabric.mod.json's {@code "version":
     *  "${version}"}, which gradle expands from {@code mod_version} in
     *  gradle.properties — the same single value that names the jar. So the
     *  menu header cannot drift from the artifact.
     *
     *  <p>It had drifted: the header was a hardcoded "0.1.0 · fabric" and
     *  still said 0.1.0 two releases later (owner, 2026-08-14). Deriving it
     *  makes a stale version string impossible rather than merely unlikely.
     *
     *  <p>Falls back to "dev" when no loader is present — unit tests run on a
     *  plain JVM, and a version label must never be the reason a test dies.
     *  Resolved once and cached; this is called from a render path. */
    public static String version() {
        if (version == null) {
            String v = "dev";
            try {
                v = net.fabricmc.loader.api.FabricLoader.getInstance()
                        .getModContainer(MOD_ID)
                        .map(c -> c.getMetadata().getVersion().getFriendlyString())
                        .orElse("dev");
            } catch (Throwable ignored) {
                // No Fabric runtime (tests, tooling): keep the placeholder.
            }
            version = v;
        }
        return version;
    }

    private Midasflip() {}
}
