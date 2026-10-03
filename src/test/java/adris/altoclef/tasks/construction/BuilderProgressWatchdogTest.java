package adris.altoclef.tasks.construction;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class BuilderProgressWatchdogTest {
    @Test
    void pausedBuilderTimesOutSeparatelyWithoutAutoResumeOrNoProgressCharge() {
        BuilderProgressWatchdog watchdog = new BuilderProgressWatchdog();
        watchdog.start(12, Optional.empty(), Optional.empty());

        for (int tick = 0; tick < 39; tick++) {
            assertEquals(BuilderProgressWatchdog.Timeout.NONE,
                    watchdog.tick(true, true, -1, Optional.empty(), Optional.empty(), 2, 1));
        }
        assertEquals(0, watchdog.noProgressTicks());
        assertEquals(BuilderProgressWatchdog.Timeout.PAUSED,
                watchdog.tick(true, true, -1, Optional.empty(), Optional.empty(), 2, 1));
    }

    @Test
    void placementsAndLayerChangesResetOnlyTheActiveNoProgressClock() {
        BuilderProgressWatchdog watchdog = new BuilderProgressWatchdog();
        watchdog.start(10, Optional.of(0), Optional.of(10));
        tick(watchdog, 20, 10, Optional.of(0), Optional.of(10), 3);
        assertEquals(20, watchdog.noProgressTicks());

        tick(watchdog, 20, 9, Optional.of(0), Optional.of(10), 3);
        assertEquals(0, watchdog.noProgressTicks(), "a placement should reset the clock");

        tick(watchdog, 20, 9, Optional.of(1), Optional.of(10), 3);
        assertEquals(0, watchdog.noProgressTicks(), "a builder layer change should reset the clock");
    }

    @Test
    void activeNoProgressTimesOutAndDisabledTimeoutNeverDoes() {
        BuilderProgressWatchdog watchdog = new BuilderProgressWatchdog();
        watchdog.start(4, Optional.empty(), Optional.empty());
        tick(watchdog, 39, 4, Optional.empty(), Optional.empty(), 2);
        assertEquals(BuilderProgressWatchdog.Timeout.NO_PROGRESS,
                watchdog.tick(true, false, 4, Optional.empty(), Optional.empty(), 2, 2));

        watchdog.start(4, Optional.empty(), Optional.empty());
        tick(watchdog, 100, 4, Optional.empty(), Optional.empty(), 0);
        assertEquals(BuilderProgressWatchdog.Timeout.NONE,
                watchdog.tick(true, false, 4, Optional.empty(), Optional.empty(), 0, -1));
    }

    private static void tick(BuilderProgressWatchdog watchdog, int ticks, int unsatisfied,
                             Optional<Integer> minLayer, Optional<Integer> maxLayer,
                             int noProgressTimeoutSeconds) {
        for (int i = 0; i < ticks; i++) {
            int sample = watchdog.shouldSamplePositionsNextTick(false) ? unsatisfied : -1;
            assertEquals(BuilderProgressWatchdog.Timeout.NONE,
                    watchdog.tick(true, false, sample, minLayer, maxLayer,
                            600, noProgressTimeoutSeconds));
        }
    }
}
