package adris.altoclef.trackers;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.ChunkLoadEvent;
import adris.altoclef.eventbus.events.ChunkUnloadEvent;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.EmptyLevelChunk;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Keeps track of currently loaded chunks. That's it.
 */
public class SimpleChunkTracker {

    private final AltoClef _mod;
    private final Set<ChunkPos> _loaded = new HashSet<>();

    public SimpleChunkTracker(AltoClef mod) {
        _mod = mod;

        // When chunks load...
        EventBus.subscribe(ChunkLoadEvent.class, evt -> onLoad(evt.chunk.getPos()));
        EventBus.subscribe(ChunkUnloadEvent.class, evt -> onUnload(evt.chunkPos));
    }

    private void onLoad(ChunkPos pos) {
        //Debug.logInternal("LOADED: " + pos);
        _loaded.add(pos);
    }

    private void onUnload(ChunkPos pos) {
        //Debug.logInternal("unloaded: " + pos);
        _loaded.remove(pos);
    }

    public boolean isChunkLoaded(ChunkPos pos) {
        return isChunkLoaded(_mod.getWorld(), pos);
    }

    public static boolean isChunkLoaded(ClientLevel world, ChunkPos pos) {
        // Baritone evaluates some AltoClef walking predicates on its pathing
        // worker. A client world can disappear while that calculation is in
        // flight (for example when leaving a singleplayer world); treat that
        // as an unloaded chunk instead of dereferencing a null level.
        return world != null && !(world.getChunk(pos.x(), pos.z()) instanceof EmptyLevelChunk);
    }

    public boolean isChunkLoaded(BlockPos pos) {
        return isChunkLoaded(new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4));
    }

    public List<ChunkPos> getLoadedChunks() {
        List<ChunkPos> result = new ArrayList<>(_loaded);
        // Only show LOADED chunks.
        result = result.stream()
                .filter(this::isChunkLoaded)
                .distinct()
                .collect(Collectors.toList());
        return result;
    }

    /**
     * Loops through every block in a chunk if it is loaded.
     * If the chunk isn't loaded, it doesn't scan anything.
     *
     * @param chunk The chunk pos to scan
     * @param onBlockStop Run for every block until it returns true, where it stops scanning.
     * @return whether `onBlockStop` returned true at any point.
     */
    public boolean scanChunk(ChunkPos chunk, Predicate<BlockPos> onBlockStop) {
        if (!isChunkLoaded(chunk)) return false;
        //Debug.logInternal("SCANNED CHUNK " + chunk.toString());
        int minY = WorldHelper.getMinBuildY(_mod.getWorld());
        int maxY = WorldHelper.getMaxBuildY(_mod.getWorld());
        for (int xx = chunk.getMinBlockX(); xx <= chunk.getMaxBlockX(); ++xx) {
            for (int yy = minY; yy <= maxY; ++yy) {
                for (int zz = chunk.getMinBlockZ(); zz <= chunk.getMaxBlockZ(); ++zz) {
                    if (onBlockStop.test(new BlockPos(xx, yy, zz))) return true;
                }
            }
        }
        return false;
    }
    public void scanChunk(ChunkPos chunk, Consumer<BlockPos> onBlock) {
        scanChunk(chunk, (block) -> {
            onBlock.accept(block);
            return false;
        });
    }

    public void reset(AltoClef mod) {
        Debug.logInternal("CHUNKS RESET");
        _loaded.clear();
    }
}
