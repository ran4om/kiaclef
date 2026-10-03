package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.construction.compound.ConstructNetherPortalBucketTask;
import adris.altoclef.tasks.construction.compound.ConstructNetherPortalObsidianTask;
import adris.altoclef.tasks.DoToClosestBlockTask;
import adris.altoclef.tasks.speedrun.BeatMinecraft2Task;
import adris.altoclef.tasks.speedrun.KillEnderDragonTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.trackers.SimpleChunkTracker;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Some generic tasks require us to go to the nether/overworld/end.
 * <p>
 * The user should be able to specify how this should be done in settings
 * (ex, craft a new portal from scratch or check particular portal areas first or highway or whatever)
 */
public class DefaultGoToDimensionTask extends Task {

    private final Dimension _target;
    // Cached to keep build properties alive if this task pauses/resumes.
    private final Task _cachedNetherBucketConstructionTask = new ConstructNetherPortalBucketTask();
    private BeatMinecraft2Task _cachedEndTravelTask;
    private KillEnderDragonTask _cachedEndDragonTask;
    // Explore reachable terrain until the tracked target block is found. SearchChunkForBlockTask
    // follows only contiguous chunks that already contain a target, so it cannot discover an
    // isolated portal or gateway in an arbitrary End chunk.
    private final TimeoutWanderTask _cachedEndPortalSearchTask = new TimeoutWanderTask(true);
    private final TimeoutWanderTask _cachedOuterGatewaySearchTask = new TimeoutWanderTask(true);
    private static final double LOCAL_OUTER_GATEWAY_RANGE = 128;
    private static final int LOCAL_PORTAL_SCAN_RADIUS = 16;
    private static final int LOCAL_PORTAL_SCAN_VERTICAL_RADIUS = 16;
    private static final int LOCAL_PORTAL_SCAN_INTERVAL_TICKS = 20;
    private final LocalPortalScanThrottle _portalScanThrottle = new LocalPortalScanThrottle();
    private volatile boolean _allowWalkingOnEndPortal;

    private static final int OUTER_END_ISLAND_START_RADIUS = 800;

    public DefaultGoToDimensionTask(Dimension target) {
        _target = target;
    }

    @Override
    protected void onStart(AltoClef mod) {
        _allowWalkingOnEndPortal = false;
        mod.getBehaviour().push();
        mod.getBehaviour().allowWalkingOn(blockPos -> {
            // Baritone can evaluate this predicate on its pathing worker while a
            // world transition is in flight. Use one world snapshot for both
            // the loaded-chunk check and block-state lookup so unload cannot
            // leave the second getWorld() dereference null.
            ClientLevel world = mod.getWorld();
            return _allowWalkingOnEndPortal
                    && world != null
                    && SimpleChunkTracker.isChunkLoaded(world,
                    new ChunkPos(blockPos.getX() >> 4, blockPos.getZ() >> 4))
                    && world.getBlockState(blockPos).getBlock() == Blocks.END_PORTAL;
        });
        mod.getBlockTracker().trackBlock(Blocks.NETHER_PORTAL);
        mod.getBlockTracker().trackBlock(Blocks.END_PORTAL);
        mod.getBlockTracker().trackBlock(Blocks.END_GATEWAY);
        _portalScanThrottle.reset();
    }

    @Override
    protected Task onTick(AltoClef mod) {
        scanNearbyPortals(mod);
        if (WorldHelper.getCurrentDimension() == _target) {
            _allowWalkingOnEndPortal = false;
            return null;
        }

        switch (_target) {
            case OVERWORLD:
                switch (WorldHelper.getCurrentDimension()) {
                    case NETHER:
                        return goToOverworldFromNetherTask(mod);
                    case END:
                        return goToOverworldFromEndTask(mod);
                }
                break;
            case NETHER:
                switch (WorldHelper.getCurrentDimension()) {
                    case OVERWORLD:
                        return goToNetherFromOverworldTask(mod);
                    case END:
                        // First go to the overworld
                        return goToOverworldFromEndTask(mod);
                }
                break;
            case END:
                switch (WorldHelper.getCurrentDimension()) {
                    case NETHER:
                        // First go to the overworld
                        return goToOverworldFromNetherTask(mod);
                    case OVERWORLD:
                        return goToEndTask(mod);
                }
                break;
        }

        setDebugState(WorldHelper.getCurrentDimension() + " -> " + _target + " is NOT IMPLEMENTED YET!");
        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        _allowWalkingOnEndPortal = false;
        _portalScanThrottle.reset();
        mod.getBehaviour().pop();
        mod.getBlockTracker().stopTracking(Blocks.NETHER_PORTAL);
        mod.getBlockTracker().stopTracking(Blocks.END_PORTAL);
        mod.getBlockTracker().stopTracking(Blocks.END_GATEWAY);
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof DefaultGoToDimensionTask task) {
            return task._target == _target;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Going to dimension: " + _target + " (default version)";
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return WorldHelper.getCurrentDimension() == _target;
    }

    private Task goToOverworldFromNetherTask(AltoClef mod) {
        if (netherPortalIsClose(mod)) {
            setDebugState("Going to nether portal");
            return new EnterNetherPortalTask(Dimension.OVERWORLD);
        }

        Optional<BlockPos> closest = mod.getMiscBlockTracker().getLastUsedNetherPortal(Dimension.NETHER);
        if (closest.isPresent()) {
            setDebugState("Going to last nether portal pos");
            return new GetToBlockTask(closest.get());
        }

        setDebugState("Constructing nether portal with obsidian");
        return new ConstructNetherPortalObsidianTask();
    }

    static BlockPos endExitGoal(BlockPos portalCell) {
        return portalCell;
    }

    private Task goToOverworldFromEndTask(AltoClef mod) {
        _allowWalkingOnEndPortal = shouldAllowWalkingOnEndPortal(
                WorldHelper.getCurrentDimension(), _target);
        boolean outerIsland = isOnOuterEndIsland(mod);
        Optional<BlockPos> localOuterGateway = outerIsland
                ? mod.getBlockTracker().getNearestTracking(mod.getPlayer().position(),
                        blockPos -> isLocalOuterGateway(mod, blockPos), Blocks.END_GATEWAY)
                : Optional.empty();
        EndReturnRoute route = chooseEndReturnRoute(
                mod.getBlockTracker().anyFound(Blocks.END_PORTAL),
                localOuterGateway.isPresent(),
                mod.getEntityTracker().entityFound(EnderDragon.class),
                outerIsland);
        return switch (route) {
            case EXIT_PORTAL -> {
                setDebugState("Going to the End return portal");
                yield new DoToClosestBlockTask(
                        blockPos -> new GetToBlockTask(endExitGoal(blockPos), false),
                        Blocks.END_PORTAL);
            }
            case OUTER_GATEWAY -> {
                setDebugState("Returning through a reachable outer-island gateway");
                yield new EnterEndGatewayTask(localOuterGateway.orElseThrow());
            }
            case KILL_DRAGON -> {
                setDebugState("Defeating the tracked Ender Dragon to open the return portal");
                if (_cachedEndDragonTask == null) {
                    _cachedEndDragonTask = new KillEnderDragonTask();
                }
                yield _cachedEndDragonTask;
            }
            case SEARCH_FOR_EXIT -> {
                setDebugState("Exploring reachable central-island terrain for the End return portal");
                yield _cachedEndPortalSearchTask;
            }
            case SEARCH_FOR_GATEWAY -> {
                setDebugState("Exploring reachable outer-island terrain for a return gateway");
                yield _cachedOuterGatewaySearchTask;
            }
        };
    }

    private static boolean isLocalOuterGateway(AltoClef mod, BlockPos blockPos) {
        if (!mod.getChunkTracker().isChunkLoaded(blockPos)) return false;
        if (WorldHelper.inRangeXZ(new Vec3(0, 64, 0), WorldHelper.toVec3d(blockPos),
                OUTER_END_ISLAND_START_RADIUS)) return false;
        return WorldHelper.inRangeXZ(mod.getPlayer().position(), WorldHelper.toVec3d(blockPos),
                LOCAL_OUTER_GATEWAY_RANGE);
    }

    private static boolean isOnOuterEndIsland(AltoClef mod) {
        return !WorldHelper.inRangeXZ(new Vec3(0, 64, 0), mod.getPlayer().position(),
                OUTER_END_ISLAND_START_RADIUS);
    }

    static EndReturnRoute chooseEndReturnRoute(boolean exitPortalFound, boolean gatewayFound,
                                                boolean dragonFound, boolean outerIsland) {
        if (outerIsland) {
            // A cached portal is normally on the central island. Do not route across the void
            // to it when an outer-island gateway is the appropriate way back to center.
            return gatewayFound ? EndReturnRoute.OUTER_GATEWAY : EndReturnRoute.SEARCH_FOR_GATEWAY;
        }
        if (exitPortalFound) return EndReturnRoute.EXIT_PORTAL;
        if (dragonFound) return EndReturnRoute.KILL_DRAGON;
        return EndReturnRoute.SEARCH_FOR_EXIT;
    }

    enum EndReturnRoute {
        EXIT_PORTAL,
        OUTER_GATEWAY,
        KILL_DRAGON,
        SEARCH_FOR_EXIT,
        SEARCH_FOR_GATEWAY
    }

    private Task goToNetherFromOverworldTask(AltoClef mod) {
        if (netherPortalIsClose(mod)) {
            setDebugState("Going to nether portal");
            return new EnterNetherPortalTask(Dimension.NETHER);
        }
        return switch (mod.getModSettings().getOverworldToNetherBehaviour()) {
            case BUILD_PORTAL_VANILLA -> _cachedNetherBucketConstructionTask;
            case GO_TO_HOME_BASE -> new GetToBlockTask(mod.getModSettings().getHomeBasePosition());
        };
    }

    private Task goToEndTask(AltoClef mod) {
        // The parent finishes as soon as the dimension changes, stopping the full-game
        // child before it proceeds to fight the dragon. BeatMinecraft2Task controls when it
        // may step into the portal, after its gear and supply checks are complete.
        setDebugState("Locating and entering the End portal");
        if (_cachedEndTravelTask == null) {
            _cachedEndTravelTask = new BeatMinecraft2Task();
        }
        return _cachedEndTravelTask;
    }

    static boolean shouldAllowWalkingOnEndPortal(Dimension current, Dimension target) {
        return current == Dimension.END && target != Dimension.END;
    }

    private boolean netherPortalIsClose(AltoClef mod) {
        if (mod.getBlockTracker().anyFound(Blocks.NETHER_PORTAL)) {
            Optional<BlockPos> closest = mod.getBlockTracker().getNearestTracking(mod.getPlayer().position(), Blocks.NETHER_PORTAL);
            return closest.isPresent() && closest.get().closerToCenterThan(mod.getPlayer().position(), 2000);
        }
        return false;
    }

    private void scanNearbyPortals(AltoClef mod) {
        if (mod.getWorld() == null || mod.getPlayer() == null) return;
        BlockPos center = mod.getPlayer().blockPosition();
        Dimension dimension = WorldHelper.getCurrentDimension();
        long gameTime = mod.getWorld().getGameTime();
        if (!_portalScanThrottle.shouldScan(gameTime, dimension, center)) return;

        scanLoadedPortalBlocks(center, mod.getWorld().getMinY(), mod.getWorld().getMaxY(),
                mod.getChunkTracker().getLoadedChunks(), mod.getWorld()::getBlockState,
                mod.getBlockTracker()::addBlock);
    }

    static int scanLoadedPortalBlocks(BlockPos center, int worldMinY, int worldMaxY,
                                      List<ChunkPos> loadedChunks,
                                      Function<BlockPos, net.minecraft.world.level.block.state.BlockState> stateAt,
                                      BiConsumer<Block, BlockPos> foundBlock) {
        int minX = center.getX() - LOCAL_PORTAL_SCAN_RADIUS;
        int maxX = center.getX() + LOCAL_PORTAL_SCAN_RADIUS;
        int minZ = center.getZ() - LOCAL_PORTAL_SCAN_RADIUS;
        int maxZ = center.getZ() + LOCAL_PORTAL_SCAN_RADIUS;
        int minY = Math.max(worldMinY, center.getY() - LOCAL_PORTAL_SCAN_VERTICAL_RADIUS);
        int maxY = Math.min(worldMaxY - 1, center.getY() + LOCAL_PORTAL_SCAN_VERTICAL_RADIUS);
        int found = 0;

        for (ChunkPos chunk : loadedChunks) {
            int chunkMinX = Math.max(minX, chunk.getMinBlockX());
            int chunkMaxX = Math.min(maxX, chunk.getMaxBlockX());
            int chunkMinZ = Math.max(minZ, chunk.getMinBlockZ());
            int chunkMaxZ = Math.min(maxZ, chunk.getMaxBlockZ());
            if (chunkMinX > chunkMaxX || chunkMinZ > chunkMaxZ) continue;

            for (int x = chunkMinX; x <= chunkMaxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = chunkMinZ; z <= chunkMaxZ; z++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        Block block = stateAt.apply(pos).getBlock();
                        if (block == Blocks.NETHER_PORTAL || block == Blocks.END_PORTAL
                                || block == Blocks.END_GATEWAY) {
                            foundBlock.accept(block, pos);
                            found++;
                        }
                    }
                }
            }
        }
        return found;
    }

    static final class LocalPortalScanThrottle {
        private long _lastScanTick = Long.MIN_VALUE;
        private Dimension _lastDimension;
        private BlockPos _lastCenter;

        boolean shouldScan(long gameTime, Dimension dimension, BlockPos center) {
            long dx = _lastCenter == null ? 0 : center.getX() - _lastCenter.getX();
            long dy = _lastCenter == null ? 0 : center.getY() - _lastCenter.getY();
            long dz = _lastCenter == null ? 0 : center.getZ() - _lastCenter.getZ();
            boolean reset = _lastDimension != dimension || _lastCenter == null
                    || dx * dx + dy * dy + dz * dz > 64;
            if (reset || gameTime - _lastScanTick >= LOCAL_PORTAL_SCAN_INTERVAL_TICKS) {
                _lastScanTick = gameTime;
                _lastDimension = dimension;
                _lastCenter = center.immutable();
                return true;
            }
            return false;
        }

        void reset() {
            _lastScanTick = Long.MIN_VALUE;
            _lastDimension = null;
            _lastCenter = null;
        }
    }

    public enum OVERWORLD_TO_NETHER_BEHAVIOUR {
        BUILD_PORTAL_VANILLA,
        GO_TO_HOME_BASE
    }
}
