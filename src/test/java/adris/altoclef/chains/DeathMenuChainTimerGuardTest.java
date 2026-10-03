package adris.altoclef.chains;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeathMenuChainTimerGuardTest {

    @Test
    void respawnTimerIsOnlyResetInAnActiveWorldOutsideTheDeathScreen() {
        assertFalse(DeathMenuChain.shouldResetRespawnTimer(false, false),
                "saving or disconnecting must not query a game-time timer");
        assertFalse(DeathMenuChain.shouldResetRespawnTimer(false, true),
                "a stale death screen outside a world must not query a game-time timer");
        assertFalse(DeathMenuChain.shouldResetRespawnTimer(true, true),
                "the death-screen timer must remain elapsed while handling death");
        assertTrue(DeathMenuChain.shouldResetRespawnTimer(true, false),
                "normal in-world screens should clear the pending respawn delay");
    }

    @Test
    void reconnectWaitsForMultiplayerScreenAndElapsedDelay() {
        assertFalse(DeathMenuChain.shouldAttemptReconnect(false, true, true));
        assertFalse(DeathMenuChain.shouldAttemptReconnect(true, false, true));
        assertFalse(DeathMenuChain.shouldAttemptReconnect(true, true, false));
        assertTrue(DeathMenuChain.shouldAttemptReconnect(true, true, true));
    }
}
