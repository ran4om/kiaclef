package adris.altoclef.mixins;

import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.SlotClickChangedEvent;
import net.minecraft.core.NonNullList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerMenu.class)
public abstract class SlotClickMixin {
    @Shadow public NonNullList<net.minecraft.world.inventory.Slot> slots;
    @Unique private NonNullList<ItemStack> altoclef$beforeStacks;
    @Unique private AbstractContainerMenu altoclef$beforeMenu;
    @Unique private boolean altoclef$trackingLocalClick;

    @Inject(method = "clicked", at = @At("HEAD"))
    private void altoclef$captureSlots(int slotIndex, int button, ContainerInput actionType,
                                       Player player, CallbackInfo ci) {
        if (!(player instanceof LocalPlayer) || player != Minecraft.getInstance().player) return;
        AbstractContainerMenu menu = (AbstractContainerMenu) (Object) this;
        altoclef$trackingLocalClick = player.containerMenu == menu;
        if (!altoclef$trackingLocalClick) {
            altoclef$clearSnapshot();
            return;
        }
        altoclef$beforeMenu = menu;
        altoclef$beforeStacks = NonNullList.withSize(slots.size(), ItemStack.EMPTY);
        for (int i = 0; i < slots.size(); i++) altoclef$beforeStacks.set(i, slots.get(i).getItem().copy());
    }

    @Inject(method = "clicked", at = @At("TAIL"))
    private void altoclef$publishSlotChanges(int slotIndex, int button, ContainerInput actionType,
                                             Player player, CallbackInfo ci) {
        if (!(player instanceof LocalPlayer) || player != Minecraft.getInstance().player) return;
        if (!altoclef$trackingLocalClick) return;
        AbstractContainerMenu menu = (AbstractContainerMenu) (Object) this;
        if (altoclef$beforeStacks == null || altoclef$beforeMenu != menu
                || player.containerMenu != menu) {
            altoclef$clearSnapshot();
            return;
        }
        for (int i = 0; i < Math.min(altoclef$beforeStacks.size(), slots.size()); i++) {
            ItemStack before = altoclef$beforeStacks.get(i);
            ItemStack after = slots.get(i).getItem();
            if (!ItemStack.matches(before, after)) {
                net.minecraft.world.inventory.Slot nativeSlot = slots.get(i);
                boolean playerInventorySlot = nativeSlot.container == player.getInventory();
                EventBus.publish(new SlotClickChangedEvent(menu, i, playerInventorySlot, before, after));
            }
        }
        altoclef$clearSnapshot();
    }

    @Unique
    private void altoclef$clearSnapshot() {
        altoclef$beforeStacks = null;
        altoclef$beforeMenu = null;
        altoclef$trackingLocalClick = false;
    }
}
