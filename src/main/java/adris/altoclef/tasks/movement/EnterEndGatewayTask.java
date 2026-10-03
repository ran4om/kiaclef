package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.resources.GetBuildingMaterialsTask;
import adris.altoclef.tasks.squashed.CataloguedResourceTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.baritone.GoalAnd;
import adris.altoclef.util.helpers.StorageHelper;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.pathing.goals.GoalYLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.item.Items;

/**
 * Enters an outer End gateway using the pearl approach required by the gateway's bedrock cage.
 * The block itself is not a walkable target: the surrounding bedrock ring blocks a player-sized
 * path into the portal, so the bot builds/reaches a nearby firing position and throws a pearl in.
 */
public class EnterEndGatewayTask extends Task {

    private static final Vec3i[] APPROACH_OFFSETS = {
            new Vec3i(1, -1, 1),
            new Vec3i(1, -1, -1),
            new Vec3i(-1, -1, 1),
            new Vec3i(-1, -1, -1),
            new Vec3i(2, -1, 0),
            new Vec3i(0, -1, 2),
            new Vec3i(-2, -1, 0),
            new Vec3i(0, -1, -2)
    };

    private final BlockPos _gateway;

    public EnterEndGatewayTask(BlockPos gateway) {
        _gateway = gateway.immutable();
    }

    @Override
    protected void onStart(AltoClef mod) {
        mod.getBehaviour().push();
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (!mod.getItemStorage().hasItemInventoryOnly(Items.ENDER_PEARL)) {
            setDebugState("Getting an ender pearl to enter the bedrock-enclosed gateway");
            return new CataloguedResourceTask(new ItemTarget(Items.ENDER_PEARL, 1));
        }

        int blocksNeeded = buildingBlocksNeeded(mod.getPlayer().blockPosition(), _gateway);
        if (StorageHelper.getBuildingMaterialCount(mod) < blocksNeeded) {
            setDebugState("Getting building materials to reach the gateway firing position");
            return new GetBuildingMaterialsTask(blocksNeeded);
        }

        GoalAnd approachGoal = makeApproachGoal(_gateway);
        if (!approachGoal.isInGoal(mod.getPlayer().blockPosition()) || !mod.getPlayer().onGround()) {
            mod.getClientBaritone().getCustomGoalProcess().setGoal(approachGoal);
            if (!mod.getClientBaritone().getPathingBehavior().isPathing()) {
                mod.getClientBaritone().getCustomGoalProcess().path();
            }
            setDebugState("Getting to a safe firing position beside the gateway");
            return null;
        }

        setDebugState("Throwing an ender pearl through the gateway");
        return new InteractWithBlockTask(Items.ENDER_PEARL, _gateway);
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getBehaviour().pop();
        mod.getClientBaritone().getCustomGoalProcess().onLostControl();
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof EnterEndGatewayTask task && task._gateway.equals(_gateway);
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        // The parent dimension task selects its central-island route as soon as the gateway
        // teleports us. This child intentionally does not claim success while still in the End.
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Entering outer End gateway at " + _gateway;
    }

    static int buildingBlocksNeeded(BlockPos player, BlockPos gateway) {
        return Math.max(0, Math.abs(player.getY() - gateway.getY())
                + Math.abs(player.getX() - gateway.getX())
                + Math.abs(player.getZ() - gateway.getZ()) - 3);
    }

    static BlockPos[] approachPositions(BlockPos gateway) {
        BlockPos[] result = new BlockPos[APPROACH_OFFSETS.length];
        for (int i = 0; i < APPROACH_OFFSETS.length; i++) {
            result[i] = gateway.offset(APPROACH_OFFSETS[i]);
        }
        return result;
    }

    private static GoalAnd makeApproachGoal(BlockPos gateway) {
        BlockPos[] positions = approachPositions(gateway);
        GoalGetToBlock[] goals = new GoalGetToBlock[positions.length];
        for (int i = 0; i < positions.length; i++) {
            goals[i] = new GoalGetToBlock(positions[i]);
        }
        return new GoalAnd(new GoalComposite(goals), new GoalYLevel(gateway.getY() - 1));
    }
}
