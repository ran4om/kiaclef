package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.SearchChunkForBlockTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.LookHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;

/** Collects only exposed kelp tips that can be harvested from a dry shoreline. */
public final class CollectKelpTask extends ResourceTask {
    private static final double BLOCK_REACH = 4.5;
    private static final double INITIAL_NEAR_SCAN_RADIUS = 32.0;

    private BlockPos _target;
    private BlockPos _stand;
    private final Set<StandAttempt> _rejectedStands = new HashSet<>();
    private final Map<ChunkPos, Set<BlockPos>> _kelpByChunk = new HashMap<>();
    // An empty scan is still proof that a loaded chunk was checked. Keep this
    // separate from _kelpByChunk, which intentionally contains only chunks with kelp.
    private final Set<ChunkPos> _scannedChunks = new HashSet<>();
    private ChunkPos _lastScannedChunk;

    public CollectKelpTask(int targetCount) {
        super(new ItemTarget(net.minecraft.world.item.Items.KELP, targetCount));
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        // Let ResourceTask collect the real kelp drops after a tip is destroyed.
        return false;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
        mod.getBlockTracker().trackBlock(Blocks.KELP);
        _target = null;
        _stand = null;
        _rejectedStands.clear();
        _kelpByChunk.clear();
        _scannedChunks.clear();
        _lastScannedChunk = null;
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        scanNextLoadedChunk(mod);
        List<ChunkPos> loadedChunks = mod.getChunkTracker().getLoadedChunks();
        _scannedChunks.retainAll(loadedChunks);
        if (_target != null && !isEligibleTip(mod, _target)) {
            _target = null;
            _stand = null;
        }
        if (_target == null || _stand == null || !isDryStand(mod, _stand)
                || mod.getBlockTracker().unreachable(_stand)
                || !withinReachFromStand(_stand, _target)) {
            _target = null;
            _stand = null;

            // Complete a bounded near-field scan before starting exploration. This
            // prevents a known distant shoreline from winning while nearby loaded
            // chunks (including a prepared resource bank) have not been examined.
            ChunkPos pendingNear = nearestUnscannedChunkWithinDistance(loadedChunks, _scannedChunks,
                    mod.getPlayer().position(), INITIAL_NEAR_SCAN_RADIUS * INITIAL_NEAR_SCAN_RADIUS);
            if (pendingNear != null) {
                setDebugState("Scanning nearby loaded kelp chunks: next=" + pendingNear
                        + " lowerBound=" + Math.sqrt(horizontalChunkDistanceSquared(pendingNear, mod.getPlayer().position())));
                return null;
            }

            TipApproach candidate = findNearestCandidateAndStand(mod);
            if (candidate != null) {
                ChunkPos pendingCloser = nearestUnscannedChunkWithinDistance(loadedChunks, _scannedChunks,
                        mod.getPlayer().position(), candidate.tipDistance());
                if (pendingCloser != null) {
                    setDebugState("Scanning for nearer kelp: candidate=" + candidate.tip()
                            + " distance=" + Math.sqrt(candidate.tipDistance()) + " next=" + pendingCloser
                            + " lowerBound=" + Math.sqrt(horizontalChunkDistanceSquared(pendingCloser, mod.getPlayer().position())));
                    return null;
                }
                _target = candidate.tip();
                _stand = candidate.stand();
            }
        }

        if (_target == null || _stand == null) {
            setDebugState("Searching for exposed kelp tips with a dry bank.");
            return new SearchChunkForBlockTask(Blocks.KELP);
        }

        if (!mod.getPlayer().blockPosition().equals(_stand)) {
            setDebugState("Walking to dry kelp bank at " + _stand);
            return new GetToBlockTask(_stand);
        }

        // DestroyBlockTask has a Baritone builder fallback when the target is not
        // in interaction reach. Only enter it after proving a direct interaction.
        if (LookHelper.getReach(_target).isEmpty()) {
            _rejectedStands.add(new StandAttempt(_target, _stand));
            _stand = null;
            setDebugState("Trying another dry bank angle for " + _target);
            return null;
        }

        setDebugState("Harvesting exposed kelp tip at " + _target);
        return new DestroyBlockTask(_target, true);
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(Blocks.KELP);
        _kelpByChunk.clear();
        _scannedChunks.clear();
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        return other instanceof CollectKelpTask;
    }

    @Override
    protected String toDebugStringName() {
        return "Collect Kelp from Dry Banks";
    }

    private TipApproach findNearestCandidateAndStand(AltoClef mod) {
        Vec3 origin = mod.getPlayer().position();
        List<TipApproach> candidates = new ArrayList<>();
        for (BlockPos tip : knownKelpLocations()) {
            if (!isEligibleTip(mod, tip)) continue;
            for (BlockPos stand : candidateStandPositions(tip)) {
                if (!isDryStand(mod, stand) || mod.getBlockTracker().unreachable(stand)
                        || !withinReachFromStand(stand, tip)
                        || _rejectedStands.contains(new StandAttempt(tip, stand))) {
                    continue;
                }
                candidates.add(new TipApproach(tip.immutable(), stand.immutable(),
                        tip.distToCenterSqr(origin), stand.distToCenterSqr(origin)));
            }
        }
        return candidates.stream()
                .min(Comparator.comparingDouble(TipApproach::tipDistance)
                        .thenComparingDouble(TipApproach::standDistance))
                .orElse(null);
    }

    /**
     * Baritone's MineProcess search rejects blocks adjacent to fluid as unsafe
     * break targets. Kelp grows submerged, so its positions never reach the
     * ordinary BlockTracker cache. Read one loaded chunk directly each task tick.
     */
    private void scanNextLoadedChunk(AltoClef mod) {
        List<ChunkPos> loadedChunks = mod.getChunkTracker().getLoadedChunks();
        _kelpByChunk.keySet().removeIf(chunk -> !mod.getChunkTracker().isChunkLoaded(chunk));
        _scannedChunks.retainAll(loadedChunks);
        if (loadedChunks.isEmpty()) return;
        ChunkPos chunk = nearestUnscannedChunk(loadedChunks, _scannedChunks, mod.getPlayer().position());
        if (chunk == null) {
            chunk = selectNextLoadedChunk(loadedChunks, _lastScannedChunk, mod.getPlayer().position());
        }
        if (chunk == null) return;
        _lastScannedChunk = chunk;
        Set<BlockPos> found = new HashSet<>();
        LevelChunk loadedChunk = mod.getWorld().getChunk(chunk.x(), chunk.z());
        LevelChunkSection[] sections = loadedChunk.getSections();
        int minSectionY = mod.getWorld().getMinSectionY();
        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            LevelChunkSection section = sections[sectionIndex];
            if (!section.maybeHas(state -> state.is(Blocks.KELP))) continue;
            int baseY = (minSectionY + sectionIndex) << 4;
            for (int localX = 0; localX < 16; localX++) {
                for (int localY = 0; localY < 16; localY++) {
                    for (int localZ = 0; localZ < 16; localZ++) {
                        if (section.getBlockState(localX, localY, localZ).is(Blocks.KELP)) {
                            found.add(new BlockPos((chunk.x() << 4) + localX,
                                    baseY + localY, (chunk.z() << 4) + localZ));
                        }
                    }
                }
            }
        }
        if (found.isEmpty()) _kelpByChunk.remove(chunk);
        else _kelpByChunk.put(chunk, found);
        _scannedChunks.add(chunk);
    }

    private List<BlockPos> knownKelpLocations() {
        return _kelpByChunk.values().stream().flatMap(Set::stream).toList();
    }

    static ChunkPos selectNextLoadedChunk(List<ChunkPos> loadedChunks, ChunkPos lastScanned, Vec3 playerPosition) {
        if (loadedChunks.isEmpty()) return null;
        List<ChunkPos> nearestFirst = loadedChunks.stream().distinct()
                .sorted(Comparator.comparingDouble(chunk -> horizontalChunkDistanceSquared(chunk, playerPosition)))
                .toList();
        if (lastScanned == null) return nearestFirst.getFirst();
        int previousIndex = nearestFirst.indexOf(lastScanned);
        return nearestFirst.get(previousIndex < 0 ? 0 : (previousIndex + 1) % nearestFirst.size());
    }

    /** Lower bound from the player to the chunk's horizontal block-coordinate AABB. */
    static double horizontalChunkDistanceSquared(ChunkPos chunk, Vec3 position) {
        double dx = distanceToInterval(position.x, chunk.getMinBlockX(), chunk.getMaxBlockX() + 1.0);
        double dz = distanceToInterval(position.z, chunk.getMinBlockZ(), chunk.getMaxBlockZ() + 1.0);
        return dx * dx + dz * dz;
    }

    static ChunkPos nearestUnscannedChunk(List<ChunkPos> loadedChunks, Set<ChunkPos> scannedChunks,
                                          Vec3 playerPosition) {
        return loadedChunks.stream().distinct()
                .filter(chunk -> !scannedChunks.contains(chunk))
                .min(Comparator.comparingDouble(chunk -> horizontalChunkDistanceSquared(chunk, playerPosition)))
                .orElse(null);
    }

    static ChunkPos nearestUnscannedChunkWithinDistance(List<ChunkPos> loadedChunks, Set<ChunkPos> scannedChunks,
                                                        Vec3 playerPosition, double maxDistanceSquared) {
        return loadedChunks.stream().distinct()
                .filter(chunk -> !scannedChunks.contains(chunk))
                .filter(chunk -> horizontalChunkDistanceSquared(chunk, playerPosition) <= maxDistanceSquared)
                .min(Comparator.comparingDouble(chunk -> horizontalChunkDistanceSquared(chunk, playerPosition)))
                .orElse(null);
    }

    private boolean isEligibleTip(AltoClef mod, BlockPos pos) {
        if (!mod.getChunkTracker().isChunkLoaded(pos)
                || !mod.getChunkTracker().isChunkLoaded(pos.above())) return false;
        BlockState state = mod.getWorld().getBlockState(pos);
        BlockState above = mod.getWorld().getBlockState(pos.above());
        return isExposedKelpTip(state, above)
                && state.getDestroySpeed(mod.getWorld(), pos) >= 0
                && !mod.getExtraBaritoneSettings().shouldAvoidBreaking(pos)
                && !mod.getBlockTracker().unreachable(pos);
    }

    static boolean isExposedKelpTip(BlockState kelp, BlockState above) {
        return kelp.is(Blocks.KELP) && above.isAir() && above.getFluidState().isEmpty();
    }

    private boolean isDryStand(AltoClef mod, BlockPos feet) {
        BlockPos head = feet.above();
        BlockPos support = feet.below();
        if (!mod.getChunkTracker().isChunkLoaded(feet)
                || !mod.getChunkTracker().isChunkLoaded(head)
                || !mod.getChunkTracker().isChunkLoaded(support)) return false;
        return isDryStand(mod.getWorld(), feet);
    }

    static boolean isDryStand(LevelReader world, BlockPos feet) {
        BlockPos head = feet.above();
        BlockPos support = feet.below();
        BlockState feetState = world.getBlockState(feet);
        BlockState headState = world.getBlockState(head);
        BlockState supportState = world.getBlockState(support);
        return isPassableAndDry(feetState)
                && isPassableAndDry(headState)
                && supportState.isRedstoneConductor(world, support)
                && isSafeDrySupport(supportState);
    }

    static boolean isPassableAndDry(BlockState state) {
        // Air is the conservative safe case; it avoids standing in fluid or in
        // partial blocks whose collision shape depends on neighboring state.
        return state.isAir() && state.getFluidState().isEmpty();
    }

    static boolean isSafeDrySupport(BlockState state) {
        return state.isSolid() && state.getFluidState().isEmpty() && !state.is(Blocks.MAGMA_BLOCK);
    }

    static boolean withinReachFromStand(BlockPos stand, BlockPos target) {
        Vec3 eye = new Vec3(stand.getX() + 0.5, stand.getY() + 1.62, stand.getZ() + 0.5);
        double dx = distanceToInterval(eye.x, target.getX(), target.getX() + 1);
        double dy = distanceToInterval(eye.y, target.getY(), target.getY() + 1);
        double dz = distanceToInterval(eye.z, target.getZ(), target.getZ() + 1);
        return dx * dx + dy * dy + dz * dz <= BLOCK_REACH * BLOCK_REACH;
    }

    static List<BlockPos> candidateStandPositions(BlockPos tip) {
        List<BlockPos> result = new ArrayList<>();
        for (int dy = 0; dy <= 1; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    if (dx == 0 && dz == 0 && dy == 0) continue;
                    BlockPos stand = tip.offset(dx, dy, dz);
                    if (withinReachFromStand(stand, tip)) result.add(stand);
                }
            }
        }
        return result;
    }

    private static double distanceToInterval(double value, double min, double max) {
        return value < min ? min - value : value > max ? value - max : 0;
    }

    private record TipApproach(BlockPos tip, BlockPos stand, double tipDistance, double standDistance) {
    }

    private record StandAttempt(BlockPos tip, BlockPos stand) {
    }
}
