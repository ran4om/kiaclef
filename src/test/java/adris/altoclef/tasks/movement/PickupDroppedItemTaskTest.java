package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.testing.MinecraftTestBootstrap;
import adris.altoclef.trackers.EntityTracker;
import adris.altoclef.trackers.TrackerManager;
import adris.altoclef.util.ItemTarget;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PickupDroppedItemTaskTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    void doesNotStartPickaxeFallbackForItsStonePickaxeRecipePrerequisites() {
        assertPrerequisite(Items.COBBLESTONE);
        assertPrerequisite(Items.STICK);
        assertPrerequisite(Items.OAK_LOG);
        assertPrerequisite(Items.BIRCH_PLANKS);
        assertPrerequisite(Items.CRAFTING_TABLE);
        assertPrerequisite(Items.WOODEN_PICKAXE);
        assertPrerequisite(Items.DIAMOND_PICKAXE);
    }

    @Test
    void allowsPickaxeFallbackForUnrelatedDrops() {
        assertFalse(PickupDroppedItemTask.isStonePickaxeRecipePrerequisite(
                new ItemTarget[]{new ItemTarget(Items.DIAMOND, 1)}));
        assertFalse(PickupDroppedItemTask.isStonePickaxeRecipePrerequisite(
                new ItemTarget[]{new ItemTarget(Items.REDSTONE, 1)}));
    }

    @Test
    void pickupTargetsOmitSatisfiedItemsWhileKeepingUnfinishedTargets() {
        ItemTarget seeds = new ItemTarget(Items.WHEAT_SEEDS, 1);
        ItemTarget wheat = new ItemTarget(Items.WHEAT, 2);
        ItemTarget[] targets = {seeds, wheat};
        java.util.function.ToIntFunction<ItemTarget> counts = target ->
                target.matches(Items.WHEAT_SEEDS) ? 2 : 0;

        assertEquals(List.of(wheat), List.of(PickupDroppedItemTask.getUnmetTargets(targets, counts)));
        assertFalse(PickupDroppedItemTask.itemIsNeededByAnyTarget(Items.WHEAT_SEEDS, targets, counts));
        assertTrue(PickupDroppedItemTask.itemIsNeededByAnyTarget(Items.WHEAT, targets, counts));
    }

    @Test
    void partiallySatisfiedTargetStillAcceptsItsDrop() {
        ItemTarget wheat = new ItemTarget(Items.WHEAT, 2);

        assertTrue(PickupDroppedItemTask.itemIsNeededByAnyTarget(
                Items.WHEAT, new ItemTarget[]{wheat}, target -> 1));
        assertFalse(PickupDroppedItemTask.itemIsNeededByAnyTarget(
                Items.WHEAT, new ItemTarget[]{wheat}, target -> 2));
    }

    @Test
    void fallbackHasOneOwnerAndOnlyThatOwnerCanReleaseIt() {
        PickupDroppedItemTask owner = new PickupDroppedItemTask(Items.DIAMOND, 1);
        PickupDroppedItemTask nestedPickup = new PickupDroppedItemTask(Items.COBBLESTONE, 1);

        assertTrue(PickupDroppedItemTask.claimPickaxeFirstOwner(owner));
        assertFalse(PickupDroppedItemTask.claimPickaxeFirstOwner(nestedPickup));
        assertTrue(PickupDroppedItemTask.isPickaxeFirstOwner(owner));
        assertFalse(PickupDroppedItemTask.isPickaxeFirstOwner(nestedPickup));

        PickupDroppedItemTask.releasePickaxeFirstOwner(nestedPickup);
        assertTrue(PickupDroppedItemTask.isPickaxeFirstOwner(owner));
        PickupDroppedItemTask.releasePickaxeFirstOwner(owner);
        assertFalse(PickupDroppedItemTask.isPickaxeFirstOwner(owner));
        assertTrue(PickupDroppedItemTask.claimPickaxeFirstOwner(nestedPickup));
        PickupDroppedItemTask.releasePickaxeFirstOwner(nestedPickup);
    }

    @Test
    void closestDropSearchSkipsDeadAndLocallyBlacklistedDrops() throws Exception {
        StubDrop deadNearest = diamondDrop(1);
        deadNearest.alive = false;
        ItemEntity blacklistedNext = diamondDrop(2);
        ItemEntity validFarther = diamondDrop(3);
        PickupDroppedItemTask task = new PickupDroppedItemTask(Items.DIAMOND, 1);
        addToLocalBlacklist(task, blacklistedNext);

        EntityTracker[] trackerRef = new EntityTracker[1];
        AltoClef mod = new AltoClef() {
            @Override
            public EntityTracker getEntityTracker() {
                return trackerRef[0];
            }
        };
        RecordingEntityTracker tracker = new RecordingEntityTracker(
                new TrackerManager(mod), List.of(deadNearest, blacklistedNext, validFarther));
        trackerRef[0] = tracker;

        Optional<ItemEntity> selected = task.getClosestTo(mod, Vec3.ZERO);

        assertFalse(tracker.lastPredicate.test(deadNearest), "dead drops must be rejected");
        assertFalse(tracker.lastPredicate.test(blacklistedNext), "locally blacklisted drops must be rejected");
        assertEquals(Optional.of(validFarther), selected);
    }

    @Test
    void currentDropPredicateRejectsSatisfiedTargetsAndKeepsPartialTargets() {
        ItemTarget seeds = new ItemTarget(Items.WHEAT_SEEDS, 2);
        ItemTarget wheat = new ItemTarget(Items.WHEAT, 3);
        int[] seedCount = {2};
        int[] wheatCount = {1};
        PickupDroppedItemTask task = new PickupDroppedItemTask(new ItemTarget[]{seeds, wheat}, true, true) {
            @Override
            protected int getAccessibleInventoryCount(AltoClef mod, ItemTarget target) {
                if (target.matches(Items.WHEAT_SEEDS)) return seedCount[0];
                if (target.matches(Items.WHEAT)) return wheatCount[0];
                return 0;
            }
        };
        PickupDroppedItemTask legacyTask = new PickupDroppedItemTask(new ItemTarget[]{seeds, wheat}, true);
        AltoClef mod = new AltoClef();

        assertFalse(task.isValid(mod, wheatSeedsDrop()), "a satisfied seed target must not keep attracting drops");
        assertTrue(legacyTask.isValid(mod, wheatSeedsDrop()),
                "existing direct pickup tasks keep their parent-owned completion behavior");
        assertTrue(task.isValid(mod, wheatDrop()), "a partially satisfied wheat target still needs drops");

        wheatCount[0] = 3;
        assertFalse(task.isValid(mod, wheatDrop()), "stop selecting wheat once its target is satisfied");
    }

    @Test
    void currentDropDiagnosticDoesNotReportLastSelectionAfterPursuitWasCleared() throws Exception {
        PickupDroppedItemTask task = new PickupDroppedItemTask(Items.DIAMOND, 1);
        ItemEntity staleSelection = diamondDrop(4);
        Field currentDrop = PickupDroppedItemTask.class.getDeclaredField("_currentDrop");
        currentDrop.setAccessible(true);
        currentDrop.set(task, staleSelection);

        assertTrue(task.getCurrentDropForDiagnostics().isEmpty(),
                "a remembered selection is not a current pursuit after the base task cleared its target");
        assertEquals(Optional.of(staleSelection), task.getLastSelectedDropForDiagnostics());

        task.reconcileCurrentDrop(new AltoClef());

        assertTrue(task.getLastSelectedDropForDiagnostics().isEmpty(),
                "reconciliation clears the stale goal before fallback timeout handling");
    }

    @Test
    void reconciliationClearsAnInvalidCurrentDropAndItsFallbackOwner() throws Exception {
        PickupDroppedItemTask task = new PickupDroppedItemTask(Items.DIAMOND, 1);
        StubDrop deadDrop = diamondDrop(5);
        deadDrop.alive = false;

        Field currentDrop = PickupDroppedItemTask.class.getDeclaredField("_currentDrop");
        currentDrop.setAccessible(true);
        currentDrop.set(task, deadDrop);
        Field pursuit = adris.altoclef.tasks.AbstractDoToClosestObjectTask.class
                .getDeclaredField("_currentlyPursuing");
        pursuit.setAccessible(true);
        pursuit.set(task, deadDrop);
        assertTrue(PickupDroppedItemTask.claimPickaxeFirstOwner(task));

        task.reconcileCurrentDrop(new AltoClef());

        assertTrue(task.getCurrentDropForDiagnostics().isEmpty());
        assertTrue(task.getLastSelectedDropForDiagnostics().isEmpty());
        assertFalse(PickupDroppedItemTask.isPickaxeFirstOwner(task),
                "an invalid target cannot retain fallback ownership");
    }

    private static void assertPrerequisite(net.minecraft.world.item.Item item) {
        assertTrue(PickupDroppedItemTask.isStonePickaxeRecipePrerequisite(
                new ItemTarget[]{new ItemTarget(item, 1)}), item.toString());
    }

    @Test
    void parentEligibilityRejectsLocallyBlacklistedDropEvenWhenTrackerStillContainsIt() throws Exception {
        StubDrop drop = diamondDrop(1);
        final EntityTracker[] trackerRef = new EntityTracker[1];
        AltoClef mod = new AltoClef() {
            @Override public EntityTracker getEntityTracker() { return trackerRef[0]; }
        };
        // Supply the search origin independently of a live player.
        PickupDroppedItemTask anchored = new PickupDroppedItemTask(Items.DIAMOND, 1) {
            @Override protected Vec3 getOriginPos(AltoClef ignored) { return Vec3.ZERO; }
        };
        trackerRef[0] = new RecordingEntityTracker(new TrackerManager(mod), List.of(drop));
        assertEquals(Optional.of(drop), anchored.getClosestEligibleDrop(mod));
        addToLocalBlacklist(anchored, drop);
        assertTrue(anchored.getClosestEligibleDrop(mod).isEmpty());
    }

    private static StubDrop diamondDrop(double x) throws Exception {
        // Entity construction requires a live Level in 26.2. This test exercises
        // the search predicate only, so use a drop double with explicit state.
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field.get(null);
        StubDrop drop = (StubDrop) unsafe.allocateInstance(StubDrop.class);
        drop.setId((int) x);
        drop.alive = true;
        drop.location = new Vec3(x, 0, 0);
        drop.stack = new ItemStack(Items.DIAMOND);
        return drop;
    }

    private static StubDrop wheatSeedsDrop() {
        return itemDrop(Items.WHEAT_SEEDS);
    }

    private static StubDrop wheatDrop() {
        return itemDrop(Items.WHEAT);
    }

    private static StubDrop itemDrop(net.minecraft.world.item.Item item) {
        try {
            Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field.get(null);
            StubDrop drop = (StubDrop) unsafe.allocateInstance(StubDrop.class);
            drop.alive = true;
            drop.stack = new ItemStack(item);
            return drop;
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static final class StubDrop extends ItemEntity {
        boolean alive;
        Vec3 location;
        ItemStack stack;
        private StubDrop() { super(null, 0, 0, 0, new ItemStack(Items.DIAMOND)); }
        @Override public boolean isAlive() { return alive; }
        @Override public Vec3 position() { return location; }
        @Override public ItemStack getItem() { return stack; }
    }

    @SuppressWarnings("unchecked")
    private static void addToLocalBlacklist(PickupDroppedItemTask task, ItemEntity drop) throws Exception {
        Field blacklist = PickupDroppedItemTask.class.getDeclaredField("_blacklist");
        blacklist.setAccessible(true);
        ((Set<ItemEntity>) blacklist.get(task)).add(drop);
    }

    private static final class RecordingEntityTracker extends EntityTracker {
        private final List<ItemEntity> drops;
        private Predicate<ItemEntity> lastPredicate;

        private RecordingEntityTracker(TrackerManager manager, List<ItemEntity> drops) {
            super(manager);
            this.drops = drops;
        }

        @Override
        public Optional<ItemEntity> getClosestItemDrop(
                Vec3 position, Predicate<ItemEntity> acceptPredicate, ItemTarget... targets) {
            lastPredicate = acceptPredicate;
            return drops.stream()
                    .filter(acceptPredicate)
                    .min(Comparator.comparingDouble(drop -> drop.position().distanceToSqr(position)));
        }
    }
}
