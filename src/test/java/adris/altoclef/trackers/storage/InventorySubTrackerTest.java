package adris.altoclef.trackers.storage;

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
    void partialCapacitySelectsOverflowStackEvenWhenSourceIsLarger() {
        assertTrue(InventorySubTracker.canFitInExistingStack(4, 8, true));
        assertFalse(InventorySubTracker.canFitInExistingStack(4, 8, false));
    }
}
