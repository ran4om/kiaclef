package adris.altoclef.tasks;

import adris.altoclef.testing.MinecraftTestBootstrap;
import adris.altoclef.util.CraftingRecipe;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.RecipeTarget;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CraftGenericManuallyTaskTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    void onlyPlansBatchesForTheRemainingOutput() {
        RecipeTarget sticks = new RecipeTarget(Items.STICK, 9,
                CraftingRecipe.newShapedRecipe("sticks", new ItemTarget[]{new ItemTarget(Items.OAK_PLANKS), null,
                        new ItemTarget(Items.OAK_PLANKS), null}, 4));

        assertEquals(1, CraftGenericManuallyTask.getRequiredCraftCount(sticks, 8, 0, 0, 0));
        assertEquals(0, CraftGenericManuallyTask.getRequiredCraftCount(sticks, 8, 0, 4, 0));
    }

    @Test
    void templateAsAnIngredientUsesNetYieldForBatchCount() {
        RecipeTarget templates = new RecipeTarget(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 3,
                CraftingRecipe.newShapedRecipe("template_duplication", new ItemTarget[]{
                        new ItemTarget(Items.DIAMOND), new ItemTarget(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE), new ItemTarget(Items.DIAMOND),
                        new ItemTarget(Items.DIAMOND), new ItemTarget(Items.NETHERRACK), new ItemTarget(Items.DIAMOND),
                        new ItemTarget(Items.DIAMOND), new ItemTarget(Items.DIAMOND), new ItemTarget(Items.DIAMOND)
                }, 2));

        assertEquals(1, CraftGenericManuallyTask.getRequiredCraftCount(templates, 2, 0, 0, 0));
        assertEquals(1, CraftGenericManuallyTask.getRequiredCraftCount(templates, 1, 0, 0, 1));
    }

    @Test
    void matchingCursorItemCountsAsAnAvailableIngredient() {
        ItemTarget diamond = new ItemTarget(Items.DIAMOND, 1);

        assertTrue(CraftGenericManuallyTask.hasMatchingIngredientAvailable(diamond, false,
                new ItemStack(Items.DIAMOND, 1)));
        assertFalse(CraftGenericManuallyTask.hasMatchingIngredientAvailable(diamond, false, ItemStack.EMPTY));
    }

    @Test
    void craftBatchDoesNotExceedIngredientStackCapacity() {
        RecipeTarget sticks = new RecipeTarget(Items.STICK, 1000,
                CraftingRecipe.newShapedRecipe("sticks", new ItemTarget[]{new ItemTarget(Items.OAK_PLANKS), null,
                        new ItemTarget(Items.OAK_PLANKS), null}, 4));

        assertEquals(64, CraftGenericManuallyTask.getRequiredCraftCount(sticks, 0, 0, 0, 0));
    }

    @Test
    void nonStackableIngredientLimitsCraftBatchToOne() {
        RecipeTarget target = new RecipeTarget(Items.STICK, 128,
                CraftingRecipe.newShapedRecipe("bow_input", new ItemTarget[]{new ItemTarget(Items.BOW),
                        new ItemTarget(Items.DIAMOND), null, null}, 4));

        assertEquals(1, CraftGenericManuallyTask.getRequiredCraftCount(target, 0, 0, 0, 0));
    }
}
