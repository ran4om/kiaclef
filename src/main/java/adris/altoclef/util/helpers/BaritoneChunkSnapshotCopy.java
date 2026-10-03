package adris.altoclef.util.helpers;

import baritone.utils.accessor.IChunkArray;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.IntConsumer;

/** Slot-copy algorithm shared by the live mixin and its focused regression tests. */
public final class BaritoneChunkSnapshotCopy {
    @FunctionalInterface
    public interface RangeCheck {
        boolean contains(int x, int z);
    }

    @FunctionalInterface
    public interface IndexLookup {
        int indexOf(int x, int z);
    }

    private BaritoneChunkSnapshotCopy() {}

    public static int copy(IChunkArray source, AtomicReferenceArray<LevelChunk> destination,
                    RangeCheck inRange, IndexLookup getIndex, IntConsumer onCopied) {
        AtomicReferenceArray<LevelChunk> sourceChunks = source.getChunks();
        int copied = 0;
        for (int slot = 0; slot < sourceChunks.length(); slot++) {
            LevelChunk chunk = sourceChunks.get(slot);
            if (chunk == null) continue;

            ChunkPos pos = chunk.getPos();
            int x = pos.x();
            int z = pos.z();
            if (!inRange.contains(x, z)) continue;

            int index = getIndex.indexOf(x, z);
            if (destination.get(index) != null) {
                throw new IllegalStateException("Doing this would mutate the client's REAL loaded chunks?!");
            }
            // Do not route through ClientChunkCache.Storage.replace: a snapshot is
            // read-only and must not publish live loaded/empty-section tracking deltas.
            destination.set(index, chunk);
            onCopied.accept(index);
            copied++;
        }
        return copied;
    }
}
