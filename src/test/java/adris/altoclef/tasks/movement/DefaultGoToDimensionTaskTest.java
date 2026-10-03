package adris.altoclef.tasks.movement;

import adris.altoclef.util.Dimension;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DefaultGoToDimensionTaskTest {
    @org.junit.jupiter.api.BeforeAll
    static void bootstrapMinecraft() {
        adris.altoclef.testing.MinecraftTestBootstrap.initialize();
    }

    @Test
    void endReturnGoalEntersPortalCellAtFeetHeight() {
        BlockPos portal = new BlockPos(0, 65, 0);
        assertEquals(portal, DefaultGoToDimensionTask.endExitGoal(portal));
    }

    @Test
    void endPortalWalkThroughRequiresExactScopedPermissionAndHonorsAvoidance() {
        var settings = adris.altoclef.baritone.AltoClefSettings.getInstance();
        BlockPos portal = new BlockPos(0, 65, 0);
        java.util.function.Predicate<BlockPos> permit = portal::equals;
        assertFalse(settings.canWalkThroughEndPortal(Blocks.END_PORTAL.defaultBlockState(), 0, 65, 0));
        settings.getForceWalkOnPredicates().add(permit);
        try {
            assertTrue(settings.canWalkThroughEndPortal(Blocks.END_PORTAL.defaultBlockState(), 0, 65, 0));
            assertFalse(settings.canWalkThroughEndPortal(Blocks.END_PORTAL.defaultBlockState(), 1, 65, 0));
            assertFalse(settings.canWalkThroughEndPortal(Blocks.STONE.defaultBlockState(), 0, 65, 0));
            settings.getForceAvoidWalkThroughPredicates().add(permit);
            assertFalse(settings.canWalkThroughEndPortal(Blocks.END_PORTAL.defaultBlockState(), 0, 65, 0));
        } finally {
            settings.getForceWalkOnPredicates().remove(permit);
            settings.getForceAvoidWalkThroughPredicates().remove(permit);
        }
    }

    @Test
    void endPortalWalkingIsOnlyEnabledToLeaveTheEnd() {
        assertFalse(DefaultGoToDimensionTask.shouldAllowWalkingOnEndPortal(
                Dimension.OVERWORLD, Dimension.END));
        assertTrue(DefaultGoToDimensionTask.shouldAllowWalkingOnEndPortal(
                Dimension.END, Dimension.OVERWORLD));
        assertTrue(DefaultGoToDimensionTask.shouldAllowWalkingOnEndPortal(
                Dimension.END, Dimension.NETHER));
        assertFalse(DefaultGoToDimensionTask.shouldAllowWalkingOnEndPortal(
                Dimension.END, Dimension.END));
    }

    @Test
    void centralIslandPrefersAnExistingExitPortal() {
        assertEquals(DefaultGoToDimensionTask.EndReturnRoute.EXIT_PORTAL,
                DefaultGoToDimensionTask.chooseEndReturnRoute(true, true, true, false));
    }

    @Test
    void outerIslandUsesLocalGatewayEvenWhenCentralPortalIsTracked() {
        assertEquals(DefaultGoToDimensionTask.EndReturnRoute.OUTER_GATEWAY,
                DefaultGoToDimensionTask.chooseEndReturnRoute(true, true, false, true));
    }

    @Test
    void outerIslandSearchesForGatewayInsteadOfRoutingToRemotePortal() {
        assertEquals(DefaultGoToDimensionTask.EndReturnRoute.SEARCH_FOR_GATEWAY,
                DefaultGoToDimensionTask.chooseEndReturnRoute(true, false, false, true));
    }

    @Test
    void onlyDelegatesDragonFightWhenDragonIsTrackedOnCentralIsland() {
        assertEquals(DefaultGoToDimensionTask.EndReturnRoute.KILL_DRAGON,
                DefaultGoToDimensionTask.chooseEndReturnRoute(false, false, true, false));
        assertEquals(DefaultGoToDimensionTask.EndReturnRoute.SEARCH_FOR_EXIT,
                DefaultGoToDimensionTask.chooseEndReturnRoute(false, false, false, false));
        assertEquals(DefaultGoToDimensionTask.EndReturnRoute.SEARCH_FOR_GATEWAY,
                DefaultGoToDimensionTask.chooseEndReturnRoute(false, false, true, true));
    }

    @Test
    void gatewayFiringPositionsStayOneBlockBelowAndAroundTheGateway() {
        var gateway = new net.minecraft.core.BlockPos(12, 81, -9);
        var positions = EnterEndGatewayTask.approachPositions(gateway);

        assertEquals(8, positions.length);
        for (var position : positions) {
            assertEquals(gateway.getY() - 1, position.getY());
            assertTrue(Math.abs(position.getX() - gateway.getX()) <= 2);
            assertTrue(Math.abs(position.getZ() - gateway.getZ()) <= 2);
            assertTrue(!position.equals(gateway));
        }
    }

    @Test
    void gatewayPlatformMaterialEstimateDoesNotGoNegative() {
        var gateway = new net.minecraft.core.BlockPos(12, 81, -9);
        assertEquals(0, EnterEndGatewayTask.buildingBlocksNeeded(gateway, gateway));
        assertEquals(13, EnterEndGatewayTask.buildingBlocksNeeded(
                new net.minecraft.core.BlockPos(2, 80, -4), gateway));
    }

    @Test
    void nearbyPortalScanOnlyReadsLoadedChunksAndCachesAllPortalTypes() {
        BlockPos center = new BlockPos(8, 64, 8);
        BlockPos nether = new BlockPos(4, 65, 4);
        BlockPos end = new BlockPos(5, 66, 5);
        BlockPos gateway = new BlockPos(6, 67, 6);
        BlockPos unloadedPortal = new BlockPos(16, 65, 4);
        List<BlockPos> reads = new ArrayList<>();
        List<String> cached = new ArrayList<>();

        int found = DefaultGoToDimensionTask.scanLoadedPortalBlocks(center, 0, 128,
                List.of(new ChunkPos(0, 0)), pos -> {
                    reads.add(pos);
                    if (pos.equals(nether)) return Blocks.NETHER_PORTAL.defaultBlockState();
                    if (pos.equals(end)) return Blocks.END_PORTAL.defaultBlockState();
                    if (pos.equals(gateway)) return Blocks.END_GATEWAY.defaultBlockState();
                    if (pos.equals(unloadedPortal)) return Blocks.NETHER_PORTAL.defaultBlockState();
                    return Blocks.AIR.defaultBlockState();
                }, (block, pos) -> cached.add(blockId(block) + "@" + pos));

        assertEquals(3, found);
        assertTrue(reads.stream().allMatch(pos -> new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4).equals(new ChunkPos(0, 0))));
        assertTrue(cached.contains("nether_portal@" + nether));
        assertTrue(cached.contains("end_portal@" + end));
        assertTrue(cached.contains("end_gateway@" + gateway));
        assertFalse(reads.contains(unloadedPortal));
        assertTrue(reads.size() <= 33 * 33 * 33);
    }

    @Test
    void portalScanThrottleIsPeriodicAndResetsAfterTeleportOrDimensionChange() {
        DefaultGoToDimensionTask.LocalPortalScanThrottle throttle =
                new DefaultGoToDimensionTask.LocalPortalScanThrottle();
        BlockPos start = new BlockPos(0, 64, 0);

        assertTrue(throttle.shouldScan(100, Dimension.OVERWORLD, start));
        assertFalse(throttle.shouldScan(119, Dimension.OVERWORLD, start));
        assertTrue(throttle.shouldScan(120, Dimension.OVERWORLD, start));
        assertTrue(throttle.shouldScan(121, Dimension.NETHER, start));
        assertTrue(throttle.shouldScan(122, Dimension.NETHER, start.offset(9, 0, 0)));
        throttle.reset();
        assertTrue(throttle.shouldScan(123, Dimension.NETHER, start.offset(9, 0, 0)));
    }

    private static String blockId(Block block) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).getPath();
    }
}
