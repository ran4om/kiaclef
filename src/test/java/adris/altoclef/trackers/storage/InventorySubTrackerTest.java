package adris.altoclef.trackers.storage;

import adris.altoclef.util.slots.ChestSlot;
import adris.altoclef.util.slots.PlayerSlot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventorySubTrackerTest {
    @Test
    void partialFitStillRequiresPositiveCapacity() {
        assertTrue(InventorySubTracker.canFitInExistingStack(1, 2, true));
        assertFalse(InventorySubTracker.canFitInExistingStack(0, 2, true));
    }

    @Test
    void completeFitAcceptsAnExactMatchAndRejectsInsufficientCapacity() {
        assertTrue(InventorySubTracker.canFitInExistingStack(2, 2, false));
        assertFalse(InventorySubTracker.canFitInExistingStack(1, 2, false));
        assertFalse(InventorySubTracker.canFitInExistingStack(0, 0, false));
    }

    @Test
    void onlyThePlayerCraftingGridIsExcludedFromStorage() {
        assertTrue(InventorySubTracker.isPlayerCraftingGridSlot(new PlayerSlot(1)));
        assertTrue(InventorySubTracker.isPlayerCraftingGridSlot(new PlayerSlot(4)));
        assertFalse(InventorySubTracker.isPlayerCraftingGridSlot(new PlayerSlot(9)));
        // Chest window slots 1-4 are ordinary storage.
        assertFalse(InventorySubTracker.isPlayerCraftingGridSlot(new ChestSlot(1, false)));
        assertFalse(InventorySubTracker.isPlayerCraftingGridSlot(new ChestSlot(4, false)));
    }

    @Test
    void partialCapacitySelectsOverflowStackEvenWhenSourceIsLarger() {
        assertTrue(InventorySubTracker.canFitInExistingStack(4, 8, true));
        assertFalse(InventorySubTracker.canFitInExistingStack(4, 8, false));
    }
}
