package adris.altoclef.tasks.resources;

import adris.altoclef.TaskCatalogue;
import adris.altoclef.util.CraftingRecipe;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CollectNetheriteUpgradeTemplateTaskTest {
    @BeforeAll
    static void bootstrap() {
        adris.altoclef.testing.MinecraftTestBootstrap.initialize();
    }

    @Test
    void catalogueUsesTheDedicatedSeedAndDuplicationTask() {
        assertInstanceOf(CollectNetheriteUpgradeTemplateTask.class,
                TaskCatalogue.getItemTask(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 3));
    }

    @Test
    void duplicationRecipeConsumesSevenDiamondsAndOneNetherrackForTwoTemplates() {
        CraftingRecipe recipe = CollectNetheriteUpgradeTemplateTask.getDuplicationRecipe().getRecipe();

        assertEquals(9, recipe.getSlotCount());
        assertEquals(2, recipe.outputCount());
        assertTrue(recipe.getSlot(1).matches(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE));
        assertTrue(recipe.getSlot(4).matches(Items.NETHERRACK));
        for (int slot = 0; slot < recipe.getSlotCount(); slot++) {
            if (slot == 1) continue;
            if (slot == 4) continue;
            assertTrue(recipe.getSlot(slot).matches(Items.DIAMOND), "slot " + slot);
        }
    }

    @Test
    void oneSeedIsRetainedWhileEachDuplicationAddsOneNetTemplate() {
        assertEquals(0, CollectNetheriteUpgradeTemplateTask.getNetCraftsNeeded(4, 4));
        assertEquals(3, CollectNetheriteUpgradeTemplateTask.getNetCraftsNeeded(4, 1));
        assertEquals(4, CollectNetheriteUpgradeTemplateTask.getNetCraftsNeeded(4, 0));
    }
}
