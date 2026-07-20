package dev.midasflip.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Live flip feed: a websocket tail of the backend's stream with a bounded
 * in-memory buffer. Pure DATA transport — nothing here touches the game.
 * Reconnects with capped backoff; drops stale flips on read.
 */
public final class FlipFeed implements WebSocket.Listener {
    private static final Gson GSON = new Gson();
    private static final int BUFFER = 100;

    private final MidasflipConfig config;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ScheduledExecutorService reconnector =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "midasflip-ws");
                t.setDaemon(true);
                return t;
            });

    private static final java.util.regex.Pattern UUID_RE =
            java.util.regex.Pattern.compile("^[0-9a-fA-F]{32}$");
    private static final int MAX_PARTIAL = 1 << 20; // 1MB frame cap

    private final ArrayDeque<Flip> flips = new ArrayDeque<>();
    private final Set<String> seen = new HashSet<>();
    private final StringBuilder partial = new StringBuilder();
    private volatile WebSocket current; // callbacks from stale sockets are ignored
    private volatile boolean connected;
    private volatile boolean connecting;
    private volatile boolean closed;
    private volatile long connectedAtMs;
    private volatile long nextRetryAtMs;
    private final java.util.concurrent.atomic.AtomicLong credentialGeneration =
            new java.util.concurrent.atomic.AtomicLong();
    private int backoffS = 1;
    private int reconcileFailures;

    public FlipFeed(MidasflipConfig config) {
        this.config = config;
    }

    public void start() {
        connect();
        // Retire bought-out rows so the next-best fill in: every 20s ask
        // the API which displayed auctions are still listed. Lags a
        // purchase by up to one snapshot tick — that is the data cadence.
        reconnector.scheduleWithFixedDelay(this::reconcile, 20, 20, TimeUnit.SECONDS);
    }

    public void stop() {
        closed = true;
        reconnector.shutdownNow();
    }

    /**
     * Drop the current authenticated transport and reconnect with the token
     * now stored in {@link MidasflipConfig}. This is queued on the feed's own
     * executor so credential hand-off never blocks the API callback thread.
     */
    public void credentialsChanged() {
        credentialGeneration.incrementAndGet();
        if (closed) {
            return;
        }
        try {
            reconnector.execute(() -> {
                backoffS = 1;
                nextRetryAtMs = System.currentTimeMillis();
                connected = false;
                WebSocket old = current;
                current = null; // makes the old socket's close callback stale
                if (old != null) {
                    old.abort();
                }
                // If an old handshake is still in flight, its completion sees the
                // generation change, aborts itself, and performs this reconnect.
                if (!connecting) {
                    connect();
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Client shutdown won the race; there is no transport left to
            // re-authenticate and the saved credential remains durable.
        }
    }

    public boolean isConnected() {
        return connected;
    }

    /** Seconds until the next reconnect attempt; 0 while connected or due. */
    public long secondsUntilRetry() {
        if (connected) {
            return 0;
        }
        return Math.max(0, (nextRetryAtMs - System.currentTimeMillis() + 999) / 1000);
    }

    /** Best-first through the config's display filters, capped for the HUD.
     *  Live flips plus, when {@code showTakenFlips} is on, reconciliation-
     *  confirmed-taken flips still inside their grace window (rendered with
     *  a "taken" marker so a purchase is SEEN, not silently dropped —
     *  owner 2026-07-15). Age-based likely-gone rows stay excluded. */
    public synchronized List<Flip> top(int n) {
        List<Flip> out = new ArrayList<>();
        for (Flip f : flips) {
            if (!config.passes(f)) {
                continue;
            }
            boolean taken = config.showTakenFlips && f.withinTakenGrace(TAKEN_GRACE_MS);
            if (!f.likelyGone() || taken) {
                out.add(f);
            }
        }
        out.sort(config.sortMode.comparator());
        return out.subList(0, Math.min(n, out.size()));
    }

    /** How long a taken flip lingers on the HUD (marked) after reconcile
     *  confirms it's gone, so the purchase is visible before it drops. */
    static final long TAKEN_GRACE_MS = 8_000;

    // ---- open-key cycling: press once → best, again → next best… ----

    private final java.util.Map<String, Long> openedAt = new java.util.HashMap<>();
    private static final long OPENED_TTL_MS = 60_000;

    /** The flip the open key should act on: the best one not opened in the
     *  last minute. Once the whole visible board has been walked, wrap to
     *  the best again. Pure selection — marking happens only after the
     *  action actually fired ({@link #markOpened}), so a cooldown-rejected
     *  press retries the SAME flip instead of silently skipping it. */
    public synchronized Flip nextToOpen(int n) {
        List<Flip> top = top(n);
        if (top.isEmpty()) {
            return null;
        }
        long now = System.currentTimeMillis();
        openedAt.values().removeIf(t -> now - t > OPENED_TTL_MS);
        // Taken flips are SHOWN for visibility but never OPENED — the
        // auction is gone, so /viewauction would just open a dead GUI
        // (owner 2026-07-15). The open key skips straight to a live one.
        for (Flip f : top) {
            if (!f.gone && !openedAt.containsKey(f.uuid)) {
                return f;
            }
        }
        openedAt.clear(); // full lap — start over from the best LIVE flip
        for (Flip f : top) {
            if (!f.gone) {
                return f;
            }
        }
        return null;
    }

    public synchronized void markOpened(Flip f) {
        openedAt.put(f.uuid, System.currentTimeMillis());
    }

    private void connect() {
        if (closed || connected || connecting) {
            return;
        }
        connecting = true;
        long generation = credentialGeneration.get();
        String ws = config.apiBase.replaceFirst("^http", "ws") + "/ws/flips";
        var builder = http.newWebSocketBuilder();
        if (!config.apiToken.isEmpty()) {
            builder.header("Authorization", "Bearer " + config.apiToken); // header, not URL — no log leakage
        }
        builder.buildAsync(URI.create(ws), this)
                .whenComplete((sock, err) -> {
                    connecting = false;
                    if (generation != credentialGeneration.get()) {
                        // This handshake used the previous key. onOpen may
                        // have run just before the future completed, so clear
                        // only if it installed this exact stale socket.
                        if (sock != null) {
                            if (current == sock) {
                                current = null;
                                connected = false;
                            }
                            sock.abort();
                        }
                        if (!closed) {
                            reconnector.execute(this::connect);
                        }
                        return;
                    }
                    if (err != null) {
                        scheduleReconnect();
                    }
                });
    }

    private void scheduleReconnect() {
        boolean wasStable = connected && (System.currentTimeMillis() - connectedAtMs > 30_000);
        connected = false;
        current = null;
        if (closed) {
            return;
        }
        // Reset backoff only if the last connection actually held — a
        // handshake-then-immediate-close (bad token, shedding backend)
        // must NOT reset to a 1s reconnect storm.
        if (wasStable) {
            backoffS = 1;
        }
        nextRetryAtMs = System.currentTimeMillis() + backoffS * 1000L;
        reconnector.schedule(this::connect, backoffS, TimeUnit.SECONDS);
        backoffS = Math.min(Math.max(backoffS, 1) * 2, 30);
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        current = webSocket;
        connected = true;
        connectedAtMs = System.currentTimeMillis();
        synchronized (this) {
            partial.setLength(0);
        }
        Midasflip.LOG.info("flip feed connected");
        // The ws only tails NEW entries — anything published while we were
        // reconnecting would be lost without this backfill (deduped by uuid).
        reconnector.execute(this::backfill);
        WebSocket.Listener.super.onOpen(webSocket);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        if (webSocket != current) {
            return WebSocket.Listener.super.onText(webSocket, data, last); // stale generation
        }
        synchronized (this) {
            if (partial.length() + data.length() > MAX_PARTIAL) {
                Midasflip.LOG.warn("oversized ws frame — dropping buffer and reconnecting");
                partial.setLength(0);
                webSocket.abort();
                return null;
            }
            partial.append(data);
            if (last) {
                String msg = partial.toString();
                partial.setLength(0);
                handleMessage(msg);
            }
        }
        return WebSocket.Listener.super.onText(webSocket, data, last);
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        if (webSocket == current) {
            scheduleReconnect();
        }
        return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        if (webSocket == current || current == null) {
            Midasflip.LOG.warn("flip feed error: {}", error.toString());
            scheduleReconnect();
        }
    }

    private void handleMessage(String msg) {
        try {
            JsonObject obj = GSON.fromJson(msg, JsonObject.class);
            if (obj == null || !obj.has("type") || !"flip".equals(obj.get("type").getAsString())) {
                return; // heartbeat
            }
            ingest(GSON.fromJson(obj.get("data"), Flip.class));
        } catch (RuntimeException e) {
            Midasflip.LOG.warn("bad flip message: {}", e.toString());
        }
    }

    /** Shared admission path for ws and REST flips: validate, dedupe, buffer. */
    private void ingest(Flip flip) {
        if (flip == null || flip.uuid == null) {
            return;
        }
        flip.uuid = flip.uuid.replace("-", "").toLowerCase(java.util.Locale.ROOT);
        if (!UUID_RE.matcher(flip.uuid).matches()) {
            Midasflip.LOG.warn("dropped flip with malformed uuid");
            return;
        }
        flip.receivedAtMs = System.currentTimeMillis();
        synchronized (this) {
            if (!seen.add(flip.uuid)) {
                return;
            }
            flips.addFirst(flip);
            while (flips.size() > BUFFER) {
                seen.remove(flips.removeLast().uuid);
            }
        }
    }

    private HttpRequest.Builder apiRequest(String path) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(config.apiBase + path))
                .timeout(Duration.ofSeconds(5)); // a dead API must never wedge this thread
        if (!config.apiToken.isEmpty()) {
            b.header("Authorization", "Bearer " + config.apiToken);
        }
        return b;
    }

    /** Merge the freshest server-side flips after (re)connect. */
    private void backfill() {
        try {
            HttpResponse<String> resp = http.send(
                    apiRequest("/flips?limit=25").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return;
            }
            JsonArray arr = GSON.fromJson(resp.body(), JsonArray.class);
            // Newest-first from the API; ingest oldest-first so the deque
            // keeps newest at the head.
            for (int i = arr.size() - 1; i >= 0; i--) {
                ingest(GSON.fromJson(arr.get(i), Flip.class));
            }
        } catch (Exception e) {
            Midasflip.LOG.warn("flip backfill failed: {}", e.toString());
        }
    }

    /** Mark displayed flips the hot state no longer lists as gone. */
    private void reconcile() {
        List<String> ids = new ArrayList<>();
        List<Flip> queried = new ArrayList<>();
        synchronized (this) {
            for (Flip f : flips) {
                if (!f.likelyGone()) {
                    ids.add(f.uuid);
                    queried.add(f);
                    if (ids.size() >= 40) {
                        break;
                    }
                }
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        try {
            HttpResponse<String> resp = http.send(
                    apiRequest("/flips/active?ids=" + String.join(",", ids)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return;
            }
            Set<String> active = new HashSet<>();
            JsonObject obj = GSON.fromJson(resp.body(), JsonObject.class);
            for (JsonElement el : obj.getAsJsonArray("active")) {
                active.add(el.getAsString());
            }
            for (Flip f : queried) {
                if (!active.contains(f.uuid) && !f.gone) {
                    f.gone = true;
                    f.goneAtMs = System.currentTimeMillis(); // stamp once, for the HUD grace
                }
            }
            reconcileFailures = 0;
        } catch (Exception e) {
            // Log sparsely — this runs every 20s and an API outage is
            // already visible on the HUD status line.
            if (reconcileFailures++ % 15 == 0) {
                Midasflip.LOG.warn("flip reconcile failed: {}", e.toString());
            }
        }
    }
}
