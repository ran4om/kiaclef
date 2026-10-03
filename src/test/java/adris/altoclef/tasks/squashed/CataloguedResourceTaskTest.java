package adris.altoclef.tasks.squashed;

import adris.altoclef.testing.MinecraftTestBootstrap;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.resources.CollectCropTask;
import adris.altoclef.tasks.resources.CollectWheatTask;
import adris.altoclef.tasksystem.TaskFailure;
import adris.altoclef.util.ItemTarget;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CataloguedResourceTaskTest {
    @BeforeAll static void bootstrap() { MinecraftTestBootstrap.initialize(); }

    @Test void currentRecipeFinishesBeforeEarlierConsumedMaterialsAreReacquired() throws Exception {
        boolean[] finished = {false};
        ResourceTask recipe = new ResourceTask(Items.TORCH, 2) {
            @Override public boolean isFinished(adris.altoclef.AltoClef mod) { return finished[0]; }
            @Override protected void onResourceStart(adris.altoclef.AltoClef mod) {}
            @Override protected adris.altoclef.tasksystem.Task onResourceTick(adris.altoclef.AltoClef mod) { return null; }
            @Override protected void onResourceStop(adris.altoclef.AltoClef mod, adris.altoclef.tasksystem.Task task) {}
            @Override protected boolean isEqualResource(ResourceTask task) { return this == task; }
            @Override protected boolean shouldAvoidPickingUp(adris.altoclef.AltoClef mod) { return true; }
            @Override protected String toDebugStringName() { return "test recipe"; }
        };
        CataloguedResourceTask list = new CataloguedResourceTask(false, new ItemTarget[0]);
        var field = CataloguedResourceTask.class.getDeclaredField("_currentResourceTask");
        field.setAccessible(true);
        field.set(list, recipe);
        assertSame(recipe, list.onResourceTick(null));
        finished[0] = true;
        assertNull(list.onResourceTick(null));
        assertNull(field.get(list));
    }

    @Test void craftedOutputOnCursorDoesNotCompleteResourceList() {
        assertFalse(CataloguedResourceTask.itemTargetMetWithoutCursor(
                new ItemTarget(Items.STICK, 1), 4, new ItemStack(Items.STICK, 4)));
    }

    @Test void inventoryOutputCompletesAfterCursorIsStored() {
        assertTrue(CataloguedResourceTask.itemTargetMetWithoutCursor(
                new ItemTarget(Items.STICK, 1), 4, ItemStack.EMPTY));
    }

    @Test void unrelatedCursorDoesNotReduceStoredResourceCount() {
        assertTrue(CataloguedResourceTask.itemTargetMetWithoutCursor(
                new ItemTarget(Items.CHEST, 1), 1, new ItemStack(Items.STICK, 4)));
    }

    @Test void cataloguedGoalRequiresItsAccessibleInventoryCount() {
        ItemTarget planks = new ItemTarget(Items.OAK_PLANKS, 3);

        assertFalse(CataloguedResourceTask.itemTargetMetWithAccessibleCount(planks, 2));
        assertTrue(CataloguedResourceTask.itemTargetMetWithAccessibleCount(planks, 3));
    }

    @Test void incompleteFailedChildPropagatesButSatisfiedTargetDoesNot() {
        ItemTarget planks = new ItemTarget(Items.OAK_PLANKS, 3);
        TaskFailure failed = new TaskFailure() {
            @Override public boolean hasFailed() { return true; }
            @Override public String getFailureReason() { return "no logs found"; }
        };

        assertTrue(CataloguedResourceTask.isIncompleteFailedTarget(planks, 2, failed));
        assertFalse(CataloguedResourceTask.isIncompleteFailedTarget(planks, 3, failed));
    }

    @Test void catalogueDefersOnlyForPendingWheatReplant() {
        CollectWheatTask pendingWheat = new CollectWheatTask(2) {
            @Override public boolean hasPendingCropReplant(adris.altoclef.AltoClef mod) { return true; }
        };
        CollectWheatTask completedWheat = new CollectWheatTask(2) {
            @Override public boolean hasPendingCropReplant(adris.altoclef.AltoClef mod) { return false; }
        };
        ResourceTask otherResource = new CollectCropTask(
                new ItemTarget(Items.WHEAT, 1), net.minecraft.world.level.block.Blocks.WHEAT, Items.WHEAT_SEEDS);

        assertTrue(CataloguedResourceTask.shouldDeferForPendingWheatReplant(pendingWheat, null));
        assertFalse(CataloguedResourceTask.shouldDeferForPendingWheatReplant(completedWheat, null));
        assertFalse(CataloguedResourceTask.shouldDeferForPendingWheatReplant(otherResource, null));
    }
}
