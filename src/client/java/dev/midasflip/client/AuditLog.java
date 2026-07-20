package dev.midasflip.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * The audit log (spec §13): every command the mod ever sends, with the
 * exact physical input that triggered it — the proof no automated
 * follow-up exists. Fields are serialized through Gson so a backend-
 * supplied value (a uuid with quotes/newlines) can never forge or split a
 * record; the log's integrity is the whole point.
 */
public final class AuditLog {
    private static final Gson GSON = new Gson();

    private AuditLog() {}

    public static void record(String action, String trigger, String detail) {
        JsonObject o = new JsonObject();
        o.addProperty("ts", Instant.now().toString());
        o.addProperty("action", action);
        o.addProperty("trigger", trigger);
        o.addProperty("detail", detail);
        try {
            Path p = FabricLoader.getInstance().getConfigDir().resolve("midasflip-audit.jsonl");
            Files.writeString(p, GSON.toJson(o) + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            Midasflip.LOG.warn("audit write failed: {}", e.toString());
        }
    }
}
