package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.TaskFailure;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.process.ICustomGoalProcess;
import baritone.api.utils.input.Input;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Converts matching concrete powder beside source water, then mines the resulting concrete. */
public final class CollectConcreteTask extends ResourceTask implements TaskFailure {
    private static final int WATER_SEARCH_LIMIT_TICKS = 1200;
    private static final int CONVERSION_WAIT_LIMIT_TICKS = 120;
    private static final int PICKUP_WAIT_LIMIT_TICKS = 100;

    private final ItemHelper.ColorfulItems _color;
    private final Block _powderBlock;
    private final Block _concreteBlock;
    private final ItemTarget _powderTarget;
    private Task _childTask;
    private BlockPos _activePowderPosition;
    private BlockPos _localWaterSearchOrigin;
    private BlockPos _nearbyWaterPosition;
    private int _nextLocalWaterSearchTick;
    private int _conversionWaitTicks;
    private int _pickupWaitTicks;
    private int _inventoryCountBeforeMining;
    private boolean _awaitingPickup;
    private boolean _currentlyMiningConcrete;
    private boolean _failed;
    private String _failureReason;

    public CollectConcreteTask(ItemHelper.ColorfulItems color, int count) {
        super(color.concrete, count);
        _color = color;
        _powderBlock = Block.byItem(color.concretePowder);
        _concreteBlock = Block.byItem(color.concrete);
        _powderTarget = new ItemTarget(color.concretePowder, count);
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
        mod.getBlockTracker().trackBlock(Blocks.WATER);
        _childTask = null;
        _localWaterSearchOrigin = null;
        _nearbyWaterPosition = null;
        _nextLocalWaterSearchTick = 0;
        if (_currentlyMiningConcrete && _activePowderPosition != null && mod.getWorld() != null
                && isConvertedConcrete(mod.getWorld().getBlockState(_activePowderPosition), _concreteBlock)) {
            _currentlyMiningConcrete = false;
        }
        if (!_currentlyMiningConcrete && !_awaitingPickup && _activePowderPosition != null && mod.getWorld() != null
                && mod.getWorld().getBlockState(_activePowderPosition).isAir()) {
            _activePowderPosition = null;
        }
        _conversionWaitTicks = 0;
        if (!_awaitingPickup && !_currentlyMiningConcrete) {
            _pickupWaitTicks = 0;
            _inventoryCountBeforeMining = 0;
        }
        _awaitingPickup = _awaitingPickup || _currentlyMiningConcrete;
        _failed = false;
        _failureReason = null;
    }

    @Override
    protected void onResetForNewRun() {
        _failed = false;
        _failureReason = null;
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        if (_failed) return null;

        if (_childTask != null) {
            if (!_childTask.isFinished(mod)) {
                if (_childTask instanceof ObservedPowderPlacementTask task && task.hasFailed()) {
                    fail(mod, task.getFailureReason());
                    return null;
                }
                if (_childTask instanceof WaterSearchTask task && task.hasFailed()) {
                    fail(mod, task.getFailureReason());
                    return null;
                }
                if (_childTask instanceof ConcretePickupTask task && task.hasFailed()) {
                    fail(mod, task.getFailureReason());
                    return null;
                }
                return _childTask;
            }
            if (_childTask instanceof ObservedPowderPlacementTask task && task.hasFailed()) {
                fail(mod, task.getFailureReason());
                return null;
            }
            if (_childTask instanceof WaterSearchTask task && task.hasFailed()) {
                fail(mod, task.getFailureReason());
                return null;
            }
            if (_childTask instanceof ConcretePickupTask task && task.hasFailed()) {
                fail(mod, task.getFailureReason());
                return null;
            }
            if (_currentlyMiningConcrete) {
                _currentlyMiningConcrete = false;
                _awaitingPickup = true;
                _pickupWaitTicks = 0;
            }
            _childTask = null;
        }

        int concreteCount = StorageHelper.getAccessibleInventoryItemCount(
                mod, new ItemTarget(_color.concrete, 1));
        if (_awaitingPickup) {
            if (concreteCount > _inventoryCountBeforeMining) {
                _awaitingPickup = false;
                _pickupWaitTicks = 0;
            } else if (++_pickupWaitTicks > PICKUP_WAIT_LIMIT_TICKS) {
                fail(mod, "Mined concrete at " + positionString(_activePowderPosition)
                        + " but its item was not collected.");
                return null;
            } else {
                if (mod.getEntityTracker().itemDropped(new ItemTarget(_color.concrete, 1))) {
                    setDebugState("Picking up mined " + _color.colorName + " concrete");
                    _childTask = new ConcretePickupTask(_inventoryCountBeforeMining + 1);
                    return _childTask;
                }
                setDebugState("Waiting for mined " + _color.colorName + " concrete pickup");
                return null;
            }
        }

        if (concreteCount >= getItemTargets()[0].getTargetCount()) return null;

        if (_activePowderPosition != null) {
            BlockState actual = mod.getWorld().getBlockState(_activePowderPosition);
            if (isConvertedConcrete(actual, _concreteBlock)) {
                if (!StorageHelper.miningRequirementMetInventory(mod, MiningRequirement.STONE)) {
                    setDebugState("Getting a stone-tier pickaxe before mining concrete");
                    return new SatisfyMiningRequirementTask(MiningRequirement.STONE);
                }
                _inventoryCountBeforeMining = concreteCount;
                setDebugState("Mining converted " + _color.colorName + " concrete");
                _currentlyMiningConcrete = true;
                _childTask = new DestroyBlockTask(_activePowderPosition);
                return _childTask;
            }
            if (actual.getBlock() == _powderBlock) {
                if (++_conversionWaitTicks > CONVERSION_WAIT_LIMIT_TICKS) {
                    fail(mod, "Concrete powder at " + _activePowderPosition.toShortString()
                            + " did not touch water and convert.");
                    return null;
                }
                setDebugState("Waiting for concrete powder to harden at "
                        + _activePowderPosition.toShortString());
                return null;
            }
            if (!actual.isAir() && actual.getBlock() != Blocks.WATER) {
                fail(mod, "Concrete conversion target " + _activePowderPosition.toShortString()
                        + " was replaced by " + actual + ".");
                return null;
            }
            // The target vanished before it could be mined. Drop the target and select a new
            // supported position; the powder item will be reacquired if it was lost.
            _activePowderPosition = null;
            _conversionWaitTicks = 0;
        }

        if (_awaitingPickup) return null;

        int powderCount = StorageHelper.getAccessibleInventoryItemCount(mod, _powderTarget);
        if (powderCount <= 0) {
            setDebugState("Gathering " + _color.colorName + " concrete powder");
            return TaskCatalogue.getItemTask(_powderTarget);
        }

        Optional<ConversionSpot> naturalSpot = findNaturalConversionSpot(mod);
        if (naturalSpot.isPresent()) {
            return placePowderAt(mod, naturalSpot.get());
        }
        setDebugState("Exploring for reachable source water");
        _childTask = new WaterSearchTask();
        return _childTask;
    }

    private Task placePowderAt(AltoClef mod, ConversionSpot spot) {
        _activePowderPosition = spot.powderPosition();
        _conversionWaitTicks = 0;
        setDebugState("Placing " + _color.colorName + " concrete powder beside source water");
        _childTask = new ObservedPowderPlacementTask(
                _powderBlock, _concreteBlock, spot.powderPosition(), spot.supportPosition(),
                mod.getWorld());
        return _childTask;
    }

    private Optional<ConversionSpot> findNaturalConversionSpot(AltoClef mod) {
        BlockPos playerPosition = mod.getPlayer().blockPosition();
        int tick = WorldHelper.getTicks();
        boolean movedBeyondCachedArea = _localWaterSearchOrigin == null
                || _localWaterSearchOrigin.distToCenterSqr(playerPosition.getX() + 0.5,
                playerPosition.getY() + 0.5, playerPosition.getZ() + 0.5) > 16;
        if (movedBeyondCachedArea || tick >= _nextLocalWaterSearchTick) {
            _localWaterSearchOrigin = playerPosition;
            _nearbyWaterPosition = findNearbySourceWater(mod.getWorld(), playerPosition, 12, 8)
                    .map(ConversionSpot::waterPosition).orElse(null);
            _nextLocalWaterSearchTick = tick + 10;
        }
        if (_nearbyWaterPosition != null
                && WorldHelper.isSourceBlock(mod, _nearbyWaterPosition, true)
                && WorldHelper.canReach(mod, _nearbyWaterPosition)) {
            Optional<ConversionSpot> nearbySpot = findPowderSpotAroundWater(
                    mod.getWorld(), _nearbyWaterPosition, mod);
            if (nearbySpot.isPresent()) return nearbySpot;
        }

        return mod.getBlockTracker().getKnownLocations(Blocks.WATER).stream()
                .filter(pos -> WorldHelper.isSourceBlock(mod, pos, true))
                .filter(pos -> WorldHelper.canReach(mod, pos))
                .map(pos -> findPowderSpotAroundWater(mod.getWorld(), pos, mod))
                .filter(Optional::isPresent)
                .map(Optional::orElseThrow)
                .min(Comparator.comparingDouble(spot -> spot.waterPosition().distToCenterSqr(
                        mod.getPlayer().position())));
    }

    static Optional<ConversionSpot> findNearbySourceWater(LevelReader world, BlockPos center,
                                                           int horizontalRadius, int verticalRadius) {
        if (world == null || center == null || horizontalRadius < 0 || verticalRadius < 0) {
            return Optional.empty();
        }
        ConversionSpot closest = null;
        double closestDistance = Double.POSITIVE_INFINITY;
        for (int x = -horizontalRadius; x <= horizontalRadius; x++) {
            for (int z = -horizontalRadius; z <= horizontalRadius; z++) {
                BlockPos column = center.offset(x, 0, z);
                if (!world.hasChunkAt(column)) continue;
                for (int y = -verticalRadius; y <= verticalRadius; y++) {
                    BlockPos water = column.offset(0, y, 0);
                    BlockState waterState = world.getBlockState(water);
                    if (waterState.getBlock() != Blocks.WATER || !waterState.getFluidState().isSource()
                            || waterState.getFluidState().getAmount() != 8) continue;
                    if (!world.getBlockState(water.above()).getFluidState().isEmpty()) continue;
                    Optional<ConversionSpot> candidate = findPowderSpotAroundWater(world, water, null);
                    if (candidate.isEmpty()) continue;
                    double distance = water.distToCenterSqr(center.getX() + 0.5, center.getY() + 0.5,
                            center.getZ() + 0.5);
                    if (distance < closestDistance) {
                        closest = candidate.get();
                        closestDistance = distance;
                    }
                }
            }
        }
        return Optional.ofNullable(closest);
    }

    static Optional<ConversionSpot> findPowderSpotAroundWater(LevelReader world, BlockPos waterPosition,
                                                               AltoClef mod) {
        if (world == null || waterPosition == null) return Optional.empty();
        for (Direction direction : Direction.values()) {
            if (direction.getAxis() == Direction.Axis.Y) continue;
            BlockPos powder = waterPosition.relative(direction);
            BlockPos support = powder.below();
            if (!isReplaceableForPowder(world.getBlockState(powder))) continue;
            if (!world.getBlockState(support).isRedstoneConductor(world, support)) continue;
            if (mod != null && (!WorldHelper.canReach(mod, powder) || !WorldHelper.canReach(mod, support))) continue;
            return Optional.of(new ConversionSpot(waterPosition, powder, support));
        }
        return Optional.empty();
    }

    private static boolean isReplaceableForPowder(BlockState state) {
        return state.isAir() || state.getBlock() == Blocks.WATER;
    }

    static List<BlockPos> safePowderApproachPositions(BlockPos powderPosition, BlockPos supportPosition,
                                                       LevelReader world) {
        if (powderPosition == null || supportPosition == null || world == null) return List.of();
        List<BlockPos> result = new ArrayList<>();
        for (int radius = 1; radius <= 4; radius++) {
            for (int x = -radius; x <= radius; x++) {
                int z = radius - Math.abs(x);
                for (int signedZ : z == 0 ? new int[]{0} : new int[]{-z, z}) {
                    for (int verticalOffset : new int[]{0, 1, -1}) {
                        BlockPos stance = supportPosition.offset(x, verticalOffset + 1, signedZ);
                        if (isSafePowderStance(world, stance) && !result.contains(stance)) result.add(stance);
                    }
                }
            }
        }
        result.sort(Comparator.comparingInt(pos -> pos.distManhattan(powderPosition)));
        return result.stream().limit(8).toList();
    }

    private static boolean isSafePowderStance(LevelReader world, BlockPos feet) {
        if (world.isOutsideBuildHeight(feet) || world.isOutsideBuildHeight(feet.above())) return false;
        BlockPos head = feet.above();
        BlockState feetState = world.getBlockState(feet);
        BlockState headState = world.getBlockState(head);
        if (!feetState.getCollisionShape(world, feet).isEmpty()
                || !headState.getCollisionShape(world, head).isEmpty()
                || !world.getFluidState(feet).isEmpty()
                || !world.getFluidState(head).isEmpty()) return false;
        BlockPos floorPos = feet.below();
        return world.getBlockState(floorPos).isFaceSturdy(world, floorPos, Direction.UP);
    }

    private final class WaterSearchTask extends Task {
        private final TimeoutWanderTask _wander = new TimeoutWanderTask(true);
        private int _ticks;
        private boolean _failed;

        @Override
        protected Task onTick(AltoClef mod) {
            if (findNaturalConversionSpot(mod).isPresent()) return null;
            if (++_ticks > WATER_SEARCH_LIMIT_TICKS) {
                _failed = true;
                if (_wander.isActive()) _wander.stop(mod);
                return null;
            }
            return _wander;
        }

        @Override
        public boolean isFinished(AltoClef mod) {
            return _failed || findNaturalConversionSpot(mod).isPresent();
        }

        private boolean hasFailed() { return _failed; }

        private String getFailureReason() {
            return "No reachable source water with a supported concrete placement spot was found.";
        }

        @Override
        protected boolean isEqual(Task other) {
            return other != null && other.getClass() == getClass();
        }

        @Override
        protected void onStart(AltoClef mod) {}

        @Override
        protected void onStop(AltoClef mod, Task interruptTask) {}

        @Override
        protected String toDebugString() {
            return "Explore for source water to convert concrete";
        }
    }

    private final class ConcretePickupTask extends Task {
        private static final int PICKUP_TASK_LIMIT_TICKS = 100;
        private final int _targetCount;
        private final PickupDroppedItemTask _pickup = new PickupDroppedItemTask(
                new ItemTarget(_color.concrete, 1), false);
        private int _ticks;
        private boolean _failed;

        private ConcretePickupTask(int targetCount) {
            _targetCount = targetCount;
        }

        @Override
        protected Task onTick(AltoClef mod) {
            if (StorageHelper.getAccessibleInventoryItemCount(
                    mod, new ItemTarget(_color.concrete, _targetCount)) >= _targetCount) return null;
            if (++_ticks > PICKUP_TASK_LIMIT_TICKS) {
                _failed = true;
                if (_pickup.isActive()) _pickup.stop(mod);
                return null;
            }
            if (mod.getEntityTracker().itemDropped(new ItemTarget(_color.concrete, 1))) return _pickup;
            return null;
        }

        @Override
        public boolean isFinished(AltoClef mod) {
            return _failed || StorageHelper.getAccessibleInventoryItemCount(
                    mod, new ItemTarget(_color.concrete, _targetCount)) >= _targetCount;
        }

        private boolean hasFailed() { return _failed; }

        private String getFailureReason() {
            return "Mined concrete at " + positionString(_activePowderPosition)
                    + " but its item was not collected.";
        }

        @Override
        protected boolean isEqual(Task other) {
            return other instanceof CollectConcreteTask.ConcretePickupTask task
                    && task._targetCount == _targetCount;
        }

        @Override
        protected String toDebugString() {
            return "Collect mined concrete drop";
        }

        @Override
        protected void onStart(AltoClef mod) {}

        @Override
        protected void onStop(AltoClef mod, Task interruptTask) {}
    }

    static boolean isConvertedConcrete(BlockState actual, Block expected) {
        return actual != null && expected != null && actual.getBlock() == expected;
    }

    private void fail(AltoClef mod, String reason) {
        _failed = true;
        _failureReason = reason;
        Debug.logError("Concrete collection failed: " + reason);
        if (_childTask != null && _childTask.isActive()) _childTask.stop(mod);
        _childTask = null;
    }

    private static String positionString(BlockPos pos) {
        return pos == null ? "unknown position" : pos.toShortString();
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        if (_failed) return true;
        return super.isFinished(mod);
    }

    public boolean hasFailed() {
        return _failed;
    }

    public String getFailureReason() {
        return _failureReason;
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(Blocks.WATER);
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        return other instanceof CollectConcreteTask task
                && task._color.concrete == _color.concrete
                && task.getItemTargets()[0].getTargetCount() == getItemTargets()[0].getTargetCount();
    }

    @Override
    protected String toDebugStringName() {
        return "Collect " + _color.colorName + " concrete";
    }

    record ConversionSpot(BlockPos waterPosition, BlockPos powderPosition, BlockPos supportPosition) {}

    private static final class ObservedPowderPlacementTask extends Task {
        private static final int APPROACH_TIMEOUT_TICKS = 200;
        private static final int CLICK_WAIT_TICKS = 30;
        private static final int AIM_WAIT_TICKS = 30;
        private static final int MAX_CLICK_ATTEMPTS = 2;
        private final Block _powder;
        private final Block _concrete;
        private final BlockPos _powderPosition;
        private final BlockPos _supportPosition;
        private final List<BlockPos> _approachPositions;
        private int _candidate;
        private int _approachWaitTicks;
        private int _aimWaitTicks;
        private int _clickWaitTicks;
        private int _clickAttempts;
        private int _conversionWaitTicks;
        private boolean _clicked;
        private boolean _failed;
        private String _failureReason;

        private ObservedPowderPlacementTask(Block powder, Block concrete,
                                            BlockPos powderPosition, BlockPos supportPosition,
                                            LevelReader world) {
            _powder = powder;
            _concrete = concrete;
            _powderPosition = powderPosition;
            _supportPosition = supportPosition;
            _approachPositions = safePowderApproachPositions(powderPosition, supportPosition, world);
        }

        @Override
        protected void onStart(AltoClef mod) {
            _candidate = 0;
            _approachWaitTicks = 0;
            _aimWaitTicks = 0;
            _clickWaitTicks = 0;
            _clickAttempts = 0;
            _conversionWaitTicks = 0;
            _clicked = false;
            _failed = false;
            _failureReason = null;
        }

        @Override
        protected Task onTick(AltoClef mod) {
            if (mod.getWorld() == null || mod.getPlayer() == null) return null;
            BlockState actual = mod.getWorld().getBlockState(_powderPosition);
            if (isConvertedConcrete(actual, _concrete)) return null;
            if (actual.getBlock() == _powder) {
                releaseInteraction(mod);
                if (++_conversionWaitTicks > CONVERSION_WAIT_LIMIT_TICKS) {
                    _failed = true;
                    _failureReason = "Concrete powder at " + _powderPosition.toShortString()
                            + " did not convert beside water.";
                }
                return null;
            }
            if (!actual.isAir() && actual.getBlock() != Blocks.WATER) {
                _failed = true;
                _failureReason = "Concrete powder target " + _powderPosition.toShortString()
                        + " became " + actual + " before placement.";
                return null;
            }
            if (!StorageHelper.itemTargetsMetAccessibleInventory(mod, new ItemTarget(_powder.asItem(), 1))) {
                setDebugState("Gathering concrete powder for placement");
                return TaskCatalogue.getItemTask(new ItemTarget(_powder.asItem(), 1));
            }
            if (_approachPositions.isEmpty()) {
                fail("No safe grounded approach to place concrete powder at "
                        + _powderPosition.toShortString() + ".");
                return null;
            }
            if (_candidate >= _approachPositions.size()) {
                fail("Could not place concrete powder into water at "
                        + _powderPosition.toShortString() + ".");
                return null;
            }

            BlockPos approach = _approachPositions.get(_candidate);
            ICustomGoalProcess goals = mod.getClientBaritone().getCustomGoalProcess();
            if (!approach.equals(mod.getPlayer().blockPosition())) {
                _clicked = false;
                _clickWaitTicks = 0;
                if (!goals.isActive()) goals.setGoalAndPath(new GoalBlock(approach));
                setDebugState("Approaching a safe stance to place concrete powder at "
                        + _powderPosition.toShortString());
                if (++_approachWaitTicks >= APPROACH_TIMEOUT_TICKS) {
                    nextApproach(mod);
                }
                return null;
            }
            _approachWaitTicks = 0;
            if (goals.isActive()) goals.onLostControl();

            if (_clicked) {
                if (++_clickWaitTicks >= CLICK_WAIT_TICKS) {
                    if (_clickAttempts >= MAX_CLICK_ATTEMPTS) nextApproach(mod);
                    else _clicked = false;
                }
                return null;
            }

            if (++_aimWaitTicks >= AIM_WAIT_TICKS) {
                nextApproach(mod);
                return null;
            }
            boolean aimAtFluidCell = actual.getBlock() == Blocks.WATER && _aimWaitTicks <= AIM_WAIT_TICKS / 2;
            BlockPos aimedAt = aimAtFluidCell ? _powderPosition : _supportPosition;
            Direction aimFace = aimAtFluidCell
                    ? directionFromTo(_powderPosition, approach).orElse(Direction.UP) : Direction.UP;
            LookHelper.lookAt(mod, aimedAt, aimFace);
            HitResult hit = Minecraft.getInstance().hitResult;
            if (!(hit instanceof BlockHitResult blockHit) || blockHit.getType() != HitResult.Type.BLOCK) {
                return null;
            }
            ItemStack held = mod.getPlayer().getMainHandItem();
            if (held.isEmpty() || held.getItem() != _powder.asItem()) {
                if (!mod.getSlotHandler().forceEquipItem(new ItemTarget(_powder.asItem(), 1), false)) return null;
                held = mod.getPlayer().getMainHandItem();
                if (held.isEmpty() || held.getItem() != _powder.asItem()) return null;
            }
            BlockPlaceContext placement = new BlockPlaceContext(
                    mod.getPlayer(), InteractionHand.MAIN_HAND, held, blockHit);
            BlockState predicted = _powder.getStateForPlacement(placement);
            if (!placement.canPlace() || predicted == null
                    || !placement.getClickedPos().equals(_powderPosition)) return null;

            if (goals.isActive()) goals.onLostControl();
            mod.getInputControls().hold(Input.SNEAK);
            mod.getInputControls().tryPress(Input.CLICK_RIGHT);
            _clicked = true;
            _clickAttempts++;
            _aimWaitTicks = 0;
            _clickWaitTicks = 0;
            setDebugState("Placing concrete powder at " + _powderPosition.toShortString());
            return null;
        }

        @Override
        public boolean isFinished(AltoClef mod) {
            return _failed || mod.getWorld() != null
                    && isConvertedConcrete(mod.getWorld().getBlockState(_powderPosition), _concrete);
        }

        private boolean hasFailed() { return _failed; }
        private String getFailureReason() { return _failureReason; }

        private void nextApproach(AltoClef mod) {
            releaseInteraction(mod);
            _candidate++;
            _approachWaitTicks = 0;
            _aimWaitTicks = 0;
            _clickWaitTicks = 0;
            _clickAttempts = 0;
            _clicked = false;
        }

        private void fail(String reason) {
            _failed = true;
            _failureReason = reason;
        }

        private void releaseInteraction(AltoClef mod) {
            mod.getInputControls().release(Input.SNEAK);
            mod.getInputControls().release(Input.CLICK_RIGHT);
            mod.getClientBaritone().getCustomGoalProcess().onLostControl();
        }

        private Optional<Direction> directionFromTo(BlockPos target, BlockPos approach) {
            int dx = approach.getX() - target.getX();
            int dz = approach.getZ() - target.getZ();
            if (Math.abs(dx) > Math.abs(dz)) {
                return Optional.of(dx < 0 ? Direction.WEST : Direction.EAST);
            }
            if (dz != 0) return Optional.of(dz < 0 ? Direction.NORTH : Direction.SOUTH);
            if (dx != 0) return Optional.of(dx < 0 ? Direction.WEST : Direction.EAST);
            return Optional.empty();
        }

        @Override
        protected void onStop(AltoClef mod, Task interruptTask) {
            releaseInteraction(mod);
        }

        @Override
        protected boolean isEqual(Task other) {
            return other instanceof ObservedPowderPlacementTask task
                    && _powderPosition.equals(task._powderPosition)
                    && _supportPosition.equals(task._supportPosition)
                    && _powder.equals(task._powder);
        }

        @Override
        protected String toDebugString() {
            return "Place concrete powder at " + _powderPosition.toShortString();
        }
    }

}
