package adris.altoclef.util.helpers;

import adris.altoclef.testing.MinecraftTestBootstrap;
import baritone.utils.accessor.IChunkArray;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Algorithm-level coverage; JUnit does not launch the client Mixin transformer. */
class BaritoneChunkSnapshotCopyTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    void copiesOnlyInRangeChunksUsingNegativeCircularIndicesAndCountsOccupiedSlots() throws Exception {
        int centerX = -17;
        int centerZ = 31;
        int radius = 1;
        int viewRange = radius * 2 + 1;
        LevelChunk center = chunk(centerX, centerZ);
        LevelChunk negativeBoundary = chunk(centerX - 1, centerZ + 1);
        LevelChunk wrappedBoundary = chunk(centerX + 1, centerZ - 1);
        LevelChunk outside = chunk(centerX - 2, centerZ);

        AtomicReferenceArray<LevelChunk> sourceSlots = new AtomicReferenceArray<>(7);
        sourceSlots.set(0, center);
        sourceSlots.set(3, negativeBoundary);
        sourceSlots.set(5, outside);
        sourceSlots.set(6, wrappedBoundary);
        IChunkArray source = new SourceArray(sourceSlots, centerX, centerZ, viewRange);
        AtomicReferenceArray<LevelChunk> destination = new AtomicReferenceArray<>(viewRange * viewRange);
        AtomicInteger occupiedCount = new AtomicInteger();

        int copied = BaritoneChunkSnapshotCopy.copy(source, destination,
                (x, z) -> Math.abs(x - centerX) <= radius && Math.abs(z - centerZ) <= radius,
                (x, z) -> circularIndex(x, z, viewRange), ignored -> occupiedCount.incrementAndGet());

        assertEquals(3, copied);
        assertEquals(3, occupiedCount.get());
        assertSame(center, destination.get(circularIndex(centerX, centerZ, viewRange)));
        assertSame(negativeBoundary,
                destination.get(circularIndex(centerX - 1, centerZ + 1, viewRange)));
        assertSame(wrappedBoundary,
                destination.get(circularIndex(centerX + 1, centerZ - 1, viewRange)));
        assertEquals(3, occupiedSlots(destination));
        assertNull(destination.get(circularIndex(centerX - 2, centerZ, viewRange)));

    }

    @Test
    void rejectsAnyOccupiedDestinationSlotWithoutOverwritingOrCountingIt() throws Exception {
        int viewRange = 3;
        LevelChunk existing = chunk(-4, 8);
        LevelChunk incoming = chunk(-4, 8);
        AtomicReferenceArray<LevelChunk> sourceSlots = new AtomicReferenceArray<>(1);
        sourceSlots.set(0, incoming);
        AtomicReferenceArray<LevelChunk> destination = new AtomicReferenceArray<>(viewRange * viewRange);
        int index = circularIndex(-4, 8, viewRange);
        destination.set(index, existing);
        AtomicInteger occupiedCount = new AtomicInteger();

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> BaritoneChunkSnapshotCopy.copy(
                        new SourceArray(sourceSlots, -4, 8, viewRange), destination,
                        (x, z) -> true, (x, z) -> circularIndex(x, z, viewRange),
                        ignored -> occupiedCount.incrementAndGet()));

        assertEquals("Doing this would mutate the client's REAL loaded chunks?!", error.getMessage());
        assertSame(existing, destination.get(index));
        assertEquals(0, occupiedCount.get());
        assertEquals(1, occupiedSlots(destination));
    }

    private static int circularIndex(int x, int z, int viewRange) {
        return Math.floorMod(z, viewRange) * viewRange + Math.floorMod(x, viewRange);
    }

    private static int occupiedSlots(AtomicReferenceArray<LevelChunk> chunks) {
        int count = 0;
        for (int index = 0; index < chunks.length(); index++) {
            if (chunks.get(index) != null) count++;
        }
        return count;
    }

    private static LevelChunk chunk(int x, int z) throws Exception {
        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        LevelChunk chunk = (LevelChunk) unsafe.allocateInstance(LevelChunk.class);
        Field chunkPos = net.minecraft.world.level.chunk.ChunkAccess.class.getDeclaredField("chunkPos");
        unsafe.putObject(chunk, unsafe.objectFieldOffset(chunkPos), new ChunkPos(x, z));
        return chunk;
    }

    private record SourceArray(AtomicReferenceArray<LevelChunk> chunks, int centerX, int centerZ,
                               int viewDistance) implements IChunkArray {
        @Override
        public AtomicReferenceArray<LevelChunk> getChunks() {
            return chunks;
        }

        @Override
        public void copyFrom(IChunkArray other) {
            throw new UnsupportedOperationException("source fixture only");
        }
    }
}
