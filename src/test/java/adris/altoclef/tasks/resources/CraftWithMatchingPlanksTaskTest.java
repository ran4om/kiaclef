package adris.altoclef.tasks.resources;

import adris.altoclef.tasks.resources.wood.CollectWoodenStairsTask;
import adris.altoclef.testing.MinecraftTestBootstrap;
import adris.altoclef.util.CraftingRecipe;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ItemHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftWithMatchingPlanksTaskTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    void explicitOakRecipeDoesNotBroadenToOtherWoodSpecies() {
        CraftingRecipe recipe = CraftingRecipe.newShapedRecipe("oak_stairs", new ItemTarget[]{
                new ItemTarget(Items.OAK_PLANKS), null, null,
                new ItemTarget(Items.OAK_PLANKS), new ItemTarget(Items.OAK_PLANKS), null,
                new ItemTarget(Items.OAK_PLANKS), new ItemTarget(Items.OAK_PLANKS), new ItemTarget(Items.OAK_PLANKS)
        }, 4);
        boolean[] sameMask = {true, false, false, true, true, false, true, true, true};

        CraftingRecipe restricted = CraftWithMatchingPlanksTask.restrictToCraftableWoodPlanks(recipe, sameMask);

        for (int i = 0; i < sameMask.length; i++) {
            if (sameMask[i]) assertArrayEquals(new Item[]{Items.OAK_PLANKS}, restricted.getSlot(i).getMatches());
        }
    }

    @Test
    void genericPlankRecipeRetainsAllCraftableWoodSpecies() {
        CraftingRecipe recipe = CraftingRecipe.newShapedRecipe("wood_stairs", new ItemTarget[]{
                new ItemTarget(ItemHelper.WOOD_PLANKS), null, null,
                new ItemTarget(ItemHelper.WOOD_PLANKS), new ItemTarget(ItemHelper.WOOD_PLANKS), null,
                new ItemTarget(ItemHelper.WOOD_PLANKS), new ItemTarget(ItemHelper.WOOD_PLANKS), new ItemTarget(ItemHelper.WOOD_PLANKS)
        }, 4);
        boolean[] sameMask = {true, false, false, true, true, false, true, true, true};

        CraftingRecipe restricted = CraftWithMatchingPlanksTask.restrictToCraftableWoodPlanks(recipe, sameMask);

        for (int i = 0; i < sameMask.length; i++) {
            if (sameMask[i]) assertArrayEquals(ItemHelper.WOOD_PLANKS, restricted.getSlot(i).getMatches());
        }
    }

    @Test
    void stairsConversionRequestsEnoughPlanksForPlannedOutputBatch() {
        CollectWoodenStairsTask task = new CollectWoodenStairsTask(
                new Item[]{Items.OAK_STAIRS}, new ItemTarget(Items.OAK_PLANKS), 5);

        assertEquals(6, task.getSameResourceCountForOutputs(1));
        assertEquals(12, task.getSameResourceCountForOutputs(5));
    }

    @Test
    void genericWoodCollectionStaysOpenUntilOneSpeciesCanCraft() {
        CollectWoodenStairsTask task = new CollectWoodenStairsTask(
                ItemHelper.WOOD_STAIRS, new ItemTarget(ItemHelper.WOOD_PLANKS), 1);
        var collectPlanks = org.junit.jupiter.api.Assertions.assertInstanceOf(
                CollectPlanksTask.class, task.getAllSameResourcesTask(null));

        assertEquals(999999, collectPlanks.getItemTargets()[0].getTargetCount());
        assertArrayEquals(ItemHelper.WOOD_PLANKS, collectPlanks.getItemTargets()[0].getMatches());
    }

    @Test
    void activePlankConversionMustFinishBeforeParentRechecksCraftableMaterials() {
        // While an oak log is in the 2x2 recipe grid, conversion slots can make it look like
        // enough oak planks exist. Keep the selected collection task alive until it confirms
        // the plank output has reached accessible inventory.
        assertTrue(CraftWithMatchingMaterialsTask.shouldContinuePendingSameResourceTask(true, false));
        assertFalse(CraftWithMatchingMaterialsTask.shouldContinuePendingSameResourceTask(true, true));
        assertFalse(CraftWithMatchingMaterialsTask.shouldContinuePendingSameResourceTask(false, false));
    }
}
