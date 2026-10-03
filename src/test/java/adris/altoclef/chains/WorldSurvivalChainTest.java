package adris.altoclef.chains;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldSurvivalChainTest {
    @Test
    void nullCurrentTaskDoesNotBreakPortalEscapeDecision() {
        assertTrue(WorldSurvivalChain.shouldShimmyOutOfNetherPortal(true, null));
        assertFalse(WorldSurvivalChain.shouldShimmyOutOfNetherPortal(false, null));
    }
}
