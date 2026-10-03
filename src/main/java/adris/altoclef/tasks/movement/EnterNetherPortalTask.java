package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.DoToClosestBlockTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.time.TimerGame;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

import java.util.Objects;
import java.util.function.Predicate;

public class EnterNetherPortalTask extends Task {

    private final Task _getPortalTask;
    private final Dimension _targetDimension;

    private final TimerGame _portalTimeout = new TimerGame(10);
    private final TimeoutWanderTask _wanderTask = new TimeoutWanderTask(2);
    private static final int PORTAL_COOLDOWN_RECOVERY_TICKS = 20;
    private final PortalCooldownRecovery _portalRecovery = new PortalCooldownRecovery(PORTAL_COOLDOWN_RECOVERY_TICKS);

    private final Predicate<BlockPos> _goodPortal;

    private Task _escapeTask;

    public EnterNetherPortalTask(Task getPortalTask, Dimension targetDimension, Predicate<BlockPos> goodPortal) {
        if (targetDimension == Dimension.END)
            throw new IllegalArgumentException("Can't build a nether portal to the end.");
        _getPortalTask = getPortalTask;
        _targetDimension = targetDimension;
        _goodPortal = goodPortal;
    }

    public EnterNetherPortalTask(Dimension targetDimension, Predicate<BlockPos> goodPortal) {
        this(null, targetDimension, goodPortal);
    }
    public EnterNetherPortalTask(Task getPortalTask, Dimension targetDimension) {
        this(getPortalTask, targetDimension, blockPos -> true);
    }
    public EnterNetherPortalTask(Dimension targetDimension) {
        this(null, targetDimension);
    }

    @Override
    protected void onStart(AltoClef mod) {
        mod.getBlockTracker().trackBlock(Blocks.NETHER_PORTAL);
        _portalTimeout.reset();

        _wanderTask.resetWander();
        _portalRecovery.reset();
        _escapeTask = null;
    }

    @Override
    protected Task onTick(AltoClef mod) {

        if (_portalRecovery.isActive()) {
            boolean insidePortal = intersectsNetherPortal(mod);
            if (_portalRecovery.tick(insidePortal)) {
                _portalTimeout.reset();
                _portalRecovery.reset();
                _escapeTask = null;
                _wanderTask.resetWander();
            } else {
                setDebugState(insidePortal ? "Escaping portal to reset cooldown." : "Waiting outside portal to reset cooldown.");
                return insidePortal ? escapeTask(mod) : null;
            }
        }

        if (intersectsNetherPortal(mod)) {
            if (_portalTimeout.elapsed()) {
                _portalRecovery.begin();
                setDebugState("Portal timed out; recovering portal cooldown.");
                return escapeTask(mod);
            }
            setDebugState("Waiting inside portal");
            return null;
        } else {
            _portalTimeout.reset();
        }

        Predicate<BlockPos> standablePortal = blockPos -> {
            // REQUIRE that there be solid ground beneath us, not more portal.
            if (!mod.getChunkTracker().isChunkLoaded(blockPos)) {
                // Eh just assume it's good for now
                return true;
            }
            BlockPos below = blockPos.below();
            boolean canStand = WorldHelper.isSolid(mod, below) && !mod.getBlockTracker().blockIsValid(below, Blocks.NETHER_PORTAL);
            return canStand && _goodPortal.test(blockPos);
        };

        if (mod.getBlockTracker().anyFound(standablePortal, Blocks.NETHER_PORTAL)) {
            setDebugState("Going to found portal");
            return new DoToClosestBlockTask(blockPos -> new GetToBlockTask(blockPos, false), standablePortal, Blocks.NETHER_PORTAL);
        }
        setDebugState("Getting our portal");
        return _getPortalTask;
    }

    private Task escapeTask(AltoClef mod) {
        if (_escapeTask != null && _escapeTask.isActive() && _escapeTask.isFinished(mod)) {
            _escapeTask.stop(mod);
            _escapeTask = null;
        }
        if (_escapeTask == null) {
            BlockPos target = findPortalEscapePosition(mod);
            if (target != null) {
                _escapeTask = new GetToBlockTask(target, false);
            } else {
                _wanderTask.reset();
                _wanderTask.resetWander();
                _escapeTask = _wanderTask;
            }
        }
        return _escapeTask;
    }

    private BlockPos findPortalEscapePosition(AltoClef mod) {
        var player = mod.getPlayer();
        if (player == null || mod.getWorld() == null) return null;
        BlockPos origin = player.blockPosition();
        // Restrict the search to loaded chunks and positions supported by solid ground.
        for (int radius = 2; radius <= 6; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    for (int dy = -1; dy <= 1; dy++) {
                        BlockPos feet = origin.offset(dx, dy, dz);
                        if (!mod.getChunkTracker().isChunkLoaded(feet)
                                || !mod.getWorld().getBlockState(feet).isAir()
                                || !mod.getWorld().getBlockState(feet.above()).isAir()
                                || !WorldHelper.isSolid(mod, feet.below())
                                || tooNearPortal(mod, feet)) continue;
                        return feet;
                    }
                }
            }
        }
        return null;
    }

    private boolean tooNearPortal(AltoClef mod, BlockPos position) {
        // Check the actual loaded world around the candidate because a portal may span
        // several blocks and the tracker cache can be one scan behind.
        for (BlockPos portal : BlockPos.betweenClosed(position.offset(-2, -2, -2), position.offset(2, 2, 2))) {
            if (mod.getChunkTracker().isChunkLoaded(portal)
                    && mod.getWorld().getBlockState(portal).getBlock() == Blocks.NETHER_PORTAL) return true;
        }
        return false;
    }

    private boolean intersectsNetherPortal(AltoClef mod) {
        if (mod.getPlayer() == null || mod.getWorld() == null) return false;
        AABB box = mod.getPlayer().getBoundingBox();
        BlockPos min = BlockPos.containing(box.minX, box.minY, box.minZ);
        BlockPos max = BlockPos.containing(box.maxX, box.maxY, box.maxZ);
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (mod.getChunkTracker().isChunkLoaded(pos)
                    && mod.getWorld().getBlockState(pos).getBlock() == Blocks.NETHER_PORTAL) return true;
        }
        return false;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(Blocks.NETHER_PORTAL);
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return WorldHelper.getCurrentDimension() == _targetDimension;
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof EnterNetherPortalTask task) {
            return (Objects.equals(task._getPortalTask, _getPortalTask) && Objects.equals(task._targetDimension, _targetDimension));
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Entering nether portal";
    }
}
