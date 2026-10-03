package adris.altoclef.util.helpers;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** One-tick handoff from Baritone's builder click to vanilla's block interaction. */
public final class BaritoneCrafterPlacementIntent {
    public record Intent(Object owner, Object player, BlockPos target, BlockPos support,
                         Direction face, float yaw, float pitch, long tick) {}

    private static long tick;
    private static Intent pending;

    /** Starts a builder tick and expires any click intent left over from the prior tick. */
    public static synchronized long beginTick() {
        tick++;
        pending = null;
        return tick;
    }

    public static synchronized void publish(Object owner, Object player, BlockPos target,
                                            BlockPos support, Direction face,
                                            float yaw, float pitch) {
        pending = new Intent(owner, player, target.immutable(), support.immutable(),
                face, yaw, pitch, tick);
    }

    /** Consumes exactly once for the owning player; same-player hit mismatches remain suppressible. */
    public static synchronized Intent consume(Object player) {
        Intent candidate = pending;
        pending = null;
        if (candidate == null || candidate.tick() != tick || candidate.player() != player) return null;
        return candidate;
    }

    public static boolean matchesHit(Intent intent, BlockPos support, Direction face) {
        return intent != null && intent.support().equals(support) && intent.face() == face;
    }

    public static boolean matchesTarget(Intent intent, BlockPos target) {
        return intent != null && intent.target().equals(target);
    }

    public static synchronized void clear() {
        pending = null;
    }

    private BaritoneCrafterPlacementIntent() {}
}
