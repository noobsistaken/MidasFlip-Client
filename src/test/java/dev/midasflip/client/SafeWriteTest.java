package dev.midasflip.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Durable writes for the config and the position ledger.
 *
 * <p>Both previously used a direct {@code Files.writeString} over the live
 * file, which truncates to zero and then writes. A crash inside that window
 * leaves the file empty — and for the ledger that is the user's entire
 * position history and cost basis, which exists nowhere else. Minecraft
 * crashing is not rare and the mod writes on every purchase.
 */
class SafeWriteTest {

    @TempDir
    Path dir;

    @Test
    void writingReplacesTheFileWhole(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("ledger.json");
        assertTrue(SafeWrite.write(f, "[{\"uuid\":\"a\"}]"));
        assertEquals("[{\"uuid\":\"a\"}]", Files.readString(f));

        assertTrue(SafeWrite.write(f, "[]"));
        assertEquals("[]", Files.readString(f), "second write must replace, not append");
    }

    @Test
    void noTempFileSurvivesASuccessfulWrite() throws IOException {
        Path f = dir.resolve("config.json");
        SafeWrite.write(f, "{}");
        try (var s = Files.list(dir)) {
            List<String> left = s.map(p -> p.getFileName().toString()).toList();
            assertEquals(List.of("config.json"), left,
                    "a leftover .tmp means the rename did not happen");
        }
    }

    @Test
    void theOldContentSurvivesAFailedWrite() throws IOException {
        // The point of the rename: a write that cannot complete must leave
        // the previous file intact rather than a truncated one.
        Path locked = dir.resolve("locked");
        Files.createDirectory(locked);
        Path target = locked.resolve("ledger.json");
        assertTrue(SafeWrite.write(target, "[\"first\"]"));
        assertTrue(locked.toFile().setWritable(false));
        try {
            // If the chmod does not bite (root, or a filesystem that ignores
            // it) this test cannot make the write fail, so it SKIPS rather
            // than asserting something weaker. A conditional assertion is a
            // test that quietly stops testing (review 2026-08-06).
            assumeTrue(!Files.isWritable(locked), "directory is still writable; cannot force a failure");

            assertFalse(SafeWrite.write(target, "[\"second\"]"),
                    "an unwritable directory must report failure, not throw");
            assertEquals("[\"first\"]", Files.readString(target),
                    "a failed write must leave the previous content untouched");
        } finally {
            locked.toFile().setWritable(true);
        }
    }

    @Test
    void aCrashedWriteLeavesAtMostOneStrayFile() throws IOException {
        // The temp name is fixed, not random, so a hard crash between write
        // and rename cannot accumulate one orphan per crash with nothing to
        // sweep them. Simulated by planting the orphan a crash would leave.
        Path f = dir.resolve("config.json");
        SafeWrite.write(f, "{\"a\":1}");
        Files.writeString(dir.resolve("config.json.tmp"), "{ half-writ");

        SafeWrite.write(f, "{\"a\":2}");

        assertEquals("{\"a\":2}", Files.readString(f));
        try (var s = Files.list(dir)) {
            assertEquals(List.of("config.json"), s.map(p -> p.getFileName().toString()).sorted().toList(),
                    "the stray temp must be reused and renamed away, not left beside a new one");
        }
    }

    @Test
    void aPathThatNamesNoFileIsRefusedBeforeAnythingIsWritten() throws IOException {
        // Both of these must be refused by the guard, NOT by a failed rename
        // further down. The earlier version of this test used Path.of("") and
        // passed for the wrong reason: toAbsolutePath() gave the empty path
        // the working directory's parent, so write() got as far as creating
        // and fsyncing "<parent-of-repo>/.tmp" on every test run before the
        // move failed (review 2026-08-06).
        Path strayFromCwd = Path.of("").toAbsolutePath().getParent().resolve(".tmp");
        Path strayFromRoot = Path.of("/.tmp");
        boolean preexistingCwd = Files.exists(strayFromCwd);
        boolean preexistingRoot = Files.exists(strayFromRoot);

        assertFalse(SafeWrite.write(Path.of(""), "{}"));
        assertFalse(SafeWrite.write(Path.of("/"), "{}"), "the root has no filename");

        assertEquals(preexistingCwd, Files.exists(strayFromCwd),
                "write() touched the filesystem outside the target it was given");
        assertEquals(preexistingRoot, Files.exists(strayFromRoot),
                "write() touched the filesystem outside the target it was given");
    }

    @Test
    void deepDirectoriesAreCreated() throws IOException {
        Path f = dir.resolve("nested/deeper/config.json");
        assertTrue(SafeWrite.write(f, "{}"));
        assertEquals("{}", Files.readString(f));
    }

    @Test
    void aCorruptFileIsKeptNotDeleted() throws IOException {
        // The load path calls this before writing defaults over the top. If
        // a ledger is ever mangled, these bytes are the only chance of
        // recovering the positions.
        Path f = dir.resolve("ledger.json");
        Files.writeString(f, "{ this is not json");
        Path aside = SafeWrite.quarantine(f);

        assertNotNull(aside);
        assertFalse(Files.exists(f), "the bad file must be moved out of the way");
        assertTrue(Files.exists(aside), "and must still exist under its new name");
        assertEquals("{ this is not json", Files.readString(aside),
                "the original bytes must be preserved exactly");
        assertTrue(aside.getFileName().toString().contains(".corrupt-"));
    }

    @Test
    void quarantineOfAMissingFileIsNotAnError() {
        // Load paths can hit this when the file vanished between the check
        // and the read. It must not throw on the way to using defaults.
        assertEquals(null, SafeWrite.quarantine(dir.resolve("does-not-exist.json")));
    }

    @Test
    void unicodeSurvivesTheRoundTrip() throws IOException {
        // Item names carry section signs and stars; a mangled encoding here
        // would corrupt every stored position name.
        Path f = dir.resolve("ledger.json");
        String content = "[{\"name\":\"§6Divan's Leggings ✪✪✪✪✪\"}]";
        assertTrue(SafeWrite.write(f, content));
        assertEquals(content, Files.readString(f));
    }
}
