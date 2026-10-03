package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.construction.PlaceBlockNearbyTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.MiningRequirement;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Places a log or wood block, strips it with an axe, and mines the stripped result. */
public class CollectStrippedBlockTask extends ResourceTask {
    private final Item _sourceItem;
    private final Item _resultItem;
    private final Block _sourceBlock;
    private final Block _resultBlock;
    private final int _count;

    private BlockPos _placedPosition;
    private PlaceBlockNearbyTask _placeTask;
    private InteractWithBlockTask _stripTask;
    private MineAndCollectTask _mineTask;
    private int _mineTargetCount;

    public CollectStrippedBlockTask(Item sourceItem, Item resultItem, int count) {
        super(resultItem, count);
        _sourceItem = sourceItem;
        _resultItem = resultItem;
        _sourceBlock = Block.byItem(sourceItem);
        _resultBlock = Block.byItem(resultItem);
        _count = count;
        if (_sourceBlock == Blocks.AIR || _resultBlock == Blocks.AIR || _sourceBlock == _resultBlock) {
            throw new IllegalArgumentException("Stripping requires distinct block items");
        }
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        if (_placedPosition != null) {
            Block block = mod.getWorld().getBlockState(_placedPosition).getBlock();
            if (block == _resultBlock) {
                int held = mod.getItemStorage().getItemCount(_resultItem);
                if (_mineTask != null && held >= _mineTargetCount) {
                    clearPlacedBlock();
                } else {
                    if (_mineTask == null) {
                        _mineTargetCount = getNextMineTargetCount(held);
                        _mineTask = new MineAndCollectTask(_resultItem, _mineTargetCount,
                                new Block[]{_resultBlock}, MiningRequirement.HAND);
                    }
                    return _mineTask;
                }
            } else if (block != _sourceBlock) {
                clearPlacedBlock();
            }
        }

        // Record a completed placement before checking inventory prerequisites. Placing may have
        // consumed the last source item; the block at this position is now the source for this
        // strip cycle, so asking for another item here would let the source task mine it.
        if (_placedPosition == null && _placeTask != null && _placeTask.isFinished(mod)) {
            _placedPosition = resolvePlacedPosition(null, true, _placeTask.getPlaced());
            _placeTask = null;
            if (_placedPosition == null) {
                // A finished placement without a position gives us nothing to strip. Start a new
                // placement attempt, while still allowing the prerequisite check below to run.
                _placeTask = new PlaceBlockNearbyTask(_sourceBlock);
            }
        }

        // Acquire the tool before placing a source block. The axe task may collect logs for planks,
        // and its generic log search could otherwise mine the block this task just placed.
        Item missingPrerequisite = getMissingPrerequisite(_sourceItem,
                mod.getItemStorage().getItemCount(Items.WOODEN_AXE),
                getSourceCountForPrerequisite(mod.getItemStorage().getItemCount(_sourceItem), _placedPosition));
        if (missingPrerequisite != null) {
            return TaskCatalogue.getItemTask(missingPrerequisite, 1);
        }

        if (_placedPosition == null) {
            if (_placeTask == null) {
                _placeTask = new PlaceBlockNearbyTask(_sourceBlock);
            }
            if (!_placeTask.isFinished(mod)) {
                return _placeTask;
            }
            _placedPosition = _placeTask.getPlaced();
            _placeTask = null;
            if (_placedPosition == null) {
                _placeTask = new PlaceBlockNearbyTask(_sourceBlock);
                return _placeTask;
            }
        }

        if (_stripTask == null) {
            _stripTask = new InteractWithBlockTask(Items.WOODEN_AXE, _placedPosition);
        }
        return _stripTask;
    }

    private void clearPlacedBlock() {
        _placedPosition = null;
        _stripTask = null;
        _mineTask = null;
        _mineTargetCount = 0;
    }

    static int getNextMineTargetCount(int currentlyCollected) {
        return currentlyCollected + 1;
    }

    static BlockPos resolvePlacedPosition(BlockPos currentPosition, boolean placementFinished,
                                          BlockPos completedPosition) {
        if (currentPosition != null) return currentPosition;
        return placementFinished ? completedPosition : null;
    }

    static int getSourceCountForPrerequisite(int inventoryCount, BlockPos placedPosition) {
        return placedPosition == null ? inventoryCount : Math.max(inventoryCount, 1);
    }

    static Item getMissingPrerequisite(Item sourceItem, int woodenAxeCount, int sourceCount) {
        if (woodenAxeCount < 1) return Items.WOODEN_AXE;
        if (sourceCount < 1) return sourceItem;
        return null;
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        return other instanceof CollectStrippedBlockTask task
                && task._sourceItem == _sourceItem
                && task._resultItem == _resultItem
                && task._count == _count;
    }

    @Override
    protected String toDebugStringName() {
        return "Stripping " + _count + " " + _sourceItem;
    }
}
