package adris.altoclef.mixins.baritonecompat;

import adris.altoclef.util.helpers.BaritoneBuilderStateCompatibility;
import adris.altoclef.util.helpers.BaritoneCrafterPlacementIntent;
import adris.altoclef.testing.MinecraftTestBootstrap;
import baritone.process.BuilderProcess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.FrontAndTop;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaritoneBuilderStateCompatibilityTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    void oneDoorItemCoversBothHalvesAndAnyPlacementOrientation() {
        var candidate = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
                .setValue(DoorBlock.FACING, Direction.NORTH);
        var upper = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER)
                .setValue(DoorBlock.HINGE, DoorHingeSide.RIGHT)
                .setValue(DoorBlock.FACING, Direction.SOUTH);

        assertTrue(BaritoneBuilderStateCompatibility.isDoorItemCandidate(List.of(candidate), upper));
    }

    @Test
    void onlyMatchesSameDoorItemAndDoesNotBroadenOrdinaryBlocks() {
        assertFalse(BaritoneBuilderStateCompatibility.isDoorItemCandidate(
                List.of(Blocks.BIRCH_DOOR.defaultBlockState()), Blocks.OAK_DOOR.defaultBlockState()));
        assertFalse(BaritoneBuilderStateCompatibility.isDoorItemCandidate(
                List.of(Blocks.OAK_PLANKS.defaultBlockState()), Blocks.OAK_DOOR.defaultBlockState()));
        assertFalse(BaritoneBuilderStateCompatibility.isDoorItemCandidate(
                List.of(Blocks.STONE.defaultBlockState()), Blocks.STONE.defaultBlockState()));
    }

    @Test
    void stairItemAvailabilityDoesNotDependOnCurrentPlayerFacing() {
        var north = Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(net.minecraft.world.level.block.StairBlock.FACING, Direction.NORTH);
        var east = north.setValue(net.minecraft.world.level.block.StairBlock.FACING, Direction.EAST);
        assertTrue(BaritoneBuilderStateCompatibility.isBlockItemCandidate(List.of(north), east));
        assertFalse(north.equals(east));
        assertFalse(BaritoneBuilderStateCompatibility.isBlockItemCandidate(
                List.of(Blocks.BIRCH_STAIRS.defaultBlockState()), east));
    }

    @Test
    void nonItemWorldStatesAreNotAvailableBlockItems() {
        assertFalse(BaritoneBuilderStateCompatibility.isBlockItemCandidate(
                List.of(Blocks.AIR.defaultBlockState()), Blocks.AIR.defaultBlockState()));
        assertFalse(BaritoneBuilderStateCompatibility.isBlockItemCandidate(
                List.of(Blocks.WATER.defaultBlockState()), Blocks.WATER.defaultBlockState()));
    }

    @Test
    void floorSupportAllowsReachableLowerAdjacentBuilderStandPositions() {
        BlockPos target = new BlockPos(4, 20, 7);
        BlockPos floorSupport = target.below();
        boolean allowSameLevel = BaritoneBuilderStateCompatibility.allowSameLevelForPlacement(
                false, target, floorSupport);
        BuilderProcess.GoalAdjacent goal = new BuilderProcess.GoalAdjacent(target, floorSupport, allowSameLevel);

        assertTrue(allowSameLevel);
        assertFalse(goal.isInGoal(target.getX(), target.getY(), target.getZ()));
        assertFalse(goal.isInGoal(floorSupport.getX(), floorSupport.getY(), floorSupport.getZ()));
        assertTrue(goal.isInGoal(target.getX() + 1, target.getY() - 1, target.getZ()));
        assertFalse(goal.isInGoal(target.getX() + 1, target.getY() - 2, target.getZ()));
    }

    @Test
    void sideSupportDoesNotEnableLowerAdjacentBuilderStandPositions() {
        BlockPos target = new BlockPos(4, 20, 7);
        BlockPos sideSupport = target.east();
        boolean allowSameLevel = BaritoneBuilderStateCompatibility.allowSameLevelForPlacement(
                false, target, sideSupport);
        BuilderProcess.GoalAdjacent goal = new BuilderProcess.GoalAdjacent(target, sideSupport, allowSameLevel);

        assertFalse(allowSameLevel);
        assertTrue(goal.isInGoal(target.getX() - 1, target.getY(), target.getZ()));
        assertFalse(goal.isInGoal(target.getX() - 1, target.getY() - 1, target.getZ()));
        assertFalse(goal.isInGoal(target.getX(), target.getY(), target.getZ()));
        assertFalse(goal.isInGoal(sideSupport.getX(), sideSupport.getY(), sideSupport.getZ()));
    }

    @Test
    void existingSameLevelPermissionIsPreserved() {
        BlockPos target = new BlockPos(4, 20, 7);
        assertTrue(BaritoneBuilderStateCompatibility.allowSameLevelForPlacement(
                true, target, target.north()));
    }

    @Test
    void uprightCraftersApproachFromEachRequestedFront() {
        BlockPos target = new BlockPos(4, 20, 7);
        for (Direction front : List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)) {
            BlockState wanted = crafterWithOrientation(FrontAndTop.fromFrontAndTop(front, Direction.UP));
            List<BlockPos> positions = BaritoneBuilderStateCompatibility
                    .crafterApproachPositions(target, wanted);

            assertEquals(List.of(
                    target.relative(front).below(),
                    target.relative(front, 2).below(),
                    target.relative(front, 3).below()), positions);
            GoalComposite goal = new GoalComposite(positions.stream()
                    .map(GoalBlock::new)
                    .toArray(Goal[]::new));
            for (BlockPos position : positions) {
                assertTrue(goal.isInGoal(position.getX(), position.getY(), position.getZ()),
                        "expected approach on " + front + " side at " + position);
            }
            BlockPos oppositeSide = target.relative(front.getOpposite()).below();
            assertFalse(goal.isInGoal(oppositeSide.getX(), oppositeSide.getY(), oppositeSide.getZ()));
        }
    }

    @Test
    void crafterClickGuardRequiresThePredictedOrientationToMatchTheSchematic() {
        for (Direction front : List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)) {
            BlockState wanted = crafterWithOrientation(FrontAndTop.fromFrontAndTop(front, Direction.UP));
            BlockState predicted = crafterWithOrientation(FrontAndTop.fromFrontAndTop(front, Direction.UP));
            assertTrue(BaritoneBuilderStateCompatibility.crafterOrientationMatches(predicted, wanted));

            BlockState wrongFacing = crafterWithOrientation(
                    FrontAndTop.fromFrontAndTop(front.getOpposite(), Direction.UP));
            assertFalse(BaritoneBuilderStateCompatibility.crafterOrientationMatches(wrongFacing, wanted));
        }
        assertFalse(BaritoneBuilderStateCompatibility.crafterOrientationMatches(
                Blocks.STONE.defaultBlockState(), Blocks.CRAFTER.defaultBlockState()));
        assertFalse(BaritoneBuilderStateCompatibility.crafterOrientationMatches(
                null, Blocks.CRAFTER.defaultBlockState()));
    }

    @Test
    void exactCrafterPlacementGuardRejectsAnyStateDifference() {
        BlockState wanted = crafterWithOrientation(FrontAndTop.fromFrontAndTop(Direction.SOUTH, Direction.UP));
        BlockState predicted = wanted;
        assertTrue(BaritoneBuilderStateCompatibility.crafterPlacementMatches(predicted, wanted));
        assertFalse(BaritoneBuilderStateCompatibility.crafterPlacementMatches(
                crafterWithOrientation(FrontAndTop.fromFrontAndTop(Direction.NORTH, Direction.UP)), wanted));
        assertFalse(BaritoneBuilderStateCompatibility.crafterPlacementMatches(
                stateWithBooleanProperty(predicted, "triggered", true), wanted));
    }

    @Test
    void crafterBuilderIntentIsOneTickAndOneClickOnly() {
        Object builder = new Object();
        Object player = new Object();
        BlockPos target = new BlockPos(12, 64, -7);
        BlockPos support = target.below();
        Direction face = Direction.UP;

        BaritoneCrafterPlacementIntent.beginTick();
        BaritoneCrafterPlacementIntent.publish(builder, player, target, support, face, 90, 0);
        var consumed = BaritoneCrafterPlacementIntent.consume(player);
        assertNotNull(consumed);
        assertSame(builder, consumed.owner());
        assertTrue(BaritoneCrafterPlacementIntent.matchesTarget(consumed, target));
        assertFalse(BaritoneCrafterPlacementIntent.matchesTarget(consumed, target.east()));
        assertTrue(BaritoneCrafterPlacementIntent.matchesHit(consumed, support, face));
        assertFalse(BaritoneCrafterPlacementIntent.matchesHit(consumed, support.east(), face));
        assertFalse(BaritoneCrafterPlacementIntent.matchesHit(consumed, support, Direction.NORTH));
        assertNull(BaritoneCrafterPlacementIntent.consume(player));
    }

    @Test
    void crafterBuilderIntentExpiresAtTheNextBuilderTickAndOnClear() {
        Object builder = new Object();
        Object player = new Object();
        BlockPos target = new BlockPos(12, 64, -7);
        BlockPos support = target.below();

        BaritoneCrafterPlacementIntent.beginTick();
        BaritoneCrafterPlacementIntent.publish(builder, player, target, support, Direction.UP, 90, 0);
        BaritoneCrafterPlacementIntent.beginTick();
        assertNull(BaritoneCrafterPlacementIntent.consume(player));

        BaritoneCrafterPlacementIntent.publish(builder, player, target, support, Direction.UP, 90, 0);
        BaritoneCrafterPlacementIntent.clear();
        assertNull(BaritoneCrafterPlacementIntent.consume(player));
    }

    @Test
    void crafterBuilderIntentRejectsWrongPlayerSupportAndFace() {
        Object builder = new Object();
        Object player = new Object();
        BlockPos target = new BlockPos(12, 64, -7);
        BlockPos support = target.below();

        BaritoneCrafterPlacementIntent.beginTick();
        BaritoneCrafterPlacementIntent.publish(builder, player, target, support, Direction.UP, 90, 0);
        assertNull(BaritoneCrafterPlacementIntent.consume(new Object()));
        BaritoneCrafterPlacementIntent.publish(builder, player, target, support, Direction.UP, 90, 0);
        var consumed = BaritoneCrafterPlacementIntent.consume(player);
        assertFalse(BaritoneCrafterPlacementIntent.matchesHit(consumed, support.east(), Direction.UP));
        BaritoneCrafterPlacementIntent.publish(builder, player, target, support, Direction.UP, 90, 0);
        consumed = BaritoneCrafterPlacementIntent.consume(player);
        assertFalse(BaritoneCrafterPlacementIntent.matchesHit(consumed, support, Direction.NORTH));
    }

    @Test
    void crafterApproachesCoverVerticalFrontOrientationsAndRejectUnsupportedStates() {
        BlockPos target = new BlockPos(4, 20, 7);
        BlockState frontUp = crafterWithOrientation(
                FrontAndTop.fromFrontAndTop(Direction.UP, Direction.NORTH));
        BlockState frontDown = crafterWithOrientation(
                FrontAndTop.fromFrontAndTop(Direction.DOWN, Direction.NORTH));
        assertNull(FrontAndTop.fromFrontAndTop(Direction.NORTH, Direction.DOWN));
        assertEquals(List.of(
                        target.above(2).relative(Direction.SOUTH, 2),
                        target.above(2).relative(Direction.SOUTH, 2).east(),
                        target.above(2).relative(Direction.SOUTH, 2).west(),
                        target.above(2).relative(Direction.SOUTH, 3),
                        target.above(2).relative(Direction.SOUTH, 3).east(),
                        target.above(2).relative(Direction.SOUTH, 3).west()),
                BaritoneBuilderStateCompatibility.crafterApproachPositions(target, frontUp));
        assertTrue(BaritoneBuilderStateCompatibility
                .crafterApproachPositions(target, frontDown).equals(List.of(target.below(2))));
        assertTrue(BaritoneBuilderStateCompatibility
                .crafterApproachPositions(target, null).isEmpty());
        assertTrue(BaritoneBuilderStateCompatibility
                .crafterApproachPositions(target, Blocks.STONE.defaultBlockState()).isEmpty());
    }

    @Test
    void upFrontCrafterApproachesIncludeValidatedLateralAlternativesOnRequestedSide() {
        BlockPos target = new BlockPos(-13, 72, 29);
        for (Direction top : List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)) {
            BlockState wanted = crafterWithOrientation(FrontAndTop.fromFrontAndTop(Direction.UP, top));
            List<BlockPos> positions = BaritoneBuilderStateCompatibility.crafterApproachPositions(target, wanted);
            Direction side = top.getOpposite();
            Direction lateral = top.getClockWise();

            assertEquals(List.of(
                            target.above(2).relative(side, 2),
                            target.above(2).relative(side, 2).relative(lateral),
                            target.above(2).relative(side, 2).relative(lateral.getOpposite()),
                            target.above(2).relative(side, 3),
                            target.above(2).relative(side, 3).relative(lateral),
                            target.above(2).relative(side, 3).relative(lateral.getOpposite())),
                    positions);
            assertTrue(positions.stream().allMatch(position -> position.getY() == target.getY() + 2));
            assertTrue(positions.stream().allMatch(position -> {
                int sideDistance = Math.abs(position.getX() - target.getX()) * Math.abs(side.getStepX())
                        + Math.abs(position.getZ() - target.getZ()) * Math.abs(side.getStepZ());
                int lateralDistance = Math.abs(position.getX() - target.getX()) * Math.abs(lateral.getStepX())
                        + Math.abs(position.getZ() - target.getZ()) * Math.abs(lateral.getStepZ());
                return (sideDistance == 2 || sideDistance == 3) && lateralDistance <= 1;
            }));
        }
    }

    @Test
    void nativeGoalPlaceFallbackIsOverriddenOnlyForAnAirDownFrontCrafterWithUpperSupport() {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState downNorth = crafterWithOrientation(
                FrontAndTop.fromFrontAndTop(Direction.DOWN, Direction.NORTH));
        BlockState northUp = crafterWithOrientation(
                FrontAndTop.fromFrontAndTop(Direction.NORTH, Direction.UP));

        assertTrue(BaritoneBuilderStateCompatibility.shouldOverrideGoalPlaceForDownCrafter(
                air, downNorth, true));
        assertFalse(BaritoneBuilderStateCompatibility.shouldOverrideGoalPlaceForDownCrafter(
                Blocks.STONE.defaultBlockState(), downNorth, true));
        assertFalse(BaritoneBuilderStateCompatibility.shouldOverrideGoalPlaceForDownCrafter(
                air, downNorth, false));
        assertFalse(BaritoneBuilderStateCompatibility.shouldOverrideGoalPlaceForDownCrafter(
                air, northUp, true));
        assertFalse(BaritoneBuilderStateCompatibility.shouldOverrideGoalPlaceForDownCrafter(
                air, Blocks.STONE.defaultBlockState(), true));
        assertFalse(BaritoneBuilderStateCompatibility.shouldOverrideGoalPlaceForDownCrafter(
                air, null, true));
    }

    @Test
    void crafterSearchAndClickDiagnosticsHaveIndependentBoundedLineBudgets() {
        assertTrue(BaritoneBuilderStateCompatibility.crafterSearchDiagnosticLineAllowed(0));
        assertTrue(BaritoneBuilderStateCompatibility.crafterSearchDiagnosticLineAllowed(119));
        assertFalse(BaritoneBuilderStateCompatibility.crafterSearchDiagnosticLineAllowed(120));
        assertFalse(BaritoneBuilderStateCompatibility.crafterSearchDiagnosticLineAllowed(-1));
        assertTrue(BaritoneBuilderStateCompatibility.crafterClickDiagnosticLineAllowed(0));
        assertTrue(BaritoneBuilderStateCompatibility.crafterClickDiagnosticLineAllowed(119));
        assertFalse(BaritoneBuilderStateCompatibility.crafterClickDiagnosticLineAllowed(120));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState crafterWithOrientation(FrontAndTop orientation) {
        BlockState state = Blocks.CRAFTER.defaultBlockState();
        Property property = state.getProperties().stream()
                .filter(candidate -> candidate.getName().equals("orientation"))
                .findFirst().orElseThrow();
        return state.setValue(property, orientation);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState stateWithBooleanProperty(BlockState state, String name, boolean value) {
        Property property = state.getProperties().stream()
                .filter(candidate -> candidate.getName().equals(name))
                .findFirst().orElseThrow();
        return state.setValue(property, value);
    }
}
