package adris.altoclef.util.schematic;

import baritone.api.schematic.IStaticSchematic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.Optional;
import java.util.function.Function;

/** Finds correct multiblock anchors that must be removed before restoring a missing partner. */
public final class MultiblockRepairPlanner {
    private MultiblockRepairPlanner() {}

    public static Optional<BlockPos> findRepairAnchor(
            IStaticSchematic schematic,
            Function<BlockPos, BlockState> actualAt,
            Function<BlockPos, BlockState> desiredAt,
            SchematicBlockStateMatcher.Options options) {
        if (schematic == null || actualAt == null || desiredAt == null || options == null) {
            throw new IllegalArgumentException("schematic, state lookups, and match options are required");
        }

        for (int x = 0; x < schematic.widthX(); x++) {
            for (int y = 0; y < schematic.heightY(); y++) {
                for (int z = 0; z < schematic.lengthZ(); z++) {
                    BlockPos anchorPos = new BlockPos(x, y, z);
                    BlockState sourceAnchor = schematic.getDirect(x, y, z);
                    BlockPos partnerPos = getPartnerPos(sourceAnchor, anchorPos);
                    if (partnerPos == null || !inBounds(schematic, partnerPos)) continue;

                    BlockState sourcePartner = schematic.getDirect(partnerPos.getX(), partnerPos.getY(), partnerPos.getZ());
                    if (!areMatchingSourceHalves(sourceAnchor, sourcePartner)) continue;

                    BlockState desiredAnchor = desiredAt.apply(anchorPos);
                    BlockState desiredPartner = desiredAt.apply(partnerPos);
                    if (desiredAnchor == null || desiredPartner == null
                            || !areMatchingSourceHalves(desiredAnchor, desiredPartner)) continue;

                    BlockState actualAnchor = actualAt.apply(anchorPos);
                    BlockState actualPartner = actualAt.apply(partnerPos);
                    // A mismatched non-air state may be considered satisfied by buildIgnoreExisting.
                    // Only remove an anchor that is actually the intended block and matches all
                    // properties Baritone is configured to check.
                    if (actualAnchor.getBlock() != desiredAnchor.getBlock()
                            || !SchematicBlockStateMatcher.matches(actualAnchor, desiredAnchor, options)) {
                        continue;
                    }
                    if (!SchematicBlockStateMatcher.matches(actualPartner, desiredPartner, options)) {
                        return Optional.of(anchorPos);
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static BlockPos getPartnerPos(BlockState state, BlockPos pos) {
        if (state == null) return null;
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER) {
            return pos.above();
        }
        if (state.getBlock() instanceof BedBlock
                && state.hasProperty(BlockStateProperties.BED_PART)
                && state.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT
                && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            return pos.relative(facing);
        }
        return null;
    }

    private static boolean areMatchingSourceHalves(BlockState anchor, BlockState partner) {
        if (anchor == null || partner == null || anchor.getBlock() != partner.getBlock()) return false;
        if (anchor.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && partner.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            return anchor.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER
                    && partner.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER;
        }
        if (anchor.getBlock() instanceof BedBlock
                && anchor.hasProperty(BlockStateProperties.BED_PART)
                && partner.hasProperty(BlockStateProperties.BED_PART)
                && anchor.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                && partner.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return anchor.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT
                    && partner.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD
                    && anchor.getValue(BlockStateProperties.HORIZONTAL_FACING)
                    == partner.getValue(BlockStateProperties.HORIZONTAL_FACING);
        }
        return false;
    }

    private static boolean inBounds(IStaticSchematic schematic, BlockPos pos) {
        return pos.getX() >= 0 && pos.getX() < schematic.widthX()
                && pos.getY() >= 0 && pos.getY() < schematic.heightY()
                && pos.getZ() >= 0 && pos.getZ() < schematic.lengthZ();
    }
}
