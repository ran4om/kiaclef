package adris.altoclef.tasks.construction;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.movement.RunAwayFromPositionTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.baritone.PlaceBlockSchematic;
import adris.altoclef.util.time.TimerGame;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.BlockPos;

import java.util.Optional;

/**
 * Destroy a block at a position.
 */
public class DestroyBlockTask extends Task implements ITaskRequiresGrounded {

    private final BlockPos _pos;
    private final boolean _directOnly;
    private final MovementProgressChecker _moveChecker = new MovementProgressChecker(6, 0.1, 4, 0.01);
    private final TimeoutWanderTask _wanderTask = new TimeoutWanderTask(5, true);

    private final TimerGame _tryToMineTimer = new TimerGame(5);

    // For vines and stuff
    private boolean _wasClose = false;

    public DestroyBlockTask(BlockPos pos) {
        this(pos, false);
    }

    /**
     * @param directOnly refuse all travel and builder fallback; mine only while grounded
     *                   and in direct interaction reach
     */
    public DestroyBlockTask(BlockPos pos, boolean directOnly) {
        _pos = pos;
        _directOnly = directOnly;
    }

    @Override
    protected void onStart(AltoClef mod) {
        _tryToMineTimer.forceElapse();
        _wanderTask.resetWander();
        StorageHelper.closeScreen();

        mod.getBehaviour().push();
        // Avoid placing on top, to prevent our annoying "run away" bug
        mod.getBehaviour().avoidBlockPlacing(pos -> _pos.above().equals(pos));
    }

    @Override
    protected Task onTick(AltoClef mod) {
        Optional<Rotation> directReach = _directOnly ? LookHelper.getReach(_pos) : Optional.empty();
        if (_directOnly) {
            if (!canDirectlyBreak(mod.getPlayer().onGround(), directReach.isPresent())) {
                mod.getClientBaritone().getInputOverrideHandler()
                        .setInputForceState(Input.CLICK_LEFT, false);
                setDebugState("Waiting for grounded direct reach; no travel or builder fallback allowed.");
                return null;
            }
        }

        // Wander and check
        if (_wanderTask.isActive() && !_wanderTask.isFinished(mod)) {
            _moveChecker.reset();
            return getRecoveryWanderTask(mod, _pos);
        }
        if (!_moveChecker.check(mod)) {
            _moveChecker.reset();
            _wanderTask.resetWander();
            Task recoveryTask = getRecoveryWanderTask(mod, _pos);
            if (recoveryTask == null) {
                // A caller that disables recovery travel wants another target selected.
                mod.getBlockTracker().requestBlockUnreachable(_pos, 0);
                return null;
            }
            mod.getBlockTracker().requestBlockUnreachable(_pos);
            return recoveryTask;
        }

        // do NOT break if we're standing above it and it's dangerous below...
        if (!WorldHelper.isSolid(mod, _pos.above()) && mod.getPlayer().position().y > _pos.getY() && _pos.closerToCenterThan(mod.getPlayer().onGround()? mod.getPlayer().position() : mod.getPlayer().position().add(0, -1, 0), 0.89)) {
            if (WorldHelper.dangerousToBreakIfRightAbove(mod, _pos)) {
                setDebugState("It's dangerous to break as we're right above it, moving away and trying again.");
                Task recoveryTask = getDangerousBreakRecoveryTask(mod, _pos);
                if (recoveryTask == null) {
                    // Do not keep selecting a target whose required safety recovery is unavailable.
                    mod.getBlockTracker().requestBlockUnreachable(_pos, 0);
                }
                return recoveryTask;
            }
        }

        // We're trying to mine
        Optional<Rotation> reach = _directOnly ? directReach : LookHelper.getReach(_pos);
        if (reach.isPresent()) {
            _tryToMineTimer.reset();
        }
        if (!_tryToMineTimer.elapsed()) {
            if (reach.isPresent() && (mod.getPlayer().isInWater() || mod.getPlayer().onGround())) {
                setDebugState("Block in range, mining...");
                // Break the block, force it.
                mod.getClientBaritone().getCustomGoalProcess().onLostControl();
                mod.getClientBaritone().getBuilderProcess().onLostControl();
                var blockState = mod.getWorld().getBlockState(_pos);
                var heldStack = StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot());
                if (mod.getBehaviour().shouldAvoidUseTool(blockState, heldStack)) {
                    StorageHelper.getSafeHandSlot(mod, blockState)
                            .ifPresent(mod.getSlotHandler()::forceEquipSlot);
                    mod.getClientBaritone().getInputOverrideHandler()
                            .setInputForceState(Input.CLICK_LEFT, false);
                    setDebugState("Waiting for a tool that preserves this block's drop");
                    return null;
                }
                if (!LookHelper.isLookingAt(mod, _pos)) {
                    LookHelper.lookAt(mod, reach.get());
                }
                if (LookHelper.isLookingAt(mod, _pos)) {
                    // Tool equip is handled in `PlayerInteractionFixChain`. Oof.
                    mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
                }
            } else {
                setDebugState("Breaking the normal way.");
                if (!mod.getClientBaritone().getBuilderProcess().isActive()) {
                    // Try breaking normally.
                    mod.getClientBaritone().getCustomGoalProcess().onLostControl();
                    Debug.logMessage("Break Block: Restarting builder process");
                    mod.getClientBaritone().getBuilderProcess().build("destroy block", new PlaceBlockSchematic(Blocks.AIR), _pos);
                }
            }
        } else {
            setDebugState("Getting to block...");
            boolean isClose = _pos.closerToCenterThan(mod.getPlayer().position(), 1);
            if (isClose != _wasClose) {
                mod.getClientBaritone().getCustomGoalProcess().onLostControl();
                _wasClose = isClose;
            }
            if (!mod.getClientBaritone().getCustomGoalProcess().isActive()) {
                mod.getClientBaritone().getBuilderProcess().onLostControl();
                // If we're close, go TO the block (potentially disrupts vines and stuff)
                mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(isClose? new GoalBlock(_pos) : new GoalNear(_pos, 1));
            }
        }

        return null;
    }

    /** Returns the movement task used when pathing to this block appears stuck. */
    protected Task getRecoveryWanderTask(AltoClef mod, BlockPos pos) {
        if (_directOnly) return null;
        return _wanderTask;
    }

    /** Returns the movement task used to retreat before breaking a dangerous block. */
    protected Task getDangerousBreakRecoveryTask(AltoClef mod, BlockPos pos) {
        if (_directOnly) return null;
        return new RunAwayFromPositionTask(3, pos.getY(), pos);
    }

    static boolean canDirectlyBreak(boolean onGround, boolean hasLookReach) {
        return onGround && hasLookReach;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        if (!AltoClef.inGame())
            return;
        mod.getBehaviour().pop();
        mod.getClientBaritone().getBuilderProcess().onLostControl();
        mod.getClientBaritone().getCustomGoalProcess().onLostControl();
        // Do not keep breaking.
        // Can lead to trouble, for example, if lava is right above the NEXT block.
        mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return WorldHelper.isAir(mod, _pos);//;
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof DestroyBlockTask task) {
            return task._pos.equals(_pos) && task._directOnly == _directOnly;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Destroy block at " + _pos.toShortString();
    }
}
