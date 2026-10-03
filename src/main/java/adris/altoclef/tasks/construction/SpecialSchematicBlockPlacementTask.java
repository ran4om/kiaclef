package adris.altoclef.tasks.construction;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskFailure;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.CursorSlot;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.process.ICustomGoalProcess;
import baritone.api.schematic.IStaticSchematic;
import baritone.api.utils.input.Input;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

/** Places paired doors and double slabs through normal player interactions before Baritone builds a batch. */
final class SpecialSchematicBlockPlacementTask extends Task {
    private final List<Placement> _placements;
    private final BlockPos _origin;
    private final IStaticSchematic _authoritativeSchematic;
    private final boolean _restoreOnlyIntoAir;
    private final boolean _allowExistingPartialSlab;
    private final Set<BlockPos> _temporarilyRemovedPositions = new HashSet<>();
    private int _index;
    private Task _doorPlacement;
    private int _ticksForCurrent;
    private boolean _failed;
    private String _failureReason;
    private boolean _startedSlabFromAir;

    SpecialSchematicBlockPlacementTask(IStaticSchematic batch, BlockPos origin, Set<BlockPos> skippedLocalPositions) {
        this(batch, origin, skippedLocalPositions, batch);
    }

    SpecialSchematicBlockPlacementTask(IStaticSchematic batch, BlockPos origin,
                                       Set<BlockPos> skippedLocalPositions,
                                       IStaticSchematic authoritativeSchematic) {
        this(batch, origin, skippedLocalPositions, authoritativeSchematic, false);
    }

    SpecialSchematicBlockPlacementTask(IStaticSchematic batch, BlockPos origin,
                                       Set<BlockPos> skippedLocalPositions,
                                       IStaticSchematic authoritativeSchematic,
                                       boolean restoreOnlyIntoAir) {
        this(batch, origin, skippedLocalPositions, authoritativeSchematic, restoreOnlyIntoAir, false);
    }

    SpecialSchematicBlockPlacementTask(IStaticSchematic batch, BlockPos origin,
                                       Set<BlockPos> skippedLocalPositions,
                                       IStaticSchematic authoritativeSchematic,
                                       boolean restoreOnlyIntoAir,
                                       boolean allowExistingPartialSlab) {
        _placements = collect(batch, origin, skippedLocalPositions);
        _origin = origin;
        _authoritativeSchematic = authoritativeSchematic;
        _restoreOnlyIntoAir = restoreOnlyIntoAir;
        _allowExistingPartialSlab = allowExistingPartialSlab;
    }

    @Override
    protected void onStart(AltoClef mod) {
        _index = 0;
        _doorPlacement = null;
        _ticksForCurrent = 0;
        _failed = false;
        _failureReason = null;
        _startedSlabFromAir = false;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return _failed || _index >= _placements.size();
    }

    boolean hasFailed() {
        return _failed;
    }

    String getFailureReason() {
        return _failureReason;
    }

    Set<BlockPos> getTemporarilyRemovedPositions() {
        return Set.copyOf(_temporarilyRemovedPositions);
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (isFinished(mod)) return null;
        Placement placement = _placements.get(_index);
        _ticksForCurrent++;
        if (_ticksForCurrent > 2400) {
            fail("Timed out preparing " + placement.block().getName().getString()
                    + " at " + placement.position().toShortString() + ".");
            return null;
        }

        if (placement.kind() == Kind.DOOR) {
            BlockState actualLower = mod.getWorld().getBlockState(placement.position());
            BlockState actualUpper = mod.getWorld().getBlockState(placement.position().above());
            if ((!actualLower.isAir() || !actualUpper.isAir())
                    && !isExpectedDoorPair(placement.state(), placement.upperState(), actualLower, actualUpper)) {
                BlockPos obstructing = !actualLower.isAir()
                        ? placement.position() : placement.position().above();
                setDebugState("Clearing the schematic door target at " + obstructing.toShortString());
                return new DestroyBlockTask(obstructing);
            }
            if (_doorPlacement == null) {
                _doorPlacement = new PlaceSchematicDoorTask(
                        placement.position(), placement.state(), placement.upperState(), _origin,
                        _authoritativeSchematic, _temporarilyRemovedPositions, mod.getWorld());
            }
            if (_doorPlacement.isFinished(mod)) {
                if (_doorPlacement instanceof PlaceSchematicDoorTask doorTask && doorTask.hasFailed()) {
                    fail(doorTask.getFailureReason());
                    return null;
                }
                advance();
                return null;
            }
            TaskFailure.Snapshot childFailure = unhandledChildFailure(_doorPlacement, mod);
            if (childFailure != null) {
                fail(failureReason(childFailure));
                return null;
            }
            return _doorPlacement;
        }

        BlockState actual = mod.getWorld().getBlockState(placement.position());
        if (actual.equals(placement.state())) {
            advance();
            return null;
        }
        if (_restoreOnlyIntoAir && !mayRestoreOnlyIntoAir(
                actual, placement.state(), _allowExistingPartialSlab || _startedSlabFromAir)) {
            fail("Skipped restoration at " + placement.position().toShortString()
                    + " because the authored target became occupied by " + actual + ".");
            return null;
        }
        if (actual.getBlock() == placement.block()
                && actual.hasProperty(SlabBlock.TYPE)
                && actual.getValue(SlabBlock.TYPE) != SlabType.DOUBLE) {
            Item item = placement.block().asItem();
            if (!StorageHelper.itemTargetsMetAccessibleInventory(mod, new ItemTarget(item, 1))) {
                setDebugState("Gathering the second slab for " + placement.position().toShortString());
                return TaskCatalogue.getItemTask(new ItemTarget(item, 1));
            }
            Direction face = actual.getValue(SlabBlock.TYPE) == SlabType.BOTTOM
                    ? Direction.UP : Direction.DOWN;
            setDebugState("Merging the second slab at " + placement.position().toShortString());
            // Sneaking bypasses block interaction and would place beside the existing slab.
            return new InteractWithBlockTask(item, face, placement.position(), Input.CLICK_RIGHT, false, false);
        }
        if (!actual.isAir()) {
            setDebugState("Clearing the double slab target at " + placement.position().toShortString());
            return new DestroyBlockTask(placement.position());
        }
        if (actual.isAir()) {
            Item item = placement.block().asItem();
            if (!StorageHelper.itemTargetsMetAccessibleInventory(mod, new ItemTarget(item, 2))) {
                setDebugState("Gathering both slabs for " + placement.position().toShortString());
                return TaskCatalogue.getItemTask(new ItemTarget(item, 2));
            }
            setDebugState("Placing the first slab at " + placement.position().toShortString());
            _startedSlabFromAir = _restoreOnlyIntoAir;
            return new PlaceBlockTask(placement.position(), placement.block());
        }

        fail("Cannot form the requested double slab at " + placement.position().toShortString()
                + ": the target is occupied by " + actual + ".");
        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        if (_doorPlacement != null && !_doorPlacement.isFinished(mod)) {
            _doorPlacement.stop(mod, interruptTask);
        }
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof SpecialSchematicBlockPlacementTask task
                && _placements.equals(task._placements)
                && _origin.equals(task._origin)
                && _authoritativeSchematic == task._authoritativeSchematic
                && _restoreOnlyIntoAir == task._restoreOnlyIntoAir
                && _allowExistingPartialSlab == task._allowExistingPartialSlab;
    }

    @Override
    protected String toDebugString() {
        return "Prepare paired schematic blocks";
    }

    private void advance() {
        _index++;
        _ticksForCurrent = 0;
        _doorPlacement = null;
    }

    private void fail(String reason) {
        _failed = true;
        _failureReason = reason;
        Debug.logError("Schematic placement preparation failed: " + reason);
    }

    static TaskFailure.Snapshot unhandledChildFailure(Task child, AltoClef mod) {
        if (child == null || child.shouldDeferFailure(mod)) return null;
        return child.getFailureSnapshot();
    }

    private static String failureReason(TaskFailure.Snapshot snapshot) {
        return snapshot.reason() == null || snapshot.reason().isBlank()
                ? "A descendant placement task failed." : snapshot.reason();
    }

    static Set<BlockPos> findDeferredCells(IStaticSchematic schematic, BlockPos origin, LevelReader world) {
        return findDeferredCells(schematic, schematic, origin, world);
    }

    /**
     * Finds special-placement cells in a batch, keeping support requirements separate from
     * authored hinge geometry. Exact authored side blocks may be cleared by the placement task
     * and are reported for later restoration through ordinary schematic planning.
     */
    static Set<BlockPos> findDeferredCells(IStaticSchematic candidatePlacements,
                                           IStaticSchematic authoritativeSchematic,
                                           BlockPos origin, LevelReader world) {
        Set<BlockPos> deferred = new HashSet<>();
        for (int x = 0; x < candidatePlacements.widthX(); x++) {
            for (int y = 0; y < candidatePlacements.heightY(); y++) {
                for (int z = 0; z < candidatePlacements.lengthZ(); z++) {
                    BlockState state = candidatePlacements.getDirect(x, y, z);
                    if (state == null || state.isAir()) continue;
                    if (!candidatePlacements.inSchematic(x, y, z, state)) continue;
                    BlockPos local = new BlockPos(x, y, z);
                    BlockPos worldPos = origin.offset(local);
                    if (state.getBlock() instanceof DoorBlock
                            && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
                        BlockState upper = y + 1 < candidatePlacements.heightY()
                                ? candidatePlacements.getDirect(x, y + 1, z) : null;
                        if (upper == null || upper.getBlock() != state.getBlock()
                                || !upper.hasProperty(DoorBlock.HALF)
                                || upper.getValue(DoorBlock.HALF) != DoubleBlockHalf.UPPER) continue;
                        if (state.equals(world.getBlockState(worldPos))
                                && upper.equals(world.getBlockState(worldPos.above()))) continue;
                        if (!state.canSurvive(world, worldPos)) {
                            deferred.add(local);
                            deferred.add(local.above());
                        }
                    } else if (state.getBlock() instanceof SlabBlock
                            && state.getValue(SlabBlock.TYPE) == SlabType.DOUBLE) {
                        BlockState current = world.getBlockState(worldPos);
                        if (current.equals(state)) continue;
                        if (current.isAir() && !hasClickableNeighbor(world, worldPos)) {
                            deferred.add(local);
                        }
                    }
                }
            }
        }
        return Set.copyOf(deferred);
    }

    private static boolean insideSchematic(IStaticSchematic schematic, BlockPos pos) {
        return pos.getX() >= 0 && pos.getX() < schematic.widthX()
                && pos.getY() >= 0 && pos.getY() < schematic.heightY()
                && pos.getZ() >= 0 && pos.getZ() < schematic.lengthZ();
    }

    static boolean isAuthorizedHingeNeighborRemoval(IStaticSchematic authoritativeSchematic,
                                                     BlockPos localPosition, BlockState actual,
                                                     BlockPos worldPosition, LevelReader world) {
        if (!insideSchematic(authoritativeSchematic, localPosition)) return false;
        BlockState desired = authoritativeSchematic.getDirect(
                localPosition.getX(), localPosition.getY(), localPosition.getZ());
        return BuildSchematicTask.isEligibleTemporaryRestorationState(desired)
                && !(desired.getBlock() instanceof DoorBlock)
                && authoritativeSchematic.inSchematic(
                        localPosition.getX(), localPosition.getY(), localPosition.getZ(), desired)
                && actual.equals(desired)
                && actual.isCollisionShapeFullBlock(world, worldPosition);
    }

    static IStaticSchematic withoutCells(IStaticSchematic source, Set<BlockPos> excluded) {
        if (excluded.isEmpty()) return source;
        return new IStaticSchematic() {
            @Override
            public BlockState getDirect(int x, int y, int z) {
                return excluded.contains(new BlockPos(x, y, z))
                        ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()
                        : source.getDirect(x, y, z);
            }

            @Override
            public BlockState desiredState(int x, int y, int z, BlockState current,
                                           List<BlockState> approxPlaceable) {
                return excluded.contains(new BlockPos(x, y, z))
                        ? current : source.desiredState(x, y, z, current, approxPlaceable);
            }

            @Override
            public boolean inSchematic(int x, int y, int z, BlockState current) {
                return !excluded.contains(new BlockPos(x, y, z)) && source.inSchematic(x, y, z, current);
            }

            @Override public int widthX() { return source.widthX(); }
            @Override public int heightY() { return source.heightY(); }
            @Override public int lengthZ() { return source.lengthZ(); }
        };
    }

    private static boolean hasClickableNeighbor(LevelReader world, BlockPos position) {
        for (Direction direction : Direction.values()) {
            BlockPos neighborPos = position.relative(direction);
            BlockState neighbor = world.getBlockState(neighborPos);
            if (!neighbor.isAir() && !neighbor.getCollisionShape(world, neighborPos).isEmpty()) return true;
        }
        return false;
    }

    static List<BlockPos> safeDoorApproachPositions(BlockPos lowerPos, Direction facing,
                                                    LevelReader world) {
        if (world == null) return List.of();
        Direction behind = facing.getOpposite();
        Direction side = facing.getClockWise();
        List<BlockPos> basePositions = List.of(
                lowerPos.relative(behind, 2),
                lowerPos.relative(behind, 2).relative(side),
                lowerPos.relative(behind, 2).relative(side.getOpposite()));
        int[] verticalOffsets = {0, -1, 1};
        Set<BlockPos> result = new java.util.LinkedHashSet<>();
        for (BlockPos base : basePositions) {
            for (int verticalOffset : verticalOffsets) {
                BlockPos stance = base.offset(0, verticalOffset, 0);
                if (isSafeDoorStance(world, stance)) result.add(stance);
            }
        }
        return List.copyOf(result);
    }

    static boolean isSafeDoorStance(LevelReader world, BlockPos feet) {
        if (world.isOutsideBuildHeight(feet) || world.isOutsideBuildHeight(feet.above())) return false;
        BlockPos head = feet.above();
        BlockState feetState = world.getBlockState(feet);
        BlockState headState = world.getBlockState(head);
        if (!feetState.getCollisionShape(world, feet).isEmpty()
                || !headState.getCollisionShape(world, head).isEmpty()
                || !world.getFluidState(feet).isEmpty()
                || !world.getFluidState(head).isEmpty()) return false;
        BlockPos supportPos = feet.below();
        BlockState support = world.getBlockState(supportPos);
        return support.isFaceSturdy(world, supportPos, Direction.UP);
    }

    static boolean approachAttemptTimedOut(int waitedTicks, int timeoutTicks) {
        return waitedTicks >= timeoutTicks;
    }

    private static List<Placement> collect(IStaticSchematic schematic, BlockPos origin,
                                           Set<BlockPos> skippedLocalPositions) {
        List<Placement> result = new ArrayList<>();
        for (int x = 0; x < schematic.widthX(); x++) {
            for (int y = 0; y < schematic.heightY(); y++) {
                for (int z = 0; z < schematic.lengthZ(); z++) {
                    BlockState state = schematic.getDirect(x, y, z);
                    if (state == null || state.isAir()) continue;
                    BlockPos local = new BlockPos(x, y, z);
                    if (skippedLocalPositions.contains(local)) continue;
                    if (!schematic.inSchematic(x, y, z, state)) continue;
                    BlockPos world = origin.offset(local);
                    if (state.getBlock() instanceof DoorBlock
                            && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
                        BlockState upper = y + 1 < schematic.heightY()
                                ? schematic.getDirect(x, y + 1, z) : null;
                        if (upper != null && upper.getBlock() == state.getBlock()
                                && upper.hasProperty(DoorBlock.HALF)
                                && upper.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER) {
                            result.add(new Placement(Kind.DOOR, world, state, upper, state.getBlock()));
                        }
                    } else if (state.getBlock() instanceof SlabBlock
                            && state.getValue(SlabBlock.TYPE) == SlabType.DOUBLE) {
                        result.add(new Placement(Kind.DOUBLE_SLAB, world, state, null, state.getBlock()));
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    static BlockState closedUnpoweredDoorState(BlockState state) {
        return state.setValue(DoorBlock.OPEN, false).setValue(DoorBlock.POWERED, false);
    }

    static boolean mayRestoreOnlyIntoAir(BlockState actual, BlockState desired) {
        return mayRestoreOnlyIntoAir(actual, desired, false);
    }

    static boolean mayRestoreOnlyIntoAir(BlockState actual, BlockState desired,
                                         boolean allowExistingPartialSlab) {
        return actual.isAir() || actual.equals(desired)
                || allowExistingPartialSlab && isPartialDoubleSlab(actual, desired);
    }

    private static boolean isPartialDoubleSlab(BlockState actual, BlockState desired) {
        return desired.getBlock() instanceof SlabBlock
                && desired.hasProperty(SlabBlock.TYPE)
                && desired.getValue(SlabBlock.TYPE) == SlabType.DOUBLE
                && actual.getBlock() == desired.getBlock()
                && actual.hasProperty(SlabBlock.TYPE)
                && actual.getValue(SlabBlock.TYPE) != SlabType.DOUBLE;
    }

    static boolean isExpectedDoorPair(BlockState desiredLower, BlockState desiredUpper,
                                      BlockState actualLower, BlockState actualUpper) {
        if (desiredLower.equals(actualLower) && desiredUpper.equals(actualUpper)) return true;
        return desiredLower.getBlock() instanceof DoorBlock
                && desiredLower.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER
                && desiredLower.getValue(DoorBlock.OPEN)
                && !desiredLower.getValue(DoorBlock.POWERED)
                && closedUnpoweredDoorState(desiredLower).equals(actualLower)
                && closedUnpoweredDoorState(desiredUpper).equals(actualUpper);
    }

    private enum Kind { DOOR, DOUBLE_SLAB }

    private record Placement(Kind kind, BlockPos position, BlockState state,
                             BlockState upperState, net.minecraft.world.level.block.Block block) {}

    /** Approaches from behind the requested facing and clicks the real support surface. */
    private static final class PlaceSchematicDoorTask extends Task {
        private static final int APPROACH_TIMEOUT_TICKS = 100;
        private static final int CLICK_WAIT_TICKS = 30;
        private final BlockPos _lowerPos;
        private final BlockState _desiredLower;
        private final BlockState _desiredUpper;
        private final BlockState _placementLower;
        private final BlockState _placementUpper;
        private final boolean _needsManualOpen;
        private final List<BlockPos> _approachPositions;
        private final BlockPos _origin;
        private final IStaticSchematic _authoritativeSchematic;
        private final Set<BlockPos> _temporarilyRemovedPositions;
        private int _candidate;
        private int _waitTicks;
        private List<BlockPos> _hingeNeighborRemovals;
        private int _hingeNeighborRemovalIndex;
        private Task _hingeNeighborRemovalTask;
        private boolean _hingeNeighborRemovalAttempted;
        private boolean _sawHingeOnlyPredictionMismatch;
        private String _lastCandidateFailureDetails = "No placement candidate reached block prediction.";
        private boolean _clicked;
        private boolean _aimedAtSupport;
        private boolean _failed;
        private String _failureReason;
        private ToggleUnpoweredDoorTask _toggleTask;

        private PlaceSchematicDoorTask(BlockPos lowerPos, BlockState lower, BlockState upper,
                                       BlockPos origin, IStaticSchematic authoritativeSchematic,
                                       Set<BlockPos> temporarilyRemovedPositions,
                                       LevelReader world) {
            _lowerPos = lowerPos;
            _desiredLower = lower;
            _desiredUpper = upper;
            _origin = origin;
            _authoritativeSchematic = authoritativeSchematic;
            _temporarilyRemovedPositions = temporarilyRemovedPositions;
            _needsManualOpen = lower.getValue(DoorBlock.OPEN) && !lower.getValue(DoorBlock.POWERED);
            _placementLower = _needsManualOpen ? closedUnpoweredDoorState(lower) : lower;
            _placementUpper = _needsManualOpen ? closedUnpoweredDoorState(upper) : upper;
            _approachPositions = safeDoorApproachPositions(lowerPos, lower.getValue(DoorBlock.FACING), world);
        }

        @Override
        public boolean isFinished(AltoClef mod) {
            return _failed || isExactlyPlaced(mod);
        }

        boolean hasFailed() { return _failed; }
        String getFailureReason() { return _failureReason; }

        @Override
        protected void onStart(AltoClef mod) {
            _candidate = 0;
            _waitTicks = 0;
            _clicked = false;
            _aimedAtSupport = false;
            _toggleTask = null;
            _hingeNeighborRemovals = null;
            _hingeNeighborRemovalIndex = 0;
            _hingeNeighborRemovalTask = null;
            _hingeNeighborRemovalAttempted = false;
            _sawHingeOnlyPredictionMismatch = false;
            _lastCandidateFailureDetails = "No placement candidate reached block prediction.";
            _failed = false;
            _failureReason = null;
            _waitTicks = 0;
        }

        @Override
        protected Task onTick(AltoClef mod) {
            if (isExactlyPlaced(mod) || _failed) return null;
            if (_hingeNeighborRemovals != null) {
                Task removalTask = tickHingeNeighborRemoval(mod);
                if (_failed) return null;
                if (_hingeNeighborRemovals != null) return removalTask;
            }
            if (_toggleTask != null) {
                if (_toggleTask.hasFailed()) {
                    fail(_toggleTask.getFailureReason());
                    return null;
                }
                if (!_toggleTask.isFinished(mod)) {
                    TaskFailure.Snapshot childFailure = unhandledChildFailure(_toggleTask, mod);
                    if (childFailure != null) {
                        fail(failureReason(childFailure));
                        return null;
                    }
                    return _toggleTask;
                }
                return null;
            }
            if (_needsManualOpen && isPlacementBasePlaced(mod)) {
                if (!((DoorBlock) _desiredLower.getBlock()).type().canOpenByHand()) {
                    fail("Door at " + _lowerPos.toShortString() + " cannot be opened by hand.");
                    return null;
                }
                _toggleTask = new ToggleUnpoweredDoorTask(
                        _lowerPos, _desiredLower, _desiredUpper, _approachPositions.get(_candidate));
                return _toggleTask;
            }
            if (_desiredLower.getValue(DoorBlock.POWERED)
                    && !_desiredLower.getValue(DoorBlock.OPEN)) {
                fail("Door at " + _lowerPos.toShortString()
                        + " requests POWERED=true with OPEN=false, which vanilla redstone updates cannot preserve.");
                return null;
            }
            if (_approachPositions.isEmpty()) {
                fail("No safe grounded approach position exists for the door at "
                        + _lowerPos.toShortString() + ".");
                return null;
            }
            if (_candidate >= _approachPositions.size()) {
                if (!_hingeNeighborRemovalAttempted && _sawHingeOnlyPredictionMismatch) {
                    _hingeNeighborRemovalAttempted = true;
                    _hingeNeighborRemovals = findRemovableAuthoredHingeNeighbors(mod);
                    _hingeNeighborRemovalIndex = 0;
                    if (!_hingeNeighborRemovals.isEmpty()) {
                        setDebugState("Clearing schematic hinge geometry for the requested door state");
                        return null;
                    }
                }
                fail("Could not place the door with the requested facing and hinge at "
                        + _lowerPos.toShortString() + ". Last candidate: " + _lastCandidateFailureDetails);
                return null;
            }

            if (_clicked) {
                if (isPlacementBasePlaced(mod)) {
                    _clicked = false;
                    _waitTicks = 0;
                    if (_needsManualOpen) {
                        _toggleTask = new ToggleUnpoweredDoorTask(
                                _lowerPos, _desiredLower, _desiredUpper, _approachPositions.get(_candidate));
                        return _toggleTask;
                    }
                }
                if (++_waitTicks >= CLICK_WAIT_TICKS) {
                    // The candidate prediction matched before the click. A failed or altered
                    // server placement must stop here rather than mark an approximate state done.
                    fail("The server did not accept the predicted door state at "
                            + _lowerPos.toShortString() + ".");
                }
                return null;
            }

            Item item = _desiredLower.getBlock().asItem();
            // Gather before navigation. The catalogued resource task may travel to a crafting
            // table; asking for it only after reaching the placement approach causes this parent
            // task to cancel that trip on every tick and restart its own approach path.
            if (!StorageHelper.itemTargetsMetAccessibleInventory(mod, new ItemTarget(item, 1))) {
                setDebugState("Gathering the requested door item");
                return TaskCatalogue.getItemTask(new ItemTarget(item, 1));
            }

            BlockPos approach = _approachPositions.get(_candidate);
            if (!approach.equals(mod.getPlayer().blockPosition())) {
                _aimedAtSupport = false;
                ICustomGoalProcess goals = mod.getClientBaritone().getCustomGoalProcess();
                if (!goals.isActive()) goals.setGoalAndPath(new GoalBlock(approach));
                _waitTicks++;
                if (approachAttemptTimedOut(_waitTicks, APPROACH_TIMEOUT_TICKS)) {
                    _lastCandidateFailureDetails = "navigation timed out after " + APPROACH_TIMEOUT_TICKS
                            + " ticks to safe stance " + approach.toShortString();
                    nextCandidate(mod);
                    return null;
                }
                setDebugState("Approaching the " + _desiredLower.getValue(DoorBlock.FACING).getName()
                        + " side of the door at " + _lowerPos.toShortString());
                return null;
            }
            _waitTicks = 0;

            BlockPos support = _lowerPos.below();
            LookHelper.lookAt(mod, doorClickPoint(support, _desiredLower));
            if (!_aimedAtSupport) {
                _aimedAtSupport = true;
                return null;
            }
            HitResult hit = Minecraft.getInstance().hitResult;
            if (!(hit instanceof BlockHitResult blockHit)
                    || blockHit.getType() != HitResult.Type.BLOCK
                    || !support.equals(blockHit.getBlockPos())
                    || blockHit.getDirection() != Direction.UP) {
                return null;
            }

            Direction horizontal = Direction.fromYRot(mod.getPlayer().getYRot());
            if (horizontal != _desiredLower.getValue(DoorBlock.FACING)) {
                _lastCandidateFailureDetails = candidateContextDetails(
                        mod, blockHit, horizontal, null, null, null, false);
                // An off-axis candidate can round into the neighboring facing. Move to the
                // next behind-side position instead of placing a differently oriented door.
                nextCandidate(mod);
                return null;
            }

            if (!mod.getSlotHandler().forceEquipItem(new ItemTarget(item, 1), false)) return null;
            ItemStack stack = mod.getPlayer().getMainHandItem();
            if (stack.isEmpty() || stack.getItem() != item) return null;
            BlockPlaceContext context = new BlockPlaceContext(
                    mod.getPlayer(), InteractionHand.MAIN_HAND, stack, blockHit);
            BlockState predicted = _desiredLower.getBlock().getStateForPlacement(context);
            BlockState predictedUpper = predicted == null ? null
                    : predicted.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER);
            boolean onlyHingeMismatch = context.canPlace() && isHingeOnlyMismatch(predicted, predictedUpper);
            if (predicted != null && (predicted.getValue(DoorBlock.OPEN) != _placementLower.getValue(DoorBlock.OPEN)
                    || predicted.getValue(DoorBlock.POWERED) != _placementLower.getValue(DoorBlock.POWERED))) {
                fail("Door at " + _lowerPos.toShortString() + " needs placement base open="
                        + _placementLower.getValue(DoorBlock.OPEN) + ", powered="
                        + _placementLower.getValue(DoorBlock.POWERED)
                        + ", but vanilla placement predicts open=" + predicted.getValue(DoorBlock.OPEN)
                        + ", powered=" + predicted.getValue(DoorBlock.POWERED)
                        + " from the current redstone signal. Check the redstone around this door.");
                return null;
            }
            if (!context.canPlace() || !_placementLower.equals(predicted)
                    || !_placementUpper.equals(predictedUpper)) {
                _lastCandidateFailureDetails = candidateContextDetails(
                        mod, blockHit, horizontal, predicted, predictedUpper, context.canPlace(), onlyHingeMismatch);
                _sawHingeOnlyPredictionMismatch |= onlyHingeMismatch;
                nextCandidate(mod);
                return null;
            }

            mod.getInputControls().tryPress(Input.CLICK_RIGHT);
            _clicked = true;
            _waitTicks = 0;
            setDebugState("Placing the verified door state at " + _lowerPos.toShortString());
            return null;
        }

        @Override
        protected void onStop(AltoClef mod, Task interruptTask) {
            mod.getClientBaritone().getCustomGoalProcess().onLostControl();
            mod.getInputControls().release(Input.CLICK_RIGHT);
        }

        @Override
        protected boolean isEqual(Task other) {
            return other instanceof PlaceSchematicDoorTask task
                    && _lowerPos.equals(task._lowerPos)
                    && _desiredLower.equals(task._desiredLower)
                    && _desiredUpper.equals(task._desiredUpper);
        }

        @Override
        protected String toDebugString() {
            return "Place schematic door at " + _lowerPos.toShortString();
        }

        private boolean isExactlyPlaced(AltoClef mod) {
            return mod.getWorld() != null
                    && _desiredLower.equals(mod.getWorld().getBlockState(_lowerPos))
                    && _desiredUpper.equals(mod.getWorld().getBlockState(_lowerPos.above()));
        }

        private boolean isPlacementBasePlaced(AltoClef mod) {
            return mod.getWorld() != null
                    && _placementLower.equals(mod.getWorld().getBlockState(_lowerPos))
                    && _placementUpper.equals(mod.getWorld().getBlockState(_lowerPos.above()));
        }

        private boolean isHingeOnlyMismatch(BlockState predicted, BlockState predictedUpper) {
            if (predicted == null || predictedUpper == null
                    || !predicted.hasProperty(DoorBlock.HINGE)) return false;
            DoorHingeSide predictedHinge = predicted.getValue(DoorBlock.HINGE);
            return predictedHinge != _placementLower.getValue(DoorBlock.HINGE)
                    && _placementLower.setValue(DoorBlock.HINGE, predictedHinge).equals(predicted)
                    && _placementUpper.setValue(DoorBlock.HINGE, predictedHinge).equals(predictedUpper);
        }

        private String candidateContextDetails(AltoClef mod, BlockHitResult hit, Direction playerFacing,
                                               BlockState predicted, BlockState predictedUpper,
                                               Boolean canPlace, boolean onlyHingeMismatch) {
            BlockPos approach = _approachPositions.get(Math.min(_candidate, _approachPositions.size() - 1));
            return "candidate=" + (_candidate + 1)
                    + ", approach=" + approach.toShortString()
                    + ", playerFacing=" + playerFacing
                    + ", desiredFacing=" + _desiredLower.getValue(DoorBlock.FACING)
                    + ", support=" + _lowerPos.below().toShortString()
                    + ", hit=" + hit.getBlockPos().toShortString() + "/" + hit.getDirection()
                    + ", canPlace=" + canPlace
                    + ", hingeOnlyMismatch=" + onlyHingeMismatch
                    + ", requestedLower=" + _placementLower
                    + ", requestedUpper=" + _placementUpper
                    + ", predictedLower=" + predicted
                    + ", predictedUpper=" + predictedUpper
                    + ", sideGeometry=" + hingeSideSnapshot(mod);
        }

        private String hingeSideSnapshot(AltoClef mod) {
            Direction facing = _desiredLower.getValue(DoorBlock.FACING);
            Direction[] sides = {facing.getCounterClockWise(), facing.getClockWise()};
            List<String> states = new ArrayList<>();
            for (Direction side : sides) {
                for (int y = 0; y <= 1; y++) {
                    BlockPos worldPos = _lowerPos.relative(side).above(y);
                    BlockPos local = worldPos.subtract(_origin);
                    BlockState actual = mod.getWorld() == null ? null : mod.getWorld().getBlockState(worldPos);
                    BlockState desired = insideSchematic(_authoritativeSchematic, local)
                            ? _authoritativeSchematic.getDirect(local.getX(), local.getY(), local.getZ()) : null;
                    states.add(side + (y == 0 ? ".lower" : ".upper") + "=" + actual + " (schematic=" + desired + ")");
                }
            }
            return states.toString();
        }

        private List<BlockPos> findRemovableAuthoredHingeNeighbors(AltoClef mod) {
            List<BlockPos> result = new ArrayList<>();
            Direction facing = _desiredLower.getValue(DoorBlock.FACING);
            Direction[] sides = {facing.getCounterClockWise(), facing.getClockWise()};
            for (Direction side : sides) {
                for (int y = 1; y >= 0; y--) {
                    BlockPos worldPos = _lowerPos.relative(side).above(y);
                    BlockPos local = worldPos.subtract(_origin);
                    if (!insideSchematic(_authoritativeSchematic, local)) continue;
                    BlockState actual = mod.getWorld().getBlockState(worldPos);
                    if (isAuthorizedHingeNeighborRemoval(
                            _authoritativeSchematic, local, actual, worldPos, mod.getWorld())) {
                        result.add(local);
                    }
                }
            }
            return List.copyOf(result);
        }

        private Task tickHingeNeighborRemoval(AltoClef mod) {
            if (_hingeNeighborRemovalTask != null) {
                BlockPos local = _hingeNeighborRemovals.get(_hingeNeighborRemovalIndex);
                BlockPos worldPos = _origin.offset(local);
                if (!_hingeNeighborRemovalTask.isFinished(mod)) {
                    TaskFailure.Snapshot childFailure = unhandledChildFailure(
                            _hingeNeighborRemovalTask, mod);
                    if (childFailure != null) {
                        if (mod.getWorld().getBlockState(worldPos).isAir()) {
                            _temporarilyRemovedPositions.add(local);
                        }
                        fail("Hinge-neighbor removal task failed at " + worldPos.toShortString()
                                + ": " + failureReason(childFailure));
                        _hingeNeighborRemovals = null;
                        _hingeNeighborRemovalTask = null;
                        return null;
                    }
                    return _hingeNeighborRemovalTask;
                }
                if (!mod.getWorld().getBlockState(worldPos).isAir()) {
                    fail("Could not temporarily clear authored hinge geometry at " + worldPos.toShortString()
                            + ". Last candidate: " + _lastCandidateFailureDetails);
                    _hingeNeighborRemovals = null;
                    return null;
                }
                _temporarilyRemovedPositions.add(local);
                _hingeNeighborRemovalIndex++;
                _hingeNeighborRemovalTask = null;
            }

            while (_hingeNeighborRemovalIndex < _hingeNeighborRemovals.size()) {
                BlockPos local = _hingeNeighborRemovals.get(_hingeNeighborRemovalIndex);
                BlockPos worldPos = _origin.offset(local);
                if (mod.getWorld().getBlockState(worldPos).isAir()) {
                    _temporarilyRemovedPositions.add(local);
                    _hingeNeighborRemovalIndex++;
                    continue;
                }
                setDebugState("Clearing authored hinge geometry at " + worldPos.toShortString());
                _hingeNeighborRemovalTask = new DestroyBlockTask(worldPos);
                return _hingeNeighborRemovalTask;
            }

            _hingeNeighborRemovals = null;
            _hingeNeighborRemovalTask = null;
            _candidate = 0;
            _waitTicks = 0;
            _clicked = false;
            _aimedAtSupport = false;
            _sawHingeOnlyPredictionMismatch = false;
            setDebugState("Retrying the requested door state after clearing authored hinge geometry");
            return null;
        }

        private void fail(String reason) {
            _failed = true;
            _failureReason = reason;
            Debug.logError("Schematic door placement failed: " + reason);
        }

        private void nextCandidate(AltoClef mod) {
            _candidate++;
            _waitTicks = 0;
            _aimedAtSupport = false;
            mod.getClientBaritone().getCustomGoalProcess().onLostControl();
        }

        private static Vec3 doorClickPoint(BlockPos support, BlockState desiredLower) {
            Direction facing = desiredLower.getValue(DoorBlock.FACING);
            boolean rightHinge = desiredLower.getValue(DoorBlock.HINGE) == DoorHingeSide.RIGHT;
            double x = support.getX() + 0.5;
            double z = support.getZ() + 0.5;
            if (facing == Direction.NORTH) {
                x = support.getX() + (rightHinge ? 0.75 : 0.25);
            } else if (facing == Direction.SOUTH) {
                x = support.getX() + (rightHinge ? 0.25 : 0.75);
            } else if (facing == Direction.EAST) {
                z = support.getZ() + (rightHinge ? 0.75 : 0.25);
            } else if (facing == Direction.WEST) {
                z = support.getZ() + (rightHinge ? 0.25 : 0.75);
            }
            return new Vec3(x, support.getY() + 0.999, z);
        }
    }

    /** Opens a closed, unpowered wooden door with the same empty-hand interaction as a player. */
    private static final class ToggleUnpoweredDoorTask extends Task {
        private static final int MAX_EMPTY_HAND_TICKS = 120;
        private static final int CLICK_WAIT_TICKS = 40;

        private final BlockPos _lowerPos;
        private final BlockState _desiredLower;
        private final BlockState _desiredUpper;
        private final BlockPos _approach;
        private final BlockState _closedLower;
        private final BlockState _closedUpper;
        private int _emptyHandTicks;
        private int _waitTicks;
        private boolean _clicked;
        private boolean _failed;
        private String _failureReason;

        private ToggleUnpoweredDoorTask(BlockPos lowerPos, BlockState desiredLower,
                                        BlockState desiredUpper, BlockPos approach) {
            _lowerPos = lowerPos;
            _desiredLower = desiredLower;
            _desiredUpper = desiredUpper;
            _approach = approach;
            _closedLower = closedUnpoweredDoorState(desiredLower);
            _closedUpper = closedUnpoweredDoorState(desiredUpper);
        }

        @Override
        public boolean isFinished(AltoClef mod) {
            return _failed || isExactlyPlaced(mod);
        }

        boolean hasFailed() { return _failed; }
        String getFailureReason() { return _failureReason; }

        @Override
        protected void onStart(AltoClef mod) {
            _emptyHandTicks = 0;
            _waitTicks = 0;
            _clicked = false;
            _failed = false;
            _failureReason = null;
        }

        @Override
        protected Task onTick(AltoClef mod) {
            if (isExactlyPlaced(mod) || _failed) return null;
            if (mod.getWorld() == null || mod.getPlayer() == null) {
                fail("The world or player disappeared while opening the door at "
                        + _lowerPos.toShortString() + ".");
                return null;
            }
            if (!isClosedUnpowered(mod)) {
                fail("The door at " + _lowerPos.toShortString()
                        + " changed before its empty-hand open interaction completed; expected closed and unpowered.");
                return null;
            }
            if (!((DoorBlock) _desiredLower.getBlock()).type().canOpenByHand()) {
                fail("Door at " + _lowerPos.toShortString() + " cannot be opened by hand.");
                return null;
            }

            if (!_approach.equals(mod.getPlayer().blockPosition())) {
                ICustomGoalProcess goals = mod.getClientBaritone().getCustomGoalProcess();
                if (!goals.isActive()) goals.setGoalAndPath(new GoalBlock(_approach));
                setDebugState("Approaching the door to open it at " + _lowerPos.toShortString());
                return null;
            }

            boolean cleared = mod.getSlotHandler().forceDeequip(stack -> !stack.isEmpty());
            ItemStack mainHand = mod.getPlayer().getMainHandItem();
            ItemStack cursor = StorageHelper.getItemStackInSlot(CursorSlot.SLOT);
            if (!cleared || !mainHand.isEmpty() || !cursor.isEmpty()) {
                if (++_emptyHandTicks >= MAX_EMPTY_HAND_TICKS) {
                    fail("Could not clear the main hand and cursor before opening the door at "
                            + _lowerPos.toShortString() + ".");
                } else {
                    setDebugState("Clearing the hand before opening the door at " + _lowerPos.toShortString());
                }
                return null;
            }

            if (_clicked) {
                if (isExactlyPlaced(mod)) return null;
                if (++_waitTicks >= CLICK_WAIT_TICKS) {
                    fail("The empty-hand interaction did not open the door at " + _lowerPos.toShortString()
                            + " into the requested lower and upper states.");
                }
                return null;
            }

            BlockState actualLower = mod.getWorld().getBlockState(_lowerPos);
            var doorShape = actualLower.getShape(mod.getWorld(), _lowerPos);
            if (doorShape.isEmpty()) {
                fail("The closed door at " + _lowerPos.toShortString() + " has no hittable shape.");
                return null;
            }
            AABB doorBounds = doorShape.bounds();
            Vec3 clickPoint = new Vec3(
                    _lowerPos.getX() + (doorBounds.minX + doorBounds.maxX) / 2.0,
                    _lowerPos.getY() + (doorBounds.minY + doorBounds.maxY) / 2.0,
                    _lowerPos.getZ() + (doorBounds.minZ + doorBounds.maxZ) / 2.0);
            LookHelper.lookAt(mod, clickPoint);
            HitResult hit = Minecraft.getInstance().hitResult;
            if (!(hit instanceof BlockHitResult blockHit)
                    || blockHit.getType() != HitResult.Type.BLOCK
                    || !_lowerPos.equals(blockHit.getBlockPos())
                    || blockHit.getDirection() != _desiredLower.getValue(DoorBlock.FACING).getOpposite()) {
                if (++_waitTicks >= CLICK_WAIT_TICKS) {
                    fail("Could not aim at the lower face of the door at " + _lowerPos.toShortString()
                            + " from its selected approach position.");
                    return null;
                }
                setDebugState("Aiming at the lower door face at " + _lowerPos.toShortString());
                return null;
            }

            mod.getInputControls().tryPress(Input.CLICK_RIGHT);
            _clicked = true;
            _waitTicks = 0;
            setDebugState("Opening the unpowered door at " + _lowerPos.toShortString());
            return null;
        }

        @Override
        protected void onStop(AltoClef mod, Task interruptTask) {
            mod.getClientBaritone().getCustomGoalProcess().onLostControl();
            mod.getInputControls().release(Input.CLICK_RIGHT);
        }

        @Override
        protected boolean isEqual(Task other) {
            return other instanceof ToggleUnpoweredDoorTask task
                    && _lowerPos.equals(task._lowerPos)
                    && _desiredLower.equals(task._desiredLower)
                    && _desiredUpper.equals(task._desiredUpper)
                    && _approach.equals(task._approach);
        }

        @Override
        protected String toDebugString() {
            return "Open schematic door at " + _lowerPos.toShortString();
        }

        private boolean isClosedUnpowered(AltoClef mod) {
            return _closedLower.equals(mod.getWorld().getBlockState(_lowerPos))
                    && _closedUpper.equals(mod.getWorld().getBlockState(_lowerPos.above()));
        }

        private boolean isExactlyPlaced(AltoClef mod) {
            return mod.getWorld() != null
                    && _desiredLower.equals(mod.getWorld().getBlockState(_lowerPos))
                    && _desiredUpper.equals(mod.getWorld().getBlockState(_lowerPos.above()));
        }

        private void fail(String reason) {
            _failed = true;
            _failureReason = reason;
            Debug.logError("Schematic door opening failed: " + reason);
        }
    }
}
