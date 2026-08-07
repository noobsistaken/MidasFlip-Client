package dev.midasflip.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.midasflip.client.ui.Phos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Account connection screen. Pairing v2 is a device-code flow: the mod asks
 * for a short public code plus a private device proof, shows only the code,
 * and polls until the website has claimed it. The proof never leaves memory
 * except in its dedicated request header; neither the current nor newly
 * issued API key is rendered.
 *
 * The server repeats a completed hand-off until the client acknowledges it.
 * We acknowledge only after {@link MidasflipConfig#save()} succeeds, so a disk
 * error cannot destroy the sole recoverable copy of a newly issued key.
 */
public final class MidasflipFirstRunScreen extends Screen {
    private static final long POLL_MS = 5_000;
    private static final long ACK_RETRY_MS = 130_000;
    /** WIRE HEADER — must stay "SkyFlip". The rename swept this to
     *  X-MidasFlip-Device-Token, but the server reads x-skyflip-device-token
     *  (routes_public.py), so every v2 poll arrived with no proof of device.
     *  The server answers a missing/wrong token with "expired" — identical to
     *  a dead code, deliberately, so it cannot be used to enumerate codes —
     *  and the mod dutifully reported "code expired" about five seconds after
     *  issuing a 600-second code. Reported live 2026-07-30.
     *
     *  docs/branding.md keeps X-SkyFlip-* headers on the old name for exactly
     *  this reason: they are a protocol contract, not user-facing copy. */
    private static final String DEVICE_HEADER = "X-SkyFlip-Device-Token";

    private final Screen parent;
    private final MidasflipConfig config;
    private final MidasflipApi api;
    private final FlipFeed feed;
    private MidasflipConfig.SafetyMode chosen = MidasflipConfig.SafetyMode.STRICT;
    private Button strictBtn;
    private Button assistedBtn;
    private Button connectBtn;

    // Network callbacks run on MidasflipApi's pool while tick/render run on the
    // client thread. Pairing secrets are intentionally memory-only.
    private volatile String pairCode;
    private volatile String deviceToken;
    private volatile String pairState = ""; // starting|waiting|saving|acking|linked|expired|error
    private volatile String pairProblem = "";
    private volatile boolean requestInflight;
    private volatile boolean gone;
    private volatile long expiresAtMs;
    private volatile long nextRequestAtMs;
    private volatile long pollMs = POLL_MS;
    private volatile long ackDeadlineMs;

    /** Compatibility constructor for the original first-run call site. */
    public MidasflipFirstRunScreen(MidasflipConfig config, MidasflipApi api) {
        this(null, config, api, null);
    }

    /** Opens pairing from first run (parent null) or from Safety (parent set). */
    public MidasflipFirstRunScreen(Screen parent, MidasflipConfig config, MidasflipApi api, FlipFeed feed) {
        super(Component.literal("MidasFlip · connect account"));
        this.parent = parent;
        this.config = config;
        this.api = api;
        this.feed = feed;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ONE layout origin, shared by init() (buttons) and the renderer (text).
    // They used to be computed independently — height/2−108 for text and
    // height/2−62 for buttons — which put the connect button exactly on top
    // of the "only the six-character code is shown" line (owner 2026-07-30).
    // Every row below is an offset from originY(), so text and widgets cannot
    // drift apart again: moving a row moves both.
    // Wide enough for the longest line this screen can draw: the code row,
    // "1 · LINK YOUR ACCOUNT · code XXXXXX · 565s · enter at
    // midasflip.com/dashboard". At 300 it ran off the card (owner 2026-07-30).
    private static final int CARD_W = 468;
    private static final int ROW_TITLE = 0;
    private static final int ROW_SUB = 12;
    private static final int ROW_STEP1 = 34;
    private static final int ROW_HINT1 = 47;
    private static final int ROW_CONNECT = 62;   // 20 tall -> ends 82
    private static final int ROW_STEP2 = 96;     // 14px clear of the button
    private static final int ROW_MODES = 112;    // 20 tall -> ends 132
    private static final int ROW_DONE = 142;     // 20 tall -> ends 162
    private static final int ROW_FOOT = 170;
    private static final int CARD_CONTENT_H = 182;

    /** Card width, clamped so a small window or a large GUI scale cannot
     *  push the card wider than the screen. */
    private int cardW() {
        return Math.min(CARD_W, Math.max(220, width - 60));
    }

    private int originY() {
        return height / 2 - 108;
    }

    @Override
    protected void init() {
        gone = false;
        int w = cardW();
        int x = (width - w) / 2;
        int y = originY();

        connectBtn = Button.builder(Component.literal(connectLabel()), b -> startPair())
                .bounds(x + (w - 150) / 2, y + ROW_CONNECT, 150, 20).build();
        addRenderableWidget(connectBtn);

        strictBtn = Button.builder(Component.literal("STRICT · copies, you paste"),
                        b -> pick(MidasflipConfig.SafetyMode.STRICT))
                .bounds(x, y + ROW_MODES, (w - 8) / 2, 20).build();
        assistedBtn = Button.builder(Component.literal("ASSISTED · sends on press"),
                        b -> pick(MidasflipConfig.SafetyMode.ASSISTED))
                .bounds(x + (w - 8) / 2 + 8, y + ROW_MODES, (w - 8) / 2, 20).build();
        addRenderableWidget(strictBtn);
        addRenderableWidget(assistedBtn);
        pick(config.safetyMode == null ? MidasflipConfig.SafetyMode.STRICT : config.safetyMode);

        String doneLabel = parent == null ? "start flipping" : "back to settings";
        addRenderableWidget(Button.builder(Component.literal(doneLabel), b -> finish())
                .bounds(x + (w - 140) / 2, y + ROW_DONE, 140, 20).build());
    }

    private void pick(MidasflipConfig.SafetyMode mode) {
        chosen = mode;
        strictBtn.active = mode != MidasflipConfig.SafetyMode.STRICT;
        assistedBtn.active = mode != MidasflipConfig.SafetyMode.ASSISTED;
    }

    private void finish() {
        config.safetyMode = chosen;
        if (!config.save()) {
            pairProblem = "couldn't save settings · check the config folder";
            return;
        }
        // First run only (parent == null): hand off to the tour once. It
        // closes to the same place this screen would have. Re-pairing from
        // Safety never replays it — that user has been here before, and
        // About keeps the tour reachable on demand.
        if (parent == null && TourScreen.Tour.shouldOpen(config.tourSeenVersion)) {
            Minecraft.getInstance().setScreen(new TourScreen(null, config));
            return;
        }
        onClose();
    }

    private void startPair() {
        pairState = "starting";
        pairProblem = "";
        pairCode = null;
        deviceToken = null;
        expiresAtMs = 0;
        ackDeadlineMs = 0;
        requestInflight = true;
        // Offer the in-game identity alongside the code so pairing and
        // linking are one step. Without the link the sell-side endpoints
        // 403 (main.py _own_player_uuid) and ListingsWatch swallows it, so
        // a user who skips the website form just gets undercut alerts that
        // never arrive. Headers, not the query string: a uuid in a URL ends
        // up in access logs and referrers.
        java.util.Map<String, String> ident = new java.util.HashMap<>();
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player != null) {
            ident.put("X-SkyFlip-MC-Uuid", mc.player.getUUID().toString().replace("-", ""));
            // getUser() is the authenticated Mojang session, not the
            // display name — a nick or a team prefix must not become the
            // name we match auction sellers on.
            ident.put("X-SkyFlip-MC-Ign", mc.getUser().getName());
        }
        api.request("POST", "/pair/start?v=2", ident, el -> {
            requestInflight = false;
            if (gone) {
                return;
            }
            JsonObject o = object(el);
            String code = string(o, "code");
            String proof = string(o, "device_token");
            if (code == null || code.isBlank() || code.length() > 16
                    || proof == null || proof.isBlank() || proof.length() > 256) {
                pairState = "error";
                pairProblem = "couldn't start a secure pairing session";
                return;
            }
            long expiresS = number(o, "expires_s", 600);
            expiresS = Math.max(1, Math.min(expiresS, 3_600));
            long pollS = number(o, "poll_s", POLL_MS / 1_000);
            pollMs = Math.max(2, Math.min(pollS, 30)) * 1_000;
            pairCode = code;
            deviceToken = proof; // memory only; never copied into config
            long now = System.currentTimeMillis();
            expiresAtMs = now + expiresS * 1_000;
            nextRequestAtMs = now + pollMs;
            pairState = "waiting";
        });
    }

    @Override
    public void tick() {
        super.tick();
        if (connectBtn != null) {
            connectBtn.setMessage(Component.literal(connectLabel()));
            connectBtn.active = !busy();
        }
        if (gone || requestInflight) {
            return;
        }
        long now = System.currentTimeMillis();
        if ("waiting".equals(pairState)) {
            if (expiresAtMs > 0 && now >= expiresAtMs) {
                expire();
            } else if (now >= nextRequestAtMs) {
                pollPair();
            }
        } else if ("acking".equals(pairState)) {
            if (ackDeadlineMs > 0 && now >= ackDeadlineMs) {
                // The server's hand-off record self-expires after 120s. The
                // key is already durable locally, so an unavailable ACK route
                // must not trap the user on this screen forever.
                finishLinked();
            } else if (now >= nextRequestAtMs) {
                ackPair();
            }
        }
    }

    private void pollPair() {
        String code = pairCode;
        String proof = deviceToken;
        if (code == null || proof == null) {
            pairState = "error";
            pairProblem = "pairing session was lost · start again";
            return;
        }
        requestInflight = true;
        api.request("GET", "/pair/poll?code=" + encode(code),
                Map.of(DEVICE_HEADER, proof), el -> {
                    requestInflight = false;
                    JsonObject o = object(el);
                    if (o == null) {
                        pairProblem = "connection interrupted · retrying";
                        nextRequestAtMs = System.currentTimeMillis() + pollMs;
                        return;
                    }
                    String status = string(o, "status");
                    if ("pending".equals(status)) {
                        pairProblem = "";
                        nextRequestAtMs = System.currentTimeMillis() + pollMs;
                    } else if ("done".equals(status)) {
                        String received = string(o, "key");
                        // Config and transport state belong to the client
                        // thread; the HTTP callback runs on MidasflipApi's pool.
                        pairState = "saving";
                        Minecraft.getInstance().execute(() -> acceptCredential(received));
                    } else if ("expired".equals(status) || "denied".equals(status)) {
                        expire();
                    } else {
                        pairState = "error";
                        pairProblem = "unexpected pairing response · start again";
                    }
                });
    }

    /**
     * Persist first, then acknowledge. On a failed write the old credential is
     * restored in memory and polling continues; v2 will deliver the same key
     * again until its pickup window expires.
     */
    private void acceptCredential(String key) {
        if (key == null || key.isBlank() || key.length() > 96) {
            pairState = "error";
            pairProblem = "server returned an invalid account key";
            return;
        }
        String previous = config.apiToken;
        config.apiToken = key;
        // Durability before acknowledgement: the key must land in the
        // TOKEN STORE (config.save() no longer persists it — the transient
        // field keeps plaintext out of midasflip.json). A one-shot secret is
        // only safe to ack once this write is confirmed.
        if (!TokenStore.save(key) || !config.save()) {
            config.apiToken = previous;
            pairState = "waiting";
            pairProblem = "couldn't save the account key · retrying";
            nextRequestAtMs = System.currentTimeMillis() + pollMs;
            return;
        }

        // The key is durable now. Clear responses fetched under the previous
        // identity and force the live feed to authenticate again immediately.
        api.credentialsChanged();
        if (feed != null) {
            feed.credentialsChanged();
        }
        pairState = "acking";
        pairProblem = "";
        long now = System.currentTimeMillis();
        ackDeadlineMs = now + ACK_RETRY_MS;
        nextRequestAtMs = now;
        ackPair();
    }

    private void ackPair() {
        String code = pairCode;
        String proof = deviceToken;
        if (code == null || proof == null) {
            finishLinked();
            return;
        }
        requestInflight = true;
        api.request("POST", "/pair/ack?code=" + encode(code),
                Map.of(DEVICE_HEADER, proof), el -> {
                    requestInflight = false;
                    JsonObject o = object(el);
                    if (o == null) {
                        pairProblem = "linked locally · securing hand-off";
                        nextRequestAtMs = System.currentTimeMillis() + pollMs;
                        return;
                    }
                    String status = string(o, "status");
                    if ("acknowledged".equals(status) || "expired".equals(status)) {
                        finishLinked();
                    } else {
                        pairProblem = "linked locally · securing hand-off";
                        nextRequestAtMs = System.currentTimeMillis() + pollMs;
                    }
                });
    }

    private void finishLinked() {
        pairState = "linked";
        pairProblem = "";
        pairCode = null;
        deviceToken = null;
        expiresAtMs = 0;
        ackDeadlineMs = 0;
    }

    private void expire() {
        pairState = "expired";
        pairProblem = "";
        pairCode = null;
        deviceToken = null;
        expiresAtMs = 0;
        ackDeadlineMs = 0;
    }

    private boolean busy() {
        return "starting".equals(pairState)
                || "waiting".equals(pairState)
                || "saving".equals(pairState)
                || "acking".equals(pairState);
    }

    private String connectLabel() {
        return config.apiToken == null || config.apiToken.isEmpty()
                ? "connect website account"
                : "re-pair website account";
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void removed() {
        gone = true;
        super.removed();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor gfx, int mouseX, int mouseY, float delta) {
        int w = cardW();
        int x = (width - w) / 2;
        int y = originY();

        // Backdrop. This screen extends Screen, not PhosScreen, so it
        // inherited no page background and painted straight onto the world —
        // white text over bright terrain, unreadable (owner 2026-07-30).
        //
        // Drawn BEFORE super so the vanilla widgets land on top of the card
        // rather than under it. Deliberately a translucent card rather than
        // PhosScreen's opaque page fill: this is the first thing a new user
        // sees, and keeping the game visible behind it says "an overlay, not
        // a takeover" — which is exactly what this mod is.
        int pad = 18;
        int cx = x - pad;
        int cy = y - pad;
        int cw = w + pad * 2;
        int ch = CARD_CONTENT_H + pad * 2;
        gfx.fill(0, 0, width, height, 0x99000000);          // world dim
        gfx.fill(cx, cy, cx + cw, cy + ch, 0xE00D1117);     // card, ~88% opaque
        Phos.border(gfx, cx, cy, cw, ch, Phos.BORDER);

        super.extractRenderState(gfx, mouseX, mouseY, delta);
        Phos.textShadow(gfx, font, "§6MIDASFLIP§r", x, y + ROW_TITLE, 0xFFFFFFFF);
        Phos.textShadow(gfx, font,
                parent == null ? "§7first run · two steps, then you're live§r"
                        : "§7account connection · replace the saved login safely§r",
                x, y + ROW_SUB, 0xFFFFFFFF);
        Phos.textShadow(gfx, font, "§71 · LINK YOUR ACCOUNT§r " + pairHint(), x, y + ROW_STEP1, 0xFFFFFFFF);
        Phos.textShadow(gfx, font,
                "§8only the six-character code is shown; your account key stays hidden§r",
                x, y + ROW_HINT1, 0xFFFFFFFF);
        Phos.textShadow(gfx, font,
                // No recommendation here (owner 2026-07-30). Nudging toward
                // STRICT was dishonest: assisted is the mode we build for and
                // the one most people want. State the difference, let them
                // pick, make it reversible.
                "§72 · CHOOSE A MODE§r §8· change it anytime in settings§r",
                x, y + ROW_STEP2, 0xFFFFFFFF);
        Phos.textShadow(gfx, font,
                "§8either way: one physical press = one action, never automated§r",
                x, y + ROW_FOOT, 0xFFFFFFFF);
    }

    private String pairHint() {
        String hint = switch (pairState) {
            case "starting" -> "§8· creating a secure code…§r";
            case "waiting" -> "§8· code §f" + pairCode + "§8 · " + remainingS()
                    + "s · enter at " + dashboardLabel() + "§r";
            case "saving" -> "§8· saving the linked account…§r";
            case "acking" -> "§a· linked locally§8 · securing hand-off…§r";
            case "linked" -> "§a· website account linked ✓§r";
            case "expired" -> "§c· code expired · try again§r";
            case "error" -> "§c· pairing failed · try again§r";
            default -> config.apiToken == null || config.apiToken.isEmpty()
                    ? "§8· connect your website account§r"
                    : "§a· account key saved§8 · re-pair any time§r";
        };
        return pairProblem == null || pairProblem.isEmpty()
                ? hint : hint + " §c· " + pairProblem + "§r";
    }

    private long remainingS() {
        return Math.max(0, (expiresAtMs - System.currentTimeMillis() + 999) / 1_000);
    }

    private String dashboardLabel() {
        String base = config.apiBase == null ? "" : config.apiBase.strip();
        if (base.isEmpty()) {
            base = MidasflipConfig.DEFAULT_API_BASE;
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base.replaceFirst("^https?://", "") + "/dashboard";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static JsonObject object(JsonElement el) {
        return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
    }

    private static String string(JsonObject o, String name) {
        try {
            return o != null && o.has(name) && !o.get(name).isJsonNull()
                    ? o.get(name).getAsString() : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static long number(JsonObject o, String name, long fallback) {
        try {
            return o != null && o.has(name) ? o.get(name).getAsLong() : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }
}
