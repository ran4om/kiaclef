package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.CraftInInventoryTask;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.CraftingRecipe;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.RecipeTarget;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.Items;

public class CollectWheatTask extends ResourceTask {

    private final int _count;
    private CollectCropTask _cropTask;

    public CollectWheatTask(int targetCount) {
        super(Items.WHEAT, targetCount);
        _count = targetCount;
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
        mod.getBlockTracker().trackBlock(Blocks.HAY_BLOCK);
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        // Inventory completion must not discard the crop child's recorded empty plots.
        // Keep ticking that same child until it has replanted any plots it can.
        if (hasPendingCropReplant(mod)) {
            return _cropTask;
        }

        // We may have enough hay blocks to meet our needs.
        int potentialCount = mod.getItemStorage().getItemCount(Items.WHEAT) + 9 * mod.getItemStorage().getItemCount(Items.HAY_BLOCK);
        if (potentialCount >= _count) {
            setDebugState("Crafting wheat");
            return new CraftInInventoryTask(new RecipeTarget(Items.WHEAT, _count, CraftingRecipe.newShapedRecipe("wheat", new ItemTarget[]{new ItemTarget(Items.HAY_BLOCK, 1), null, null, null}, 9)));
        }
        if (mod.getBlockTracker().anyFound(Blocks.HAY_BLOCK) || mod.getEntityTracker().itemDropped(Items.HAY_BLOCK)) {
            return new MineAndCollectTask(Items.HAY_BLOCK, 99999999, new Block[]{Blocks.HAY_BLOCK}, MiningRequirement.HAND);
        }
        // Collect wheat
        if (_cropTask == null) {
            _cropTask = new CollectCropTask(new ItemTarget(Items.WHEAT, _count),
                    new Block[]{Blocks.WHEAT}, Items.WHEAT_SEEDS);
        }
        return _cropTask;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return super.isFinished(mod) && !hasPendingCropReplant(mod);
    }

    public boolean hasPendingCropReplant(AltoClef mod) {
        return _cropTask != null && _cropTask.hasPendingReplant(mod);
    }

    public Task getPendingCropReplantTask(AltoClef mod) {
        return hasPendingCropReplant(mod) ? _cropTask : null;
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(Blocks.HAY_BLOCK);
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        return other instanceof CollectWheatTask;
    }

    @Override
    protected String toDebugStringName() {
        return "Collecting " + _count + " wheat.";
    }

}
