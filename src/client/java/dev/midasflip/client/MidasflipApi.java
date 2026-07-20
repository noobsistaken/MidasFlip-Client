package dev.midasflip.client;

import com.google.gson.Gson;
import com.google.gson.JsonElement;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared authenticated GET with a small TTL cache — every screen fetch
 * goes through here so the render thread never blocks (fabric rule: all
 * network async with hard timeouts; a dead API degrades to stale/empty,
 * never a freeze).
 */
public final class MidasflipApi {
    private static final Gson GSON = new Gson();

    private final MidasflipConfig config;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "midasflip-api");
        t.setDaemon(true);
        return t;
    });

    private record Entry(JsonElement value, long fetchedAt, long expiresAt) {}
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final Map<String, Boolean> inflight = new ConcurrentHashMap<>();
    private final Map<String, Integer> lastStatus = new ConcurrentHashMap<>();
    private volatile long lastLatencyMs = -1;
    // Atomic, not volatile-++: two pool threads can fail concurrently and
    // a lost increment would delay the likelyDown() signal.
    private final AtomicInteger consecutiveFails = new AtomicInteger();
    // A credential swap invalidates every cached response. In-flight work
    // captures this generation and is forbidden from repopulating the cache
    // with a response fetched under the previous account.
    private final AtomicInteger credentialGeneration = new AtomicInteger();

    /** Most recent successful request latency; -1 before any. */
    public long lastLatencyMs() {
        return lastLatencyMs;
    }

    /** Last HTTP status seen for {@code path}; 0 before any response.
     *  Lets panes tell a 402 entitlement gate ("pro feature") apart from
     *  a genuinely empty/missing dataset. */
    public int lastStatus(String path) {
        Integer s = lastStatus.get(path);
        return s == null ? 0 : s;
    }

    /** True once several requests in a row have failed — transport error
     *  OR an HTTP error status (401/429/5xx): the API is unreachable or
     *  refusing us (almost always: the SSH tunnel isn't running). Panes
     *  use this to show an honest hint instead of a forever "loading…". */
    public boolean likelyDown() {
        return consecutiveFails.get() >= 3;
    }

    /** Milliseconds since the cached value for {@code path} was fetched;
     *  -1 when nothing is cached. get() keeps serving expired entries
     *  while the API is unreachable (stale-while-unreachable is the
     *  graceful-degradation rule), so any caller that ACTS on a value —
     *  clipboard copy, not just display — must check the age. */
    public long ageMs(String path) {
        Entry e = cache.get(path);
        return e == null ? -1 : System.currentTimeMillis() - e.fetchedAt;
    }

    /** True when the cached value for {@code path} is older than
     *  {@code maxAgeMs} — or absent entirely (absent fails stale-safe). */
    public boolean isStale(String path, long maxAgeMs) {
        long age = ageMs(path);
        return age < 0 || age > maxAgeMs;
    }

    public MidasflipApi(MidasflipConfig config) {
        this.config = config;
    }

    /**
     * Called immediately after a newly paired credential has been persisted.
     * Account/entitlement-sensitive responses must be fetched again under the
     * new key; an old in-flight response is discarded by the generation check
     * in {@link #get(String, long)}.
     */
    public void credentialsChanged() {
        credentialGeneration.incrementAndGet();
        cache.clear();
        lastStatus.clear();
        consecutiveFails.set(0);
        lastLatencyMs = -1;
    }

    /**
     * Cached value if fresh, else null while a background refresh runs.
     * Callers re-poll each frame; the panel just renders "loading…" until
     * data lands. Never blocks.
     */
    public JsonElement get(String path, long ttlMs) {
        Entry e = cache.get(path);
        long now = System.currentTimeMillis();
        if (e != null && e.expiresAt > now) {
            return e.value;
        }
        if (inflight.putIfAbsent(path, Boolean.TRUE) == null) {
            int generation = credentialGeneration.get();
            pool.execute(() -> {
                try {
                    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(config.apiBase + path))
                            .timeout(Duration.ofSeconds(5));
                    if (!config.apiToken.isEmpty()) {
                        b.header("Authorization", "Bearer " + config.apiToken);
                    }
                    long t0 = System.currentTimeMillis();
                    HttpResponse<String> resp = http.send(b.GET().build(), HttpResponse.BodyHandlers.ofString());
                    if (generation != credentialGeneration.get()) {
                        return; // response belongs to the previous credential
                    }
                    lastStatus.put(path, resp.statusCode());
                    if (resp.statusCode() == 200) {
                        long doneAt = System.currentTimeMillis();
                        lastLatencyMs = doneAt - t0;
                        consecutiveFails.set(0);
                        cache.put(path, new Entry(GSON.fromJson(resp.body(), JsonElement.class),
                                doneAt, doneAt + ttlMs));
                    } else if (resp.statusCode() == 404) {
                        // A definitive "no data" — cache it so panes can say
                        // so instead of spinning "loading…" forever. NOT a
                        // failure: SellOverlay's reforge fallback branches
                        // on this JsonNull.
                        consecutiveFails.set(0);
                        long doneAt = System.currentTimeMillis();
                        cache.put(path, new Entry(com.google.gson.JsonNull.INSTANCE,
                                doneAt, doneAt + Math.min(ttlMs, 5 * 60_000)));
                    } else if (resp.statusCode() == 402) {
                        // Entitlement gate (server-side tiers): a definitive
                        // "not on your plan", NOT an outage — cache the null
                        // briefly so panes render the pro hint (via
                        // lastStatus) instead of re-requesting every frame.
                        // The mod carries no entitlement logic of its own.
                        consecutiveFails.set(0);
                        long doneAt = System.currentTimeMillis();
                        cache.put(path, new Entry(com.google.gson.JsonNull.INSTANCE,
                                doneAt, doneAt + Math.min(ttlMs, 60_000)));
                    } else {
                        // 401/429/503/…: HttpClient does NOT throw on error
                        // statuses, so without this branch an HTTP-level
                        // outage never counted as a failure and likelyDown()
                        // stayed false forever (count errors, never swallow).
                        consecutiveFails.incrementAndGet();
                        Midasflip.LOG.warn("api get {} -> HTTP {}", path, resp.statusCode());
                    }
                } catch (Exception ex) {
                    consecutiveFails.incrementAndGet();
                    Midasflip.LOG.warn("api get {} failed: {}", path, ex.toString());
                } finally {
                    inflight.remove(path);
                }
            });
        }
        return e != null ? e.value : null; // stale-while-revalidate
    }

    public void invalidate(String path) {
        cache.remove(path);
    }

    /** One-shot UNCACHED request (the account-pairing flow): async on the
     *  pool with the same hard timeout; {@code callback} runs on the pool
     *  thread with the parsed 200 body, or null on any failure — callers
     *  marshal back to the render thread themselves. Never blocks. */
    public void request(String method, String path, java.util.function.Consumer<JsonElement> callback) {
        request(method, path, Map.of(), callback);
    }

    /**
     * Uncached request with additional per-request headers. Pairing v2 uses
     * this for its in-memory device proof; the proof is never placed in a URL,
     * config file, cache key, or log line.
     */
    public void request(String method, String path, Map<String, String> headers,
                        java.util.function.Consumer<JsonElement> callback) {
        pool.execute(() -> {
            JsonElement out = null;
            try {
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(config.apiBase + path))
                        .timeout(Duration.ofSeconds(5));
                if (!config.apiToken.isEmpty()) {
                    b.header("Authorization", "Bearer " + config.apiToken);
                }
                if (headers != null) {
                    headers.forEach((name, value) -> {
                        if (name != null && value != null && !name.isBlank()) {
                            b.header(name, value);
                        }
                    });
                }
                HttpRequest req = "POST".equals(method)
                        ? b.POST(HttpRequest.BodyPublishers.noBody()).build()
                        : b.GET().build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    out = GSON.fromJson(resp.body(), JsonElement.class);
                } else {
                    Midasflip.LOG.warn("api {} {} -> HTTP {}", method, logPath(path), resp.statusCode());
                }
            } catch (Exception ex) {
                Midasflip.LOG.warn("api {} {} failed: {}", method, logPath(path), ex.toString());
            }
            callback.accept(out);
        });
    }

    /** Query strings stay out of logs: pairing polls carry the one-shot
     *  code as ?code=… and latest.log gets pasted publicly all the time. */
    private static String logPath(String path) {
        int q = path.indexOf('?');
        return q < 0 ? path : path.substring(0, q + 1) + "…";
    }
}
