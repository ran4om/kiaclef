package adris.altoclef.util.schematic;

import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Mirrors Baritone BuilderProcess.valid/sameBlockstate for post-build verification. */
public final class SchematicBlockStateMatcher {
    private static final Set<Property<?>> ORIENTATION_PROPERTIES = Set.copyOf(List.of(
            RotatedPillarBlock.AXIS,
            HorizontalDirectionalBlock.FACING,
            StairBlock.FACING,
            StairBlock.HALF,
            StairBlock.SHAPE,
            PipeBlock.NORTH,
            PipeBlock.EAST,
            PipeBlock.SOUTH,
            PipeBlock.WEST,
            PipeBlock.UP,
            TrapDoorBlock.OPEN,
            TrapDoorBlock.HALF
    ));

    private SchematicBlockStateMatcher() {}

    public record Options(boolean ignoreExisting,
                          boolean ignoreDirection,
                          List<String> ignoredProperties,
                          boolean acceptWater,
                          List<Block> acceptAirFor,
                          List<Block> ignoreWhenClearing,
                          Map<Block, List<Block>> validSubstitutes) {
        public Options {
            ignoredProperties = List.copyOf(ignoredProperties);
            acceptAirFor = List.copyOf(acceptAirFor);
            ignoreWhenClearing = List.copyOf(ignoreWhenClearing);
            validSubstitutes = validSubstitutes.entrySet().stream().collect(
                    java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey,
                            entry -> List.copyOf(entry.getValue())));
        }
    }

    public static boolean matches(BlockState current, BlockState desired, Options options) {
        if (desired == null) return true;
        if (current.getBlock() instanceof LiquidBlock && options.acceptWater()) return true;
        if (current.getBlock() instanceof AirBlock && desired.getBlock() instanceof AirBlock) return true;
        if (current.getBlock() instanceof AirBlock && options.acceptAirFor().contains(desired.getBlock())) return true;
        if (desired.getBlock() instanceof AirBlock && options.ignoreWhenClearing().contains(current.getBlock())) return true;
        if (!(current.getBlock() instanceof AirBlock) && options.ignoreExisting()) return true;
        if (options.validSubstitutes().getOrDefault(desired.getBlock(), List.of()).contains(current.getBlock())) {
            return true;
        }
        if (current.equals(desired)) return true;
        return sameBlockstate(current, desired, options.ignoreDirection(), options.ignoredProperties());
    }

    private static boolean sameBlockstate(BlockState first, BlockState second,
                                          boolean ignoreDirection, List<String> ignoredProperties) {
        if (first.getBlock() != second.getBlock()) return false;
        if (!ignoreDirection && ignoredProperties.isEmpty()) return first.equals(second);

        for (Property<?> property : first.getProperties()) {
            if (!Objects.equals(value(first, property), value(second, property))
                    && !(ignoreDirection && ORIENTATION_PROPERTIES.contains(property))
                    && !ignoredProperties.contains(property.getName())) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Comparable<?> value(BlockState state, Property<?> property) {
        return state.getValue((Property) property);
    }
}
