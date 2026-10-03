package adris.altoclef.util.schematic;

import adris.altoclef.testing.MinecraftTestBootstrap;
import baritone.api.schematic.IStaticSchematic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MultiblockRepairPlannerTest {
    private static final SchematicBlockStateMatcher.Options STRICT =
            new SchematicBlockStateMatcher.Options(false, false, List.of(), false,
                    List.of(), List.of(), Map.of());

    @BeforeAll
    static void bootstrap() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    void findsCorrectDoorAnchorWhenItsSchematicUpperHalfIsMissing() {
        BlockState lower = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
        BlockState upper = lower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
        IStaticSchematic schematic = grid(1, 2, 1, Map.of(new BlockPos(0, 0, 0), lower,
                new BlockPos(0, 1, 0), upper));

        Optional<BlockPos> repair = MultiblockRepairPlanner.findRepairAnchor(schematic,
                pos -> pos.equals(BlockPos.ZERO) ? lower : Blocks.AIR.defaultBlockState(),
                pos -> schematic.getDirect(pos.getX(), pos.getY(), pos.getZ()), STRICT);

        assertEquals(Optional.of(BlockPos.ZERO), repair);
    }

    @Test
    void findsCorrectDoorAnchorWhenItsSchematicUpperHalfIsWrong() {
        BlockState lower = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
        BlockState upper = lower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
        IStaticSchematic schematic = grid(1, 2, 1, Map.of(new BlockPos(0, 0, 0), lower,
                new BlockPos(0, 1, 0), upper));

        Optional<BlockPos> repair = MultiblockRepairPlanner.findRepairAnchor(schematic,
                pos -> pos.getY() == 0 ? lower : Blocks.DIRT.defaultBlockState(),
                pos -> schematic.getDirect(pos.getX(), pos.getY(), pos.getZ()), STRICT);

        assertEquals(Optional.of(BlockPos.ZERO), repair);
    }

    @Test
    void leavesIntactDoorsAlone() {
        BlockState lower = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
        BlockState upper = lower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
        IStaticSchematic schematic = grid(1, 2, 1, Map.of(new BlockPos(0, 0, 0), lower,
                new BlockPos(0, 1, 0), upper));

        Optional<BlockPos> repair = MultiblockRepairPlanner.findRepairAnchor(schematic,
                pos -> schematic.getDirect(pos.getX(), pos.getY(), pos.getZ()),
                pos -> schematic.getDirect(pos.getX(), pos.getY(), pos.getZ()), STRICT);

        assertEquals(Optional.empty(), repair);
    }

    @Test
    void doesNotRepairAnOrphanedUpperHalfWithoutItsSchematicAnchor() {
        BlockState upper = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
        IStaticSchematic schematic = grid(1, 1, 1, Map.of(BlockPos.ZERO, upper));

        Optional<BlockPos> repair = MultiblockRepairPlanner.findRepairAnchor(schematic,
                pos -> Blocks.AIR.defaultBlockState(),
                pos -> schematic.getDirect(pos.getX(), pos.getY(), pos.getZ()), STRICT);

        assertEquals(Optional.empty(), repair);
    }

    @Test
    void doesNotBreakAnUnrelatedOrIgnoredAnchorOrPartner() {
        BlockState lower = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
        BlockState upper = lower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
        IStaticSchematic schematic = grid(1, 2, 1, Map.of(new BlockPos(0, 0, 0), lower,
                new BlockPos(0, 1, 0), upper));
        SchematicBlockStateMatcher.Options ignoreExisting = new SchematicBlockStateMatcher.Options(
                true, false, List.of(), false, List.of(), List.of(), Map.of());

        Optional<BlockPos> wrongAnchor = MultiblockRepairPlanner.findRepairAnchor(schematic,
                pos -> pos.getY() == 0 ? Blocks.DIRT.defaultBlockState() : Blocks.AIR.defaultBlockState(),
                pos -> schematic.getDirect(pos.getX(), pos.getY(), pos.getZ()), STRICT);
        Optional<BlockPos> ignoredPartner = MultiblockRepairPlanner.findRepairAnchor(schematic,
                pos -> pos.getY() == 0 ? lower : Blocks.STONE.defaultBlockState(),
                pos -> schematic.getDirect(pos.getX(), pos.getY(), pos.getZ()), ignoreExisting);

        assertEquals(Optional.empty(), wrongAnchor);
        assertEquals(Optional.empty(), ignoredPartner);
    }

    @Test
    void repairsBedByRemovingItsMatchingFootWhenTheHeadIsMissing() {
        BlockState foot = Blocks.BED.red().defaultBlockState()
                .setValue(BlockStateProperties.BED_PART, BedPart.FOOT)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
        BlockState head = foot.setValue(BlockStateProperties.BED_PART, BedPart.HEAD);
        IStaticSchematic schematic = grid(2, 1, 1, Map.of(new BlockPos(0, 0, 0), foot,
                new BlockPos(1, 0, 0), head));

        Optional<BlockPos> repair = MultiblockRepairPlanner.findRepairAnchor(schematic,
                pos -> pos.equals(BlockPos.ZERO) ? foot : Blocks.AIR.defaultBlockState(),
                pos -> schematic.getDirect(pos.getX(), pos.getY(), pos.getZ()), STRICT);

        assertEquals(Optional.of(BlockPos.ZERO), repair);
    }

    private static IStaticSchematic grid(int width, int height, int length, Map<BlockPos, BlockState> states) {
        return new IStaticSchematic() {
            @Override
            public BlockState getDirect(int x, int y, int z) {
                return states.getOrDefault(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
            }

            @Override public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> available) { return getDirect(x, y, z); }

            @Override public int widthX() { return width; }
            @Override public int heightY() { return height; }
            @Override public int lengthZ() { return length; }
        };
    }
}
