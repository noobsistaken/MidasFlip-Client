package dev.midasflip.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * MidasFlip client entrypoint. Wiring rules (the safety architecture):
 * keybinds are the ONLY bridge from data to action, polled in the client
 * tick — physical input by construction. The HUD is draw-only. The feed is
 * network-in only.
 */
public final class MidasflipClient implements ClientModInitializer {
    private MidasflipConfig config;
    private FlipFeed feed;
    private ActionController actions;
    private FlipHud hud;
    private SessionTracker session;
    private MidasflipApi api;

    /** Package-visible static so display surfaces (FlipHud's margin-alert
     *  banner) can NAME the actual bound key ("press [O]") — read-only
     *  labeling; the keybind itself stays the only bridge to action. */
    static KeyMapping openBest;
    private KeyMapping toggleOverlay;
    private KeyMapping openMenu;

    @Override
    public void onInitializeClient() {
        config = MidasflipConfig.load();
        feed = new FlipFeed(config);
        actions = new ActionController(config);
        session = new SessionTracker(new PositionLedger(), config);
        api = new MidasflipApi(config);
        NameMap.init(api); // pretty item names on every surface
        ListingsWatch.init(api, config, session.ledger()); // undercut monitoring, display-only
        hud = new FlipHud(config, feed, session);
        feed.start();

        // Purchase tracking is chat-LISTEN only (spec §13: read-only) —
        // it retires bought flips from the board and tallies the session.
        ClientReceiveMessageEvents.GAME.register(session::onGameMessage);

        // Purchase confirm overlay — owner's §13/§14 amendment; off by
        // default, ASSISTED-only, own-opened auctions only.
        new PurchaseOverlay(config, actions).register();

        // Item hover tooltip — read-only, appends MidasFlip data to matching
        // items (owner request); toggle in Display.
        new ItemTooltip(config, feed, api).register();

        // Sell-assist overlay — display-only price help when listing an
        // item; ledger cost-basis or market estimate, forwards nothing.
        // Copies the suggested price to the clipboard so YOUR paste (a
        // physical keystroke) fills the sign — the mod never writes the
        // sign buffer (closing a sign submits it; only a human paste is
        // safe here).
        new SellOverlay(config, session.ledger(), api).register();

        KeyMapping.Category category =
                new KeyMapping.Category(Identifier.fromNamespaceAndPath(Midasflip.MOD_ID, "main"));
        openBest = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.midasflip.open_best", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_O, category));
        toggleOverlay = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.midasflip.toggle_overlay", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, category));
        openMenu = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.midasflip.open_menu", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, category));

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath(Midasflip.MOD_ID, "flip_board"),
                (extractor, tickCounter) -> hud.render(extractor));
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> feed.stop());

        Midasflip.LOG.info("MidasFlip loaded — mode={}, overlay={}", config.safetyMode, config.overlayEnabled);
    }

    private void onTick(Minecraft mc) {
        boolean toggled = false;
        while (toggleOverlay.consumeClick()) {
            toggled = true; // collapse OS key-repeat: one net toggle per tick
        }
        if (toggled) {
            config.overlayEnabled = !config.overlayEnabled;
            config.save();
        }

        while (openMenu.consumeClick()) {
            // First run (design 3c): unpaired client goes to setup first.
            if (config.apiToken.isEmpty()) {
                mc.setScreen(new MidasflipFirstRunScreen(null, config, api, feed));
            } else {
                MidasflipShellScreen shell = new MidasflipShellScreen(config, feed, actions, session, api,
                        MidasflipShellScreen.Tab.FLIPS);
                // The tour's other trigger lives in MidasflipFirstRunScreen,
                // which only ever renders for a client with NO token — so
                // anyone who already had one never reached it and never saw
                // the tour. That is every upgrader, not an edge case: the
                // gate was auth state when it should have been "have they
                // seen it". Opening it here with the shell as parent means
                // closing the tour lands where the keypress was headed.
                // onClose() records Tour.VERSION, so this fires exactly once.
                if (TourScreen.Tour.shouldOpen(config.tourSeenVersion)) {
                    mc.setScreen(new TourScreen(shell, config));
                } else {
                    mc.setScreen(shell);
                }
            }
        }

        // At most ONE action per tick regardless of key-repeat: drain the
        // rest so a held key can't amplify one physical press into spam.
        boolean pressed = false;
        while (openBest.consumeClick()) {
            pressed = true;
        }
        if (pressed) {
            // Cycling: first press opens the best, the next press the next
            // best, and so on; wraps after a full lap. Advance only when the
            // action actually fired — a cooldown-rejected press retries.
            Flip flip = feed.nextToOpen(config.overlayMaxRows);
            if (actions.onOpenKeyPressed(mc, flip)) {
                feed.markOpened(flip);
                session.onOpened(flip);
            }
        }
    }
}
