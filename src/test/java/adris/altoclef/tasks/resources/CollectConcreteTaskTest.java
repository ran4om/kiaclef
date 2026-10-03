package adris.altoclef.tasks.resources;

import adris.altoclef.TaskCatalogue;
import adris.altoclef.util.helpers.ItemHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CollectConcreteTaskTest {
    @BeforeAll
    static void bootstrap() {
        adris.altoclef.testing.MinecraftTestBootstrap.initialize();
    }

    @Test
    void everyConcreteColorUsesTheObservedConversionCollector() {
        var colors = ItemHelper.getColorfulItems();
        assertEquals(16, colors.size());
        for (var color : colors) {
            assertTrue(TaskCatalogue.taskExists(color.concrete), color.colorName);
            var task = assertInstanceOf(CollectConcreteTask.class,
                    TaskCatalogue.getItemTask(color.concrete, 5));
            assertEquals(5, task.getItemTargets()[0].getTargetCount(), color.colorName);
            assertTrue(task.getItemTargets()[0].matches(color.concrete), color.colorName);
        }
    }

    @Test
    void conversionSpotAcceptsDryOrWaterPowderSpaceWithSolidSupportNextToSourceWater() {
        BlockPos water = BlockPos.ZERO;
        BlockPos powder = water.east();
        BlockPos support = powder.below();
        LevelReader world = testWorld(Map.of(
                water, Blocks.WATER.defaultBlockState(),
                support, Blocks.STONE.defaultBlockState()));

        var spot = CollectConcreteTask.findPowderSpotAroundWater(world, water, null).orElseThrow();

        assertEquals(water, spot.waterPosition());
        assertEquals(powder, spot.powderPosition());
        assertEquals(support, spot.supportPosition());
        assertTrue(CollectConcreteTask.findPowderSpotAroundWater(
                testWorld(Map.of(water, Blocks.WATER.defaultBlockState())), water, null).isEmpty());

        LevelReader flowingWaterAtPowder = testWorld(Map.of(
                water, Blocks.WATER.defaultBlockState(),
                powder, Blocks.WATER.defaultBlockState(),
                support, Blocks.STONE.defaultBlockState()));
        var wetSpot = CollectConcreteTask.findPowderSpotAroundWater(
                flowingWaterAtPowder, water, null).orElseThrow();
        assertEquals(powder, wetSpot.powderPosition());
    }

    @Test
    void nearbySourceWaterScanFindsThePoolBeforeTheAsynchronousBlockTrackerDoes() {
        BlockPos player = BlockPos.ZERO;
        BlockPos water = player.offset(8, 0, 0);
        BlockPos powder = water.east();
        LevelReader world = testWorld(Map.of(
                water, Blocks.WATER.defaultBlockState(),
                powder.below(), Blocks.STONE.defaultBlockState()));

        var spot = CollectConcreteTask.findNearbySourceWater(world, player, 12, 8).orElseThrow();

        assertEquals(water, spot.waterPosition());
        assertEquals(powder, spot.powderPosition());
        assertTrue(CollectConcreteTask.findNearbySourceWater(world, player, 7, 8).isEmpty());
    }

    @Test
    void nearbyScanChoosesDrySupportedCellOutsideTwoByTwoWaterPoolWhenAvailable() {
        BlockPos water = new BlockPos(8, 0, 8);
        BlockPos dryPowderCell = water.north();
        BlockPos support = dryPowderCell.below();
        LevelReader world = testWorld(Map.of(
                water, Blocks.WATER.defaultBlockState(),
                water.east(), Blocks.WATER.defaultBlockState(),
                water.south(), Blocks.WATER.defaultBlockState(),
                water.east().south(), Blocks.WATER.defaultBlockState(),
                support, Blocks.STONE.defaultBlockState()));

        var spot = CollectConcreteTask.findNearbySourceWater(world, water, 2, 1).orElseThrow();

        assertEquals(dryPowderCell, spot.powderPosition());
        assertEquals(support, spot.supportPosition());
        assertNotEquals(Blocks.WATER, world.getBlockState(spot.powderPosition()).getBlock());
        assertTrue(world.getBlockState(spot.powderPosition()).isAir());
    }

    @Test
    void safeApproachesStayGroundedAndOutsideContainedWaterPool() {
        BlockPos water = new BlockPos(0, 0, 0);
        BlockPos powder = water.east();
        BlockPos support = powder.below();
        Map<BlockPos, BlockState> states = new java.util.HashMap<>();
        for (int x = -2; x <= 3; x++) {
            for (int z = -2; z <= 3; z++) {
                states.put(new BlockPos(x, -1, z), Blocks.STONE.defaultBlockState());
                if (x < 0 || x > 1 || z < 0 || z > 1) {
                    states.put(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
                }
            }
        }
        states.put(water, Blocks.WATER.defaultBlockState());
        states.put(water.east(), Blocks.WATER.defaultBlockState());
        states.put(water.south(), Blocks.WATER.defaultBlockState());
        states.put(water.east().south(), Blocks.WATER.defaultBlockState());
        LevelReader world = testWorld(states);

        var approaches = CollectConcreteTask.safePowderApproachPositions(powder, support, world);

        assertFalse(approaches.isEmpty());
        for (BlockPos stance : approaches) {
            assertTrue(world.getBlockState(stance).isAir(), stance.toString());
            assertTrue(world.getBlockState(stance.above()).isAir(), stance.toString());
            assertTrue(world.getFluidState(stance).isEmpty(), stance.toString());
            assertTrue(world.getFluidState(stance.above()).isEmpty(), stance.toString());
            assertTrue(world.getBlockState(stance.below())
                    .isFaceSturdy(world, stance.below(), net.minecraft.core.Direction.UP), stance.toString());
        }
    }

    @Test
    void nearbyScanRejectsCoveredSourceAndUnsupportedPlacement() {
        BlockPos water = new BlockPos(8, 0, 0);
        assertTrue(CollectConcreteTask.findNearbySourceWater(
                testWorld(Map.of(water, Blocks.WATER.defaultBlockState())),
                BlockPos.ZERO, 12, 8).isEmpty());
        assertTrue(CollectConcreteTask.findNearbySourceWater(testWorld(Map.of(
                water, Blocks.WATER.defaultBlockState(),
                water.above(), Blocks.WATER.defaultBlockState(),
                water.east().below(), Blocks.STONE.defaultBlockState())),
                BlockPos.ZERO, 12, 8).isEmpty());
    }

    @Test
    void onlyTheMatchingConcreteBlockCountsAsConverted() {
        Block concrete = Blocks.CONCRETE.white();
        assertTrue(CollectConcreteTask.isConvertedConcrete(concrete.defaultBlockState(), concrete));
        assertFalse(CollectConcreteTask.isConvertedConcrete(Blocks.CONCRETE_POWDER.white().defaultBlockState(), concrete));
        assertFalse(CollectConcreteTask.isConvertedConcrete(Blocks.CONCRETE.black().defaultBlockState(), concrete));
    }

    private static LevelReader testWorld(Map<BlockPos, BlockState> states) {
        return (LevelReader) Proxy.newProxyInstance(LevelReader.class.getClassLoader(),
                new Class<?>[]{LevelReader.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getBlockState")) {
                        return states.getOrDefault(args[0], Blocks.AIR.defaultBlockState());
                    }
                    if (method.getName().equals("getFluidState")) {
                        return states.getOrDefault(args[0], Blocks.AIR.defaultBlockState()).getFluidState();
                    }
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
}
