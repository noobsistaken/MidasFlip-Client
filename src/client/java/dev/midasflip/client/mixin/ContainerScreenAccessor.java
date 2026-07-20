package dev.midasflip.client.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read-only accessor for the hovered slot (behavior-neutral — no @Inject,
 * no code runs). The sell panel prices the exact slot the game reports as
 * hovered, which is what draws the vanilla tooltip; deriving GUI geometry
 * ourselves misfired on Hypixel's create-auction menu. No Fabric event
 * exposes this field, hence the accessor (fabric-mod-client rule).
 */
@Mixin(AbstractContainerScreen.class)
public interface ContainerScreenAccessor {
    @Accessor("hoveredSlot")
    Slot midasflip$hoveredSlot();
}
