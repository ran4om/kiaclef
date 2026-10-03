package adris.altoclef.util.schematic;

import baritone.api.schematic.IStaticSchematic;
import baritone.api.schematic.SubstituteSchematic;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.function.Predicate;

/** A static, inventory-independent view of a schematic with fixed Baritone block substitutions. */
public final class SubstitutedStaticSchematic implements IStaticSchematic {
    private final IStaticSchematic _source;
    private final SubstituteSchematic _substituted;
    private final List<BlockState> _fixedPlaceable;

    public SubstitutedStaticSchematic(IStaticSchematic source, Map<Block, List<Block>> substitutions) {
        if (source == null || substitutions == null) {
            throw new IllegalArgumentException("source schematic and substitutions are required");
        }
        _source = source;
        Map<Block, List<Block>> fixedSubstitutions = Map.copyOf(substitutions);
        _substituted = new SubstituteSchematic(source, fixedSubstitutions);
        _fixedPlaceable = fixedSubstitutions.values().stream()
                .flatMap(List::stream)
                .filter(block -> !(block instanceof AirBlock))
                .map(Block::defaultBlockState)
                .toList();
    }

    /** Picks the first configured substitute that AltoClef can gather, independent of inventory state. */
    public static Map<Block, List<Block>> selectSupportedSubstitutions(
            IStaticSchematic source,
            Map<Block, List<Block>> configured,
            Predicate<Item> hasResourceTask) {
        if (source == null || configured == null || hasResourceTask == null) {
            throw new IllegalArgumentException("source, configured substitutions, and item support predicate are required");
        }

        Set<Block> usedBlocks = new HashSet<>();
        for (int x = 0; x < source.widthX(); x++) {
            for (int y = 0; y < source.heightY(); y++) {
                for (int z = 0; z < source.lengthZ(); z++) {
                    BlockState state = source.getDirect(x, y, z);
                    if (source.inSchematic(x, y, z, state) && state != null && !state.isAir()) {
                        usedBlocks.add(state.getBlock());
                    }
                }
            }
        }

        Map<Block, List<Block>> selected = new LinkedHashMap<>();
        for (Map.Entry<Block, List<Block>> entry : configured.entrySet()) {
            if (!usedBlocks.contains(entry.getKey())) continue;
            Block chosen = null;
            for (Block candidate : entry.getValue()) {
                if (candidate instanceof AirBlock) {
                    chosen = candidate;
                    break;
                }
                Item item = candidate.asItem();
                if (item != Items.AIR && hasResourceTask.test(item)) {
                    chosen = candidate;
                    break;
                }
            }
            if (chosen == null) {
                throw new IllegalArgumentException("No configured substitute for " + entry.getKey()
                        + " has a registered resource task.");
            }
            selected.put(entry.getKey(), List.of(chosen));
        }
        return Map.copyOf(selected);
    }

    @Override
    public BlockState getDirect(int x, int y, int z) {
        BlockState original = _source.getDirect(x, y, z);
        if (!_source.inSchematic(x, y, z, original)) return original;
        return _substituted.desiredState(x, y, z, original, _fixedPlaceable);
    }

    @Override
    public BlockState desiredState(int x, int y, int z, BlockState current,
                                   List<BlockState> approxPlaceable) {
        BlockState original = _source.getDirect(x, y, z);
        if (!_source.inSchematic(x, y, z, original)) return current;
        // The substitute choice is deliberately fixed when this view is created. Inventory
        // changes during collection must not make the requested material set change underneath it.
        return _substituted.desiredState(x, y, z, current, _fixedPlaceable);
    }

    @Override
    public boolean inSchematic(int x, int y, int z, BlockState current) {
        if (x < 0 || x >= widthX() || y < 0 || y >= heightY() || z < 0 || z >= lengthZ()) {
            return false;
        }
        return _source.inSchematic(x, y, z, _source.getDirect(x, y, z));
    }

    @Override public int widthX() { return _source.widthX(); }
    @Override public int heightY() { return _source.heightY(); }
    @Override public int lengthZ() { return _source.lengthZ(); }
}
