package dev.midasflip.client.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Immediate-mode base for the phosphor UI: panes draw with Phos
 * primitives and register hit zones as they go; clicks and drags resolve
 * against the zones from the LAST rendered frame (render thread on both
 * sides, so the frame-old rects are exactly what the player saw).
 * Vanilla widgets still work on top (EditBox for text input).
 */
public abstract class PhosScreen extends Screen {
    protected record Zone(int x, int y, int w, int h, Runnable onClick, DragSink drag) {}

    public interface DragSink {
        void dragTo(double frac); // 0..1 across the zone width
    }

    private final List<Zone> zones = new ArrayList<>();
    private Zone dragging;

    protected PhosScreen(Component title) {
        super(title);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Panes call this while drawing to make the region clickable. */
    protected void zone(int x, int y, int w, int h, Runnable onClick) {
        zones.add(new Zone(x, y, w, h, onClick, null));
    }

    /** A horizontal drag zone (sliders): click or drag sets 0..1. */
    protected void dragZone(int x, int y, int w, int h, DragSink sink) {
        zones.add(new Zone(x, y, w, h, null, sink));
    }

    protected boolean hovered(int x, int y, int w, int h, int mx, int my) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** Subclasses draw everything here; zones reset each frame. */
    protected abstract void drawPhos(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta);

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        zones.clear();
        g.fill(0, 0, width, height, Phos.BG);
        super.extractRenderState(g, mouseX, mouseY, delta); // vanilla widgets (EditBoxes)
        drawPhos(g, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (super.mouseClicked(event, doubled)) {
            return true;
        }
        if (event.button() != 0) {
            return false;
        }
        for (int i = zones.size() - 1; i >= 0; i--) {
            Zone z = zones.get(i);
            if (event.x() >= z.x() && event.x() < z.x() + z.w()
                    && event.y() >= z.y() && event.y() < z.y() + z.h()) {
                if (z.drag() != null) {
                    dragging = z;
                    z.drag().dragTo((event.x() - z.x()) / z.w());
                    return true;
                }
                if (z.onClick() != null) {
                    z.onClick().run();
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (dragging != null) {
            dragging.drag().dragTo((event.x() - dragging.x()) / dragging.w());
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        dragging = null;
        return super.mouseReleased(event);
    }
}
