package dev.midasflip.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Durable writes for the two files a user cannot afford to lose: the config
 * and the position ledger.
 *
 * <p>Both used a direct {@code Files.writeString} over the live file, which
 * truncates it to zero and then writes. A crash or a full disk inside that
 * window leaves the file truncated or half-written — and for the
 * ledger that is the user's ENTIRE position history, including cost basis
 * they cannot reconstruct from anywhere else. Minecraft crashing is not a
 * rare event, and this mod writes on every purchase (audit 2026-08-06).
 *
 * <p>The fix is the standard one: write a temp file beside the target, force
 * it to disk, then rename over the target. A rename is atomic on the same
 * filesystem, so a reader sees either the whole old file or the whole new
 * one, never a torn middle.
 *
 * <p>Scope, stated precisely because the failure this guards is silent: the
 * file's own bytes are fsynced before the rename, so a process crash or a
 * full disk cannot produce a torn file. The DIRECTORY entry is not synced,
 * so a power loss in the instant after the rename can still lose the update
 * on some filesystems — it just cannot leave a half-written one. Guarding
 * that too would mean an fsync on the config directory per write, which is
 * not worth the cost here (review 2026-08-06).
 *
 * <p>Reads get the matching guard. A file that fails to parse is moved aside
 * with a {@code .corrupt-<millis>} suffix rather than deleted or silently
 * overwritten: if a ledger ever does get mangled, the bytes are the only
 * chance of recovering the positions, and quietly starting fresh over the
 * top destroys the evidence at exactly the moment it matters.
 */
final class SafeWrite {

    private SafeWrite() {
    }

    /**
     * Write {@code content} to {@code target} atomically. Returns false on
     * failure rather than throwing — a failed settings save must never take
     * the game down, and callers already degrade gracefully.
     */
    static boolean write(Path target, String content) {
        // Refuse anything that cannot name a file, BEFORE touching the disk.
        // Checking only the parent was not enough: toAbsolutePath() gives the
        // empty path a parent (the working directory's), so a nameless target
        // sailed past the guard and got as far as creating "<cwd-parent>/.tmp"
        // before the rename failed (review 2026-08-06). Both callers resolve
        // against the config dir, so this guards the contract, not a bug.
        Path name = target.getFileName();
        if (name == null || name.toString().isEmpty()) {
            Midasflip.LOG.warn("refusing to write {}: not a file path", target);
            return false;
        }
        Path dir = target.toAbsolutePath().getParent();
        if (dir == null) {
            Midasflip.LOG.warn("refusing to write {}: no parent directory", target);
            return false;
        }
        Path tmp = dir.resolve(name + ".tmp");
        try {
            Files.createDirectories(dir);
            // Same directory, so the rename below stays on one filesystem. A
            // temp dir elsewhere would make ATOMIC_MOVE fail on every call.
            // The name is FIXED, not random: a hard crash between write and
            // rename leaves exactly one stray file that the next write reuses,
            // instead of accumulating one per crash forever with nothing to
            // sweep them.
            try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(tmp,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.WRITE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
                ch.write(StandardCharsets.UTF_8.encode(content));
                // Force before the rename. Without it the rename can land
                // while the contents are still only in the page cache, which
                // is the one ordering that turns a crash into an empty file.
                ch.force(true);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                // Some filesystems refuse ATOMIC_MOVE. A plain replace is
                // still strictly better than truncate-then-write: the window
                // shrinks to the rename itself.
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            Midasflip.LOG.warn("atomic write of {} failed: {}", target.getFileName(), e.toString());
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // A leftover .tmp is untidy, never harmful — the next write
                // truncates it anyway.
            }
            return false;
        }
    }

    /**
     * Move an unparseable file aside so a fresh one can be written without
     * destroying it. Returns the quarantine path, or null if it could not be
     * moved — in which case the caller should still carry on with defaults
     * rather than crash.
     */
    static Path quarantine(Path bad) {
        try {
            Path aside = bad.resolveSibling(
                    bad.getFileName() + ".corrupt-" + System.currentTimeMillis());
            Files.move(bad, aside, StandardCopyOption.REPLACE_EXISTING);
            Midasflip.LOG.warn("{} could not be parsed; kept a copy at {} and started fresh",
                    bad.getFileName(), aside.getFileName());
            return aside;
        } catch (IOException e) {
            Midasflip.LOG.warn("could not set aside unreadable {}: {}",
                    bad.getFileName(), e.toString());
            return null;
        }
    }
}
