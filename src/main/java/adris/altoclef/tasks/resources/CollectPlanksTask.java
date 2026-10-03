package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.CraftInInventoryTask;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.*;
import adris.altoclef.util.helpers.ItemHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Arrays;

public class CollectPlanksTask extends ResourceTask {

    private final Item[] _planks;
    private final Item[] _logs;
    private final int _targetCount;
    private boolean _logsInNether;

    public CollectPlanksTask(Item[] planks, Item[] logs, int count, boolean logsInNether) {
        super(new ItemTarget(planks, count));
        _planks = planks;
        _logs = logs;
        _targetCount = count;
        _logsInNether = logsInNether;
    }

    public CollectPlanksTask(int count) {
        this(ItemHelper.PLANKS, ItemHelper.LOG, count, false);
    }

    public CollectPlanksTask(Item plank, Item log, int count) {
        this(new Item[]{plank}, new Item[]{log}, count, false);
    }

    public CollectPlanksTask(Item plank, int count) {
        this(plank, ItemHelper.planksToLog(plank), count);
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

        // Craft when we can
        int totalInventoryPlankCount = mod.getItemStorage().getItemCount(_planks);
        int bambooBlocks = Arrays.asList(_planks).contains(Items.BAMBOO_PLANKS)
                ? mod.getItemStorage().getItemCount(Items.BAMBOO_BLOCK, Items.STRIPPED_BAMBOO_BLOCK)
                : 0;
        int potentialPlanks = totalInventoryPlankCount
                + mod.getItemStorage().getItemCount(_logs) * 4
                + bambooBlocks * 2;
        if (potentialPlanks >= _targetCount) {
            for (Item logCheck : _logs) {
                int count = mod.getItemStorage().getItemCount(logCheck);
                if (count > 0) {
                    Item plankCheck = ItemHelper.logToPlanks(logCheck);
                    if (plankCheck == null) {
                        Debug.logError("Invalid/Un-convertable log: " + logCheck + " (failed to find corresponding plank)");
                    }
                    int plankCount = mod.getItemStorage().getItemCount(plankCheck);
                    int otherPlankCount = totalInventoryPlankCount - plankCount;
                    int targetTotalPlanks = Math.min(count*4 + plankCount, _targetCount - otherPlankCount);
                    setDebugState("We have " + logCheck + ", crafting " + targetTotalPlanks + " planks.");
                    // Once a concrete plank output is selected, only accept its matching log in the recipe.
                    // A broad family target here can place another wood's log in the grid and craft the wrong planks.
                    return new CraftInInventoryTask(new RecipeTarget(plankCheck, targetTotalPlanks, generatePlankRecipe(logCheck)));
                }
            }
            if (bambooBlocks > 0) {
                int plankCount = mod.getItemStorage().getItemCount(Items.BAMBOO_PLANKS);
                int otherPlankCount = totalInventoryPlankCount - plankCount;
                int targetTotalPlanks = Math.min(bambooBlocks * 2 + plankCount, _targetCount - otherPlankCount);
                setDebugState("We have bamboo blocks, crafting " + targetTotalPlanks + " bamboo planks.");
                CraftingRecipe recipe = CraftingRecipe.newShapedRecipe(
                        "bamboo_planks",
                        new ItemTarget[]{new ItemTarget(new Item[]{Items.BAMBOO_BLOCK, Items.STRIPPED_BAMBOO_BLOCK}, 1), null, null, null},
                        2);
                return new CraftInInventoryTask(new RecipeTarget(Items.BAMBOO_PLANKS, targetTotalPlanks, recipe));
            }
        }

        // Collect planks and logs
        ArrayList<ItemTarget> blocksTomine = new ArrayList<>(2);
        blocksTomine.add(new ItemTarget(_logs));
        // Ignore planks if we're told to.
        if (!mod.getBehaviour().exclusivelyMineLogs()) {
            // TODO: Add planks back in, but with a heuristic check (so we don't go for abandoned mineshafts)
            //blocksTomine.add(new ItemTarget(ItemUtil.PLANKS));
        }

        ResourceTask mineTask = new MineAndCollectTask(blocksTomine.toArray(ItemTarget[]::new), MiningRequirement.HAND);
        // Kinda jank
        if (_logsInNether) {
            mineTask.forceDimension(Dimension.NETHER);
        }
        return mineTask;
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {

    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        return other instanceof CollectPlanksTask task
                && Arrays.equals(task._planks, _planks)
                && Arrays.equals(task._logs, _logs)
                && task._logsInNether == _logsInNether;
    }

    @Override
    protected String toDebugStringName() {
        return "Crafting " + _targetCount + " planks " + Arrays.toString(_planks);
    }

    public CollectPlanksTask logsInNether() {
        _logsInNether = true;
        return this;
    }

    static CraftingRecipe generatePlankRecipe(Item log) {
        return CraftingRecipe.newShapedRecipe(
                "planks",
                new Item[][]{
                        new Item[]{log}, null,
                        null, null
                },
                4
        );
    }
}
