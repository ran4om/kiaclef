package adris.altoclef.tasks.resources;

import adris.altoclef.TaskCatalogue;
import adris.altoclef.testing.MinecraftTestBootstrap;
import adris.altoclef.util.helpers.ItemHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CollectStrippedBambooBlockTaskTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    void keepsTheRequestedMultiBlockTarget() {
        var task = new CollectStrippedBambooBlockTask(3);

        assertEquals(3, task.getItemTargets()[0].getTargetCount());
        assertTrue(task.getItemTargets()[0].matches(Items.STRIPPED_BAMBOO_BLOCK));
    }

    @Test
    void eachPlaceStripMineCycleAdvancesTheMiningTargetByOne() {
        assertEquals(1, CollectStrippedBambooBlockTask.getNextMineTargetCount(0));
        assertEquals(2, CollectStrippedBambooBlockTask.getNextMineTargetCount(1));
        assertEquals(3, CollectStrippedBambooBlockTask.getNextMineTargetCount(2));
    }

    @Test
    void woodenAxePrerequisiteIsAvailableToTheStripTask() {
        var axeTask = TaskCatalogue.getItemTask(Items.WOODEN_AXE, 1);

        assertNotNull(axeTask);
        assertTrue(axeTask.getItemTargets()[0].matches(Items.WOODEN_AXE));
    }

    @Test
    void completedPlacementIsRememberedBeforeRequestingAnotherSourceItem() {
        BlockPos placed = new BlockPos(4, 64, 9);

        BlockPos resolved = CollectStrippedBlockTask.resolvePlacedPosition(null, true, placed);

        assertEquals(placed, resolved);
        assertEquals(1, CollectStrippedBlockTask.getSourceCountForPrerequisite(0, resolved));
        assertNull(CollectStrippedBlockTask.getMissingPrerequisite(
                Items.OAK_LOG, 1, CollectStrippedBlockTask.getSourceCountForPrerequisite(0, resolved)));
    }

    @Test
    void unfinishedOrPositionlessPlacementDoesNotPretendSourceWasPlaced() {
        BlockPos placed = new BlockPos(4, 64, 9);

        assertNull(CollectStrippedBlockTask.resolvePlacedPosition(null, false, placed));
        assertNull(CollectStrippedBlockTask.resolvePlacedPosition(null, true, null));
        assertEquals(0, CollectStrippedBlockTask.getSourceCountForPrerequisite(0, null));
        assertSame(Items.OAK_LOG, CollectStrippedBlockTask.getMissingPrerequisite(
                Items.OAK_LOG, 1, CollectStrippedBlockTask.getSourceCountForPrerequisite(0, null)));
    }

    @Test
    void everyWoodFamilyHasRegistryNamedStrippedLogAndWoodProviders() {
        for (ItemHelper.WoodItems wood : ItemHelper.getWoodItems()) {
            var strippedLog = TaskCatalogue.getItemTask(wood.strippedLog, 1);
            var strippedWood = TaskCatalogue.getItemTask(wood.strippedWood, 1);

            assertNotNull(strippedLog, BuiltInRegistries.ITEM.getKey(wood.strippedLog).toString());
            assertNotNull(strippedWood, BuiltInRegistries.ITEM.getKey(wood.strippedWood).toString());
            assertTrue(strippedLog.getItemTargets()[0].matches(wood.strippedLog));
            assertTrue(strippedWood.getItemTargets()[0].matches(wood.strippedWood));
        }
    }

    @Test
    void genericStrippingAdvancesMultiCountMiningOneItemAtATime() {
        assertEquals(1, CollectStrippedBlockTask.getNextMineTargetCount(0));
        assertEquals(2, CollectStrippedBlockTask.getNextMineTargetCount(1));
        assertEquals(3, CollectStrippedBlockTask.getNextMineTargetCount(2));
    }
}
