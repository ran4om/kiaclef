package adris.altoclef.trackers;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.BlockPlaceEvent;
import adris.altoclef.trackers.blacklisting.WorldLocateBlacklist;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.helpers.BaritoneHelper;
import adris.altoclef.util.time.TimerGame;
import adris.altoclef.util.helpers.ConfigHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.helpers.StlHelper;
import baritone.Baritone;
import baritone.api.utils.BlockOptionalMetaLookup;
import baritone.pathing.movement.CalculationContext;
import baritone.process.MineProcess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Tracks blocks the way we want it, when we want it.
 *
 * Gives you a "Check and don't care" interface where you can check for blocks and their locations over and over again
 * without scanning the world over and over again.
 *
 * Also keeps track of blacklists for unreachable blocks
 */
public class BlockTracker extends Tracker {

    private static BlockTrackerConfig _config = new BlockTrackerConfig();
    static {
        ConfigHelper.loadConfig("configs/block_tracker.json", BlockTrackerConfig::new, BlockTrackerConfig.class, newConfig -> _config = newConfig);
    }

    private static final String KELP_COAL_SOURCE_POSITIONS_PROPERTY =
            "altoclef.runtimeTest.kelpCoalSourcePositions";
    private static final int MAX_KELP_COAL_PURGE_TRACE_RECORDS = 1024;
    private static final int MAX_KELP_COAL_INGESTION_TRACE_RECORDS = 8192;
    private static final AtomicLong NEXT_POS_CACHE_TRACE_ID = new AtomicLong(1);
    private static final Object KELP_COAL_TRACE_CONFIG_LOCK = new Object();
    private static volatile String _cachedKelpCoalTraceEncoding;
    private static volatile Set<BlockPos> _cachedKelpCoalTracePositions = Set.of();

    // This should be moved to an instance variable
    // but if set to true, block scanning will happen
    // asynchronously to spread out the expensive cost of scanning.
    private static final boolean ASYNC_SCANNING = true;

    private static Set<BlockPos> runtimeKelpCoalTracePositions() {
        if (!Boolean.getBoolean("altoclef.runtimeTest")
                || !"kelp".equalsIgnoreCase(System.getProperty("altoclef.runtimeStart"))) {
            return Set.of();
        }
        String encoded = System.getProperty(KELP_COAL_SOURCE_POSITIONS_PROPERTY);
        if (encoded == null) return Set.of();
        if (encoded.equals(_cachedKelpCoalTraceEncoding)) return _cachedKelpCoalTracePositions;

        synchronized (KELP_COAL_TRACE_CONFIG_LOCK) {
            if (encoded.equals(_cachedKelpCoalTraceEncoding)) return _cachedKelpCoalTracePositions;
            Set<BlockPos> positions = new HashSet<>();
            boolean malformed = false;
            for (String entry : encoded.split(";", -1)) {
                String[] coordinates = entry.split(",", -1);
                if (coordinates.length != 3) {
                    malformed = true;
                    continue;
                }
                try {
                    BlockPos position = new BlockPos(Integer.parseInt(coordinates[0]),
                            Integer.parseInt(coordinates[1]), Integer.parseInt(coordinates[2]));
                    if (!positions.add(position)) malformed = true;
                } catch (NumberFormatException error) {
                    malformed = true;
                }
            }
            if (malformed || positions.size() != 16) {
                Debug.logInternal("[KELP_COAL_CACHE_CONFIG_WARNING] parsedPositions=" + positions.size()
                        + ",expectedPositions=16,malformedOrDuplicate=" + malformed);
            }
            _cachedKelpCoalTraceEncoding = encoded;
            _cachedKelpCoalTracePositions = Set.copyOf(positions);
            return _cachedKelpCoalTracePositions;
        }
    }

    private final HashMap<Dimension, PosCache> _caches = new HashMap<>();

    //private final PosCache _cache = new PosCache(100, 64*1.5);

    private final TimerGame _timer = new TimerGame(_config.scanInterval);

    private final TimerGame _forceElapseTimer = new TimerGame(_config.scanIntervalWhenNewBlocksFound);

    // A scan can last no more than 15 seconds
    private final TimerGame _asyncForceResetScanFlag = new TimerGame(15);

    private final Map<Block, Integer> _trackingBlocks = new HashMap<>();

    private final Object _scanMutex = new Object();

    private boolean _scanning = false;

    // Only perform scans at the END of our frame
    private final Semaphore _endOfFrameMutex = new Semaphore(1);

    //private Block _currentlyTracking = null;
    private final AltoClef _mod;

    public BlockTracker(AltoClef mod, TrackerManager manager) {
        super(manager);
        _mod = mod;
        // First time, track immediately
        _timer.forceElapse();
        _forceElapseTimer.forceElapse();

        // Listen for block placement
        EventBus.subscribe(BlockPlaceEvent.class, evt -> addBlock(evt.blockState.getBlock(), evt.blockPos));
    }

    @Override
    protected void updateState() {
        if (shouldUpdate()) {
            update();
        }
    }

    // We want our `_trackingBlocks` value to be read AFTER tasks have finished ticking
    public void preTickTask() {
        try {
            _endOfFrameMutex.acquire();
        } catch (InterruptedException e) {
            Debug.logWarning("Pre-tick failed to acquire block track mutex! (send logs)");
            e.printStackTrace();
        }
    }
    public void postTickTask() {
        _endOfFrameMutex.release();
    }

    @Override
    protected void reset() {
        // Tasks will handle de-tracking blocks.
        for (PosCache cache : _caches.values()) {
            cache.clear();
        }
    }

    public boolean isTracking(Block block) {
        synchronized (_trackingBlocks) {
            return _trackingBlocks.containsKey(block) && _trackingBlocks.get(block) > 0;
        }
    }

    /**
     * Starts tracking/pay attention to some blocks.
     * <b>IMPORTANT:</b> ALWAYS pair with {@link #stopTracking(Block...) stopTracking}! Otherwise this block type will be
     * tracked forever (not the end of the world, but other block types will be lost.
     */
    public void trackBlock(Block... blocks) {
        synchronized (_trackingBlocks) {
            for (Block block : blocks) {
                if (!_trackingBlocks.containsKey(block)) {
                    // We're tracking a new block, so we're not updated.
                    setDirty();
                    _trackingBlocks.put(block, 0);
                    // Force a rescan if these are new blocks and we aren't doing this like every frame.
                    if (_forceElapseTimer.elapsed()) {
                        _timer.forceElapse();
                        _forceElapseTimer.reset();
                        _forceElapseTimer.setInterval(_config.scanIntervalWhenNewBlocksFound);
                    }
                }
                _trackingBlocks.put(block, _trackingBlocks.get(block) + 1);
            }
        }
    }

    /**
     * Stops tracking some blocks, after calling {@link #trackBlock(Block...) trackBlock}.
     *
     * Only call this once for every {@link #trackBlock(Block...) trackBlock}.
     */
    public void stopTracking(Block... blocks) {
        synchronized (_trackingBlocks) {
            for (Block block : blocks) {
                if (_trackingBlocks.containsKey(block)) {
                    int current = _trackingBlocks.get(block);
                    if (current == 0) {
                        Debug.logWarning("Untracked block " + block + " more times than necessary. BlockTracker stack is unreliable from this point on.");
                    } else {
                        _trackingBlocks.put(block, current - 1);
                        if (_trackingBlocks.get(block) <= 0) {
                            _trackingBlocks.remove(block);
                        }
                    }
                }
            }
        }
    }

    /**
     * Manually add a block at a position.
     */
    public void addBlock(Block block, BlockPos pos) {
        if (blockIsValid(pos, block)) {
            synchronized (_scanMutex) {
                currentCache().addBlock(block, pos);
            }
        } else {
            Debug.logInternal("INVALID SET: " + block + " " + pos);
        }
    }

    public boolean anyFound(Block... blocks) {
        updateState();
        synchronized (_scanMutex) {
            return currentCache().anyFound(blocks);
        }
    }

    /**
     * Checks whether any blocks of a type have been found.
     * @param isValidTest A filter predicate, returns true if a block at a position should be included.
     * @param blocks The blocks to check for
     */
    public boolean anyFound(Predicate<BlockPos> isValidTest, Block... blocks) {
        updateState();
        synchronized (_scanMutex) {
            return currentCache().anyFound(isValidTest, blocks);
        }
    }

    public Optional<BlockPos> getNearestTracking(Block... blocks) {
        // Add juuust a little, to prevent digging down all the time/bias towards blocks BELOW the player
        return getNearestTracking(_mod.getPlayer().position().add(0, 0.6f, 0), blocks);
    }
    public Optional<BlockPos> getNearestTracking(Vec3 pos, Block... blocks) {
        return getNearestTracking(pos, p -> true, blocks);
    }
    public Optional<BlockPos> getNearestTracking(Predicate<BlockPos> isValidTest, Block... blocks) {
        return getNearestTracking(_mod.getPlayer().position(), isValidTest, blocks);
    }

    /**
     * Gets the nearest tracked block.
     * @param pos From what position? (defaults to the player's position)
     * @param isValidTest Filter predicate
     * @param blocks The blocks to check for
     * @return Optional.of(block position) if found, otherwise Optional.empty
     */
    public Optional<BlockPos> getNearestTracking(Vec3 pos, Predicate<BlockPos> isValidTest, Block... blocks) {
        return getNearestTrackingWithDiagnostics(pos, isValidTest, null, blocks);
    }

    /** Diagnostic variant for recording the candidates examined by this exact query. */
    public Optional<BlockPos> getNearestTrackingWithDiagnostics(Vec3 pos, Predicate<BlockPos> isValidTest,
                                                                Consumer<NearestQueryTrace> diagnostics,
                                                                Block... blocks) {
        boolean tracking = true;
        synchronized (_trackingBlocks) {
            for (Block block : blocks) {
                if (!_trackingBlocks.containsKey(block)) {
                    Debug.logWarning("BlockTracker: Not tracking block " + block + " right now.");
                    tracking = false;
                    break;
                }
            }
        }
        if (!tracking) {
            if (diagnostics != null) {
                diagnostics.accept(new NearestQueryTrace(List.of(), null,
                        Double.POSITIVE_INFINITY, "block-not-tracked"));
            }
            return Optional.empty();
        }
        // Make sure we've scanned the first time if we need to.
        updateState();
        NearestQueryTrace[] trace = diagnostics == null ? null : new NearestQueryTrace[1];
        Optional<BlockPos> nearest;
        synchronized (_scanMutex) {
            nearest = currentCache().getNearest(_mod, pos, isValidTest, trace, blocks);
        }
        if (diagnostics != null) {
            diagnostics.accept(trace[0] == null
                    ? new NearestQueryTrace(List.of(), null, Double.POSITIVE_INFINITY, "trace-missing")
                    : trace[0]);
        }
        return nearest;
    }

    public record NearestQueryCandidate(BlockPos position, boolean trackerValid,
                                        String trackerDetail, boolean taskFilterValid,
                                        double heuristic, boolean selected) { }

    public record NearestQueryTrace(List<NearestQueryCandidate> candidates, BlockPos selected,
                                    double selectedHeuristic, String stopReason) { }

    /**
     * Returns the locations of all tracked blocks of a given type
     */
    public List<BlockPos> getKnownLocations(Block... blocks) {
        updateState();
        synchronized (_scanMutex) {
            return currentCache().getKnownLocations(blocks);
        }
    }

    public Optional<BlockPos> getNearestWithinRange(BlockPos pos, double range, Block... blocks) {
        return getNearestWithinRange(new Vec3(pos.getX(), pos.getY(), pos.getZ()), range, blocks);
    }

    /**
     * Scans a radius for the closest block of a given type .
     * @param pos The center of this radius
     * @param range Radius to scan for
     * @param blocks What blocks to check for
     */
    public Optional<BlockPos> getNearestWithinRange(Vec3 pos, double range, Block... blocks) {
        int minX = (int) Math.floor(pos.x - range),
                maxX = (int) Math.floor(pos.x + range),
                minY = (int) Math.floor(pos.y - range),
                maxY = (int) Math.floor(pos.y + range),
                minZ = (int) Math.floor(pos.z - range),
                maxZ = (int) Math.floor(pos.z + range);
        double closestDistance = Float.POSITIVE_INFINITY;
        BlockPos nearest = null;
        for (int x = minX; x <= maxX; ++x) {
            for (int y = minY; y <= maxY; ++y) {
                for (int z = minZ; z <= maxZ; ++z) {
                    BlockPos check = new BlockPos(x, y, z);
                    synchronized (_scanMutex) {
                        if (currentCache().blockUnreachable(check)) continue;
                    }

                    assert Minecraft.getInstance().level != null;
                    Block b = Minecraft.getInstance().level.getBlockState(check).getBlock();
                    boolean valid = false;
                    for (Block type : blocks) {
                        if (type == b) {
                            valid = true;
                            break;
                        }
                    }
                    if (!valid) continue;
                    if (check.closerToCenterThan(pos, range)) {
                        double sq = check.distToCenterSqr(pos);
                        if (sq < closestDistance) {
                            closestDistance = sq;
                            nearest = check;
                        }
                    }
                }
            }
        }
        return Optional.ofNullable(nearest);
    }

    private boolean shouldUpdate() {
        return _timer.elapsed();
    }

    private void update() {
        // Perform a baritone scan
        _timer.reset();
        _timer.setInterval(_config.scanInterval);
        CalculationContext ctx = new CalculationContext(_mod.getClientBaritone(), _config.scanAsynchronously);
        if (_config.scanAsynchronously) {
            if (_scanning && _asyncForceResetScanFlag.elapsed()) {
                Debug.logMessage("SCANNING TOOK TOO LONG! Will assume it ended mid way. Hopefully this won't break anything...");
                _scanning = false;
            }
            if (!_scanning) {
                Baritone.getExecutor().execute(() -> {
                    _scanning = true;
                    _asyncForceResetScanFlag.reset();
                    rescanWorld(ctx, true);
                    _scanning = false;
                });
            }
        } else {
            // Synchronous scanning.
            rescanWorld(ctx, false);
        }
    }

    private void rescanWorld(CalculationContext ctx, boolean async) {
        Block[] blocksToScan;
        if (async) {
            // Wait for end of frame
            try {
                _endOfFrameMutex.acquire();
                _endOfFrameMutex.release();
            } catch (InterruptedException e) {
                Debug.logWarning("RESCAN INTERRUPTED! Will SKIP the scan (see logs)");
                _endOfFrameMutex.release();
                e.printStackTrace();
                return;
            }
        }
        synchronized (_trackingBlocks) {
            Debug.logInternal("Rescanning world for " + _trackingBlocks.size() + " blocks... Hopefully not dummy slow.");
            blocksToScan = new Block[_trackingBlocks.size()];
            _trackingBlocks.keySet().toArray(blocksToScan);
        }

        List<BlockPos> knownBlocks;
        synchronized (_scanMutex) {
            knownBlocks = currentCache().getKnownLocations(blocksToScan);
        }

        // Clear invalid block pos before rescan
        for (BlockPos check : knownBlocks) {
            if (!blockIsValid(check, blocksToScan)) {
                //Debug.logInternal("Removed at " + check);
                synchronized (_scanMutex) {
                    currentCache().removeBlock(check, blocksToScan);
                }
            }
        }

        // The scanning may run asynchronously.
        // MineProcess.searchWorld applies maxCacheSize to the combined lookup, not
        // to each block type. A common block (for example, the stone under a
        // superflat spawn) can therefore consume the whole result budget and keep
        // nearby logs/ores out of this tracker's cache. Search each type with its
        // own budget so one abundant block cannot hide the other tracked targets.
        List<BlockPos> found = searchByBlockType(blocksToScan, _config.maxCacheSizePerBlockType, (block, limit) -> {
            BlockOptionalMetaLookup lookup = new BlockOptionalMetaLookup(block);
            return MineProcess.searchWorld(
                    ctx,
                    lookup,
                    limit,
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList());
        });

        synchronized (_scanMutex) {
            if (Minecraft.getInstance().level != null) {
                for (BlockPos pos : found) {
                    Block block = Minecraft.getInstance().level.getBlockState(pos).getBlock();
                    synchronized (_trackingBlocks) {
                        if (_trackingBlocks.containsKey(block)) {
                            //Debug.logInternal("Good: " + block + " at " + pos);
                            currentCache().addBlock(block, pos);
                        }
                    }
                }

                // Purge if we have too many blocks tracked at once.
                currentCache().smartPurge(_mod.getPlayer().position());
            }
        }
    }

    static List<BlockPos> searchByBlockType(Block[] blocks, int limit, BiFunction<Block, Integer, List<BlockPos>> search) {
        List<BlockPos> found = new ArrayList<>();
        for (Block block : blocks) {
            found.addAll(search.apply(block, limit));
        }
        return found;
    }

    // Checks whether it would be WRONG to say "at pos the block is block"
    // Returns true if wrong, false if correct OR undetermined/unsure.
    public boolean blockIsValid(BlockPos pos, Block... blocks) {
        return blockIsValid(pos, null, blocks);
    }

    private boolean blockIsValid(BlockPos pos, Consumer<String> diagnosticObserver, Block... blocks) {
        synchronized (_scanMutex) {
            // We can't reach it, don't even try.
            if (currentCache().blockUnreachable(pos)) {
                if (diagnosticObserver != null) diagnosticObserver.accept("unreachable-blacklist");
                return false;
            }
        }
        // It might be OK to remove this. Will have to test.
        if (!_mod.getChunkTracker().isChunkLoaded(pos)) {
            //Debug.logInternal("(failed chunkcheck: " + new ChunkPos(pos) + ")");
            //Debug.logStack();
            if (diagnosticObserver != null) diagnosticObserver.accept("chunk-not-visible-assumed-valid");
            return true;
        }
        // I'm bored
        ClientLevel zaWarudo = Minecraft.getInstance().level;
        // No world, therefore we don't assume block is invalid.
        if (zaWarudo == null) {
            if (diagnosticObserver != null) diagnosticObserver.accept("client-world-unavailable-assumed-valid");
            return true;
        }
        try {
            BlockState lastCheckedState = null;
            for (Block block : blocks) {
                if (zaWarudo.isEmptyBlock(pos) && WorldHelper.isAir(block)) {
                    return true;
                }
                BlockState state = zaWarudo.getBlockState(pos);
                lastCheckedState = state;
                if (state.getBlock() == block) {
                    return true;
                }
            }
            if (diagnosticObserver != null) {
                diagnosticObserver.accept("loaded-block-mismatch:"
                        + (lastCheckedState == null ? "unknown" : lastCheckedState.getBlock()));
            }
            return false;
        } catch (NullPointerException e) {
            // Probably out of chunk. This means we can't judge its state.
            if (diagnosticObserver != null) diagnosticObserver.accept("chunk-access-unknown-assumed-valid");
            return true;
        }
    }

    /**
     * @param pos BlockPos to check for
     * @return Whether that block is considered unreachable
     */
    public boolean unreachable(BlockPos pos) {
        synchronized (_scanMutex) {
            return currentCache().blockUnreachable(pos);
        }
    }

    /**
     * Inform the block tracker that the bot was NOT able to reach a block.
     * @param pos block that we were unable to reach
     * @param allowedFailures how many times we can try reaching before we finally declare this block "unreachable"
     */
    public void requestBlockUnreachable(BlockPos pos, int allowedFailures) {
        synchronized (_scanMutex) {
            currentCache().blacklistBlockUnreachable(_mod, pos, allowedFailures);
        }
    }

    public void requestBlockUnreachable(BlockPos pos) {
        requestBlockUnreachable(pos, _config.defaultUnreachableAttemptsAllowed);
    }

    private PosCache currentCache() {
        Dimension dimension = WorldHelper.getCurrentDimension();
        if (!_caches.containsKey(dimension)) {
            _caches.put(dimension, new PosCache());
        }
        return _caches.get(dimension);
    }


    static class PosCache {
        private final HashMap<Block, List<BlockPos>> _cachedBlocks = new HashMap<>();

        private final HashMap<BlockPos, Block> _cachedByPosition = new HashMap<>();

        private final WorldLocateBlacklist _blacklist = new WorldLocateBlacklist();
        private final long _traceCacheIdentity = NEXT_POS_CACHE_TRACE_ID.getAndIncrement();
        private long _traceSequence;
        private int _purgeTraceRecords;
        private int _ingestionTraceRecords;
        private boolean _sourceManifestRecorded;
        private boolean _purgeTraceCapReported;
        private boolean _ingestionTraceCapReported;

        private long nextTraceSequence() {
            return ++_traceSequence;
        }

        private static String describeTraceBlock(Block block) {
            return block == null ? "none" : block.toString();
        }

        private int listOccurrences(Block block, BlockPos pos) {
            List<BlockPos> positions = _cachedBlocks.get(block);
            return positions == null ? 0 : Collections.frequency(positions, pos);
        }

        private String describeAddState(Block block, BlockPos pos) {
            return "listOccurrences=" + listOccurrences(block, pos)
                    + ",reverse=" + describeTraceBlock(_cachedByPosition.get(pos));
        }

        private int listSize(Block block) {
            List<BlockPos> positions = _cachedBlocks.get(block);
            return positions == null ? 0 : positions.size();
        }

        private String describeCoalSourceStates(Set<BlockPos> sources) {
            return sources.stream()
                    .sorted(Comparator.<BlockPos>comparingInt(BlockPos::getX)
                            .thenComparingInt(BlockPos::getY)
                            .thenComparingInt(BlockPos::getZ))
                    .map(pos -> pos + "{coalOccurrences=" + listOccurrences(Blocks.COAL_ORE, pos)
                            + ",deepslateCoalOccurrences=" + listOccurrences(Blocks.DEEPSLATE_COAL_ORE, pos)
                            + ",reverse=" + describeTraceBlock(_cachedByPosition.get(pos)) + "}")
                    .toList()
                    .toString();
        }

        private void recordSourceManifest(Set<BlockPos> sources) {
            if (_sourceManifestRecorded || sources.isEmpty()) return;
            String positions = sources.stream()
                    .sorted(Comparator.<BlockPos>comparingInt(BlockPos::getX)
                            .thenComparingInt(BlockPos::getY)
                            .thenComparingInt(BlockPos::getZ))
                    .toList()
                    .toString();
            Debug.logInternal("[KELP_COAL_CACHE_SOURCES] cache=" + _traceCacheIdentity
                    + ",seq=" + nextTraceSequence() + ",positions=" + positions);
            _sourceManifestRecorded = true;
        }

        private void recordIngestionTrace(Set<BlockPos> sources, Block block, BlockPos pos,
                                          String outcome, String before) {
            if (!sources.contains(pos)) return;
            recordSourceManifest(sources);
            if (_ingestionTraceRecords >= MAX_KELP_COAL_INGESTION_TRACE_RECORDS) {
                if (!_ingestionTraceCapReported) {
                    Debug.logInternal("[KELP_COAL_CACHE_INGESTION_CAP] cache=" + _traceCacheIdentity
                            + ",seq=" + nextTraceSequence()
                            + ",limit=" + MAX_KELP_COAL_INGESTION_TRACE_RECORDS);
                    _ingestionTraceCapReported = true;
                }
                return;
            }
            _ingestionTraceRecords++;
            Debug.logInternal("[KELP_COAL_CACHE_ADD] cache=" + _traceCacheIdentity
                    + ",seq=" + nextTraceSequence() + ",pos=" + pos + ",incoming=" + block
                    + ",outcome=" + outcome + ",before{" + before + "},after{"
                    + describeAddState(block, pos) + "}");
            if (_ingestionTraceRecords == MAX_KELP_COAL_INGESTION_TRACE_RECORDS) {
                Debug.logInternal("[KELP_COAL_CACHE_INGESTION_CAP] cache=" + _traceCacheIdentity
                        + ",seq=" + nextTraceSequence()
                        + ",limit=" + MAX_KELP_COAL_INGESTION_TRACE_RECORDS);
                _ingestionTraceCapReported = true;
            }
        }

        private void recordPurgeTrace(Set<BlockPos> sources, Vec3 playerPos,
                                      int reverseSizeBefore, int trackedCountBefore,
                                      int coalListBefore, int deepslateCoalListBefore,
                                      String sourceStatesBefore,
                                      int reverseSizeAfterGlobalPurge, int trackedCountAfterGlobalPurge,
                                      int coalListAfterGlobalPurge, int deepslateCoalListAfterGlobalPurge,
                                      String sourceStatesAfterGlobalPurge) {
            if (sources.isEmpty()) return;
            recordSourceManifest(sources);
            if (_purgeTraceRecords >= MAX_KELP_COAL_PURGE_TRACE_RECORDS) {
                if (!_purgeTraceCapReported) {
                    Debug.logInternal("[KELP_COAL_CACHE_PURGE_CAP] cache=" + _traceCacheIdentity
                            + ",seq=" + nextTraceSequence()
                            + ",limit=" + MAX_KELP_COAL_PURGE_TRACE_RECORDS);
                    _purgeTraceCapReported = true;
                }
                return;
            }
            _purgeTraceRecords++;
            Debug.logInternal("[KELP_COAL_CACHE_PURGE] cache=" + _traceCacheIdentity
                    + ",seq=" + nextTraceSequence() + ",origin=" + playerPos
                    + ",limits{total=" + _config.maxTotalCacheSize
                    + ",perType=" + _config.maxCacheSizePerBlockType + "}"
                    + ",before{reverse=" + reverseSizeBefore + ",tracked=" + trackedCountBefore
                    + ",coalList=" + coalListBefore + ",deepslateCoalList=" + deepslateCoalListBefore
                    + ",sources=" + sourceStatesBefore + "}"
                    + ",afterGlobalPurge{reverse=" + reverseSizeAfterGlobalPurge
                    + ",tracked=" + trackedCountAfterGlobalPurge
                    + ",coalList=" + coalListAfterGlobalPurge
                    + ",deepslateCoalList=" + deepslateCoalListAfterGlobalPurge
                    + ",sources=" + sourceStatesAfterGlobalPurge + "}"
                    + ",after{reverse=" + _cachedByPosition.size()
                    + ",tracked=" + getBlockTrackCount()
                    + ",coalList=" + listSize(Blocks.COAL_ORE)
                    + ",deepslateCoalList=" + listSize(Blocks.DEEPSLATE_COAL_ORE)
                    + ",sources=" + describeCoalSourceStates(sources) + "}");
            if (_purgeTraceRecords == MAX_KELP_COAL_PURGE_TRACE_RECORDS) {
                Debug.logInternal("[KELP_COAL_CACHE_PURGE_CAP] cache=" + _traceCacheIdentity
                        + ",seq=" + nextTraceSequence()
                        + ",limit=" + MAX_KELP_COAL_PURGE_TRACE_RECORDS);
                _purgeTraceCapReported = true;
            }
        }

        public boolean anyFound(Block... blocks) {
            for (Block block : blocks) {
                if (_cachedBlocks.containsKey(block)) return true;
            }
            return false;
        }

        public boolean anyFound(Predicate<BlockPos> isValidTest, Block... blocks) {
            for (Block block : blocks) {
                if (_cachedBlocks.containsKey(block)) {
                    for (BlockPos pos : _cachedBlocks.get(block)) {
                        if (isValidTest.test(pos)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        public List<BlockPos> getKnownLocations(Block... blocks) {
            List<BlockPos> result = new ArrayList<>();
            for (Block block : blocks) {
                List<BlockPos> found = _cachedBlocks.get(block);
                if (found != null) {
                    result.addAll(found);
                }
            }
            return result;
        }

        public void removeBlock(BlockPos pos, Block... blocks) {
            for (Block block : blocks) {
                if (_cachedBlocks.containsKey(block)) {
                    _cachedBlocks.get(block).remove(pos);
                    _cachedByPosition.remove(pos);
                    if (_cachedBlocks.get(block).size() == 0) {
                        _cachedBlocks.remove(block);
                    }
                }
            }
        }

        public void addBlock(Block block, BlockPos pos) {
            Set<BlockPos> traceSources = runtimeKelpCoalTracePositions();
            boolean traceSource = traceSources.contains(pos);
            String before = traceSource ? describeAddState(block, pos) : null;
            if (blockUnreachable(pos)) {
                if (traceSource) recordIngestionTrace(traceSources, block, pos, "skip-unreachable", before);
                return;
            }
            if (_cachedByPosition.containsKey(pos)) {
                if (_cachedByPosition.get(pos) == block) {
                    // We're already tracked
                    if (traceSource) recordIngestionTrace(traceSources, block, pos,
                            "skip-already-indexed", before);
                    return;
                } else {
                    // We're tracked incorrectly, fix
                    removeBlock(pos, block);
                }
            }
            if (!anyFound(block)) {
                _cachedBlocks.put(block, new ArrayList<>());
            }
            _cachedBlocks.get(block).add(pos);
            _cachedByPosition.put(pos, block);
            if (traceSource) recordIngestionTrace(traceSources, block, pos, "added", before);
        }


        public void clear() {
            Debug.logInternal("CLEARED BLOCK CACHE");
            _cachedBlocks.clear();
            _cachedByPosition.clear();
            _blacklist.clear();
        }

        public int getBlockTrackCount() {
            int count = 0;
            for (List<BlockPos> list : _cachedBlocks.values()) {
                count += list.size();
            }
            return count;
        }

        public void blacklistBlockUnreachable(AltoClef mod, BlockPos pos, int allowedFailures) {
            _blacklist.blackListItem(mod, pos, allowedFailures);
        }

        public boolean blockUnreachable(BlockPos pos) {
            return _blacklist.unreachable(pos);
        }

        // Gets nearest block. For now does linear search. In the future might optimize this a bit
        public Optional<BlockPos> getNearest(AltoClef mod, Vec3 position, Predicate<BlockPos> isValid, Block... blocks) {
            return getNearest(mod, position, isValid, null, blocks);
        }

        private Optional<BlockPos> getNearest(AltoClef mod, Vec3 position, Predicate<BlockPos> isValid,
                                              NearestQueryTrace[] trace, Block... blocks) {
            if (!anyFound(blocks)) {
                //Debug.logInternal("(failed cataloguecheck for " + block.getTranslationKey() + ")");
                if (trace != null) {
                    trace[0] = new NearestQueryTrace(List.of(), null,
                            Double.POSITIVE_INFINITY, "empty-cache");
                }
                return Optional.empty();
            }

            BlockPos closest = null;
            double minScore = Double.POSITIVE_INFINITY;

            List<BlockPos> blockList = getKnownLocations(blocks);
            List<NearestQueryCandidate> examined = trace == null ? null : new ArrayList<>(blockList.size());

            int toPurge = blockList.size() - _config.maxCacheSizePerBlockType;

            boolean closestPurged = false;

            for (BlockPos pos : blockList) {
                // If our current block isn't valid, fix it up. This cleans while we're iterating.
                String[] trackerRejection = trace == null ? null : new String[1];
                boolean trackerValid = mod.getBlockTracker().blockIsValid(pos,
                        trackerRejection == null ? null : reason -> trackerRejection[0] = reason,
                        blocks);
                if (!trackerValid) {
                    if (examined != null) {
                        examined.add(new NearestQueryCandidate(pos, false, trackerRejection[0],
                                false, Double.POSITIVE_INFINITY, false));
                    }
                    removeBlock(pos, blocks);
                    continue;
                }
                if (!isValid.test(pos)) {
                    if (examined != null) {
                        examined.add(new NearestQueryCandidate(pos, true,
                                trackerRejection == null ? null : trackerRejection[0],
                                false, Double.POSITIVE_INFINITY, false));
                    }
                    continue;
                }

                double score = BaritoneHelper.calculateGenericHeuristic(position, WorldHelper.toVec3d(pos));

                boolean currentlyClosest = false;
                boolean purged = false;

                if (score < minScore) {
                    minScore = score;
                    closest = pos;
                    currentlyClosest = true;
                }

                if (toPurge > 0) {
                    double sqDist = position.distanceToSqr(WorldHelper.toVec3d(pos));
                    if (sqDist > _config.cutoffDistance * _config.cutoffDistance) {
                        // cut this one off.
                        for (Block block : blocks) {
                            if (_cachedBlocks.containsKey(block)) {
                                removeBlock(pos, block);
                            }
                        }
                        toPurge--;
                        purged = true;
                    }
                }

                if (currentlyClosest) {
                    closestPurged = purged;
                }
                if (examined != null) {
                    examined.add(new NearestQueryCandidate(pos, true,
                            trackerRejection == null ? null : trackerRejection[0],
                            true, score, false));
                }
            }

            while (toPurge > 0) {
                if (blockList.size() == 0) {
                    //noinspection UnusedAssignment
                    toPurge = 0;
                    break;
                }
                blockList.remove(blockList.size() - 1);
                toPurge--;
            }

            // Special case: Our closest was purged. Add us back.
            if (closestPurged) {
                Debug.logInternal("Rare edge case: Closest block was purged cause it was real far away, it will now be added back.");
                blockList.add(closest);
            }

            if (trace != null) {
                List<NearestQueryCandidate> finalCandidates = new ArrayList<>(examined.size());
                for (NearestQueryCandidate candidate : examined) {
                    boolean selected = closest != null && closest.equals(candidate.position())
                            && candidate.taskFilterValid();
                    finalCandidates.add(new NearestQueryCandidate(candidate.position(),
                            candidate.trackerValid(), candidate.trackerDetail(),
                            candidate.taskFilterValid(), candidate.heuristic(), selected));
                }
                trace[0] = new NearestQueryTrace(List.copyOf(finalCandidates), closest, minScore,
                        closest == null ? "no-eligible-candidate" : "selected");
            }

            return Optional.ofNullable(closest);
        }

        /**
         * Purge enough blocks so our size is small enough
         */
        public void smartPurge(Vec3 playerPos) {
            Set<BlockPos> traceSources = runtimeKelpCoalTracePositions();
            boolean traceCoalSources = !traceSources.isEmpty();
            if (traceCoalSources) recordSourceManifest(traceSources);
            int reverseSizeBefore = _cachedByPosition.size();
            int trackedCountBefore = getBlockTrackCount();
            int coalListBefore = listSize(Blocks.COAL_ORE);
            int deepslateCoalListBefore = listSize(Blocks.DEEPSLATE_COAL_ORE);
            String sourceStatesBefore = traceCoalSources
                    ? describeCoalSourceStates(traceSources) : "";

            // Clear cached by position blocks, as they can be a handful.
            try {
                int MAX_CACHE_SIZE = _config.maxTotalCacheSize;
                if (_cachedByPosition.size() > MAX_CACHE_SIZE) {
                    List<BlockPos> toRemoveList = new ArrayList<>(_cachedByPosition.size() - MAX_CACHE_SIZE);
                    // Just purge randomly.
                    for (BlockPos pos : _cachedByPosition.keySet()) {
                        if (_cachedByPosition.size() - toRemoveList.size() < MAX_CACHE_SIZE) {
                            break;
                        }
                        toRemoveList.add(pos);
                    }
                    for (BlockPos toDelete : toRemoveList) {
                        _cachedByPosition.remove(toDelete);
                    }
                }
            } catch (Exception e) {
                Debug.logWarning("Failed to purge/reduce _cachedByPosition cache.: Its size remains at " + _cachedByPosition.size());
            }

            int reverseSizeAfterGlobalPurge = _cachedByPosition.size();
            int trackedCountAfterGlobalPurge = getBlockTrackCount();
            int coalListAfterGlobalPurge = listSize(Blocks.COAL_ORE);
            int deepslateCoalListAfterGlobalPurge = listSize(Blocks.DEEPSLATE_COAL_ORE);
            String sourceStatesAfterGlobalPurge = traceCoalSources
                    ? describeCoalSourceStates(traceSources) : "";

            // The total-count purge above still needs to reconcile reverse-index
            // removals with _cachedBlocks; this per-type cap does so below.

            for (Block block : _cachedBlocks.keySet()) {
                List<BlockPos> previousTracking = _cachedBlocks.get(block);
                List<BlockPos> tracking = previousTracking;

                // Clear blacklisted blocks
                try {
                    // Untrack the blocks further away
                    tracking = tracking.stream()
                            .filter(pos -> !_blacklist.unreachable(pos))
                            // This is invalid, because some blocks we may want to GO TO not BREAK.
                            //.filter(pos -> !mod.getExtraBaritoneSettings().shouldAvoidBreaking(pos))
                            .distinct()
                            .sorted(StlHelper.compareValues((BlockPos blockpos) -> blockpos.distToCenterSqr(playerPos)))
                            .collect(Collectors.toList());
                    tracking = tracking.stream()
                            .limit(_config.maxCacheSizePerBlockType)
                            .collect(Collectors.toList());

                    // A later world scan calls addBlock again for any rediscovered
                    // position. Remove the reverse entry for positions dropped by
                    // this per-type limit so addBlock can cache them again.
                    Set<BlockPos> retainedPositions = new HashSet<>(tracking);
                    for (BlockPos previous : previousTracking) {
                        if (!retainedPositions.contains(previous)) {
                            _cachedByPosition.remove(previous, block);
                        }
                    }

                    _cachedBlocks.put(block, tracking);
                } catch (IllegalArgumentException e) {
                    // Comparison method violates its general contract: Sometimes transitivity breaks.
                    // In which case, ignore it.
                    Debug.logWarning("Failed to purge/reduce block search count for " + block + ": It remains at " + tracking.size());
                }
            }
            if (traceCoalSources) {
                recordPurgeTrace(traceSources, playerPos, reverseSizeBefore, trackedCountBefore,
                        coalListBefore, deepslateCoalListBefore, sourceStatesBefore,
                        reverseSizeAfterGlobalPurge, trackedCountAfterGlobalPurge,
                        coalListAfterGlobalPurge, deepslateCoalListAfterGlobalPurge,
                        sourceStatesAfterGlobalPurge);
            }
        }
    }

    static class BlockTrackerConfig {
        public double scanInterval = 7;
        public double scanIntervalWhenNewBlocksFound = 2;
        public boolean scanAsynchronously = true;
        public int maxTotalCacheSize = 10000;
        public int maxCacheSizePerBlockType = 100;
        public double cutoffDistance = 64*2;
        public int defaultUnreachableAttemptsAllowed = 4;
    }
}
