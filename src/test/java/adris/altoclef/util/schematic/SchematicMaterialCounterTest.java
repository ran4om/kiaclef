package adris.altoclef.util.schematic;

import adris.altoclef.testing.MinecraftTestBootstrap;
import baritone.api.schematic.IStaticSchematic;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchematicMaterialCounterTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    void excludesAirAndCountsNormalBlocks() {
        Map<Item, Integer> counts = SchematicMaterialCounter.count(schematic(
                Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState(), Blocks.STONE.defaultBlockState()));

        assertEquals(Map.of(Items.STONE, 2), counts);
    }

    @Test
    void countsDoubleSlabsAsTwoMatchingSlabItems() {
        BlockState single = Blocks.OAK_SLAB.defaultBlockState()
                .setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM);
        BlockState doubled = Blocks.OAK_SLAB.defaultBlockState()
                .setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE);

        assertEquals(Map.of(Blocks.OAK_SLAB.asItem(), 3),
                SchematicMaterialCounter.count(schematic(single, doubled)));
    }

    @Test
    void countsOnlyThePlaceableHalfOfDoorsBedsAndTallPlants() {
        BlockState doorLower = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
        BlockState doorUpper = doorLower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
        BlockState redBed = Blocks.BED.red().defaultBlockState();
        BlockState bedFoot = redBed
                .setValue(BlockStateProperties.BED_PART, BedPart.FOOT);
        BlockState bedHead = bedFoot.setValue(BlockStateProperties.BED_PART, BedPart.HEAD);
        BlockState flowerLower = Blocks.SUNFLOWER.defaultBlockState()
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
        BlockState flowerUpper = flowerLower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);

        Map<Item, Integer> counts = SchematicMaterialCounter.count(schematic(
                doorLower, doorUpper, bedFoot, bedHead, flowerLower, flowerUpper));

        assertEquals(Map.of(Blocks.OAK_DOOR.asItem(), 1,
                Blocks.BED.red().asItem(), 1,
                Blocks.SUNFLOWER.asItem(), 1), counts);
    }

    @Test
    void turtleEggClusterRequiresItsEggCountInItems() {
        BlockState threeEggs = Blocks.TURTLE_EGG.defaultBlockState()
                .setValue(BlockStateProperties.EGGS, 3);

        assertEquals(Map.of(Items.TURTLE_EGG, 3), SchematicMaterialCounter.count(schematic(threeEggs)));
    }

    @Test
    void returnedCountsCannotBeMutated() {
        Map<Item, Integer> counts = SchematicMaterialCounter.count(schematic(Blocks.STONE.defaultBlockState()));

        assertFalse(counts.isEmpty());
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> counts.put(Items.DIRT, 1));
    }

    @Test
    void countsOnlyPositionsNotAlreadySatisfied() {
        IStaticSchematic schematic = schematic(Blocks.STONE.defaultBlockState(),
                Blocks.DIRT.defaultBlockState(), Blocks.STONE.defaultBlockState());

        Map<Item, Integer> missing = SchematicMaterialCounter.countMissing(schematic,
                local -> local.getX() == 0);

        assertEquals(Map.of(Items.DIRT, 1, Items.STONE, 1), missing);
    }

    @Test
    void missingMaterialCountsSkipMaskedNonNullCells() {
        IStaticSchematic masked = maskedSchematic(Set.of(0),
                Blocks.STONE.defaultBlockState(), Blocks.DIRT.defaultBlockState());

        assertEquals(Map.of(Items.STONE, 1), SchematicMaterialCounter.count(masked));
        assertEquals(Map.of(Items.STONE, 1),
                SchematicMaterialCounter.countMissing(masked, ignored -> false));
    }

    @Test
    void fitsMaterialBatchesToHeldStacksAndFreeSlots() {
        Map<Item, Integer> batch = SchematicMaterialCounter.fitBatch(
                Map.of(Items.STONE, 200),
                Map.of(Items.STONE, 16),
                Map.of(Items.STONE, 48),
                2);

        assertEquals(Map.of(Items.STONE, 192), batch);
    }

    @Test
    void masksSatisfiedAndOverBudgetPlacementsFromBatch() {
        IStaticSchematic original = schematic(Blocks.STONE.defaultBlockState(),
                Blocks.DIRT.defaultBlockState(), Blocks.STONE.defaultBlockState());

        IStaticSchematic batch = SchematicMaterialCounter.createBatch(original,
                Map.of(Items.STONE, 1), local -> local.getX() == 0);

        assertEquals(Blocks.AIR.defaultBlockState(), batch.getDirect(0, 0, 0));
        assertEquals(Blocks.AIR.defaultBlockState(), batch.getDirect(1, 0, 0));
        assertEquals(Blocks.STONE.defaultBlockState(), batch.getDirect(2, 0, 0));
        assertFalse(batch.inSchematic(0, 0, 0, Blocks.STONE.defaultBlockState()));
        assertFalse(batch.inSchematic(1, 0, 0, Blocks.AIR.defaultBlockState()));
        assertTrue(batch.inSchematic(2, 0, 0, Blocks.STONE.defaultBlockState()));
        assertEquals(Map.of(Items.STONE, 1), SchematicMaterialCounter.count(batch));
    }

    @Test
    void batchesSkipMaskedNonNullBuildAndMaterialCells() {
        IStaticSchematic masked = maskedSchematic(Set.of(0),
                Blocks.STONE.defaultBlockState(), Blocks.DIRT.defaultBlockState());
        IStaticSchematic batch = SchematicMaterialCounter.createBatch(masked, masked,
                Map.of(Items.STONE, 1, Items.DIRT, 1), ignored -> false);

        assertTrue(batch.inSchematic(0, 0, 0, batch.getDirect(0, 0, 0)));
        assertFalse(batch.inSchematic(1, 0, 0, batch.getDirect(1, 0, 0)));
        assertEquals(Map.of(Items.STONE, 1), SchematicMaterialCounter.count(batch));
    }

    @Test
    void batchedMultiblockPartnerMustAlsoBeSelectedByBothSourceMasks() {
        BlockState lower = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
        BlockState upper = lower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
        IStaticSchematic maskedDoor = new IStaticSchematic() {
            @Override public BlockState getDirect(int x, int y, int z) { return y == 0 ? lower : upper; }
            @Override public boolean inSchematic(int x, int y, int z, BlockState current) { return y == 0; }
            @Override public int widthX() { return 1; }
            @Override public int heightY() { return 2; }
            @Override public int lengthZ() { return 1; }
            @Override public BlockState desiredState(int x, int y, int z, BlockState current,
                                                     java.util.List<BlockState> approxPlaceable) {
                return getDirect(x, y, z);
            }
        };

        IStaticSchematic batch = SchematicMaterialCounter.createBatch(maskedDoor,
                Map.of(Blocks.OAK_DOOR.asItem(), 1), ignored -> false);

        assertEquals(lower, batch.getDirect(0, 0, 0));
        assertFalse(batch.inSchematic(0, 1, 0, batch.getDirect(0, 1, 0)));
        assertEquals(Map.of(Blocks.OAK_DOOR.asItem(), 1), SchematicMaterialCounter.count(batch));
    }

    @Test
    void batchKeepsBothHalvesOfDoorPlacement() {
        BlockState lower = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
        BlockState upper = lower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
        IStaticSchematic original = verticalSchematic(lower, upper);

        IStaticSchematic batch = SchematicMaterialCounter.createBatch(original,
                Map.of(Items.OAK_DOOR, 1), ignored -> false);

        assertEquals(lower, batch.getDirect(0, 0, 0));
        assertEquals(upper, batch.getDirect(0, 1, 0));
        assertEquals(Map.of(Items.OAK_DOOR, 1), SchematicMaterialCounter.count(batch));
    }

    @Test
    void appliesSubstitutionsToMaterialStatesAndPreservesSharedProperties() {
        BlockState oakLog = Blocks.OAK_LOG.defaultBlockState()
                .setValue(RotatedPillarBlock.AXIS, Direction.Axis.X);
        IStaticSchematic raw = schematic(oakLog);
        IStaticSchematic effective = new SubstitutedStaticSchematic(raw,
                Map.of(Blocks.OAK_LOG, java.util.List.of(Blocks.BIRCH_LOG)));

        BlockState substituted = effective.getDirect(0, 0, 0);
        assertEquals(Blocks.BIRCH_LOG, substituted.getBlock());
        assertEquals(Direction.Axis.X, substituted.getValue(RotatedPillarBlock.AXIS));
        assertEquals(effective.desiredState(0, 0, 0, oakLog, java.util.List.of()),
                effective.desiredState(0, 0, 0, oakLog, java.util.List.of(Blocks.OAK_LOG.defaultBlockState())));
        assertEquals(Map.of(Blocks.BIRCH_LOG.asItem(), 1), SchematicMaterialCounter.count(effective));
    }

    @Test
    void budgetsRawBuilderBatchesByEffectiveSubstituteItems() {
        IStaticSchematic raw = schematic(Blocks.STONE.defaultBlockState(), Blocks.STONE.defaultBlockState());
        IStaticSchematic effective = new SubstitutedStaticSchematic(raw,
                Map.of(Blocks.STONE, java.util.List.of(Blocks.COBBLESTONE)));

        IStaticSchematic buildBatch = SchematicMaterialCounter.createBatch(raw, effective,
                Map.of(Blocks.COBBLESTONE.asItem(), 1), ignored -> false);
        IStaticSchematic materialBatch = SchematicMaterialCounter.createBatch(effective,
                Map.of(Blocks.COBBLESTONE.asItem(), 1), ignored -> false);

        assertEquals(Blocks.STONE.defaultBlockState(), buildBatch.getDirect(0, 0, 0));
        assertEquals(Blocks.AIR.defaultBlockState(), buildBatch.getDirect(1, 0, 0));
        assertEquals(Map.of(Blocks.COBBLESTONE.asItem(), 1), SchematicMaterialCounter.count(materialBatch));
    }

    @Test
    void choosesFirstGatherableSubstituteAndFreezesTheChoice() {
        IStaticSchematic raw = schematic(Blocks.STONE.defaultBlockState());
        Map<Block, java.util.List<Block>> selected =
                SubstitutedStaticSchematic.selectSupportedSubstitutions(raw,
                        Map.of(Blocks.STONE, java.util.List.of(Blocks.DIRT, Blocks.COBBLESTONE, Blocks.GRAVEL)),
                        item -> item == Blocks.COBBLESTONE.asItem() || item == Blocks.GRAVEL.asItem());
        IStaticSchematic effective = new SubstitutedStaticSchematic(raw, selected);

        assertEquals(java.util.List.of(Blocks.COBBLESTONE), selected.get(Blocks.STONE));
        assertEquals(Blocks.COBBLESTONE.defaultBlockState(), effective.getDirect(0, 0, 0));
        assertEquals(effective.desiredState(0, 0, 0, Blocks.STONE.defaultBlockState(), java.util.List.of()),
                effective.desiredState(0, 0, 0, Blocks.STONE.defaultBlockState(),
                        java.util.List.of(Blocks.GRAVEL.defaultBlockState())));
    }

    @Test
    void substitutionsPreserveSourceMaskAndIgnoreMaskedMaterials() {
        IStaticSchematic masked = maskedSchematic(Set.of(0),
                Blocks.STONE.defaultBlockState(), Blocks.DIRT.defaultBlockState());
        Map<Block, java.util.List<Block>> selected = SubstitutedStaticSchematic.selectSupportedSubstitutions(
                masked,
                Map.of(Blocks.STONE, java.util.List.of(Blocks.COBBLESTONE),
                        Blocks.DIRT, java.util.List.of(Blocks.GRAVEL)),
                item -> item == Blocks.COBBLESTONE.asItem());
        IStaticSchematic substituted = new SubstitutedStaticSchematic(masked, selected);

        assertEquals(Map.of(Blocks.STONE, java.util.List.of(Blocks.COBBLESTONE)), selected);
        assertEquals(Blocks.COBBLESTONE.defaultBlockState(), substituted.getDirect(0, 0, 0));
        assertEquals(Blocks.DIRT.defaultBlockState(), substituted.getDirect(1, 0, 0));
        assertTrue(substituted.inSchematic(0, 0, 0, substituted.getDirect(0, 0, 0)));
        assertFalse(substituted.inSchematic(1, 0, 0, substituted.getDirect(1, 0, 0)));
        assertEquals(Blocks.STONE.defaultBlockState(),
                substituted.desiredState(1, 0, 0, Blocks.STONE.defaultBlockState(), java.util.List.of()));
        assertEquals(Map.of(Blocks.COBBLESTONE.asItem(), 1), SchematicMaterialCounter.count(substituted));
    }

    private static IStaticSchematic maskedSchematic(Set<Integer> includedX, BlockState... states) {
        return new IStaticSchematic() {
            @Override public BlockState getDirect(int x, int y, int z) { return states[x]; }
            @Override public boolean inSchematic(int x, int y, int z, BlockState current) {
                return y == 0 && z == 0 && includedX.contains(x);
            }
            @Override public int widthX() { return states.length; }
            @Override public int heightY() { return 1; }
            @Override public int lengthZ() { return 1; }
            @Override public BlockState desiredState(int x, int y, int z, BlockState current,
                                                     java.util.List<BlockState> approxPlaceable) {
                return getDirect(x, y, z);
            }
        };
    }

    private static IStaticSchematic schematic(BlockState... states) {
        return new IStaticSchematic() {
            @Override
            public BlockState getDirect(int x, int y, int z) {
                return states[x];
            }

            @Override
            public int widthX() {
                return states.length;
            }

            @Override
            public int heightY() {
                return 1;
            }

            @Override
            public int lengthZ() {
                return 1;
            }

            @Override
            public BlockState desiredState(int x, int y, int z, BlockState current,
                                           java.util.List<BlockState> approxPlaceable) {
                return getDirect(x, y, z);
            }
        };
    }

    private static IStaticSchematic verticalSchematic(BlockState lower, BlockState upper) {
        return new IStaticSchematic() {
            @Override
            public BlockState getDirect(int x, int y, int z) {
                return y == 0 ? lower : upper;
            }

            @Override public int widthX() { return 1; }
            @Override public int heightY() { return 2; }
            @Override public int lengthZ() { return 1; }
            @Override public BlockState desiredState(int x, int y, int z, BlockState current,
                                                     java.util.List<BlockState> approxPlaceable) {
                return getDirect(x, y, z);
            }
        };
    }
}
