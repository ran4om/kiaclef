package adris.altoclef.tasks.container;

import adris.altoclef.util.ItemTarget;
import adris.altoclef.testing.MinecraftTestBootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.Optional;

class ContainerDepositCompletionTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    void depositDoesNotFinishWhileCursorCarriesRemainder() {
        assertFalse(ContainerDepositCompletion.isComplete(true, false, true, false));
        assertFalse(ContainerDepositCompletion.isComplete(true, false, true, true));
    }

    @Test
    void depositDoesNotFinishWhileContainerMenuRemainsOpen() {
        assertFalse(ContainerDepositCompletion.isComplete(true, true, true, false));
    }

    @Test
    void depositDoesNotFinishWithItemsLeftInCraftingGrid() {
        assertFalse(ContainerDepositCompletion.isComplete(true, true, false, true));
    }

    @Test
    void depositFinishesOnlyAfterRequestedCountCursorAndMenuAreClean() {
        assertFalse(ContainerDepositCompletion.isComplete(false, true, true, true));
        assertTrue(ContainerDepositCompletion.isComplete(true, true, true, true));
    }

    @Test
    void transferGoalIncludesExistingItemsAndNeverExceedsStackCapacity() {
        int deposit = ContainerDepositCompletion.depositCountForSlot(1, 2, 64);
        assertEquals(1, deposit);
        assertEquals(3, ContainerDepositCompletion.destinationStackGoal(2, deposit));

        int capacityLimited = ContainerDepositCompletion.depositCountForSlot(5, 63, 64);
        assertEquals(1, capacityLimited);
        assertEquals(64, ContainerDepositCompletion.destinationStackGoal(63, capacityLimited));
        assertEquals(0, ContainerDepositCompletion.depositCountForSlot(5, 64, 64));
    }

    @Test
    void activeTransferIdentityKeepsShrinkingGoalsButRestartsWhenGoalGrows() {
        assertTrue(StoreInContainerTask.sameTargetKinds(
                new ItemTarget[]{new ItemTarget(Items.DIAMOND, 3)},
                new ItemTarget[]{new ItemTarget(Items.DIAMOND, 2)}));
        assertFalse(StoreInContainerTask.sameTargetKinds(
                new ItemTarget[]{new ItemTarget(Items.DIAMOND, 2)},
                new ItemTarget[]{new ItemTarget(Items.DIAMOND, 3)}));
        assertFalse(StoreInContainerTask.sameTargetKinds(
                new ItemTarget[]{new ItemTarget(Items.DIAMOND, 3)},
                new ItemTarget[]{new ItemTarget(Items.EMERALD, 3)}));
    }

    @Test
    void activeTransferRequiresTheSameMenuAndBoundTarget() {
        Object originalMenu = new Object();
        Object replacementMenu = new Object();
        BlockPos target = new BlockPos(5, 72, -12);

        assertTrue(ContainerDepositCompletion.isTransferSessionValid(
                originalMenu, originalMenu, Optional.of(target), target));
        assertFalse(ContainerDepositCompletion.isTransferSessionValid(
                originalMenu, replacementMenu, Optional.of(target), target));
        assertFalse(ContainerDepositCompletion.isTransferSessionValid(
                originalMenu, originalMenu, Optional.of(target.offset(1, 0, 0)), target));
        assertFalse(ContainerDepositCompletion.isTransferSessionValid(
                originalMenu, originalMenu, Optional.empty(), target));
    }
}
