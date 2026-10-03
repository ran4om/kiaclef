package adris.altoclef.util.schematic;

import baritone.api.schematic.IStaticSchematic;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Converts desired schematic states to the placeable items Baritone needs in inventory. */
public final class SchematicMaterialCounter {
    private SchematicMaterialCounter() {}

    public static Map<Item, Integer> count(IStaticSchematic schematic) {
        return countMissing(schematic, ignored -> false);
    }

    /** Counts only schematic positions for which the predicate reports the placed state is missing. */
    public static Map<Item, Integer> countMissing(IStaticSchematic schematic, Predicate<BlockPos> alreadySatisfied) {
        if (schematic == null) {
            throw new IllegalArgumentException("schematic cannot be null");
        }
        if (alreadySatisfied == null) {
            throw new IllegalArgumentException("alreadySatisfied cannot be null");
        }

        Map<Item, Integer> counts = new LinkedHashMap<>();
        for (int x = 0; x < schematic.widthX(); x++) {
            for (int y = 0; y < schematic.heightY(); y++) {
                for (int z = 0; z < schematic.lengthZ(); z++) {
                    BlockState state = schematic.getDirect(x, y, z);
                    if (!schematic.inSchematic(x, y, z, state)) continue;
                    if (!alreadySatisfied.test(new BlockPos(x, y, z))) {
                        addState(counts, state);
                    }
                }
            }
        }
        return Collections.unmodifiableMap(counts);
    }

    /**
     * Returns a schematic containing only unmet placements whose material units fit the supplied
     * inventory batch. Partner halves are retained when their lower/foot half is selected.
     */
    public static IStaticSchematic createBatch(IStaticSchematic schematic,
                                                Map<Item, Integer> materialBudget,
                                                Predicate<BlockPos> alreadySatisfied) {
        return createBatch(schematic, schematic, materialBudget, alreadySatisfied);
    }

    /**
     * Makes a builder batch from one schematic while budgeting material from its effective states.
     * This lets Baritone build its configured source schematic while AltoClef counts substituted items.
     */
    public static IStaticSchematic createBatch(IStaticSchematic buildSchematic,
                                                IStaticSchematic materialSchematic,
                                                Map<Item, Integer> materialBudget,
                                                Predicate<BlockPos> alreadySatisfied) {
        if (buildSchematic == null || materialSchematic == null || materialBudget == null || alreadySatisfied == null) {
            throw new IllegalArgumentException("both schematics, materialBudget, and alreadySatisfied are required");
        }
        if (buildSchematic.widthX() != materialSchematic.widthX()
                || buildSchematic.heightY() != materialSchematic.heightY()
                || buildSchematic.lengthZ() != materialSchematic.lengthZ()) {
            throw new IllegalArgumentException("build and material schematics must have the same dimensions");
        }

        Map<Item, Integer> available = new HashMap<>(materialBudget);
        Set<BlockPos> selected = new HashSet<>();
        for (int x = 0; x < buildSchematic.widthX(); x++) {
            for (int y = 0; y < buildSchematic.heightY(); y++) {
                for (int z = 0; z < buildSchematic.lengthZ(); z++) {
                    BlockPos local = new BlockPos(x, y, z);
                    BlockState buildState = buildSchematic.getDirect(x, y, z);
                    if (!buildSchematic.inSchematic(x, y, z, buildState)) continue;
                    BlockState state = materialSchematic.getDirect(x, y, z);
                    if (!materialSchematic.inSchematic(x, y, z, state)) continue;
                    if (alreadySatisfied.test(local)) {
                        continue;
                    }
                    MaterialCost cost = cost(state);
                    if (cost == null) {
                        // Air is a meaningful schematic target when the world currently has a
                        // block there. Other itemless states also need a builder pass so the
                        // caller can verify or report them instead of silently skipping them.
                        if (state != null && state.isAir()) {
                            selected.add(local);
                        } else if (state != null && !isDependentNonItemHalf(state)) {
                            selected.add(local);
                        }
                        continue;
                    }
                    if (available.getOrDefault(cost.item(), 0) < cost.count()) {
                        continue;
                    }
                    selected.add(local);
                    available.compute(cost.item(), (item, count) -> count - cost.count());
                    addPartnerHalf(buildSchematic, materialSchematic, local, state,
                            alreadySatisfied, selected);
                }
            }
        }
        return new BatchedSchematic(buildSchematic, selected);
    }

    /** Fits as many outstanding materials as possible into current stacks and a number of empty slots. */
    public static Map<Item, Integer> fitBatch(Map<Item, Integer> remaining,
                                               Map<Item, Integer> held,
                                               Map<Item, Integer> existingStackSpace,
                                               int emptySlots) {
        Map<Item, Integer> batch = new LinkedHashMap<>();
        int freeSlots = Math.max(0, emptySlots);
        for (Map.Entry<Item, Integer> entry : remaining.entrySet()) {
            Item item = entry.getKey();
            int required = Math.max(0, entry.getValue());
            int alreadyHeld = Math.max(0, held.getOrDefault(item, 0));
            int openSpace = Math.max(0, existingStackSpace.getOrDefault(item, 0));
            int inCurrentStacks = Math.min(required, alreadyHeld + openSpace);
            int stillNeeded = required - inCurrentStacks;
            int stackSize = Math.max(1, item.getDefaultMaxStackSize());
            int slotsNeeded = stillNeeded == 0 ? 0 : (stillNeeded + stackSize - 1) / stackSize;
            int slotsAllocated = Math.min(freeSlots, slotsNeeded);
            freeSlots -= slotsAllocated;
            int fromEmptySlots = Math.min(stillNeeded, slotsAllocated * stackSize);
            int included = inCurrentStacks + fromEmptySlots;
            if (included > 0) {
                batch.put(item, included);
            }
        }
        return Collections.unmodifiableMap(batch);
    }

    /** Adds one block-state's material cost. Public so placement rules can be tested in isolation. */
    public static void addState(Map<Item, Integer> counts, BlockState state) {
        MaterialCost cost = cost(state);
        if (cost != null) counts.merge(cost.item(), cost.count(), Integer::sum);
    }

    private static MaterialCost cost(BlockState state) {
        if (state == null || state.isAir() || isNonItemHalf(state)) return null;
        Block block = state.getBlock();
        Item item = block.asItem();
        if (item == Items.AIR) return null;

        int count = 1;
        if (state.hasProperty(BlockStateProperties.SLAB_TYPE)
                && state.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE
                && block instanceof SlabBlock) {
            count = 2;
        }
        if (state.hasProperty(BlockStateProperties.EGGS)) {
            count = state.getValue(BlockStateProperties.EGGS);
        }
        return new MaterialCost(item, count);
    }

    private static boolean isNonItemHalf(BlockState state) {
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
            return true;
        }
        return state.getBlock() instanceof BedBlock
                && state.hasProperty(BlockStateProperties.BED_PART)
                && state.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD;
    }

    private static boolean isDependentNonItemHalf(BlockState state) {
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
            return true;
        }
        return state.getBlock() instanceof BedBlock
                && state.hasProperty(BlockStateProperties.BED_PART)
                && state.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD;
    }

    private static void addPartnerHalf(IStaticSchematic buildSchematic,
                                       IStaticSchematic materialSchematic,
                                       BlockPos local, BlockState state,
                                       Predicate<BlockPos> alreadySatisfied, Set<BlockPos> selected) {
        if (state == null) return;
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER) {
            addIfMatchingHalf(buildSchematic, materialSchematic, local.above(), state,
                    DoubleBlockHalf.UPPER, alreadySatisfied, selected);
        }
        if (state.getBlock() instanceof BedBlock
                && state.hasProperty(BlockStateProperties.BED_PART)
                && state.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT
                && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            BlockPos headPos = local.relative(state.getValue(BlockStateProperties.HORIZONTAL_FACING));
            if (inBounds(materialSchematic, headPos) && inBounds(buildSchematic, headPos)
                    && !alreadySatisfied.test(headPos)) {
                BlockState head = materialSchematic.getDirect(headPos.getX(), headPos.getY(), headPos.getZ());
                BlockState buildHead = buildSchematic.getDirect(headPos.getX(), headPos.getY(), headPos.getZ());
                if (!materialSchematic.inSchematic(headPos.getX(), headPos.getY(), headPos.getZ(), head)
                        || !buildSchematic.inSchematic(headPos.getX(), headPos.getY(), headPos.getZ(), buildHead)) {
                    return;
                }
                if (head != null && head.getBlock() == state.getBlock()
                        && head.hasProperty(BlockStateProperties.BED_PART)
                        && head.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD) {
                    selected.add(headPos);
                }
            }
        }
    }

    private static void addIfMatchingHalf(IStaticSchematic buildSchematic,
                                         IStaticSchematic materialSchematic,
                                         BlockPos local, BlockState state,
                                         DoubleBlockHalf half, Predicate<BlockPos> alreadySatisfied,
                                         Set<BlockPos> selected) {
        if (!inBounds(materialSchematic, local) || !inBounds(buildSchematic, local)
                || alreadySatisfied.test(local)) return;
        BlockState partner = materialSchematic.getDirect(local.getX(), local.getY(), local.getZ());
        BlockState buildPartner = buildSchematic.getDirect(local.getX(), local.getY(), local.getZ());
        if (partner == null || buildPartner == null
                || !materialSchematic.inSchematic(local.getX(), local.getY(), local.getZ(), partner)
                || !buildSchematic.inSchematic(local.getX(), local.getY(), local.getZ(), buildPartner)) return;
        if (partner.getBlock() == state.getBlock()
                && partner.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && partner.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == half) {
            selected.add(local);
        }
    }

    private static boolean inBounds(IStaticSchematic schematic, BlockPos pos) {
        return pos.getX() >= 0 && pos.getX() < schematic.widthX()
                && pos.getY() >= 0 && pos.getY() < schematic.heightY()
                && pos.getZ() >= 0 && pos.getZ() < schematic.lengthZ();
    }

    private record MaterialCost(Item item, int count) {}

    private static final class BatchedSchematic implements IStaticSchematic {
        private final IStaticSchematic _source;
        private final Set<BlockPos> _selected;

        private BatchedSchematic(IStaticSchematic source, Set<BlockPos> selected) {
            _source = source;
            _selected = Set.copyOf(selected);
        }

        @Override
        public BlockState getDirect(int x, int y, int z) {
            return _selected.contains(new BlockPos(x, y, z))
                    ? _source.getDirect(x, y, z) : Blocks.AIR.defaultBlockState();
        }

        @Override
        public BlockState desiredState(int x, int y, int z, BlockState current,
                                       java.util.List<BlockState> approxPlaceable) {
            return _selected.contains(new BlockPos(x, y, z))
                    ? _source.desiredState(x, y, z, current, approxPlaceable) : current;
        }

        @Override
        public boolean inSchematic(int x, int y, int z, BlockState current) {
            return _selected.contains(new BlockPos(x, y, z))
                    && _source.inSchematic(x, y, z, current);
        }

        @Override public int widthX() { return _source.widthX(); }
        @Override public int heightY() { return _source.heightY(); }
        @Override public int lengthZ() { return _source.lengthZ(); }
    }
}
