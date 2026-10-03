package adris.altoclef.tasks.resources;

import adris.altoclef.testing.MinecraftTestBootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollectKelpTaskTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    void onlyAnExposedKelpTipIsEligible() {
        assertTrue(CollectKelpTask.isExposedKelpTip(
                Blocks.KELP.defaultBlockState(), Blocks.AIR.defaultBlockState()));
        assertFalse(CollectKelpTask.isExposedKelpTip(
                Blocks.KELP_PLANT.defaultBlockState(), Blocks.AIR.defaultBlockState()));
        assertFalse(CollectKelpTask.isExposedKelpTip(
                Blocks.KELP.defaultBlockState(), Blocks.WATER.defaultBlockState()));
    }

    @Test
    void dryBankMustHaveSafeSolidSupportAndNoWaterInFeetOrHead() {
        assertTrue(CollectKelpTask.isPassableAndDry(Blocks.AIR.defaultBlockState()));
        assertFalse(CollectKelpTask.isPassableAndDry(Blocks.WATER.defaultBlockState()));
        assertFalse(CollectKelpTask.isPassableAndDry(Blocks.STONE.defaultBlockState()));
        assertTrue(CollectKelpTask.isSafeDrySupport(Blocks.STONE.defaultBlockState()));
        assertFalse(CollectKelpTask.isSafeDrySupport(Blocks.WATER.defaultBlockState()));
        assertFalse(CollectKelpTask.isSafeDrySupport(Blocks.MAGMA_BLOCK.defaultBlockState()));
    }

    @Test
    void bankStandMustPutTheTipInsideSurvivalBlockReach() {
        assertTrue(CollectKelpTask.withinReachFromStand(BlockPos.ZERO, BlockPos.ZERO.east()));
        assertFalse(CollectKelpTask.withinReachFromStand(BlockPos.ZERO, BlockPos.ZERO.east(6)));

        BlockPos centerPoolTip = new BlockPos(3, 0, 3);
        var stands = CollectKelpTask.candidateStandPositions(centerPoolTip);
        assertTrue(stands.contains(new BlockPos(-1, 0, 3)), "radius-four same-height dry bank");
        assertTrue(stands.contains(new BlockPos(-1, 1, 3)), "one-block-higher dry bank");
    }

    @Test
    void loadedChunkScannerStartsNearbyAndAdvancesUsingChunkIdentity() {
        ChunkPos near = new ChunkPos(0, 0);
        ChunkPos middle = new ChunkPos(2, 0);
        ChunkPos far = new ChunkPos(6, 0);
        var loaded = java.util.List.of(far, near, middle);

        assertEquals(near, CollectKelpTask.selectNextLoadedChunk(loaded, null, Vec3.ZERO));
        assertEquals(middle, CollectKelpTask.selectNextLoadedChunk(loaded, near, Vec3.ZERO));
        assertEquals(near, CollectKelpTask.selectNextLoadedChunk(loaded, new ChunkPos(99, 99), Vec3.ZERO));
    }

    @Test
    void chunkDistanceUsesHorizontalAabbLowerBound() {
        ChunkPos containingPlayer = new ChunkPos(0, 0);
        ChunkPos adjacent = new ChunkPos(1, 0);
        ChunkPos twoAway = new ChunkPos(2, 0);

        assertEquals(0, CollectKelpTask.horizontalChunkDistanceSquared(containingPlayer,
                new Vec3(15.5, 90, 8.5)));
        assertEquals(0, CollectKelpTask.horizontalChunkDistanceSquared(adjacent,
                new Vec3(16, -40, 8.5)));
        assertEquals(0.25, CollectKelpTask.horizontalChunkDistanceSquared(twoAway,
                new Vec3(31.5, 0, 8.5)));
    }

    @Test
    void emptyScannedChunkStillProvesNoNearerLoadedChunkRemains() {
        ChunkPos emptyScanned = new ChunkPos(0, 0);
        ChunkPos fartherUnscanned = new ChunkPos(3, 0);
        var loaded = java.util.List.of(emptyScanned, fartherUnscanned);
        // Empty scans are intentionally absent from the kelp result map. The
        // independent scan set must still let the candidate-distance proof finish.
        Set<ChunkPos> scanned = Set.of(emptyScanned);

        assertEquals(fartherUnscanned, CollectKelpTask.nearestUnscannedChunk(loaded, scanned, Vec3.ZERO));
        assertNull(CollectKelpTask.nearestUnscannedChunkWithinDistance(
                loaded, scanned, Vec3.ZERO, 32 * 32));
        assertEquals(fartherUnscanned, CollectKelpTask.nearestUnscannedChunkWithinDistance(
                loaded, scanned, Vec3.ZERO, 48 * 48));
    }
}
