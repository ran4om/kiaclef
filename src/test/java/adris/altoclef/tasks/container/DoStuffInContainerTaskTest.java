package adris.altoclef.tasks.container;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoStuffInContainerTaskTest {
    @Test
    void staleJustPlacedTimerDoesNotForcePlacementWithoutPlacementIntent() {
        assertFalse(DoStuffInContainerTask.shouldForcePlace(false, false, true));
    }

    @Test
    void committedPlacementContinuesAfterJustPlacedDelayUntilForceTimerExpires() {
        assertTrue(DoStuffInContainerTask.shouldForcePlace(true, false, true));
        assertFalse(DoStuffInContainerTask.shouldForcePlace(true, true, true));
        assertFalse(DoStuffInContainerTask.shouldForcePlace(true, false, false));
    }

    @Test
    void missingContainerStillStartsPlacementWithoutAnExistingIntent() {
        assertTrue(DoStuffInContainerTask.shouldPlaceContainer(true, false, true, false));
        assertFalse(DoStuffInContainerTask.shouldPlaceContainer(false, false, false, true));
    }
}
