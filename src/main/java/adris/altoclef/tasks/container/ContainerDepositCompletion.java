package adris.altoclef.tasks.container;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.slot.ClickSlotTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.slots.Slot;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.InventoryMenu;

import java.util.Arrays;
import java.util.Optional;
import java.util.Optional;

/** Shared accounting and cursor/UI cleanup for item deposit tasks. */
final class ContainerDepositCompletion {
    private ContainerDepositCompletion() {}

    static boolean targetsStored(ContainerStoredTracker tracker, ItemTarget[] targets) {
        return tracker != null && Arrays.stream(targets).allMatch(tracker::matches);
    }

    static boolean shouldFinish(AltoClef mod, ContainerStoredTracker tracker,
                                ItemTarget[] targets, boolean getIfNotPresent) {
        return targetsStored(tracker, targets)
                || (!getIfNotPresent && tracker != null
                    && tracker.getUnstoredItemTargetsYouCanStore(mod, targets).length == 0);
    }

    static boolean isComplete(boolean targetsStored, boolean cursorEmpty,
                              boolean craftingGridEmpty, boolean playerInventoryOpen) {
        return targetsStored && cursorEmpty && craftingGridEmpty && playerInventoryOpen;
    }

    static int depositCountForSlot(int requestedCount, int existingCount, int stackLimit) {
        return Math.max(0, Math.min(requestedCount, stackLimit - existingCount));
    }

    static int destinationStackGoal(int existingCount, int depositCount) {
        return existingCount + depositCount;
    }

    static boolean isTransferSessionValid(Object transferMenu, Object currentMenu,
                                          Optional<BlockPos> boundPosition, BlockPos targetPosition) {
        return transferMenu != null && transferMenu == currentMenu
                && boundPosition.filter(targetPosition::equals).isPresent();
    }

    static boolean isClean() {
        var player = Minecraft.getInstance().player;
        return player != null && StorageHelper.getItemStackInCursorSlot().isEmpty()
                && player.containerMenu == player.inventoryMenu
                && inventoryCraftingGridEmpty(player.inventoryMenu);
    }

    static boolean craftingGridEmpty() {
        var player = Minecraft.getInstance().player;
        return player != null && inventoryCraftingGridEmpty(player.inventoryMenu);
    }

    /**
     * Returns a task to put any remainder carried on the cursor back into the
     * player inventory, or closes the container after the cursor is empty.
     */
    static Task cleanupTask(AltoClef mod) {
        Task returnCursor = returnCursorTask(mod);
        if (returnCursor != null) return returnCursor;
        var player = Minecraft.getInstance().player;
        if (player != null && player.containerMenu == player.inventoryMenu) {
            for (int index = 0; index < 4; index++) {
                if (!player.inventoryMenu.getSlot(index + 1).getItem().isEmpty()) {
                    return new ClickSlotTask(PlayerSlot.getCraftInputSlot(index));
                }
            }
        }
        if (!StorageHelper.isPlayerInventoryOpen()) {
            StorageHelper.closeScreen();
        }
        return null;
    }

    static Task returnCursorTask(AltoClef mod) {
        ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
        if (cursor.isEmpty()) return null;
        Optional<Slot> destination = mod.getItemStorage()
                .getSlotThatCanFitInPlayerInventory(cursor, true);
        return destination.map(ClickSlotTask::new).orElse(null);
    }

    private static boolean inventoryCraftingGridEmpty(InventoryMenu menu) {
        for (int index = 1; index <= 4; index++) {
            if (!menu.getSlot(index).getItem().isEmpty()) return false;
        }
        return true;
    }
}
