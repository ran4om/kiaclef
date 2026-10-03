package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.CraftingRecipe;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ItemHelper;
import java.util.Arrays;
import net.minecraft.world.item.Item;

import java.util.HashSet;
import java.util.function.Function;

public class CraftWithMatchingPlanksTask extends CraftWithMatchingMaterialsTask {

    private final ItemTarget _visualTarget;
    private final Function<ItemHelper.WoodItems, Item> _getTargetItem;

    public CraftWithMatchingPlanksTask(Item[] validTargets, Function<ItemHelper.WoodItems, Item> getTargetItem, CraftingRecipe recipe, boolean[] sameMask, int count) {
        super(new ItemTarget(validTargets, count), restrictToCraftableWoodPlanks(recipe, sameMask), sameMask);
        _getTargetItem = getTargetItem;
        _visualTarget = new ItemTarget(validTargets, count);
    }

    static CraftingRecipe restrictToCraftableWoodPlanks(CraftingRecipe recipe, boolean[] sameMask) {
        HashSet<Item> craftablePlanks = new HashSet<>(Arrays.asList(ItemHelper.WOOD_PLANKS));
        ItemTarget[] slots = new ItemTarget[recipe.getSlotCount()];
        for (int i = 0; i < slots.length; i++) {
            ItemTarget ingredient = recipe.getSlot(i);
            if (!sameMask[i] || ingredient == null || ingredient.isEmpty()) {
                slots[i] = ingredient;
                continue;
            }
            Item[] matchingPlanks = Arrays.stream(ingredient.getMatches())
                    .filter(craftablePlanks::contains)
                    .toArray(Item[]::new);
            if (matchingPlanks.length == 0) {
                throw new IllegalArgumentException("Matching-plank recipe slot " + i
                        + " contains no craftable wood planks: " + ingredient);
            }
            slots[i] = new ItemTarget(matchingPlanks, ingredient.getTargetCount());
        }
        return CraftingRecipe.newShapedRecipe(slots, recipe.outputCount());
    }


    @Override
    protected int getExpectedTotalCountOfSameItem(AltoClef mod, Item sameItem) {
        // Include logs
        return mod.getItemStorage().getItemCount(sameItem) + mod.getItemStorage().getItemCount(ItemHelper.planksToLog(sameItem)) * 4;
    }

    @Override
    protected Task getAllSameResourcesTask(AltoClef mod) {
        ItemTarget acceptedPlanks = getSameResourceTarget();
        // Keep this child open-ended. The parent combines same-species planks and logs
        // before deciding when one species can support the next craft batch; an aggregate
        // count target could finish early across several different wood variants.
        int requiredCount = 999999;
        if (acceptedPlanks.isCatalogueItem() || acceptedPlanks.getMatches().length == 1) {
            // Keep the registered resource path for concrete woods so Nether stem/dimension
            // settings and other species-specific behavior are preserved.
            return TaskCatalogue.getItemTask(new ItemTarget(acceptedPlanks, requiredCount));
        }
        Item[] planks = acceptedPlanks.getMatches();
        Item[] logs = Arrays.stream(planks)
                .map(ItemHelper::planksToLog)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toArray(Item[]::new);
        if (logs.length == 0) {
            throw new IllegalArgumentException("No matching logs are registered for plank target " + acceptedPlanks);
        }
        return new CollectPlanksTask(planks, logs, requiredCount, false);
    }

    @Override
    protected Task getSpecificSameResourceTask(AltoClef mod, Item sameItem, int targetCount) {
        Item log = ItemHelper.planksToLog(sameItem);
        if (log == null || targetCount <= 0) {
            throw new IllegalArgumentException("Cannot collect " + targetCount + " matching planks for " + sameItem);
        }
        return TaskCatalogue.getItemTask(sameItem, targetCount);
    }

    @Override
    protected Item getSpecificItemCorrespondingToMajorityResource(Item majority) {
        for (ItemHelper.WoodItems woodItems : ItemHelper.getWoodItems()) {
            if (woodItems.planks == majority) {
                return _getTargetItem.apply(woodItems);
            }
        }
        return null;
    }


    @Override
    protected boolean isEqualResource(ResourceTask other) {
        if (other instanceof CraftWithMatchingPlanksTask task) {
            return task._visualTarget.equals(_visualTarget);
        }
        return false;
    }

    @Override
    protected String toDebugStringName() {
        return "Crafting: " + _visualTarget;
    }


    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

}
