package adris.altoclef.tasks.resources;

import adris.altoclef.testing.MinecraftTestBootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollectFuelTaskTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    void oneCoalOrCharcoalSatisfiesThreeSmeltsByFuelCapacity() {
        assertTrue(CollectFuelTask.hasEnoughFuel(3, 8));
        assertEquals(1, CollectFuelTask.getItemTargetForFuel(3, 0, 0, 8));
    }

    @Test
    void gathersOnlyTheCoalNeededAfterExistingCharcoalCapacity() {
        // One charcoal already supplies eight smelts. A seventeen-smelt goal needs two
        // coal total, not seventeen coal items.
        assertFalse(CollectFuelTask.hasEnoughFuel(17, 8));
        assertEquals(2, CollectFuelTask.getItemTargetForFuel(17, 8, 0, 8));
    }

    @Test
    void includesPartialPlankFuelWhenPlanningCoalCount() {
        // A plank supplies 1.5 smelts, so the remaining 1.5 operations need one coal.
        assertEquals(1, CollectFuelTask.getItemTargetForFuel(3, 1.5, 0, 8));
    }

    @Test
    void alreadySufficientFuelDoesNotRequestMoreItems() {
        assertTrue(CollectFuelTask.hasEnoughFuel(10, 10));
        assertEquals(2, CollectFuelTask.getItemTargetForFuel(10, 10, 2, 8));
    }

    @Test
    void plannedFuelItemTargetNeverOscillatesDown() {
        int planned = CollectFuelTask.retainPlannedItemTarget(0, 1);
        assertEquals(1, planned);
        planned = CollectFuelTask.retainPlannedItemTarget(planned, 2);
        assertEquals(2, planned);
        planned = CollectFuelTask.retainPlannedItemTarget(planned, 1);
        assertEquals(2, planned);
        planned = CollectFuelTask.retainPlannedItemTarget(planned, 2);
        assertEquals(2, planned);
    }

    @Test
    void plannedCoalTargetSurvivesPlanksMovingIntoAndOutOfCraftingSlots() {
        // Two accessible planks supply 3 fuel units. Moving both out of accessible
        // inventory temporarily lowers measured capacity to zero and raises the
        // coal plan from two to three; moving them back must not lower that plan.
        int withPlanks = CollectFuelTask.getItemTargetForFuel(17, 3, 0, 8);
        int movedToCrafting = CollectFuelTask.getItemTargetForFuel(17, 0, 0, 8);
        int movedBack = CollectFuelTask.getItemTargetForFuel(17, 3, 0, 8);

        assertEquals(2, withPlanks);
        assertEquals(3, movedToCrafting);
        int planned = CollectFuelTask.retainPlannedItemTarget(0, withPlanks);
        planned = CollectFuelTask.retainPlannedItemTarget(planned, movedToCrafting);
        planned = CollectFuelTask.retainPlannedItemTarget(planned, movedBack);
        assertEquals(3, planned);
    }

    @Test
    void selectedFuelItemRemainsStableWhenInventoryChangesTheCandidate() {
        Item selected = CollectFuelTask.retainSelectedFuelItem(null, Items.COAL);

        assertSame(Items.COAL,
                CollectFuelTask.retainSelectedFuelItem(selected, Items.OAK_PLANKS));
        assertSame(Items.OAK_PLANKS,
                CollectFuelTask.retainSelectedFuelItem(null, Items.OAK_PLANKS));
    }

    @Test
    void prefersAccessiblePlankFuelUntilCoalCanBeMinedWithoutCraftingATool() {
        var supported = Set.of(Items.COAL, Items.OAK_PLANKS, Items.BIRCH_PLANKS);
        Item selectedWithoutPickaxe = CollectFuelTask.chooseGatherableFuelItem(false,
                supported::contains, ignored -> true,
                item -> item == Items.BIRCH_PLANKS ? 4 : item == Items.OAK_PLANKS ? 2 : 0);
        Item selectedWithPickaxe = CollectFuelTask.chooseGatherableFuelItem(true,
                supported::contains, ignored -> true,
                item -> item == Items.BIRCH_PLANKS ? 4 : item == Items.OAK_PLANKS ? 2 : 0);

        assertSame(Items.BIRCH_PLANKS, selectedWithoutPickaxe);
        assertSame(Items.COAL, selectedWithPickaxe);
    }

    @Test
    void fuelCapacityCombinesCoalCharcoalAndPlankBurnValues() {
        List<ItemStack> accessibleInventory = new java.util.ArrayList<>(
                List.of(new ItemStack(Items.COAL, 1),
                        new ItemStack(Items.CHARCOAL, 1),
                        new ItemStack(Items.OAK_PLANKS, 2)));
        while (accessibleInventory.size() < 36) accessibleInventory.add(ItemStack.EMPTY);
        // Fuel held on the cursor (the next stack after the 36 inventory slots)
        // must not satisfy either the collection task or the furnace's fuel gate.
        accessibleInventory.add(new ItemStack(Items.COAL, 1));

        double capacity = CollectFuelTask.calculateFuelCapacity(accessibleInventory,
                CollectFuelTaskTest::isSupportedTestFuel,
                CollectFuelTaskTest::testFuelValue);

        assertEquals(19.0, capacity);
    }

    private static boolean isSupportedTestFuel(Item item) {
        return item == Items.COAL || item == Items.CHARCOAL || item == Items.OAK_PLANKS;
    }

    private static double testFuelValue(Item item) {
        if (item == Items.COAL || item == Items.CHARCOAL) return 8.0;
        if (item == Items.OAK_PLANKS) return 1.5;
        return 0;
    }
    @Test
    void ordinaryResetPreservesFuelPlanButExplicitNewRunClearsIt() throws Exception {
        CollectFuelTask task = new CollectFuelTask(9);
        var item = CollectFuelTask.class.getDeclaredField("_selectedFuelItem");
        var target = CollectFuelTask.class.getDeclaredField("_plannedItemTarget");
        item.setAccessible(true);
        target.setAccessible(true);
        item.set(task, Items.COAL);
        target.setInt(task, 2);
        task.reset();
        assertSame(Items.COAL, item.get(task));
        assertEquals(2, target.getInt(task));
        task.restartForNewRun();
        org.junit.jupiter.api.Assertions.assertNull(item.get(task));
        assertEquals(0, target.getInt(task));
    }
}
