package adris.altoclef.tasks.resources;

import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.CraftInInventoryTask;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.CraftingRecipe;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.RecipeTarget;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** Handles bamboo's distinct two-planks-per-bamboo-block conversion. */
public final class CollectBambooPlanksTask extends ResourceTask {
    private static final Item[] BLOCKS = {Items.BAMBOO_BLOCK, Items.STRIPPED_BAMBOO_BLOCK};
    private static final CraftingRecipe PLANK_RECIPE = CraftingRecipe.newShapedRecipe(
            "bamboo_planks", new ItemTarget[]{new ItemTarget(BLOCKS, 1), null, null, null}, 2);

    private final int _count;

    public CollectBambooPlanksTask(int count) {
        super(Items.BAMBOO_PLANKS, count);
        _count = count;
    }

    @Override
    protected boolean shouldAvoidPickingUp(adris.altoclef.AltoClef mod) {
        return false;
    }

    @Override
    protected void onResourceStart(adris.altoclef.AltoClef mod) {
    }

    @Override
    protected Task onResourceTick(adris.altoclef.AltoClef mod) {
        int availablePlanks = mod.getItemStorage().getItemCount(Items.BAMBOO_PLANKS);
        int needed = Math.max(0, _count - availablePlanks);
        int availableBlocks = mod.getItemStorage().getItemCount(BLOCKS);
        if (availableBlocks * 2 >= needed) {
            return new CraftInInventoryTask(new RecipeTarget(Items.BAMBOO_PLANKS, _count, PLANK_RECIPE));
        }

        int blocksNeeded = (needed + 1) / 2;
        return TaskCatalogue.getItemTask(Items.BAMBOO_BLOCK, blocksNeeded);
    }

    @Override
    protected void onResourceStop(adris.altoclef.AltoClef mod, Task interruptTask) {
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        return other instanceof CollectBambooPlanksTask task && task._count == _count;
    }

    @Override
    protected String toDebugStringName() {
        return "Crafting " + _count + " bamboo planks";
    }
}
