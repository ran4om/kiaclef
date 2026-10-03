package adris.altoclef.tasks.resources;

import adris.altoclef.util.helpers.ItemCapabilities;
import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.AbstractDoToClosestObjectTask;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasks.slot.EnsureFreeInventorySlotTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.trackers.BlockTracker;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.time.TimerGame;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import adris.altoclef.util.slots.CursorSlot;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

public class MineAndCollectTask extends ResourceTask {

    private final Block[] _blocksToMine;

    private final MiningRequirement _requirement;

    private final TimerGame _cursorStackTimer = new TimerGame(3);

    private final MineOrCollectTask _subtask;

    public MineAndCollectTask(ItemTarget[] itemTargets, Block[] blocksToMine, MiningRequirement requirement) {
        this(itemTargets, blocksToMine, requirement, state -> true);
    }

    protected MineAndCollectTask(ItemTarget[] itemTargets, Block[] blocksToMine, MiningRequirement requirement,
                                 Predicate<BlockState> blockStatePredicate) {
        super(itemTargets);
        _requirement = requirement;
        _blocksToMine = blocksToMine;
        _subtask = new MineOrCollectTask(_blocksToMine, _itemTargets, blockStatePredicate);
    }

    public MineAndCollectTask(ItemTarget[] blocksToMine, MiningRequirement requirement) {
        this(blocksToMine, itemTargetToBlockList(blocksToMine), requirement);
    }

    public MineAndCollectTask(ItemTarget target, Block[] blocksToMine, MiningRequirement requirement) {
        this(new ItemTarget[]{target}, blocksToMine, requirement);
    }

    public MineAndCollectTask(Item item, int count, Block[] blocksToMine, MiningRequirement requirement) {
        this(new ItemTarget(item, count), blocksToMine, requirement);
    }

    public static Block[] itemTargetToBlockList(ItemTarget[] targets) {
        List<Block> result = new ArrayList<>(targets.length);
        for (ItemTarget target : targets) {
            for (Item item : target.getMatches()) {
                Block block = Block.byItem(item);
                if (block != null && !WorldHelper.isAir(block)) {
                    result.add(block);
                }
            }
        }
        return result.toArray(Block[]::new);
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
        mod.getBehaviour().push();
        mod.getBlockTracker().trackBlock(_blocksToMine);

        // We're mining, so don't throw away pickaxes.
        mod.getBehaviour().addProtectedItems(Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE);

        _subtask.resetSearch();
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        // Picking up is controlled by a separate task here.
        return true;
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {

        if (!StorageHelper.miningRequirementMet(mod, _requirement)) {
            return new SatisfyMiningRequirementTask(_requirement);
        }

        if (_subtask.isMining()) {
            makeSureToolIsEquipped(mod);
        }

        // Wrong dimension check.
        if (_subtask.wasWandering() && isInWrongDimension(mod)) {
            return getToCorrectDimensionTask(mod);
        }

        return _subtask;
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(_blocksToMine);
        mod.getBehaviour().pop();
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        if (other instanceof MineAndCollectTask task) {
            return Arrays.equals(task._blocksToMine, _blocksToMine);
        }
        return false;
    }

    @Override
    protected String toDebugStringName() {
        return "Mine And Collect";
    }

    private void makeSureToolIsEquipped(AltoClef mod) {
        if (_cursorStackTimer.elapsed() && !mod.getFoodChain().isTryingToEat()) {
            assert Minecraft.getInstance().player != null;
            ItemStack cursorStack = StorageHelper.getItemStackInCursorSlot();
            if (cursorStack != null && !cursorStack.isEmpty()) {
                // We have something in our cursor stack
                Item item = cursorStack.getItem();
                if (cursorStack.isCorrectToolForDrops(mod.getWorld().getBlockState(_subtask.miningPos()))) {
                    // Our cursor stack would help us mine our current block
                    Item currentlyEquipped = StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot()).getItem();
                    if (ItemCapabilities.isTool(item)) {
                        if (ItemCapabilities.isTool(currentlyEquipped)) {
                            Item swapPick = item;
                            if (ItemCapabilities.miningLevel(swapPick) > ItemCapabilities.miningLevel(currentlyEquipped)) {
                                // We can equip a better pickaxe.
                                mod.getSlotHandler().forceEquipSlot(CursorSlot.SLOT);
                            }
                        } else {
                            // We're not equipped with a pickaxe...
                            mod.getSlotHandler().forceEquipSlot(CursorSlot.SLOT);
                        }
                    }
                }
            }
            _cursorStackTimer.reset();
        }
    }

    private static class MineOrCollectTask extends AbstractDoToClosestObjectTask<Object> {

        private static final String KELP_COAL_POSITIONS_PROPERTY =
                "altoclef.runtimeTest.kelpCoalSourcePositions";
        private static final long KELP_COAL_TRACE_INTERVAL_TICKS = 200;
        private static final int MAX_KELP_COAL_TRACE_EVENTS = 256;
        private static final AtomicInteger KELP_COAL_TRACE_EVENT_COUNT = new AtomicInteger();
        private static final AtomicBoolean KELP_COAL_TRACE_CAP_REPORTED = new AtomicBoolean();

        private final Block[] _blocks;
        private final ItemTarget[] _targets;
        private final Predicate<BlockState> _blockStatePredicate;
        private final Set<BlockPos> _blacklist = new HashSet<>();
        private final MovementProgressChecker _progressChecker = new MovementProgressChecker(1);
        private final Task _pickupTask;
        private BlockPos _miningPos;
        private AltoClef _mod;
        private boolean _kelpCoalTraceEnabled;
        private Set<BlockPos> _kelpCoalTraceSources = Set.of();
        private long _lastKelpCoalTraceTick = Long.MIN_VALUE;
        private String _lastKelpCoalTraceSignature;
        private long _lastKelpCoalSelectionTick = Long.MIN_VALUE;
        private Object _lastKelpCoalSelected;
        private Object _lastKelpCoalPursuitAtQuery;
        private String _lastKelpCoalPursuitSignature;

        public MineOrCollectTask(Block[] blocks, ItemTarget[] targets,
                                 Predicate<BlockState> blockStatePredicate) {
            _blocks = blocks;
            _targets = targets;
            _blockStatePredicate = blockStatePredicate;
            _pickupTask = new PickupDroppedItemTask(_targets, true);
        }

        @Override
        protected Vec3 getPos(AltoClef mod, Object obj) {
            if (obj instanceof BlockPos b) {
                return WorldHelper.toVec3d(b);
            }
            if (obj instanceof ItemEntity item) {
                return item.position();
            }
            throw new UnsupportedOperationException("Shouldn't try to get the position of object " + obj + " of type " + (obj != null ? obj.getClass().toString() : "(null object)"));
        }

        @Override
        protected Optional<Object> getClosestTo(AltoClef mod, Vec3 pos) {
            boolean traceCoal = _kelpCoalTraceEnabled
                    && KELP_COAL_TRACE_EVENT_COUNT.get() < MAX_KELP_COAL_TRACE_EVENTS;
            BlockTracker.NearestQueryTrace[] blockQueryTrace = traceCoal
                    ? new BlockTracker.NearestQueryTrace[1]
                    : null;
            Map<BlockPos, String> sourceFilterResults = traceCoal ? new HashMap<>() : Map.of();
            Optional<BlockPos> closestBlock = mod.getBlockTracker().getNearestTrackingWithDiagnostics(pos, check -> {
                if (_blacklist.contains(check)) {
                    if (traceCoal) sourceFilterResults.put(check, "localBlacklist=true");
                    return false;
                }
                boolean statePredicateMatches = _blockStatePredicate.test(mod.getWorld().getBlockState(check));
                if (!statePredicateMatches) {
                    if (traceCoal) sourceFilterResults.put(check,
                            "localBlacklist=false,statePredicate=false");
                    return false;
                }
                boolean canBreak = WorldHelper.canBreak(mod, check);
                if (traceCoal) {
                    sourceFilterResults.put(check, "localBlacklist=false,statePredicate=true,canBreak=" + canBreak);
                }
                return canBreak;
            }, blockQueryTrace == null ? null : trace -> blockQueryTrace[0] = trace, _blocks);

            Optional<ItemEntity> closestDrop = Optional.empty();
            if (mod.getEntityTracker().itemDropped(_targets)) {
                closestDrop = mod.getEntityTracker().getClosestItemDrop(pos, _targets);
            }

            double blockSq = closestBlock.isEmpty() ? Double.POSITIVE_INFINITY : closestBlock.get().distToCenterSqr(pos);
            double dropSq = closestDrop.isEmpty() ? Double.POSITIVE_INFINITY : closestDrop.get().distanceToSqr(pos) + 5; // + 5 to make the bot stop mining a bit less

            // We can't mine right now.
            Object selected;
            String selectionReason;
            if (mod.getExtraBaritoneSettings().isInteractionPaused()) {
                selected = closestDrop.orElse(null);
                selectionReason = "interaction-paused";
            } else if (dropSq <= blockSq) {
                selected = closestDrop.orElse(null);
                selectionReason = "drop-distance";
            } else {
                selected = closestBlock.orElse(null);
                selectionReason = "block-distance";
            }

            if (traceCoal) {
                _lastKelpCoalSelectionTick = mod.getWorld().getGameTime();
                _lastKelpCoalSelected = selected;
                _lastKelpCoalPursuitAtQuery = getCurrentPursuit();
                traceKelpCoalSelection(mod, pos, blockQueryTrace[0], sourceFilterResults,
                        closestBlock.orElse(null), closestDrop.orElse(null), blockSq, dropSq,
                        selected, selectionReason, _lastKelpCoalPursuitAtQuery);
            }
            return Optional.ofNullable(selected);
        }

        @Override
        protected Vec3 getOriginPos(AltoClef mod) {
            return mod.getPlayer().position();
        }

        @Override
        protected Task onTick(AltoClef mod) {
            _mod = mod;
            Object pursuitBefore = getCurrentPursuit();
            if (_kelpCoalTraceEnabled) reportKelpTraceCapIfNeeded();
            if (_miningPos != null && !_progressChecker.check(mod)) {
                Debug.logMessage("Failed to mine block. Suggesting it may be unreachable.");
                mod.getBlockTracker().requestBlockUnreachable(_miningPos, 2);
                _blacklist.add(_miningPos);
                _miningPos = null;
                _progressChecker.reset();
            }
            Task next = super.onTick(mod);
            Object pursuitAfter = getCurrentPursuit();
            long tick = mod.getWorld().getGameTime();
            if (_kelpCoalTraceEnabled && _lastKelpCoalSelectionTick == tick) {
                boolean selectedMismatch = !Objects.equals(_lastKelpCoalSelected, pursuitAfter);
                boolean pursuitChanged = !Objects.equals(pursuitBefore, pursuitAfter);
                String signature = describeCoalTarget(_lastKelpCoalSelected) + ":"
                        + describeCoalTarget(_lastKelpCoalPursuitAtQuery) + ":"
                        + describeCoalTarget(pursuitBefore) + ":"
                        + describeCoalTarget(pursuitAfter) + ":" + selectedMismatch + ":" + pursuitChanged;
                boolean pursuitSignatureChanged = !Objects.equals(signature, _lastKelpCoalPursuitSignature);
                _lastKelpCoalPursuitSignature = signature;
                if ((selectedMismatch || pursuitChanged) && pursuitSignatureChanged
                        && reserveKelpTraceEvent()) {
                    Debug.logMessage("[KELP_COAL_PURSUIT] tick=" + tick
                            + ",selected=" + describeCoalTarget(_lastKelpCoalSelected)
                            + ",pursuitAtQuery=" + describeCoalTarget(_lastKelpCoalPursuitAtQuery)
                            + ",pursuitBeforeTick=" + describeCoalTarget(pursuitBefore)
                            + ",pursuitAfterTick=" + describeCoalTarget(pursuitAfter)
                            + ",destroyTarget=" + describeCoalTarget(
                                    next instanceof DestroyBlockTask ? _miningPos : null)
                            + ",selectionMismatch=" + selectedMismatch
                            + ",nextTask=" + (next == null ? "none" : next.getClass().getSimpleName()));
                }
            }
            return next;
        }

        @Override
        protected Task getGoalTask(Object obj) {
            if (obj instanceof BlockPos newPos) {
                if (_miningPos == null || !_miningPos.equals(newPos)) {
                    _progressChecker.reset();
                }
                _miningPos = newPos;
                return new DestroyBlockTask(_miningPos);
            }
            if (obj instanceof ItemEntity itemEntity) {
                _miningPos = null;

                if (_mod.getItemStorage().getSlotThatCanFitInPlayerInventory(itemEntity.getItem(), false).or(() -> StorageHelper.getGarbageSlot(_mod)).isEmpty()) {
                    return new EnsureFreeInventorySlotTask();
                }

                return _pickupTask;
            }
            throw new UnsupportedOperationException("Shouldn't try to get the goal from object " + obj + " of type " + (obj != null ? obj.getClass().toString() : "(null object)"));
        }

        @Override
        protected boolean isValid(AltoClef mod, Object obj) {
            if (obj instanceof BlockPos b) {
                return mod.getBlockTracker().blockIsValid(b, _blocks)
                        && _blockStatePredicate.test(mod.getWorld().getBlockState(b))
                        && WorldHelper.canBreak(mod, b);
            }
            if (obj instanceof ItemEntity drop) {
                Item item = drop.getItem().getItem();
                for (ItemTarget target : _targets) {
                    if (target.matches(item)) return true;
                }
                return false;
            }
            return false;
        }

        @Override
        protected void onStart(AltoClef mod) {
            _progressChecker.reset();
            _miningPos = null;
            _kelpCoalTraceEnabled = Boolean.getBoolean("altoclef.runtimeTest")
                    && "kelp".equalsIgnoreCase(System.getProperty("altoclef.runtimeStart"))
                    && Arrays.stream(_blocks).anyMatch(block -> block == Blocks.COAL_ORE
                    || block == Blocks.DEEPSLATE_COAL_ORE);
            _kelpCoalTraceSources = _kelpCoalTraceEnabled
                    ? parseCoalTraceSources(System.getProperty(KELP_COAL_POSITIONS_PROPERTY))
                    : Set.of();
            _lastKelpCoalTraceTick = Long.MIN_VALUE;
            _lastKelpCoalTraceSignature = null;
            _lastKelpCoalSelectionTick = Long.MIN_VALUE;
            _lastKelpCoalSelected = null;
            _lastKelpCoalPursuitAtQuery = null;
            _lastKelpCoalPursuitSignature = null;
        }

        private void traceKelpCoalSelection(AltoClef mod, Vec3 origin,
                                            BlockTracker.NearestQueryTrace queryTrace,
                                            Map<BlockPos, String> sourceFilterResults,
                                            BlockPos nearestBlock, ItemEntity nearestDrop,
                                            double blockDistanceSq, double dropDistanceSq,
                                            Object selected, String selectionReason,
                                            Object pursuitAtQuery) {
            long tick = mod.getWorld().getGameTime();
            String signature = buildCoalTraceSignature(queryTrace, sourceFilterResults,
                    nearestBlock, nearestDrop, selected, pursuitAtQuery);
            boolean firstOrPeriodic = _lastKelpCoalTraceTick == Long.MIN_VALUE
                    || tick - _lastKelpCoalTraceTick >= KELP_COAL_TRACE_INTERVAL_TICKS;
            boolean stateChanged = !Objects.equals(signature, _lastKelpCoalTraceSignature);
            if ((!firstOrPeriodic && !stateChanged) || !reserveKelpTraceEvent()) return;

            Map<BlockPos, BlockTracker.NearestQueryCandidate> candidates = new HashMap<>();
            for (BlockTracker.NearestQueryCandidate candidate : queryTrace.candidates()) {
                candidates.put(candidate.position(), candidate);
            }

            List<BlockPos> orderedSources = _kelpCoalTraceSources.stream()
                    .sorted(Comparator.<BlockPos>comparingInt(BlockPos::getX)
                            .thenComparingInt(BlockPos::getY)
                            .thenComparingInt(BlockPos::getZ))
                    .toList();
            List<String> sourceStates = new ArrayList<>(orderedSources.size());
            String missingCandidateState = switch (queryTrace.stopReason()) {
                case "block-not-tracked", "trace-missing" -> "not-examined";
                default -> "not-cached";
            };
            for (BlockPos source : orderedSources) {
                BlockTracker.NearestQueryCandidate candidate = candidates.get(source);
                String cacheState = candidate == null ? missingCandidateState : "cached";
                String trackerState = candidate == null ? "not-evaluated"
                        : candidate.trackerValid() ? "valid" + (candidate.trackerDetail() == null ? ""
                        : "(" + candidate.trackerDetail() + ")")
                        : "rejected:" + candidate.trackerDetail();
                String filter = candidate == null || !candidate.trackerValid() ? "not-evaluated"
                        : sourceFilterResults.getOrDefault(source, "not-evaluated");
                String heuristic = candidate != null && candidate.trackerValid() && candidate.taskFilterValid()
                        ? Double.toString(candidate.heuristic()) : "not-scored";
                sourceStates.add(source + "{cache=" + cacheState
                        + ",tracker=" + trackerState
                        + ",block=" + mod.getWorld().getBlockState(source)
                        + ",taskFilter=" + filter
                        + ",heuristic=" + heuristic
                        + ",trackerSelected=" + (candidate != null && candidate.selected()) + "}");
            }

            _lastKelpCoalTraceTick = tick;
            _lastKelpCoalTraceSignature = signature;
            Debug.logMessage("[KELP_COAL_SELECTION] tick=" + tick
                    + ",origin=" + origin
                    + ",queryCandidates=" + queryTrace.candidates().size()
                    + ",queryStopReason=" + queryTrace.stopReason()
                    + ",trackerWinner=" + describeCoalTarget(queryTrace.selected())
                    + ",trackerWinnerHeuristic=" + Double.toString(queryTrace.selectedHeuristic())
                    + ",nearestBlock=" + describeCoalTarget(nearestBlock)
                    + ",nearestBlockHeuristic=" + (nearestBlock == null ? "infinity"
                    : Double.toString(queryTrace.selectedHeuristic()))
                    + ",blockDistanceSq=" + blockDistanceSq
                    + ",nearestDrop=" + describeCoalTarget(nearestDrop)
                    + ",dropDistanceSqWithBias=" + dropDistanceSq
                    + ",nearestBlockTaskFilter=" + (nearestBlock == null ? "none"
                    : sourceFilterResults.getOrDefault(nearestBlock, "not-evaluated"))
                    + ",selected=" + describeCoalTarget(selected)
                    + ",reason=" + selectionReason
                    + ",pursuitAtQuery=" + describeCoalTarget(pursuitAtQuery)
                    + ",seededSources=" + sourceStates);
        }

        private String buildCoalTraceSignature(BlockTracker.NearestQueryTrace queryTrace,
                                               Map<BlockPos, String> sourceFilterResults,
                                               BlockPos nearestBlock, ItemEntity nearestDrop,
                                               Object selected, Object pursuitAtQuery) {
            Map<BlockPos, BlockTracker.NearestQueryCandidate> candidates = new HashMap<>();
            for (BlockTracker.NearestQueryCandidate candidate : queryTrace.candidates()) {
                candidates.put(candidate.position(), candidate);
            }
            List<BlockPos> orderedSources = _kelpCoalTraceSources.stream()
                    .sorted(Comparator.<BlockPos>comparingInt(BlockPos::getX)
                            .thenComparingInt(BlockPos::getY)
                            .thenComparingInt(BlockPos::getZ))
                    .toList();
            StringBuilder signature = new StringBuilder();
            String missingCandidateState = switch (queryTrace.stopReason()) {
                case "block-not-tracked", "trace-missing" -> "not-examined";
                default -> "not-cached";
            };
            for (BlockPos source : orderedSources) {
                BlockTracker.NearestQueryCandidate candidate = candidates.get(source);
                signature.append(source).append(':').append(candidate == null ? missingCandidateState
                                : candidate.trackerValid() ? "tracker-valid:" + candidate.trackerDetail()
                                : "tracker-rejected:" + candidate.trackerDetail())
                        .append(':').append(candidate == null || !candidate.trackerValid() ? "not-evaluated"
                                : sourceFilterResults.getOrDefault(source, "not-evaluated"))
                        .append(';');
            }
            return signature.append("queryStop=").append(queryTrace.stopReason())
                    .append(";trackerWinner=").append(describeCoalTarget(queryTrace.selected()))
                    .append(";block=").append(describeCoalTarget(nearestBlock))
                    .append(";blockFilter=").append(nearestBlock == null ? "none"
                            : sourceFilterResults.getOrDefault(nearestBlock, "not-evaluated"))
                    .append(";drop=").append(describeCoalTarget(nearestDrop))
                    .append(";selected=").append(describeCoalTarget(selected))
                    .append(";pursuitAtQuery=").append(describeCoalTarget(pursuitAtQuery)).toString();
        }

        private static boolean reserveKelpTraceEvent() {
            int event = KELP_COAL_TRACE_EVENT_COUNT.getAndIncrement();
            if (event < MAX_KELP_COAL_TRACE_EVENTS) return true;
            if (event == MAX_KELP_COAL_TRACE_EVENTS
                    && KELP_COAL_TRACE_CAP_REPORTED.compareAndSet(false, true)) {
                Debug.logMessage("[KELP_COAL_TRACE_CAP_REACHED] limit=" + MAX_KELP_COAL_TRACE_EVENTS);
            }
            return false;
        }

        private static void reportKelpTraceCapIfNeeded() {
            if (KELP_COAL_TRACE_EVENT_COUNT.get() >= MAX_KELP_COAL_TRACE_EVENTS
                    && KELP_COAL_TRACE_CAP_REPORTED.compareAndSet(false, true)) {
                Debug.logMessage("[KELP_COAL_TRACE_CAP_REACHED] limit=" + MAX_KELP_COAL_TRACE_EVENTS);
            }
        }

        private static Set<BlockPos> parseCoalTraceSources(String encoded) {
            if (encoded == null || encoded.isBlank()) return Set.of();
            Set<BlockPos> positions = new HashSet<>();
            for (String entry : encoded.split(";")) {
                String[] coordinates = entry.split(",");
                if (coordinates.length != 3) continue;
                try {
                    positions.add(new BlockPos(Integer.parseInt(coordinates[0]),
                            Integer.parseInt(coordinates[1]), Integer.parseInt(coordinates[2])));
                } catch (NumberFormatException ignored) {
                    // A malformed test-only diagnostic coordinate must not affect task behavior.
                }
            }
            return Set.copyOf(positions);
        }

        private static String describeCoalTarget(Object target) {
            if (target == null) return "none";
            if (target instanceof BlockPos position) return "block@" + position;
            if (target instanceof ItemEntity drop) {
                return "drop@" + drop.blockPosition() + "/" + drop.getItem().getItem();
            }
            return target.getClass().getSimpleName();
        }

        @Override
        protected void onStop(AltoClef mod, Task interruptTask) {

        }

        @Override
        protected boolean isEqual(Task other) {
            if (other instanceof MineOrCollectTask task) {
                return Arrays.equals(task._blocks, _blocks) && Arrays.equals(task._targets, _targets);
            }
            return false;
        }

        @Override
        protected String toDebugString() {
            return "Mining or Collecting";
        }

        public boolean isMining() {
            return _miningPos != null;
        }

        public BlockPos miningPos() {
            return _miningPos;
        }
    }

}
