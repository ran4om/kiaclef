package adris.altoclef.eventbus.events;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/** A client-predicted slot change for the local player's current menu. */
public final class SlotClickChangedEvent {
    private final AbstractContainerMenu menu;
    private final int menuId;
    private final int windowSlot;
    private final boolean playerInventorySlot;
    private final ItemStack before;
    private final ItemStack after;

    public SlotClickChangedEvent(AbstractContainerMenu menu, int windowSlot,
                                 boolean playerInventorySlot, ItemStack before, ItemStack after) {
        this.menu = menu;
        this.menuId = menu.containerId;
        this.windowSlot = windowSlot;
        this.playerInventorySlot = playerInventorySlot;
        this.before = before.copy();
        this.after = after.copy();
    }

    public AbstractContainerMenu menu() {
        return menu;
    }

    public int menuId() {
        return menuId;
    }

    public int windowSlot() {
        return windowSlot;
    }

    public boolean playerInventorySlot() {
        return playerInventorySlot;
    }

    public ItemStack before() {
        return before.copy();
    }

    public ItemStack after() {
        return after.copy();
    }
}
