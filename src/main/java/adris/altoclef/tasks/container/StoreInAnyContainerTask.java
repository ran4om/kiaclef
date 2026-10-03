package adris.altoclef.tasks.container;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.DoToClosestBlockTask;
import adris.altoclef.tasks.construction.PlaceBlockNearbyTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.trackers.storage.ContainerCache;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.BlockPos;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Dumps items in any container, placing a chest if we can't find any.
 */
public class StoreInAnyContainerTask extends Task {

    private final ItemTarget[] _toStore;
    private final boolean _getIfNotPresent;
    private final Block[] _containerBlocks;

    private final HashSet<BlockPos> _dungeonChests = new HashSet<>();
    private final HashSet<BlockPos> _nonDungeonChests = new HashSet<>();

    private final MovementProgressChecker _progressChecker = new MovementProgressChecker(2);
    private BlockPos _currentChestTry = null;

    private static final Block[] TO_SCAN = Stream.concat(Arrays.stream(new Block[]{Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL}), Arrays.stream(ItemHelper.itemsToBlocks(ItemHelper.SHULKER_BOXES))).toArray(Block[]::new);

    private ContainerStoredTracker _storedItems;
    private Task _activeContainerTask;
    private boolean _enderChestFull;

    public StoreInAnyContainerTask(boolean getIfNotPresent, ItemTarget ...toStore) {
        this(getIfNotPresent, TO_SCAN, toStore);
    }

    private StoreInAnyContainerTask(boolean getIfNotPresent, Block[] containerBlocks, ItemTarget...toStore) {
        _getIfNotPresent = getIfNotPresent;
        if (containerBlocks == null || containerBlocks.length == 0) {
            throw new IllegalArgumentException("At least one container block is required");
        }
        _containerBlocks = Arrays.copyOf(containerBlocks, containerBlocks.length);
        _toStore = Arrays.copyOf(toStore, toStore.length);
    }

    public static StoreInAnyContainerTask forContainerBlocks(boolean getIfNotPresent,
                                                              Block[] containerBlocks,
                                                              ItemTarget...toStore) {
        return new StoreInAnyContainerTask(getIfNotPresent, containerBlocks, toStore);
    }

    @Override
    protected void onStart(AltoClef mod) {
        mod.getBlockTracker().trackBlock(_containerBlocks);
        if (_storedItems == null) {
            _storedItems = new ContainerStoredTracker(event -> {
                Optional<BlockPos> openContainer = mod.getItemStorage().getContainerPositionForMenu(event.menu());
                return ContainerStoredTracker.acceptsBoundContainer(openContainer, position -> {
                    Block openedBlock = mod.getWorld().getBlockState(position).getBlock();
                    return ContainerStoredTracker.isConfiguredContainerBlock(openedBlock, _containerBlocks);
                });
            });
        }
        _storedItems.startTracking();
        _dungeonChests.clear();
        _nonDungeonChests.clear();
        _activeContainerTask = null;
        _enderChestFull = false;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (ContainerDepositCompletion.shouldFinish(mod, _storedItems, _toStore, _getIfNotPresent)) {
            return ContainerDepositCompletion.isClean()
                    ? null
                    : ContainerDepositCompletion.cleanupTask(mod);
        }
        if (!StorageHelper.getItemStackInCursorSlot().isEmpty() && _activeContainerTask != null) {
            return _activeContainerTask;
        }
        if (isEnderChestOnly()
                && mod.getItemStorage().getEnderChestStorage().map(ContainerCache::isFull).orElse(false)) {
            _enderChestFull = true;
            mod.logWarning("The Ender Chest is full. Free space and rerun the gear_up_99 custom task.");
            return null;
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

        Predicate<BlockPos> validContainer = containerPos -> {

            // If it's a chest and the block above can't be broken, we can't open this one.
            boolean isChest = WorldHelper.isChest(mod, containerPos);
            if (isChest && WorldHelper.isSolid(mod, containerPos.above()) && !WorldHelper.canBreak(mod, containerPos.above())) return false;

            //if (!_acceptableContainer.test(containerPos))
            //    return false;

            Optional<ContainerCache> data = mod.getItemStorage().getContainerAtPosition(containerPos);

            if (data.isPresent() && data.get().isFull()) return false;

            if (isChest && mod.getModSettings().shouldAvoidSearchingForDungeonChests()) {
                boolean cachedDungeon = _dungeonChests.contains(containerPos) && !_nonDungeonChests.contains(containerPos);
                if (cachedDungeon) {
                    return false;
                }
                // Spawner
                int range = 6;
                for (int dx = -range; dx <= range; ++dx) {
                    for (int dz = -range; dz <= range; ++dz) {
                        BlockPos offset = containerPos.offset(dx, 0, dz);
                        if (mod.getWorld().getBlockState(offset).getBlock() == Blocks.SPAWNER) {
                            _dungeonChests.add(containerPos);
                            return false;
                        }
                    }
                }
                _nonDungeonChests.add(containerPos);
            }
            return true;
        };

        if (mod.getBlockTracker().anyFound(validContainer, _containerBlocks)) {

            setDebugState("Going to container and depositing items");

            if (!_progressChecker.check(mod) && _currentChestTry != null) {
                Debug.logMessage("Failed to open container. Suggesting it may be unreachable.");
                mod.getBlockTracker().requestBlockUnreachable(_currentChestTry, 2);
                _currentChestTry = null;
                _progressChecker.reset();
            }

            _activeContainerTask = new DoToClosestBlockTask(
                    blockPos -> {
                        if (_currentChestTry != blockPos) {
                            _progressChecker.reset();
                        }
                        _currentChestTry = blockPos;
                        return new StoreInContainerTask(blockPos, _getIfNotPresent,
                                _storedItems.getUnstoredItemTargetsYouCanStore(mod, _toStore));
                    },
                    validContainer,
                    _containerBlocks);
            return _activeContainerTask;
        }

        _progressChecker.reset();
        // Craft + place chest nearby
        for (Block couldPlace : _containerBlocks) {
            if (mod.getItemStorage().hasItem(couldPlace.asItem())) {
                setDebugState("Placing container nearby");
                return new PlaceBlockNearbyTask(canPlace -> {
                    // For chests, above must be air OR breakable.
                    if (WorldHelper.isChest(couldPlace)) {
                        return WorldHelper.isAir(mod, canPlace.above()) || WorldHelper.canBreak(mod, canPlace.above());
                    }
                    return true;
                }, couldPlace);
            }
        }
        for (Block containerBlock : _containerBlocks) {
            if (TaskCatalogue.taskExists(containerBlock.asItem())) {
                setDebugState("Obtaining " + containerBlock.asItem().getDescriptionId());
                return TaskCatalogue.getItemTask(containerBlock.asItem(), 1);
            }
        }
        setDebugState("No supported container item is available");
        return null;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        if (_enderChestFull) return true;
        return ContainerDepositCompletion.isComplete(
                ContainerDepositCompletion.shouldFinish(mod, _storedItems, _toStore, _getIfNotPresent),
                StorageHelper.getItemStackInCursorSlot().isEmpty(),
                ContainerDepositCompletion.craftingGridEmpty(),
                StorageHelper.isPlayerInventoryOpen());
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        _storedItems.stopTracking();
        mod.getBlockTracker().stopTracking(_containerBlocks);
    }

    @Override
    protected void onResetForNewRun() {
        if (_storedItems != null) {
            _storedItems.stopTracking();
            _storedItems.resetForNewRun();
        }
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof StoreInAnyContainerTask task) {
            return task._getIfNotPresent == _getIfNotPresent
                    && Arrays.equals(task._containerBlocks, _containerBlocks)
                    && Arrays.equals(task._toStore, _toStore);
        }
        return false;
    }

    private boolean isEnderChestOnly() {
        return _containerBlocks.length == 1 && _containerBlocks[0] == Blocks.ENDER_CHEST;
    }

    @Override
    protected String toDebugString() {
        return "Storing in " + Arrays.toString(_containerBlocks) + ": " + Arrays.toString(_toStore);
    }
}
