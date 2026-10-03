package adris.altoclef.trackers;

import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class SimpleChunkTrackerTest {
    @Test
    void treatsChunksAsUnloadedAfterClientWorldIsCleared() {
        assertFalse(SimpleChunkTracker.isChunkLoaded(null, new ChunkPos(0, 0)));
    }
}
