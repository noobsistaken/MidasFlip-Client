package dev.midasflip.client;

import net.fabricmc.loader.api.FabricLoader;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * At-rest storage for the user's API token, replacing the old plaintext
 * field inside midasflip.json.
 *
 * Storage ladder, best available first:
 *   1. OS keychain — macOS `security`, Linux `secret-tool` (libsecret).
 *      The token never touches our files at all.
 *   2. Fallback file (config/midasflip.token): owner-only permissions where
 *      the OS supports them, content AES-GCM-wrapped with a key derived
 *      from stable machine identity.
 *
 * HONEST RESIDUAL RISK (also documented in the README): the fallback is
 * OBFUSCATION, not encryption-grade secrecy. The derivation inputs live on
 * the same machine, so any program running as this user can recover the
 * token. What it actually defends against is the leak that happens in
 * practice: users pasting their whole config file into Discord support
 * channels, and casual directory browsing. Treat a shared midasflip.token
 * the same as a shared password: revoke the key on the account page.
 *
 * All process invocations are fixed argv arrays (no shell). The token
 * travels via stdin where the tool allows it (secret-tool); macOS
 * `security add` only accepts it via argv — that trade-off is documented
 * honestly at the call site.
 */
public final class TokenStore {
    private TokenStore() {}

    // Neutral service id until the public rename lands (flagged for the
    // rename batch — changing it later triggers a silent re-pair prompt,
    // acceptable, or a one-time migration read of the old service name).
    private static final String SERVICE = "midasflip-mod";
    private static final String ACCOUNT = "api-token";
    private static final byte[] SALT = "midasflip-token-v1".getBytes(StandardCharsets.UTF_8);
    // Pre-rename installs stored under these — read-once migration only.
    // LEGACY_SALT must stay byte-identical to what old blobs were sealed
    // with, or every existing user is silently signed out by the rename.
    private static final String LEGACY_SERVICE = "skyflip-mod";
    private static final byte[] LEGACY_SALT = "skyflip-token-v1".getBytes(StandardCharsets.UTF_8);
    private static final String LEGACY_FILE = "skyflip.token";
    private static final long TOOL_TIMEOUT_S = 5;

    private enum Backend { MAC_KEYCHAIN, SECRET_TOOL, FILE }

    private static volatile Backend backend;

    /** Load the stored token, or "" when none is stored anywhere. */
    public static String load() {
        switch (resolveBackend()) {
            case MAC_KEYCHAIN -> {
                String out = run(new String[]{
                        "security", "find-generic-password",
                        "-s", SERVICE, "-a", ACCOUNT, "-w"}, null);
                if (out != null) return out.trim();
            }
            case SECRET_TOOL -> {
                String out = run(new String[]{
                        "secret-tool", "lookup", "service", SERVICE, "account", ACCOUNT}, null);
                if (out != null) return out.trim();
            }
            default -> { }
        }
        String file = loadFile();
        if (!file.isEmpty()) {
            return file;
        }
        // Rename migration: nothing under the new identity — look once
        // under the old one and, if found, re-store under the new.
        String legacy = loadLegacy();
        if (!legacy.isEmpty() && save(legacy)) {
            clearLegacy();
            Midasflip.LOG.info("api token migrated to the MidasFlip identity");
        }
        return legacy;
    }

    private static String loadLegacy() {
        switch (resolveBackend()) {
            case MAC_KEYCHAIN -> {
                String out = run(new String[]{
                        "security", "find-generic-password",
                        "-s", LEGACY_SERVICE, "-a", ACCOUNT, "-w"}, null);
                if (out != null) return out.trim();
            }
            case SECRET_TOOL -> {
                String out = run(new String[]{
                        "secret-tool", "lookup", "service", LEGACY_SERVICE, "account", ACCOUNT}, null);
                if (out != null) return out.trim();
            }
            default -> { }
        }
        return readWrappedFile(
                FabricLoader.getInstance().getConfigDir().resolve(LEGACY_FILE), LEGACY_SALT);
    }

    private static void clearLegacy() {
        run(new String[]{"security", "delete-generic-password",
                "-s", LEGACY_SERVICE, "-a", ACCOUNT}, null);
        run(new String[]{"secret-tool", "clear",
                "service", LEGACY_SERVICE, "account", ACCOUNT}, null);
        try {
            Files.deleteIfExists(FabricLoader.getInstance().getConfigDir().resolve(LEGACY_FILE));
        } catch (IOException ignored) {
        }
    }

    /** Persist the token durably. TRUE only when the write is confirmed —
     *  the pairing flow must not acknowledge a one-shot secret otherwise. */
    public static boolean save(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        switch (resolveBackend()) {
            case MAC_KEYCHAIN -> {
                // -U updates in place. `security add` only takes the value
                // via argv (-w), which is visible in the process listing —
                // including to OTHER local users running ps during the
                // subprocess's brief lifetime. On a single-user gaming
                // machine that is acceptable; on a genuinely shared box the
                // fallback file (owner-only) is actually the tighter path.
                // Honest trade-off, kept because the keychain wins at rest.
                String out = run(new String[]{
                        "security", "add-generic-password", "-U",
                        "-s", SERVICE, "-a", ACCOUNT, "-w", token}, null);
                if (out != null) {
                    deleteFile(); // never leave a stale fallback copy behind
                    return true;
                }
            }
            case SECRET_TOOL -> {
                String out = run(new String[]{
                        "secret-tool", "store", "--label=" + SERVICE,
                        "service", SERVICE, "account", ACCOUNT}, token);
                if (out != null) {
                    deleteFile();
                    return true;
                }
            }
            default -> { }
        }
        // Keychain path failed (or FILE backend): the file becomes the
        // authoritative copy. Best-effort clear any STALE keychain entry so
        // a revoked old token can never shadow this fresher one on load.
        if (resolveBackend() == Backend.MAC_KEYCHAIN) {
            run(new String[]{"security", "delete-generic-password",
                    "-s", SERVICE, "-a", ACCOUNT}, null);
        } else if (resolveBackend() == Backend.SECRET_TOOL) {
            run(new String[]{"secret-tool", "clear",
                    "service", SERVICE, "account", ACCOUNT}, null);
        }
        return saveFile(token);
    }

    /** Remove the token everywhere (sign-out / revoke path). */
    public static void clear() {
        run(new String[]{"security", "delete-generic-password",
                "-s", SERVICE, "-a", ACCOUNT}, null);
        run(new String[]{"secret-tool", "clear",
                "service", SERVICE, "account", ACCOUNT}, null);
        deleteFile();
    }

    // ---- backend detection -------------------------------------------------

    private static Backend resolveBackend() {
        Backend b = backend;
        if (b != null) {
            return b;
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac") && toolWorks(new String[]{"security", "help"})) {
            b = Backend.MAC_KEYCHAIN;
        } else if (os.contains("linux") && toolWorks(new String[]{"secret-tool", "--help"})) {
            b = Backend.SECRET_TOOL;
        } else {
            b = Backend.FILE; // Windows and keychain-less setups
        }
        backend = b;
        return b;
    }

    private static boolean toolWorks(String[] argv) {
        return run(argv, null) != null;
    }

    /** Run argv, optionally feeding stdin; stdout on exit 0, else null.
     *  The deadline is REAL: stdout drains on a daemon thread while
     *  waitFor() holds the 5s bound, so a keychain tool blocking on an
     *  unlock dialog (locked macOS keychain, GNOME keyring D-Bus prompt)
     *  gets killed instead of freezing the game thread — this runs
     *  synchronously during startup config load and pairing. stderr is
     *  discarded (never mixed into a value we might treat as the token). */
    private static String run(String[] argv, String stdin) {
        try {
            Process p = new ProcessBuilder(argv)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (stdin != null) {
                try (var w = p.getOutputStream()) {
                    w.write(stdin.getBytes(StandardCharsets.UTF_8));
                }
            } else {
                p.getOutputStream().close();
            }
            var out = new java.io.ByteArrayOutputStream();
            Thread drain = new Thread(() -> {
                try {
                    p.getInputStream().transferTo(out);
                } catch (IOException ignored) {
                }
            }, "midasflip-token-tool-drain");
            drain.setDaemon(true);
            drain.start();
            if (!p.waitFor(TOOL_TIMEOUT_S, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            drain.join(1000);
            return p.exitValue() == 0 ? out.toString(StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            return null; // tool absent — normal, fall through the ladder
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    // ---- fallback file: owner-only perms + machine-keyed AES-GCM ----------

    private static Path filePath() {
        return FabricLoader.getInstance().getConfigDir().resolve("midasflip.token");
    }

    private static String loadFile() {
        return readWrappedFile(filePath(), SALT);
    }

    private static String readWrappedFile(Path f, byte[] salt) {
        try {
            if (!Files.exists(f)) {
                return "";
            }
            byte[] blob = Base64.getDecoder().decode(Files.readString(f).trim());
            if (blob.length < 13) {
                return "";
            }
            byte[] iv = new byte[12];
            System.arraycopy(blob, 0, iv, 0, 12);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, machineKey(salt), new GCMParameterSpec(128, iv));
            byte[] plain = c.doFinal(blob, 12, blob.length - 12);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) { // corrupt/foreign file = no token, never crash
            Midasflip.LOG.warn("token file unreadable — treating as signed out: {}", e.toString());
            return "";
        }
    }

    private static boolean saveFile(String token) {
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, machineKey(SALT), new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(token.getBytes(StandardCharsets.UTF_8));
            byte[] blob = new byte[12 + ct.length];
            System.arraycopy(iv, 0, blob, 0, 12);
            System.arraycopy(ct, 0, blob, 12, ct.length);
            Path f = filePath();
            try {
                // POSIX: create owner-only atomically so there is never a
                // world-readable window between write and chmod.
                Files.deleteIfExists(f);
                Files.createFile(f, PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rw-------")));
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX (Windows ACLs): fall through, restrict after.
            }
            Files.writeString(f, Base64.getEncoder().encodeToString(blob));
            restrictToOwner(f);
            return true;
        } catch (Exception e) {
            Midasflip.LOG.warn("token save failed: {}", e.toString());
            return false;
        }
    }

    private static void deleteFile() {
        try {
            Files.deleteIfExists(filePath());
        } catch (IOException ignored) {
        }
    }

    /** Owner-only where the filesystem supports it; best-effort elsewhere. */
    static void restrictToOwner(Path f) {
        try {
            Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            var file = f.toFile();
            // Windows/non-POSIX: java.io best-effort owner-only.
            if (!(file.setReadable(false, false) && file.setReadable(true, true)
                    && file.setWritable(false, false) && file.setWritable(true, true))) {
                Midasflip.LOG.warn("could not restrict {} to owner-only", f.getFileName());
            }
        } catch (IOException e) {
            Midasflip.LOG.warn("perms on {}: {}", f.getFileName(), e.toString());
        }
    }

    /** Key derived from stable machine identity. Obfuscation-grade only —
     *  see the class javadoc; the honest security boundary is the OS user
     *  account, not this key. */
    private static SecretKeySpec machineKey(byte[] salt) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        d.update(salt);
        // Deliberately NOT os.name: "Windows 10" -> "Windows 11" on an OS
        // upgrade would silently sign out the entire Windows population
        // (the largest file-backend group). user rename / profile move
        // still loses the token — that degrades to "signed out", which
        // the pairing flow recovers in one click.
        d.update(System.getProperty("user.name", "?").getBytes(StandardCharsets.UTF_8));
        d.update(System.getProperty("user.home", "?").getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(d.digest(), "AES");
    }
}
