package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.testing.MinecraftTestBootstrap;
import adris.altoclef.tasks.DoToClosestBlockTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasks.movement.RunAwayFromPositionTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.trackers.TrackerManager;
import adris.altoclef.trackers.EntityTracker;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.trackers.storage.ItemStorageTracker;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CollectFoodTaskTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    void foodDropInsideConfiguredRangeIsEligible() {
        assertTrue(CollectFoodTask.isDropWithinRange(Vec3.ZERO, new Vec3(2, 0, 1), 3));
    }

    @Test
    void foodDropAtRangeBoundaryIsOutsideStrictSearchRange() {
        assertFalse(CollectFoodTask.isDropWithinRange(Vec3.ZERO, new Vec3(3, 0, 0), 3));
    }

    @Test
    void foodDropOutsideConfiguredRangeIsIgnored() {
        assertFalse(CollectFoodTask.isDropWithinRange(Vec3.ZERO, new Vec3(0, 0, 4), 3));
    }

    @Test
    void negativeRangePreservesUnboundedPickupConvention() {
        assertTrue(CollectFoodTask.isDropWithinRange(Vec3.ZERO, new Vec3(1000, 0, 0), -1));
    }

    @Test
    void foodBlockSearchUsesStrictBlockCenterRange() {
        assertTrue(CollectFoodTask.isBlockWithinRange(Vec3.ZERO, new BlockPos(1, 0, 0), 3));
        assertFalse(CollectFoodTask.isBlockWithinRange(new Vec3(0.5, 0.5, 0.5), new BlockPos(3, 0, 0), 3));
        assertTrue(CollectFoodTask.isBlockWithinRange(Vec3.ZERO, new BlockPos(1000, 0, 0), -1));
    }

    @Test
    void foodBlockSearchRequiresBreakabilityAndCallerAcceptance() {
        BlockPos target = new BlockPos(1, 0, 0);
        assertTrue(CollectFoodTask.isFoodBlockEligible(Vec3.ZERO, target, 3, true, true));
        assertFalse(CollectFoodTask.isFoodBlockEligible(Vec3.ZERO, target, 3, false, true));
        assertFalse(CollectFoodTask.isFoodBlockEligible(Vec3.ZERO, target, 3, true, false));
        assertFalse(CollectFoodTask.isFoodBlockEligible(
                new Vec3(0.5, 0.5, 0.5), new BlockPos(3, 0, 0), 3, true, true));
    }

    @Test
    void boundedBlockFollowupHasStableBlockEqualityAndDoesNotWander() throws ReflectiveOperationException {
        AltoClef mod = new AltoClef() {};
        var first = new CollectFoodTask.BoundedBlockFoodTask(
                mod, Blocks.WHEAT, pos -> true, Vec3.ZERO, 100);
        var sameBlockDifferentRunAnchor = new CollectFoodTask.BoundedBlockFoodTask(
                mod, Blocks.WHEAT, pos -> false, new Vec3(20, 0, 20), 10);
        var differentBlock = new CollectFoodTask.BoundedBlockFoodTask(
                mod, Blocks.CARROTS, pos -> true, Vec3.ZERO, 100);
        assertEquals(first, sameBlockDifferentRunAnchor);
        assertNotEquals(first, differentBlock);

        var wander = first.getClass().getDeclaredMethod("getWanderTask", AltoClef.class);
        wander.setAccessible(true);
        assertNull(wander.invoke(first, mod));
    }

    @Test
    void destroyBlockRecoveryHooksKeepDefaultRecoveryBehavior() throws ReflectiveOperationException {
        DestroyBlockTask task = new DestroyBlockTask(BlockPos.ZERO);
        var wander = DestroyBlockTask.class.getDeclaredMethod("getRecoveryWanderTask", AltoClef.class, BlockPos.class);
        var retreat = DestroyBlockTask.class.getDeclaredMethod("getDangerousBreakRecoveryTask", AltoClef.class, BlockPos.class);
        wander.setAccessible(true);
        retreat.setAccessible(true);

        assertTrue(wander.invoke(task, new AltoClef(), BlockPos.ZERO) instanceof TimeoutWanderTask);
        assertTrue(retreat.invoke(task, new AltoClef(), BlockPos.ZERO) instanceof RunAwayFromPositionTask);
    }

    @Test
    void boundedBlockFollowupDisablesDestroyBlockRecoveryTravel() throws ReflectiveOperationException {
        AltoClef mod = new AltoClef() {};
        var bounded = new CollectFoodTask.BoundedBlockFoodTask(
                mod, Blocks.WHEAT, pos -> true, Vec3.ZERO, 100);
        var getGoal = DoToClosestBlockTask.class.getDeclaredMethod("getGoalTask", BlockPos.class);
        getGoal.setAccessible(true);
        DestroyBlockTask destroy = (DestroyBlockTask) getGoal.invoke(bounded, BlockPos.ZERO);
        var wander = DestroyBlockTask.class.getDeclaredMethod("getRecoveryWanderTask", AltoClef.class, BlockPos.class);
        var retreat = DestroyBlockTask.class.getDeclaredMethod("getDangerousBreakRecoveryTask", AltoClef.class, BlockPos.class);
        wander.setAccessible(true);
        retreat.setAccessible(true);

        assertNull(wander.invoke(destroy, mod, BlockPos.ZERO));
        assertNull(retreat.invoke(destroy, mod, BlockPos.ZERO));
    }

    @Test
    void boundedPickupEqualityDistinguishesUnboundedTaskAndDifferentRanges() {
        var bounded = new CollectFoodTask.PickupFoodDropTask(Items.WHEAT, 100, Vec3.ZERO);
        var sameConfigDifferentOrigin = new CollectFoodTask.PickupFoodDropTask(
                Items.WHEAT, 100, new Vec3(25, 0, 25));
        var differentRange = new CollectFoodTask.PickupFoodDropTask(Items.WHEAT, 300, Vec3.ZERO);
        var unbounded = new PickupDroppedItemTask(Items.WHEAT, Integer.MAX_VALUE, true);
        var differentItem = new CollectFoodTask.PickupFoodDropTask(Items.HAY_BLOCK, 100, Vec3.ZERO);

        // The origin is per-run state; equivalent parent-generated tasks keep the original anchor.
        assertEquals(bounded, sameConfigDifferentOrigin);
        assertNotEquals(bounded, differentRange);
        assertNotEquals(bounded, unbounded);
        assertNotEquals(bounded, differentItem);
    }

    @Test
    void boundedPickupDelegateRejectsDropsOutsideItsRunAnchor() {
        CollectFoodTask.PickupFoodDropTask bounded = new CollectFoodTask.PickupFoodDropTask(
                Items.WHEAT, 3, Vec3.ZERO);
        AltoClef mod = new AltoClef() {
            @Override
            public ItemStorageTracker getItemStorage() {
                return new EmptyItemStorageTracker(this);
            }
        };

        assertTrue(bounded.acceptsDrop(mod, itemDrop(Items.WHEAT, new Vec3(2, 0, 0))));
        assertFalse(bounded.acceptsDrop(mod, itemDrop(Items.WHEAT, new Vec3(4, 0, 0))));
        assertFalse(bounded.acceptsDrop(mod, itemDrop(Items.BREAD, new Vec3(1, 0, 0))));
    }

    @Test
    void boundedPickupWaitsWithOnlyOutsideDropsAndResumesForNewInsideDrop() {
        ItemEntity[] drops = {itemDrop(Items.WHEAT, new Vec3(4, 0, 0))};
        AltoClef mod = new AltoClef() {
            final EntityTracker tracker = new EntityTracker(new TrackerManager(this)) {
                @Override
                public Optional<ItemEntity> getClosestItemDrop(Vec3 origin, Predicate<ItemEntity> accept,
                                                               ItemTarget... targets) {
                    return java.util.Arrays.stream(drops).filter(accept).findFirst();
                }
            };
            @Override public EntityTracker getEntityTracker() { return tracker; }
        };
        var task = new CollectFoodTask.PickupFoodDropTask(Items.WHEAT, 3, Vec3.ZERO);
        assertNull(task.onTick(mod));
        drops[0] = itemDrop(Items.WHEAT, new Vec3(2, 0, 0));
        assertNotNull(task.onTick(mod));
        drops[0] = itemDrop(Items.WHEAT, new Vec3(4, 0, 0));
        assertNull(task.onTick(mod));
    }

    @Test
    void boundedPickupDisablesSearchAndRecoveryWandering() throws ReflectiveOperationException {
        var task = new CollectFoodTask.PickupFoodDropTask(Items.WHEAT, 3, Vec3.ZERO);
        Field delegateField = task.getClass().getDeclaredField("_pickupTask");
        delegateField.setAccessible(true);
        Object delegate = delegateField.get(task);
        for (String name : new String[]{"getWanderTask", "getRecoveryWanderTask"}) {
            var method = delegate.getClass().getDeclaredMethod(name, AltoClef.class);
            method.setAccessible(true);
            assertNull(method.invoke(delegate, new AltoClef()));
        }
        var create = delegate.getClass().getDeclaredMethod("createGetToEntityTask", ItemEntity.class);
        create.setAccessible(true);
        Object approach = create.invoke(delegate, itemDrop(Items.WHEAT, Vec3.ZERO));
        var recovery = approach.getClass().getDeclaredMethod("getRecoveryTask", AltoClef.class);
        recovery.setAccessible(true);
        assertNull(recovery.invoke(approach, new AltoClef()));
    }

    private static ItemEntity itemDrop(Item item, Vec3 position) {
        try {
            Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field.get(null);
            StubItemEntity drop = (StubItemEntity) unsafe.allocateInstance(StubItemEntity.class);
            drop.alive = true;
            drop.location = position;
            drop.stack = new ItemStack(item);
            return drop;
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static final class StubItemEntity extends ItemEntity {
        boolean alive;
        Vec3 location;
        ItemStack stack;

        private StubItemEntity() { super(null, 0, 0, 0, ItemStack.EMPTY); }

        @Override public boolean isAlive() { return alive; }
        @Override public Vec3 position() { return location; }
        @Override public ItemStack getItem() { return stack; }
    }

    private static final class EmptyItemStorageTracker extends ItemStorageTracker {
        private EmptyItemStorageTracker(AltoClef mod) {
            super(mod, new TrackerManager(mod), ignored -> {});
        }

        @Override
        public int getItemCountInventoryOnly(Item... items) {
            return 0;
        }
    }
}
