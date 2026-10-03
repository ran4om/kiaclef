package adris.altoclef.trackers.storage;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Associates explicit block interactions with the container menus they open.
 * Each click on the same block allows one menu to bind, so a repeated click that makes
 * the server close and reopen the container still binds the final menu, while an
 * unprompted replacement menu stays unbound.
 * Menu, player, and world comparisons intentionally use object identity.
 */
final class ContainerMenuSession {
    private static final long MAX_PENDING_AGE_TICKS = 40;
    private PendingInteraction pending;
    private BoundContainer bound;

    void noteInteraction(Object world, Object player, Object originMenu,
                         BlockPos position, Block block, long currentTick) {
        if (world == null || player == null || originMenu == null || position == null || block == null) {
            return;
        }
        expirePendingInteraction(currentTick);

        BlockPos immutablePosition = copy(position);
        boolean samePosition = pending != null
                && pending.position.equals(immutablePosition) && pending.block == block;
        if (pending == null
                || pending.world != world
                || pending.player != player
                || pending.originMenu != originMenu
                || (pending.hasBound && !samePosition)) {
            pending = new PendingInteraction(world, player, originMenu, immutablePosition, block);
            pending.createdAtTick = currentTick;
            pending.clicks = 1;
            return;
        }

        if (!samePosition) {
            pending.ambiguous = true;
        }
        pending.clicks++;
        pending.createdAtTick = currentTick;
    }

    void onScreenChanged(Object world, Object player, Object menu, boolean trackedContainerScreen,
                         long currentTick,
                         boolean screenMenuMatchesPlayerMenu,
                         Predicate<Block> blockMatchesMenu,
                         BiPredicate<BlockPos, Block> blockStillMatches) {
        if (!trackedContainerScreen) {
            // Keep the pending interaction: a repeated click closes and reopens the menu.
            bound = null;
            return;
        }

        if (bound != null && bound.menu != menu) {
            bound = null;
        }

        PendingInteraction candidate = pending;
        if (candidate == null || candidate.clicks <= 0 || (bound != null && bound.menu == menu)) {
            // No click left to account for this menu, or it is already bound.
            if (candidate != null && candidate.clicks <= 0) pending = null;
            return;
        }
        if (isExpired(candidate, currentTick) || candidate.ambiguous
                || world == null || player == null || menu == null
                || candidate.world != world || candidate.player != player
                || candidate.originMenu == menu || !screenMenuMatchesPlayerMenu
                || !blockMatchesMenu.test(candidate.block)
                || !blockStillMatches.test(candidate.position, candidate.block)) {
            if (Boolean.getBoolean("altoclef.containerSessionDiagnostics")) {
                adris.altoclef.Debug.logInternal("[CONTAINER_SESSION] reject expired=" + isExpired(candidate, currentTick)
                        + ",ambiguous=" + candidate.ambiguous + ",sameWorld=" + (candidate.world == world)
                        + ",samePlayer=" + (candidate.player == player) + ",menuChanged=" + (candidate.originMenu != menu)
                        + ",screenMenuMatchesPlayerMenu=" + screenMenuMatchesPlayerMenu
                        + ",blockMatchesMenu=" + blockMatchesMenu.test(candidate.block)
                        + ",tick=" + currentTick + ",created=" + candidate.createdAtTick);
            }
            pending = null;
            return;
        }

        bound = new BoundContainer(world, player, menu, candidate.position, candidate.block);
        candidate.clicks--;
        candidate.hasBound = true;
    }

    Optional<BoundContainer> getBoundContainer(Object world, Object player, Object currentMenu,
                                                BiPredicate<BlockPos, Block> blockStillMatches) {
        if (bound == null) return Optional.empty();
        if (bound.world != world || bound.player != player || bound.menu != currentMenu) {
            bound = null;
            return Optional.empty();
        }
        if (!blockStillMatches.test(bound.position, bound.block)) {
            bound = null;
            return Optional.empty();
        }
        return Optional.of(bound);
    }

    void clear() {
        pending = null;
        bound = null;
    }

    void clearPendingInteraction() {
        pending = null;
    }

    void expirePendingInteraction(long currentTick) {
        if (pending != null && isExpired(pending, currentTick)) pending = null;
    }

    private static boolean isExpired(PendingInteraction interaction, long currentTick) {
        long age = currentTick - interaction.createdAtTick;
        return age < 0 || age > MAX_PENDING_AGE_TICKS;
    }

    private static BlockPos copy(BlockPos position) {
        return new BlockPos(position.getX(), position.getY(), position.getZ());
    }

    record BoundContainer(Object world, Object player, Object menu, BlockPos position, Block block) {
        BoundContainer {
            position = copy(position);
        }
    }

    private static final class PendingInteraction {
        private final Object world;
        private final Object player;
        private final Object originMenu;
        private final BlockPos position;
        private final Block block;
        private boolean ambiguous;
        private boolean hasBound;
        private int clicks;
        private long createdAtTick;

        private PendingInteraction(Object world, Object player, Object originMenu,
                                   BlockPos position, Block block) {
            this.world = world;
            this.player = player;
            this.originMenu = originMenu;
            this.position = copy(position);
            this.block = block;
        }
    }
}
