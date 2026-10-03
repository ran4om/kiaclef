package adris.altoclef.tasks.container;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.slot.MoveItemToSlotFromInventoryTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.trackers.storage.ContainerCache;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.Slot;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.BlockPos;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Moves items from your inventory to a storage container.
 */
public class StoreInContainerTask extends AbstractDoToStorageContainerTask {

    private final BlockPos _targetContainer;
    private final boolean _getIfNotPresent;
    private final ItemTarget[] _toStore;

    private ContainerStoredTracker _storedItems;
    private Task _activeTransferTask;
    private AbstractContainerMenu _activeTransferMenu;

    public StoreInContainerTask(BlockPos targetContainer, boolean getIfNotPresent, ItemTarget ...toStore) {
        _targetContainer = targetContainer;
        _getIfNotPresent = getIfNotPresent;
        _toStore = toStore;
    }

    @Override
    protected Optional<BlockPos> getContainerTarget() {
        return Optional.of(_targetContainer);
    }

    @Override
    protected void onStart(AltoClef mod) {
        super.onStart(mod);
        _activeTransferTask = null;
        _activeTransferMenu = null;
        if (_storedItems == null) {
            // Only consider transfers to the container we wish
            _storedItems = new ContainerStoredTracker(slot -> {
                Optional<BlockPos> openContainer = mod.getItemStorage().getContainerPositionForMenu(slot.menu());
                return ContainerStoredTracker.acceptsBoundContainer(openContainer,
                        position -> position.equals(_targetContainer));
            });
        }
        _storedItems.startTracking();
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (_activeTransferTask != null) {
            Player player = Minecraft.getInstance().player;
            AbstractContainerMenu currentMenu = player == null ? null : player.containerMenu;
            Optional<BlockPos> boundPosition = _activeTransferMenu == null
                    ? Optional.empty()
                    : mod.getItemStorage().getContainerPositionForMenu(_activeTransferMenu);
            if (!ContainerDepositCompletion.isTransferSessionValid(
                    _activeTransferMenu, currentMenu, boundPosition, _targetContainer)) {
                _activeTransferTask = null;
                _activeTransferMenu = null;
                setDebugState("Transfer stopped because its container menu changed; recovering cursor");
                if (!StorageHelper.getItemStackInCursorSlot().isEmpty()) {
                    return ContainerDepositCompletion.returnCursorTask(mod);
                }
                return null;
            }
            if (!_activeTransferTask.isFinished(mod)) return _activeTransferTask;
            _activeTransferTask = null;
            _activeTransferMenu = null;
        }
        if (!StorageHelper.getItemStackInCursorSlot().isEmpty()) {
            return ContainerDepositCompletion.returnCursorTask(mod);
        }
        if (ContainerDepositCompletion.shouldFinish(mod, _storedItems, _toStore, _getIfNotPresent)) {
            return ContainerDepositCompletion.isClean()
                    ? null
                    : ContainerDepositCompletion.cleanupTask(mod);
        }
        // Get more if we don't have & "get if not present" is true.
        if (_getIfNotPresent) {
            for (ItemTarget target : _toStore) {
                int inventoryNeed = target.getTargetCount() - _storedItems.getStoredCount(target.getMatches());
                if (inventoryNeed > mod.getItemStorage().getItemCount(target)) {
                    return TaskCatalogue.getItemTask(new ItemTarget(target, inventoryNeed));
                }
            }
        }
        return super.onTick(mod);
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        super.onStop(mod, interruptTask);
        _storedItems.stopTracking();
    }

    @Override
    protected void onResetForNewRun() {
        _activeTransferTask = null;
        _activeTransferMenu = null;
        if (_storedItems != null) {
            _storedItems.stopTracking();
            _storedItems.resetForNewRun();
        }
    }

    @Override
    protected Task onContainerOpenSubtask(AltoClef mod, ContainerCache containerCache) {
        // Move all items that aren't in the container
        for (ItemTarget target : _storedItems.getUnstoredItemTargetsYouCanStore(mod, _toStore)) {
                setDebugState("Dumping " + target);
                // Grab the item from the current chest that most closely matches our requirements
                List<Slot> potentials = mod.getItemStorage().getSlotsWithItemPlayerInventory(false, target.getMatches());

                // Pick the best slot to grab from.
                Optional<Slot> bestPotential = PickupFromContainerTask.getBestSlotToTransfer(
                        mod,
                        target,
                        mod.getItemStorage().getItemCountContainer(target.getMatches()),
                        potentials,
                        stack -> mod.getItemStorage().getSlotThatCanFitInOpenContainer(stack, true).isPresent());
                if (bestPotential.isPresent()) {
                    ItemStack stackIn = StorageHelper.getItemStackInSlot(bestPotential.get());
                    Optional<Slot> toMoveTo = mod.getItemStorage().getSlotThatCanFitInOpenContainer(stackIn, true);
                    if (toMoveTo.isEmpty()) {
                        setDebugState("CONTAINER FULL!");
                        return null;
                    }
                    ItemStack atDestination = StorageHelper.getItemStackInSlot(toMoveTo.get());
                    int existingCount = target.matches(atDestination.getItem()) ? atDestination.getCount() : 0;
                    int stackLimit = atDestination.isEmpty()
                            ? stackIn.getMaxStackSize() : atDestination.getMaxStackSize();
                    int depositCount = ContainerDepositCompletion.depositCountForSlot(
                            target.getTargetCount(), existingCount, stackLimit);
                    if (depositCount <= 0) continue;
                    ItemTarget destinationTarget = new ItemTarget(target,
                            ContainerDepositCompletion.destinationStackGoal(existingCount, depositCount));
                    setDebugState("Moving to slot...");
                    _activeTransferTask = new MoveItemToSlotFromInventoryTask(destinationTarget, toMoveTo.get());
                    Player player = Minecraft.getInstance().player;
                    _activeTransferMenu = player == null ? null : player.containerMenu;
                    return _activeTransferTask;
                }
                setDebugState("SHOULD NOT HAPPEN! No valid items detected.");
        }
        setDebugState("SHOULD NOT HAPPEN! All items stored but we're still trying.");
        return null;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return ContainerDepositCompletion.isComplete(
                ContainerDepositCompletion.shouldFinish(mod, _storedItems, _toStore, _getIfNotPresent),
                StorageHelper.getItemStackInCursorSlot().isEmpty(),
                ContainerDepositCompletion.craftingGridEmpty(),
                StorageHelper.isPlayerInventoryOpen());
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof StoreInContainerTask task) {
            return task._targetContainer.equals(_targetContainer)
                    && task._getIfNotPresent == _getIfNotPresent
                    && sameTargetKinds(task._toStore, _toStore);
        }
        return false;
    }

    static boolean sameTargetKinds(ItemTarget[] left, ItemTarget[] right) {
        if (left.length != right.length) return false;
        for (int index = 0; index < left.length; index++) {
            if (!Arrays.equals(left[index].getMatches(), right[index].getMatches())
                    || left[index].getTargetCount() < right[index].getTargetCount()) return false;
        }
        return true;
    }

    @Override
    protected String toDebugString() {
        return "Storing in container[" + _targetContainer.toShortString() + "] " + Arrays.toString(_toStore);
    }
}
