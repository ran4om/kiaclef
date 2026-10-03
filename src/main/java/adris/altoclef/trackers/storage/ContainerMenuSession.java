package adris.altoclef.trackers.storage;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Associates one explicit block interaction with the container menu it opens.
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
        if (pending == null
                || pending.world != world
                || pending.player != player
                || pending.originMenu != originMenu) {
            pending = new PendingInteraction(world, player, originMenu, immutablePosition, block);
            pending.createdAtTick = currentTick;
            return;
        }

        if (!pending.position.equals(immutablePosition) || pending.block != block) {
            pending.ambiguous = true;
        }
        pending.createdAtTick = currentTick;
    }

    void onScreenChanged(Object world, Object player, Object menu, boolean trackedContainerScreen,
                         long currentTick,
                         boolean screenMenuMatchesPlayerMenu,
                         Predicate<Block> blockMatchesMenu,
                         BiPredicate<BlockPos, Block> blockStillMatches) {
        if (!trackedContainerScreen) {
            clear();
            return;
        }

        if (bound != null && bound.menu != menu) {
            bound = null;
        }

        PendingInteraction candidate = pending;
        pending = null;
        if (candidate == null || isExpired(candidate, currentTick) || candidate.ambiguous
                || world == null || player == null || menu == null
                || candidate.world != world || candidate.player != player
                || candidate.originMenu == menu || !screenMenuMatchesPlayerMenu
                || !blockMatchesMenu.test(candidate.block)
                || !blockStillMatches.test(candidate.position, candidate.block)) {
            return;
        }

        bound = new BoundContainer(world, player, menu, candidate.position, candidate.block);
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
