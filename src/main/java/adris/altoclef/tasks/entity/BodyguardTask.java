package adris.altoclef.tasks.entity;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.movement.FollowPlayerTask;
import adris.altoclef.tasksystem.Task;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.entity.monster.Zoglin;
import net.minecraft.world.entity.player.Player;

import java.util.Comparator;
import java.util.Optional;

/** Follows a player and attacks nearby hostile mobs or untrusted players. */
public final class BodyguardTask extends Task {
    private static final double THREAT_RADIUS = 12;

    private final String _protectedPlayerName;

    public BodyguardTask(String protectedPlayerName) {
        _protectedPlayerName = protectedPlayerName;
    }

    @Override
    protected void onStart(AltoClef mod) {
        mod.getBehaviour().push();
        // The global MobDefenseChain forcefield attacks every nearby unauthorized
        // player without knowing who this task is protecting. BodyguardTask picks
        // player threats itself, so leave that indiscriminate player attack off.
        mod.getBehaviour().setForceFieldPlayers(false);
    }

    @Override
    protected Task onTick(AltoClef mod) {
        Optional<Player> protectedPlayer = mod.getEntityTracker().getPlayerEntity(_protectedPlayerName);
        if (protectedPlayer.isPresent()) {
            Player guarded = protectedPlayer.get();
            Optional<Entity> threat = mod.getEntityTracker().getAllTrackedEntities().stream()
                    .filter(entity -> isThreat(mod, guarded, entity))
                    .min(Comparator.comparingDouble(entity -> entity.distanceToSqr(guarded)));

            if (threat.isPresent()) {
                Entity entity = threat.get();
                setDebugState("Defending " + _protectedPlayerName + " from " + entity.getName().getString());
                if (entity instanceof Player player) {
                    return new KillPlayerTask(player.getName().getString());
                }
                return new KillEntityTask(entity);
            }
        }

        setDebugState("Following " + _protectedPlayerName);
        return new FollowPlayerTask(_protectedPlayerName);
    }

    private boolean isThreat(AltoClef mod, Player guarded, Entity entity) {
        if (entity == guarded || !entity.isAlive() || !entity.closerThan(guarded, THREAT_RADIUS)
                || !mod.getEntityTracker().isEntityReachable(entity)) {
            return false;
        }

        if (entity instanceof Player player) {
            String name = player.getName().getString();
            return !name.equalsIgnoreCase(_protectedPlayerName)
                    && !player.isDeadOrDying()
                    && !player.isCreative()
                    && !player.isSpectator()
                    && !mod.getButler().isUserAuthorized(name);
        }

        return entity instanceof Monster || entity instanceof Hoglin || entity instanceof Zoglin;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getBehaviour().pop();
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof BodyguardTask task
                && task._protectedPlayerName.equalsIgnoreCase(_protectedPlayerName);
    }

    @Override
    protected String toDebugString() {
        return "Bodyguarding " + _protectedPlayerName;
    }
}
