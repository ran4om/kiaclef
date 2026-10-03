package adris.altoclef.tasks.construction;

import adris.altoclef.testing.MinecraftTestBootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlaceBlockTaskTest {
    @BeforeAll
    static void bootstrap() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    void emptyAvailableListKeepsTheExplicitRequestedBlock() {
        BlockState selected = PlaceBlockTask.selectDesiredPlacementState(
                new Block[]{Blocks.FURNACE}, false,
                state -> false, List.of());

        assertEquals(Blocks.FURNACE.defaultBlockState(), selected);
    }

    @Test
    void unrelatedAvailableCandidatesDoNotReplaceTheExplicitRequestedBlock() {
        BlockState selected = PlaceBlockTask.selectDesiredPlacementState(
                new Block[]{Blocks.FURNACE}, false,
                state -> false, List.of(Blocks.DIRT.defaultBlockState(), Blocks.STONE.defaultBlockState()));

        assertEquals(Blocks.FURNACE.defaultBlockState(), selected);
    }

    @Test
    void availableRequestedBlockIsPreferredOverFallback() {
        BlockState selected = PlaceBlockTask.selectDesiredPlacementState(
                new Block[]{Blocks.FURNACE}, false,
                state -> false, List.of(Blocks.COBBLESTONE.defaultBlockState(), Blocks.FURNACE.defaultBlockState()));

        assertEquals(Blocks.FURNACE.defaultBlockState(), selected);
    }
}
