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
    private static final String DEVICE_HEADER = "X-MidasFlip-Device-Token";

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
        super(Component.literal("MidasFlip — connect account"));
        this.parent = parent;
        this.config = config;
        this.api = api;
        this.feed = feed;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        gone = false;
        int w = 300;
        int x = (width - w) / 2;
        int y = height / 2 - 62;

        connectBtn = Button.builder(Component.literal(connectLabel()), b -> startPair())
                .bounds(x + (w - 150) / 2, y, 150, 20).build();
        addRenderableWidget(connectBtn);
        y += 44;

        strictBtn = Button.builder(Component.literal("STRICT — copies, you paste"),
                        b -> pick(MidasflipConfig.SafetyMode.STRICT))
                .bounds(x, y, (w - 8) / 2, 20).build();
        assistedBtn = Button.builder(Component.literal("ASSISTED — sends on press"),
                        b -> pick(MidasflipConfig.SafetyMode.ASSISTED))
                .bounds(x + (w - 8) / 2 + 8, y, (w - 8) / 2, 20).build();
        addRenderableWidget(strictBtn);
        addRenderableWidget(assistedBtn);
        pick(config.safetyMode == null ? MidasflipConfig.SafetyMode.STRICT : config.safetyMode);
        y += 34;

        String doneLabel = parent == null ? "start flipping" : "back to settings";
        addRenderableWidget(Button.builder(Component.literal(doneLabel), b -> finish())
                .bounds(x + (w - 140) / 2, y, 140, 20).build());
    }

    private void pick(MidasflipConfig.SafetyMode mode) {
        chosen = mode;
        strictBtn.active = mode != MidasflipConfig.SafetyMode.STRICT;
        assistedBtn.active = mode != MidasflipConfig.SafetyMode.ASSISTED;
    }

    private void finish() {
        config.safetyMode = chosen;
        if (config.save()) {
            onClose();
        } else {
            pairProblem = "couldn't save settings — check the config folder";
        }
    }

    private void startPair() {
        pairState = "starting";
        pairProblem = "";
        pairCode = null;
        deviceToken = null;
        expiresAtMs = 0;
        ackDeadlineMs = 0;
        requestInflight = true;
        api.request("POST", "/pair/start?v=2", el -> {
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
            pairProblem = "pairing session was lost — start again";
            return;
        }
        requestInflight = true;
        api.request("GET", "/pair/poll?code=" + encode(code),
                Map.of(DEVICE_HEADER, proof), el -> {
                    requestInflight = false;
                    JsonObject o = object(el);
                    if (o == null) {
                        pairProblem = "connection interrupted — retrying";
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
                        pairProblem = "unexpected pairing response — start again";
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
            pairProblem = "couldn't save the account key — retrying";
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
                        pairProblem = "linked locally — securing hand-off";
                        nextRequestAtMs = System.currentTimeMillis() + pollMs;
                        return;
                    }
                    String status = string(o, "status");
                    if ("acknowledged".equals(status) || "expired".equals(status)) {
                        finishLinked();
                    } else {
                        pairProblem = "linked locally — securing hand-off";
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
        super.extractRenderState(gfx, mouseX, mouseY, delta);
        int w = 300;
        int x = (width - w) / 2;
        int y = height / 2 - 108;
        Phos.textShadow(gfx, font, "§6MIDASFLIP§r", x, y, 0xFFFFFFFF);
        Phos.textShadow(gfx, font,
                parent == null ? "§7first run · two steps, then you're live§r"
                        : "§7account connection · replace the saved login safely§r",
                x, y + 12, 0xFFFFFFFF);
        Phos.textShadow(gfx, font, "§71 · LINK YOUR ACCOUNT§r " + pairHint(), x, y + 32, 0xFFFFFFFF);
        Phos.textShadow(gfx, font,
                "§8only the six-character code is shown; your account key stays hidden§r",
                x, y + 45, 0xFFFFFFFF);
        Phos.textShadow(gfx, font,
                "§72 · CHOOSE A MODE§r §8· change anytime in settings · STRICT recommended§r",
                x, y + 76, 0xFFFFFFFF);
        Phos.textShadow(gfx, font,
                "§8either way: one physical press = one action, never automated§r",
                x, y + 148, 0xFFFFFFFF);
    }

    private String pairHint() {
        String hint = switch (pairState) {
            case "starting" -> "§8· creating a secure code…§r";
            case "waiting" -> "§8· code §f" + pairCode + "§8 · " + remainingS()
                    + "s — enter at " + dashboardLabel() + "§r";
            case "saving" -> "§8· saving the linked account…§r";
            case "acking" -> "§a· linked locally§8 · securing hand-off…§r";
            case "linked" -> "§a· website account linked ✓§r";
            case "expired" -> "§c· code expired — try again§r";
            case "error" -> "§c· pairing failed — try again§r";
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
