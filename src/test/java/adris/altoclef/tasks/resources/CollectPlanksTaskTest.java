package adris.altoclef.tasks.resources;

import adris.altoclef.testing.MinecraftTestBootstrap;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CollectPlanksTaskTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    void selectedPlankRecipeOnlyAcceptsItsMatchingLog() {
        var recipe = CollectPlanksTask.generatePlankRecipe(Items.OAK_LOG);

        assertTrue(recipe.getSlot(0).matches(Items.OAK_LOG));
        assertFalse(recipe.getSlot(0).matches(Items.BIRCH_LOG));
    }

    @Test
    void tasksForSameOutputButDifferentLogSourcesAreNotEqual() {
        var oakSource = new CollectPlanksTask(Items.OAK_PLANKS, Items.OAK_LOG, 4);
        var birchSource = new CollectPlanksTask(Items.OAK_PLANKS, Items.BIRCH_LOG, 4);

        assertNotEquals(oakSource, birchSource);
    }
}
