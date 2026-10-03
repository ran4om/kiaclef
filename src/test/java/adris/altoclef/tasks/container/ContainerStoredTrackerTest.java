package adris.altoclef.tasks.container;

import adris.altoclef.testing.MinecraftTestBootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.Optional;

class ContainerStoredTrackerTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    void unstoredCountIsBoundedByRemainingTargetAndAvailableItems() {
        assertEquals(3, ContainerStoredTracker.remainingAvailableCount(64, 0, 3));
        assertEquals(6, ContainerStoredTracker.remainingAvailableCount(10, 4, 8));
        assertEquals(0, ContainerStoredTracker.remainingAvailableCount(10, 10, 8));
        assertEquals(0, ContainerStoredTracker.remainingAvailableCount(10, 12, 8));
        assertEquals(0, ContainerStoredTracker.remainingAvailableCount(10, 4, 0));
    }

    @Test
    void accessibleCountIncludesOnlyMatchingCursorItems() {
        assertEquals(5, ContainerStoredTracker.accessibleItemCount(3, 2, true));
        assertEquals(3, ContainerStoredTracker.accessibleItemCount(3, 2, false));
    }

    @Test
    void depositAccountingIgnoresCraftingMenusAndPlayerInventorySlots() {
        assertFalse(ContainerStoredTracker.shouldCountDeposit(InventoryMenu.class, false, true));
        assertFalse(ContainerStoredTracker.shouldCountDeposit(CraftingMenu.class, false, true));
        assertFalse(ContainerStoredTracker.shouldCountDeposit(ChestMenu.class, true, true));
        assertTrue(ContainerStoredTracker.shouldCountDeposit(ChestMenu.class, false, true));
        assertFalse(ContainerStoredTracker.shouldCountDeposit(ChestMenu.class, false, false));
    }

    @Test
    void containerReceiptsRequireABlockConfiguredForTheActiveTask() {
        assertTrue(ContainerStoredTracker.isConfiguredContainerBlock(Blocks.CHEST,
                new Block[]{Blocks.CHEST}));
        assertFalse(ContainerStoredTracker.isConfiguredContainerBlock(Blocks.FURNACE,
                new Block[]{Blocks.CHEST, Blocks.BARREL}));
    }

    @Test
    void boundPositionCanBeFilteredForConfiguredContainerRangeAndFixedTarget() {
        BlockPos target = new BlockPos(5, 72, -12);
        Optional<BlockPos> bound = Optional.of(target);

        assertTrue(ContainerStoredTracker.acceptsBoundContainer(bound, target::equals));
        assertFalse(ContainerStoredTracker.acceptsBoundContainer(bound,
                position -> position.getX() >= 0 && position.getX() <= 4));
        assertFalse(ContainerStoredTracker.acceptsBoundContainer(Optional.empty(), ignored -> true));
    }

    @Test
    void explicitNewRunClearsStoredReceipts() {
        ContainerStoredTracker tracker = new ContainerStoredTracker(ignored -> true);
        tracker.recordChange(Items.DIAMOND, 3);

        assertEquals(3, tracker.getStoredCount(Items.DIAMOND));
        tracker.resetForNewRun();
        assertEquals(0, tracker.getStoredCount(Items.DIAMOND));
    }
}
