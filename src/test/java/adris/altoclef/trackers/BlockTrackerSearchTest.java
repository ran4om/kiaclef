package adris.altoclef.trackers;

import adris.altoclef.testing.MinecraftTestBootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockTrackerSearchTest {
    @BeforeAll
    static void bootstrap() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    void appliesSearchLimitPerBlockTypeSoAbundantBlocksDoNotStarveLogs() {
        BlockPos stone = new BlockPos(0, -60, 0);
        BlockPos log = new BlockPos(1, -59, 0);
        List<Block> searched = new ArrayList<>();

        List<BlockPos> found = BlockTracker.searchByBlockType(
                new Block[]{Blocks.STONE, Blocks.OAK_LOG},
                1,
                (block, limit) -> {
                    searched.add(block);
                    assertEquals(1, limit);
                    return List.of(block == Blocks.STONE ? stone : log);
                });

        assertEquals(List.of(Blocks.STONE, Blocks.OAK_LOG), searched);
        assertEquals(2, found.size());
        assertTrue(found.contains(stone));
        assertTrue(found.contains(log));
    }

    @Test
    void perTypePurgeRemovesEvictedPositionsFromReverseIndexSoRescanCanRestoreThem() {
        BlockTracker.PosCache cache = new BlockTracker.PosCache();
        List<BlockPos> coal = new ArrayList<>();
        for (int x = 0; x <= 100; x++) {
            BlockPos pos = new BlockPos(x, 64, 0);
            coal.add(pos);
            cache.addBlock(Blocks.COAL_ORE, pos);
        }
        BlockPos otherType = new BlockPos(0, 70, 0);
        cache.addBlock(Blocks.STONE, otherType);

        cache.smartPurge(new Vec3(0.5, 64.5, 0.5));

        List<BlockPos> retainedCoal = cache.getKnownLocations(Blocks.COAL_ORE);
        assertEquals(100, retainedCoal.size());
        assertFalse(retainedCoal.contains(coal.get(100)));
        assertEquals(List.of(otherType), cache.getKnownLocations(Blocks.STONE));
        cache.addBlock(Blocks.STONE, otherType);
        assertEquals(1, cache.getKnownLocations(Blocks.STONE).stream()
                .filter(otherType::equals).count());

        // rescanWorld feeds rediscovered blocks through addBlock.
        cache.addBlock(Blocks.COAL_ORE, coal.get(100));
        assertTrue(cache.getKnownLocations(Blocks.COAL_ORE).contains(coal.get(100)));
        assertEquals(101, cache.getKnownLocations(Blocks.COAL_ORE).size());

        // Re-adding a retained position must not create a duplicate.
        cache.addBlock(Blocks.COAL_ORE, coal.get(0));
        assertEquals(1, cache.getKnownLocations(Blocks.COAL_ORE).stream()
                .filter(coal.get(0)::equals).count());

        // After the player moves beside it, the formerly evicted source stays in
        // the nearest per-type window through the next purge.
        cache.smartPurge(new Vec3(100.5, 64.5, 0.5));
        List<BlockPos> movedWindow = cache.getKnownLocations(Blocks.COAL_ORE);
        assertEquals(100, movedWindow.size());
        assertTrue(movedWindow.contains(coal.get(100)));
        assertEquals(List.of(otherType), cache.getKnownLocations(Blocks.STONE));
        cache.addBlock(Blocks.STONE, otherType);
        assertEquals(1, cache.getKnownLocations(Blocks.STONE).stream()
                .filter(otherType::equals).count());
    }
}
