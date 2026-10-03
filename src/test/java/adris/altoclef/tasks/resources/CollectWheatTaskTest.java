package adris.altoclef.tasks.resources;

import adris.altoclef.testing.MinecraftTestBootstrap;
import adris.altoclef.util.ItemTarget;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

class CollectWheatTaskTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    void noCropChildMeansNoPendingReplant() {
        assertFalse(new CollectWheatTask(2).hasPendingCropReplant(null));
    }

    @Test
    void cachedCropChildSurvivesRootRestartAndRemainsThePendingChild() throws Exception {
        CollectWheatTask wheatTask = new CollectWheatTask(2);
        CollectCropTask pendingCropTask = new CollectCropTask(
                new ItemTarget(Items.WHEAT, 2), Blocks.WHEAT, Items.WHEAT_SEEDS) {
            @Override public boolean hasPendingReplant(adris.altoclef.AltoClef mod) { return true; }
        };
        Field cachedCropTask = CollectWheatTask.class.getDeclaredField("_cropTask");
        cachedCropTask.setAccessible(true);
        cachedCropTask.set(wheatTask, pendingCropTask);

        assertSame(pendingCropTask, wheatTask.getPendingCropReplantTask(null));
        wheatTask.restartForNewRun();
        assertSame(pendingCropTask, wheatTask.getPendingCropReplantTask(null));
    }

    @Test
    void completedCropChildDoesNotRemainPending() throws Exception {
        CollectWheatTask wheatTask = new CollectWheatTask(2);
        CollectCropTask completedCropTask = new CollectCropTask(
                new ItemTarget(Items.WHEAT, 2), Blocks.WHEAT, Items.WHEAT_SEEDS) {
            @Override public boolean hasPendingReplant(adris.altoclef.AltoClef mod) { return false; }
        };
        Field cachedCropTask = CollectWheatTask.class.getDeclaredField("_cropTask");
        cachedCropTask.setAccessible(true);
        cachedCropTask.set(wheatTask, completedCropTask);

        assertFalse(wheatTask.hasPendingCropReplant(null));
        assertNull(wheatTask.getPendingCropReplantTask(null));
    }
}
