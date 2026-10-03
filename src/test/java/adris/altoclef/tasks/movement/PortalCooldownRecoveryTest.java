package adris.altoclef.tasks.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalCooldownRecoveryTest {
    @Test
    void recoveryRequiresTwentyConsecutiveTicksOutsidePortal() {
        PortalCooldownRecovery recovery = new PortalCooldownRecovery(20);
        recovery.begin();

        for (int tick = 0; tick < 19; tick++) {
            assertFalse(recovery.tick(false));
        }
        assertEquals(19, recovery.outsideTicks());
        assertTrue(recovery.isActive());
        assertTrue(recovery.tick(false));
    }

    @Test
    void touchingPortalResetsTheOutsideInterval() {
        PortalCooldownRecovery recovery = new PortalCooldownRecovery(4);
        recovery.begin();
        assertFalse(recovery.tick(false));
        assertFalse(recovery.tick(false));
        assertFalse(recovery.tick(true));
        assertEquals(0, recovery.outsideTicks());

        assertFalse(recovery.tick(false));
        assertFalse(recovery.tick(false));
        assertFalse(recovery.tick(false));
        assertTrue(recovery.tick(false));
    }

    @Test
    void completedRecoveryCanBeginAgainForAnotherPortalAttempt() {
        PortalCooldownRecovery recovery = new PortalCooldownRecovery(2);
        recovery.begin();
        assertFalse(recovery.tick(false));
        assertTrue(recovery.tick(false));
        recovery.reset();

        assertFalse(recovery.isActive());
        recovery.begin();
        assertTrue(recovery.isActive());
        assertEquals(0, recovery.outsideTicks());
        assertFalse(recovery.tick(true));
        assertEquals(0, recovery.outsideTicks());
    }
}
