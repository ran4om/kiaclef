package adris.altoclef.util.schematic;

import adris.altoclef.testing.MinecraftTestBootstrap;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchematicBlockStateMatcherTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    void keepsFacingSignificantByDefault() {
        BlockState desired = Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.NORTH);
        BlockState current = desired.setValue(StairBlock.FACING, Direction.SOUTH);

        assertFalse(matches(current, desired, options(false, List.of(), false, List.of(), List.of(), Map.of())));
        assertTrue(matches(current, desired, options(true, List.of(), false, List.of(), List.of(), Map.of())));
    }

    @Test
    void ignoresOnlyConfiguredVolatileProperties() {
        BlockState desired = Blocks.OAK_TRAPDOOR.defaultBlockState()
                .setValue(TrapDoorBlock.OPEN, false);
        BlockState current = desired.setValue(TrapDoorBlock.OPEN, true);

        assertFalse(matches(current, desired, options(false, List.of(), false, List.of(), List.of(), Map.of())));
        assertTrue(matches(current, desired, options(false, List.of("open"), false, List.of(), List.of(), Map.of())));
    }

    @Test
    void honorsBaritoneBuildValidSubstitutesAndAirSettings() {
        BlockState desired = Blocks.STONE.defaultBlockState();
        BlockState substitute = Blocks.COBBLESTONE.defaultBlockState();
        assertTrue(matches(substitute, desired,
                options(false, List.of(), false, List.of(), List.of(), Map.of(Blocks.STONE, List.of(Blocks.COBBLESTONE)))));
        assertTrue(matches(Blocks.AIR.defaultBlockState(), desired,
                options(false, List.of(), false, List.of(Blocks.STONE), List.of(), Map.of())));
    }

    @Test
    void followsBaritoneIgnoreExistingAndLiquidRules() {
        assertTrue(matches(Blocks.DIRT.defaultBlockState(), Blocks.STONE.defaultBlockState(),
                options(false, List.of(), false, List.of(), List.of(), Map.of(), true)));
        assertTrue(matches(Blocks.WATER.defaultBlockState(), Blocks.STONE.defaultBlockState(),
                options(false, List.of(), true, List.of(), List.of(), Map.of())));
        assertTrue(matches(Blocks.DIRT.defaultBlockState(), Blocks.AIR.defaultBlockState(),
                options(false, List.of(), false, List.of(), List.of(Blocks.DIRT), Map.of())));
    }

    private static boolean matches(BlockState current, BlockState desired,
                                   SchematicBlockStateMatcher.Options options) {
        return SchematicBlockStateMatcher.matches(current, desired, options);
    }

    private static SchematicBlockStateMatcher.Options options(boolean ignoreDirection,
                                                               List<String> ignoredProperties,
                                                               boolean acceptWater,
                                                               List<Block> acceptAirFor,
                                                               List<Block> ignoreWhenClearing,
                                                               Map<Block, List<Block>> validSubstitutes) {
        return options(ignoreDirection, ignoredProperties, acceptWater, acceptAirFor,
                ignoreWhenClearing, validSubstitutes, false);
    }

    private static SchematicBlockStateMatcher.Options options(boolean ignoreDirection,
                                                               List<String> ignoredProperties,
                                                               boolean acceptWater,
                                                               List<Block> acceptAirFor,
                                                               List<Block> ignoreWhenClearing,
                                                               Map<Block, List<Block>> validSubstitutes,
                                                               boolean ignoreExisting) {
        return new SchematicBlockStateMatcher.Options(ignoreExisting, ignoreDirection, ignoredProperties,
                acceptWater, acceptAirFor, ignoreWhenClearing, validSubstitutes);
    }
}
