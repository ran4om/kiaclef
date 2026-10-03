package adris.altoclef.trackers.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ItemStorageTrackerTest {
    @Test
    void playerInventoryConversionStackIsNotCountedTwice() {
        assertEquals(0, ItemStorageTracker.countConversionStack(true, true, 3));
    }

    @Test
    void externalConversionStackStillContributesToAvailableItems() {
        assertEquals(3, ItemStorageTracker.countConversionStack(false, true, 3));
    }

    @Test
    void invalidConversionStackDoesNotContribute() {
        assertEquals(0, ItemStorageTracker.countConversionStack(false, false, 3));
    }
}
