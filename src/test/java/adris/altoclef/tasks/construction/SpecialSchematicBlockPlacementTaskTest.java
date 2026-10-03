package adris.altoclef.tasks.construction;

import adris.altoclef.AltoClef;
import adris.altoclef.testing.MinecraftTestBootstrap;
import adris.altoclef.tasksystem.ITaskCanForce;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskFailure;
import baritone.api.schematic.IStaticSchematic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpecialSchematicBlockPlacementTaskTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    void selectsDoubleSlabsForTwoStepPlacementButLeavesSingleSlabsToBaritone() {
        BlockState single = Blocks.OAK_SLAB.defaultBlockState().setValue(net.minecraft.world.level.block.SlabBlock.TYPE, SlabType.BOTTOM);
        BlockState doubled = Blocks.OAK_SLAB.defaultBlockState().setValue(net.minecraft.world.level.block.SlabBlock.TYPE, SlabType.DOUBLE);

        assertTrue(new SpecialSchematicBlockPlacementTask(schematic(single), BlockPos.ZERO, Set.of()).isFinished(null));
        assertFalse(new SpecialSchematicBlockPlacementTask(schematic(doubled), BlockPos.ZERO, Set.of()).isFinished(null));
    }

    @Test
    void failedDescendantIsConsumedBySpecialPlacementUnlessCursorWorkMustBeForced() {
        Task failedDescendant = new FailedDescendant("failed placement interaction", false);
        Task forcedDescendant = new FailedDescendant("cursor still owns slab", true);

        assertEquals("failed placement interaction",
                SpecialSchematicBlockPlacementTask.unhandledChildFailure(failedDescendant, null).reason());
        assertNull(SpecialSchematicBlockPlacementTask.unhandledChildFailure(forcedDescendant, null));
    }

    @Test
    void cleanupSlabMayOnlyContinueIntoAirOrItsExactAuthoredState() {
        BlockState slab = Blocks.OAK_SLAB.defaultBlockState()
                .setValue(net.minecraft.world.level.block.SlabBlock.TYPE, SlabType.DOUBLE);
        assertTrue(SpecialSchematicBlockPlacementTask.mayRestoreOnlyIntoAir(
                Blocks.AIR.defaultBlockState(), slab));
        assertTrue(SpecialSchematicBlockPlacementTask.mayRestoreOnlyIntoAir(slab, slab));
        assertFalse(SpecialSchematicBlockPlacementTask.mayRestoreOnlyIntoAir(
                Blocks.DIRT.defaultBlockState(), slab));
        BlockState partial = slab.setValue(
                net.minecraft.world.level.block.SlabBlock.TYPE, SlabType.BOTTOM);
        assertFalse(SpecialSchematicBlockPlacementTask.mayRestoreOnlyIntoAir(partial, slab));
        assertTrue(SpecialSchematicBlockPlacementTask.mayRestoreOnlyIntoAir(partial, slab, true));
    }

    @Test
    void doorApproachCandidatesAllowOneBlockVerticalAdjustmentsOnlyOverSafeGround() {
        BlockPos door = new BlockPos(4, 10, 4);
        Map<BlockPos, BlockState> worldStates = new HashMap<>();
        for (BlockPos stance : List.of(
                new BlockPos(4, 9, 6), new BlockPos(3, 9, 6), new BlockPos(5, 9, 6))) {
            worldStates.put(stance.below(), Blocks.STONE.defaultBlockState());
        }
        // The middle stance is blocked at head height and must not be selected.
        worldStates.put(new BlockPos(3, 10, 6), Blocks.STONE.defaultBlockState());
        worldStates.put(new BlockPos(3, 11, 6), Blocks.STONE.defaultBlockState());

        List<BlockPos> approaches = SpecialSchematicBlockPlacementTask.safeDoorApproachPositions(
                door, Direction.NORTH, testWorld(worldStates));

        assertEquals(List.of(new BlockPos(4, 9, 6), new BlockPos(5, 9, 6)), approaches);
        assertTrue(approaches.stream().allMatch(pos -> Math.abs(pos.getY() - door.getY()) == 1));
    }

    @Test
    void doorNavigationAttemptTimesOutAtConfiguredBound() {
        assertFalse(SpecialSchematicBlockPlacementTask.approachAttemptTimedOut(99, 100));
        assertTrue(SpecialSchematicBlockPlacementTask.approachAttemptTimedOut(100, 100));
        assertTrue(SpecialSchematicBlockPlacementTask.approachAttemptTimedOut(101, 100));
    }

    @Test
    void selectsDoorLowerOnlyWhenItsUpperPartnerIsInTheSameBatch() {
        BlockState lower = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(net.minecraft.world.level.block.DoorBlock.HALF, DoubleBlockHalf.LOWER);
        BlockState upper = lower.setValue(net.minecraft.world.level.block.DoorBlock.HALF, DoubleBlockHalf.UPPER);

        assertFalse(new SpecialSchematicBlockPlacementTask(vertical(lower, upper), BlockPos.ZERO, Set.of()).isFinished(null));
        assertTrue(new SpecialSchematicBlockPlacementTask(schematic(upper), BlockPos.ZERO, Set.of()).isFinished(null));
    }

    @Test
    void openUnpoweredDoorPlacementBasePreservesTheSchematicGeometry() {
        BlockState lower = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
                .setValue(DoorBlock.FACING, net.minecraft.core.Direction.EAST)
                .setValue(DoorBlock.HINGE, DoorHingeSide.RIGHT)
                .setValue(DoorBlock.OPEN, true)
                .setValue(DoorBlock.POWERED, false);
        BlockState upper = lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER);

        BlockState closedLower = SpecialSchematicBlockPlacementTask.closedUnpoweredDoorState(lower);
        BlockState closedUpper = SpecialSchematicBlockPlacementTask.closedUnpoweredDoorState(upper);

        assertFalse(closedLower.getValue(DoorBlock.OPEN));
        assertFalse(closedLower.getValue(DoorBlock.POWERED));
        assertFalse(closedUpper.getValue(DoorBlock.OPEN));
        assertFalse(closedUpper.getValue(DoorBlock.POWERED));
        assertTrue(closedLower.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER);
        assertTrue(closedUpper.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER);
        assertTrue(closedLower.getValue(DoorBlock.FACING) == net.minecraft.core.Direction.EAST);
        assertTrue(closedLower.getValue(DoorBlock.HINGE) == DoorHingeSide.RIGHT);

        assertTrue(SpecialSchematicBlockPlacementTask.isExpectedDoorPair(lower, upper, lower, upper));
        assertTrue(SpecialSchematicBlockPlacementTask.isExpectedDoorPair(lower, upper, closedLower, closedUpper));
        BlockState wrongHinge = closedLower.setValue(DoorBlock.HINGE, DoorHingeSide.LEFT);
        assertFalse(SpecialSchematicBlockPlacementTask.isExpectedDoorPair(lower, upper, wrongHinge, closedUpper));
    }

    @Test
    void doorCanBePlacedBeforeFullBlockHingeGeometry() {
        BlockPos door = new BlockPos(1, 1, 1);
        BlockPos neighborLower = new BlockPos(0, 1, 1);
        BlockPos neighborUpper = neighborLower.above();
        BlockState lower = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
                .setValue(DoorBlock.FACING, Direction.NORTH)
                .setValue(DoorBlock.HINGE, DoorHingeSide.RIGHT);
        BlockState upper = lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER);
        Map<BlockPos, BlockState> authoritativeCells = new HashMap<>();
        authoritativeCells.put(door, lower);
        authoritativeCells.put(door.above(), upper);
        authoritativeCells.put(neighborLower, Blocks.STONE.defaultBlockState());
        authoritativeCells.put(neighborUpper, Blocks.STONE.defaultBlockState());
        IStaticSchematic authoritative = schematic3d(3, 3, 3, authoritativeCells);
        IStaticSchematic candidate = SpecialSchematicBlockPlacementTask.withoutCells(
                authoritative, Set.of(neighborLower, neighborUpper));
        Map<BlockPos, BlockState> worldCells = new HashMap<>();
        worldCells.put(door.below(), Blocks.STONE.defaultBlockState());
        LevelReader world = testWorld(worldCells);

        Set<BlockPos> deferredBeforeNeighborPlacement = SpecialSchematicBlockPlacementTask.findDeferredCells(
                candidate, authoritative, BlockPos.ZERO, world);
        assertTrue(deferredBeforeNeighborPlacement.isEmpty());

        worldCells.put(neighborLower, Blocks.STONE.defaultBlockState());
        worldCells.put(neighborUpper, Blocks.STONE.defaultBlockState());
        assertTrue(SpecialSchematicBlockPlacementTask.findDeferredCells(
                candidate, authoritative, BlockPos.ZERO, world).isEmpty());
    }

    @Test
    void temporaryHingeRemovalRequiresExactAuthoredFullCollisionBlock() {
        BlockPos door = new BlockPos(1, 1, 1);
        BlockPos side = new BlockPos(0, 1, 1);
        BlockState lower = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
                .setValue(DoorBlock.FACING, Direction.NORTH);
        Map<BlockPos, BlockState> cells = new HashMap<>();
        cells.put(door, lower);
        cells.put(door.above(), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        cells.put(side, Blocks.STONE.defaultBlockState());
        IStaticSchematic authoritative = schematic3d(3, 3, 3, cells);
        LevelReader world = testWorld(Map.of());
        BlockState stone = Blocks.STONE.defaultBlockState();

        assertTrue(SpecialSchematicBlockPlacementTask.isAuthorizedHingeNeighborRemoval(
                authoritative, side, stone, side, world));
        assertFalse(SpecialSchematicBlockPlacementTask.isAuthorizedHingeNeighborRemoval(
                authoritative, side, Blocks.DIRT.defaultBlockState(), side, world));
        assertFalse(SpecialSchematicBlockPlacementTask.isAuthorizedHingeNeighborRemoval(
                authoritative, door, lower, door, world));
        assertFalse(SpecialSchematicBlockPlacementTask.isAuthorizedHingeNeighborRemoval(
                authoritative, new BlockPos(-1, 1, 1), stone, new BlockPos(-1, 1, 1), world));
        IStaticSchematic masked = SpecialSchematicBlockPlacementTask.withoutCells(authoritative, Set.of(side));
        assertFalse(SpecialSchematicBlockPlacementTask.isAuthorizedHingeNeighborRemoval(
                masked, side, stone, side, world));

        Map<BlockPos, BlockState> unsupportedCells = new HashMap<>(cells);
        unsupportedCells.put(side, Blocks.BEDROCK.defaultBlockState());
        IStaticSchematic withUnsupportedNeighbor = schematic3d(3, 3, 3, unsupportedCells);
        assertFalse(SpecialSchematicBlockPlacementTask.isAuthorizedHingeNeighborRemoval(
                withUnsupportedNeighbor, side, Blocks.BEDROCK.defaultBlockState(), side, world));

        BlockState doubleSlab = Blocks.OAK_SLAB.defaultBlockState()
                .setValue(SlabBlock.TYPE, SlabType.DOUBLE);
        Map<BlockPos, BlockState> slabCells = new HashMap<>(cells);
        slabCells.put(side, doubleSlab);
        IStaticSchematic withSlabNeighbor = schematic3d(3, 3, 3, slabCells);
        assertTrue(SpecialSchematicBlockPlacementTask.isAuthorizedHingeNeighborRemoval(
                withSlabNeighbor, side, doubleSlab, side, world));
    }

    @Test
    void adjacentSameFacingDoorsDoNotDeferEachOtherAsHingePrerequisites() {
        BlockPos westDoor = new BlockPos(1, 1, 1);
        BlockPos eastDoor = westDoor.east();
        BlockState westLower = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
                .setValue(DoorBlock.FACING, Direction.NORTH)
                .setValue(DoorBlock.HINGE, DoorHingeSide.LEFT);
        BlockState eastLower = westLower.setValue(DoorBlock.HINGE, DoorHingeSide.RIGHT);
        Map<BlockPos, BlockState> cells = new HashMap<>();
        cells.put(westDoor, westLower);
        cells.put(westDoor.above(), westLower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        cells.put(eastDoor, eastLower);
        cells.put(eastDoor.above(), eastLower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        IStaticSchematic pair = schematic3d(4, 3, 3, cells);
        Map<BlockPos, BlockState> worldCells = new HashMap<>();
        worldCells.put(westDoor.below(), Blocks.STONE.defaultBlockState());
        worldCells.put(eastDoor.below(), Blocks.STONE.defaultBlockState());

        assertTrue(SpecialSchematicBlockPlacementTask.findDeferredCells(
                pair, pair, BlockPos.ZERO, testWorld(worldCells)).isEmpty());
    }

    @Test
    void occupiedDoorAndDoubleSlabTargetsAreLeftForSpecialTaskCleanup() {
        BlockState lower = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
                .setValue(DoorBlock.FACING, Direction.NORTH);
        BlockState upper = lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER);
        Map<BlockPos, BlockState> doorWorldStates = new HashMap<>();
        doorWorldStates.put(BlockPos.ZERO, Blocks.DIRT.defaultBlockState());
        doorWorldStates.put(BlockPos.ZERO.below(), Blocks.STONE.defaultBlockState());
        LevelReader doorWorld = testWorld(doorWorldStates);
        assertTrue(SpecialSchematicBlockPlacementTask.findDeferredCells(
                vertical(lower, upper), BlockPos.ZERO, doorWorld).isEmpty());

        BlockState doubleSlab = Blocks.OAK_SLAB.defaultBlockState()
                .setValue(net.minecraft.world.level.block.SlabBlock.TYPE, SlabType.DOUBLE);
        Map<BlockPos, BlockState> slabWorldStates = Map.of(BlockPos.ZERO, Blocks.DIRT.defaultBlockState());
        assertTrue(SpecialSchematicBlockPlacementTask.findDeferredCells(
                schematic(doubleSlab), BlockPos.ZERO, testWorld(slabWorldStates)).isEmpty());
    }

    @Test
    void ignoresOrdinaryBlocksAndEmptyBatchCells() {
        assertTrue(new SpecialSchematicBlockPlacementTask(
                schematic(Blocks.AIR.defaultBlockState(), Blocks.OAK_STAIRS.defaultBlockState()), BlockPos.ZERO, Set.of())
                .isFinished(null));
    }

    private static IStaticSchematic schematic(BlockState... states) {
        return new IStaticSchematic() {
            @Override public BlockState getDirect(int x, int y, int z) { return states[x]; }
            @Override public int widthX() { return states.length; }
            @Override public int heightY() { return 1; }
            @Override public int lengthZ() { return 1; }
            @Override public BlockState desiredState(int x, int y, int z, BlockState current,
                                                     List<BlockState> approxPlaceable) {
                return getDirect(x, y, z);
            }
        };
    }

    private static IStaticSchematic vertical(BlockState lower, BlockState upper) {
        return new IStaticSchematic() {
            @Override public BlockState getDirect(int x, int y, int z) { return y == 0 ? lower : upper; }
            @Override public int widthX() { return 1; }
            @Override public int heightY() { return 2; }
            @Override public int lengthZ() { return 1; }
            @Override public BlockState desiredState(int x, int y, int z, BlockState current,
                                                     List<BlockState> approxPlaceable) {
                return getDirect(x, y, z);
            }
        };
    }

    private static IStaticSchematic schematic3d(int width, int height, int length,
                                               Map<BlockPos, BlockState> states) {
        return new IStaticSchematic() {
            @Override public BlockState getDirect(int x, int y, int z) {
                return states.getOrDefault(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
            }
            @Override public int widthX() { return width; }
            @Override public int heightY() { return height; }
            @Override public int lengthZ() { return length; }
            @Override public BlockState desiredState(int x, int y, int z, BlockState current,
                                                     List<BlockState> approxPlaceable) {
                return getDirect(x, y, z);
            }
        };
    }

    private static LevelReader testWorld(Map<BlockPos, BlockState> states) {
        return (LevelReader) Proxy.newProxyInstance(LevelReader.class.getClassLoader(),
                new Class<?>[]{LevelReader.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getBlockState")) {
                        return states.getOrDefault(args[0], Blocks.AIR.defaultBlockState());
                    }
                    if (method.getName().equals("getFluidState")) return Fluids.EMPTY.defaultFluidState();
                    if (method.getName().equals("getMinBuildHeight")) return -64;
                    if (method.getName().equals("getMaxBuildHeight")) return 320;
                    if (method.getName().equals("hasChunkAt")) return true;
                    if (method.getName().equals("isOutsideBuildHeight")) return false;
                    Class<?> returnType = method.getReturnType();
                    if (returnType == boolean.class) return false;
                    if (returnType == int.class) return 0;
                    if (returnType == long.class) return 0L;
                    if (returnType == float.class) return 0.0F;
                    if (returnType == double.class) return 0.0D;
                    return null;
                });
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
