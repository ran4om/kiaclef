package adris.altoclef.tasks.construction;

import adris.altoclef.AltoClef;
import adris.altoclef.testing.MinecraftTestBootstrap;
import adris.altoclef.tasksystem.ITaskCanForce;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskFailure;
import baritone.api.schematic.IStaticSchematic;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildSchematicTaskTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    void cachedBuilderPositionsIncludeSelectedAirAndOmitUnselectedCells() {
        IStaticSchematic batch = new IStaticSchematic() {
            @Override
            public BlockState getDirect(int x, int y, int z) {
                if (x == 2) return Blocks.STONE.defaultBlockState();
                return Blocks.AIR.defaultBlockState();
            }

            @Override
            public BlockState desiredState(int x, int y, int z, BlockState current,
                                           List<BlockState> approxPlaceable) {
                return getDirect(x, y, z);
            }

            @Override
            public boolean inSchematic(int x, int y, int z, BlockState current) {
                return x == 0 || x == 2;
            }

            @Override public int widthX() { return 3; }
            @Override public int heightY() { return 1; }
            @Override public int lengthZ() { return 1; }
        };

        assertEquals(List.of(new BlockPos(0, 0, 0), new BlockPos(2, 0, 0)),
                BuildSchematicTask.collectSelectedPositions(batch));
    }

    @Test
    void readySpecialCandidatesExcludeOnlyCellsStillWaitingOnDependencies() {
        BlockPos readyDoorLower = new BlockPos(1, 0, 0);
        BlockPos readyDoorUpper = new BlockPos(1, 1, 0);
        BlockPos pendingSlab = new BlockPos(3, 0, 0);
        assertEquals(java.util.Set.of(readyDoorLower, readyDoorUpper),
                BuildSchematicTask.readySpecialPositions(
                        java.util.Set.of(readyDoorLower, readyDoorUpper, pendingSlab),
                        java.util.Set.of(pendingSlab)));
    }

    @Test
    void builderLaunchBarrierYieldsOnceThenAllowsTheNextTickToLaunch() {
        IStaticSchematic schematic = new IStaticSchematic() {
            @Override public BlockState getDirect(int x, int y, int z) { return Blocks.AIR.defaultBlockState(); }
            @Override public BlockState desiredState(int x, int y, int z, BlockState current,
                                                     List<BlockState> approxPlaceable) { return getDirect(x, y, z); }
            @Override public boolean inSchematic(int x, int y, int z, BlockState current) { return false; }
            @Override public int widthX() { return 1; }
            @Override public int heightY() { return 1; }
            @Override public int lengthZ() { return 1; }
        };
        BuildSchematicTask task = new BuildSchematicTask("launch barrier", schematic, BlockPos.ZERO);

        assertTrue(BuildSchematicTask.shouldWaitForBuilderLaunch(false, false));
        assertTrue(BuildSchematicTask.shouldWaitForBuilderLaunch(true, true));
        assertFalse(BuildSchematicTask.shouldWaitForBuilderLaunch(true, false));
        assertTrue(task.shouldDeferBuilderLaunch());
        assertFalse(task.shouldDeferBuilderLaunch());
        assertTrue(task.shouldDeferBuilderLaunch());
    }

    @Test
    void schematicSuccessWaitsForCursorGridAndContainerCleanup() {
        assertTrue(BuildSchematicTask.isSuccessfulCleanupComplete(true, true, true));
        assertFalse(BuildSchematicTask.isSuccessfulCleanupComplete(false, true, true));
        assertFalse(BuildSchematicTask.isSuccessfulCleanupComplete(true, false, true));
        assertFalse(BuildSchematicTask.isSuccessfulCleanupComplete(true, true, false));
    }

    @Test
    void failedDescendantIsConsumedByBuildOwnerUnlessCursorWorkMustBeForced() {
        Task failedDescendant = new FailedDescendant("failed block placement", false);
        Task forcedDescendant = new FailedDescendant("cursor still owns target item", true);

        assertEquals("failed block placement",
                BuildSchematicTask.unhandledChildFailure(failedDescendant, null).reason());
        assertNull(BuildSchematicTask.unhandledChildFailure(forcedDescendant, null));
    }

    @Test
    void batchMismatchWaiverAllowsOnlyTrackedRestorableCells() {
        BlockPos removedSupport = new BlockPos(4, 0, 2);
        BlockPos misplacedDoor = new BlockPos(5, 0, 2);
        Set<BlockPos> tracked = Set.of(removedSupport);
        Set<BlockPos> eligibleRestorations = Set.of(removedSupport);

        assertTrue(BuildSchematicTask.onlyTrackedRemovedMismatches(
                Set.of(removedSupport), tracked, eligibleRestorations));
        assertFalse(BuildSchematicTask.onlyTrackedRemovedMismatches(
                Set.of(removedSupport, misplacedDoor), tracked, eligibleRestorations));
        assertFalse(BuildSchematicTask.onlyTrackedRemovedMismatches(
                Set.of(misplacedDoor), tracked, eligibleRestorations));

        assertTrue(BuildSchematicTask.isEligibleTemporaryRestorationState(
                Blocks.OAK_SLAB.defaultBlockState().setValue(
                        net.minecraft.world.level.block.SlabBlock.TYPE,
                        net.minecraft.world.level.block.state.properties.SlabType.DOUBLE)));
        assertTrue(BuildSchematicTask.isEligibleTemporaryRestorationState(Blocks.STONE.defaultBlockState()));
        assertFalse(BuildSchematicTask.isEligibleTemporaryRestorationState(
                Blocks.OAK_DOOR.defaultBlockState()));
        assertFalse(BuildSchematicTask.isEligibleTemporaryRestorationState(
                Blocks.AIR.defaultBlockState()));
    }

    @Test
    void failureCleanupSelectsOnlyTrackedAuthoredAirCellsAndMasksExactOrdinaryAndSlabStates() {
        BlockPos ordinary = new BlockPos(0, 0, 0);
        BlockPos doubleSlab = new BlockPos(1, 0, 0);
        BlockPos door = new BlockPos(2, 0, 0);
        BlockPos authoredAir = new BlockPos(3, 0, 0);
        BlockPos occupied = new BlockPos(4, 0, 0);
        BlockPos untracked = new BlockPos(5, 0, 0);
        BlockState desiredSlab = Blocks.OAK_SLAB.defaultBlockState().setValue(
                net.minecraft.world.level.block.SlabBlock.TYPE,
                net.minecraft.world.level.block.state.properties.SlabType.DOUBLE);
        IStaticSchematic source = new IStaticSchematic() {
            private final BlockState[] states = {
                    Blocks.STONE.defaultBlockState(), desiredSlab, Blocks.OAK_DOOR.defaultBlockState(),
                    Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState(), Blocks.BRICKS.defaultBlockState()
            };

            @Override public BlockState getDirect(int x, int y, int z) { return states[x]; }
            @Override public BlockState desiredState(int x, int y, int z, BlockState current,
                                                     List<BlockState> approxPlaceable) { return getDirect(x, y, z); }
            @Override public boolean inSchematic(int x, int y, int z, BlockState current) { return true; }
            @Override public int widthX() { return states.length; }
            @Override public int heightY() { return 1; }
            @Override public int lengthZ() { return 1; }
        };
        BlockPos origin = new BlockPos(100, 64, -5);
        Set<BlockPos> tracked = Set.of(ordinary, doubleSlab, door, authoredAir, occupied);
        Set<BlockPos> safe = BuildSchematicTask.selectTemporaryRestorationTargets(
                source, tracked, origin, worldPos -> worldPos.equals(origin.offset(occupied))
                        ? Blocks.DIRT.defaultBlockState() : Blocks.AIR.defaultBlockState());

        assertEquals(Set.of(ordinary, doubleSlab), safe);

        IStaticSchematic masked = BuildSchematicTask.schematicWithOnlyCells(source, safe);
        assertTrue(masked.inSchematic(ordinary.getX(), 0, 0, masked.getDirect(0, 0, 0)));
        assertEquals(Blocks.STONE.defaultBlockState(), masked.getDirect(ordinary.getX(), 0, 0));
        assertTrue(masked.inSchematic(doubleSlab.getX(), 0, 0, masked.getDirect(1, 0, 0)));
        assertEquals(desiredSlab, masked.getDirect(doubleSlab.getX(), 0, 0));
        assertFalse(masked.inSchematic(door.getX(), 0, 0, masked.getDirect(door.getX(), 0, 0)));
        assertFalse(masked.inSchematic(untracked.getX(), 0, 0, masked.getDirect(untracked.getX(), 0, 0)));

        BlockState partialSlab = desiredSlab.setValue(
                net.minecraft.world.level.block.SlabBlock.TYPE,
                net.minecraft.world.level.block.state.properties.SlabType.BOTTOM);
        assertEquals(Set.of(doubleSlab), BuildSchematicTask.selectInProgressDoubleSlabRestorations(
                source, tracked, Set.of(doubleSlab), origin, worldPos ->
                        worldPos.equals(origin.offset(doubleSlab)) ? partialSlab : Blocks.AIR.defaultBlockState()));
        assertTrue(BuildSchematicTask.selectInProgressDoubleSlabRestorations(
                source, tracked, Set.of(), origin, worldPos -> partialSlab).isEmpty());
    }

    private static final class FailedDescendant extends Task implements TaskFailure, ITaskCanForce {
        private final String reason;
        private final boolean force;

        private FailedDescendant(String reason, boolean force) {
            this.reason = reason;
            this.force = force;
        }

        @Override public boolean hasFailed() { return true; }
        @Override public String getFailureReason() { return reason; }
        @Override public boolean shouldForce(AltoClef mod, Task interruptingCandidate) { return force; }
        @Override public boolean isActive() { return true; }
        @Override protected void onStart(AltoClef mod) { }
        @Override protected Task onTick(AltoClef mod) { return null; }
        @Override protected void onStop(AltoClef mod, Task interruptTask) { }
        @Override protected boolean isEqual(Task other) { return other == this; }
        @Override protected String toDebugString() { return "failed descendant"; }
    }
}
