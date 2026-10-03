package adris.altoclef.tasks.construction;

import java.util.Optional;

/** Tracks paused duration and active no-progress duration for one Baritone builder run. */
final class BuilderProgressWatchdog {
    static final int POSITION_SAMPLE_INTERVAL_TICKS = 20;

    enum Timeout {
        NONE,
        PAUSED,
        NO_PROGRESS
    }

    private long pausedTicks;
    private long noProgressTicks;
    private int sampleTicks;
    private int lastUnsatisfiedPositions;
    private Optional<Integer> lastMinLayer = Optional.empty();
    private Optional<Integer> lastMaxLayer = Optional.empty();

    void reset() {
        pausedTicks = 0;
        noProgressTicks = 0;
        sampleTicks = 0;
        lastUnsatisfiedPositions = 0;
        lastMinLayer = Optional.empty();
        lastMaxLayer = Optional.empty();
    }

    void start(int unsatisfiedPositions, Optional<Integer> minLayer, Optional<Integer> maxLayer) {
        reset();
        lastUnsatisfiedPositions = unsatisfiedPositions;
        lastMinLayer = minLayer;
        lastMaxLayer = maxLayer;
    }

    boolean shouldSamplePositionsNextTick(boolean paused) {
        return !paused && sampleTicks + 1 >= POSITION_SAMPLE_INTERVAL_TICKS;
    }

    Timeout tick(boolean active, boolean paused, int unsatisfiedPositions,
                 Optional<Integer> minLayer, Optional<Integer> maxLayer,
                 int pausedTimeoutSeconds, int noProgressTimeoutSeconds) {
        if (!active) return Timeout.NONE;

        if (paused) {
            pausedTicks++;
            return reachedTimeout(pausedTicks, pausedTimeoutSeconds) ? Timeout.PAUSED : Timeout.NONE;
        }

        pausedTicks = 0;
        noProgressTicks++;
        sampleTicks++;
        if (sampleTicks >= POSITION_SAMPLE_INTERVAL_TICKS) {
            sampleTicks = 0;
            boolean placedBlocks = unsatisfiedPositions < lastUnsatisfiedPositions;
            boolean changedLayer = !minLayer.equals(lastMinLayer) || !maxLayer.equals(lastMaxLayer);
            lastUnsatisfiedPositions = unsatisfiedPositions;
            lastMinLayer = minLayer;
            lastMaxLayer = maxLayer;
            if (placedBlocks || changedLayer) {
                noProgressTicks = 0;
            }
        }
        return reachedTimeout(noProgressTicks, noProgressTimeoutSeconds)
                ? Timeout.NO_PROGRESS : Timeout.NONE;
    }

    long pausedTicks() {
        return pausedTicks;
    }

    long noProgressTicks() {
        return noProgressTicks;
    }

    private static boolean reachedTimeout(long ticks, int seconds) {
        return seconds > 0 && ticks >= (long) seconds * 20;
    }
}
