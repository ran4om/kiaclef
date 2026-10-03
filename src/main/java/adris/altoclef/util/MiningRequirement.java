package adris.altoclef.util;

import adris.altoclef.Debug;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

public enum MiningRequirement implements Comparable<MiningRequirement> {
    HAND(Items.AIR), WOOD(Items.WOODEN_PICKAXE), STONE(Items.STONE_PICKAXE), IRON(Items.IRON_PICKAXE), DIAMOND(Items.DIAMOND_PICKAXE);

    private final Item _minPickaxe;

    MiningRequirement(Item minPickaxe) {
        _minPickaxe = minPickaxe;
    }

    public static MiningRequirement getMinimumRequirementForBlock(Block block) {
        if (block.defaultBlockState().requiresCorrectToolForDrops()) {
            for (MiningRequirement req : MiningRequirement.values()) {
                if (req == MiningRequirement.HAND) continue;
                Item pick = req.getMinimumPickaxe();
                if (pick.getDefaultInstance().isCorrectToolForDrops(block.defaultBlockState())) {
                    return req;
                }
            }
            Debug.logWarning("Failed to find ANY effective tool against: " + block + ". I assume netherite is not required anywhere, so something else probably went wrong.");
            return MiningRequirement.DIAMOND;
        }
        return MiningRequirement.HAND;
    }

    /** Tests the modern tool component instead of assuming material names imply mining level. */
    public boolean isSatisfiedBy(Item item) {
        if (this == HAND) return true;
        if (!net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).getPath().endsWith("_pickaxe")) return false;
        var block = switch (this) {
            case WOOD -> net.minecraft.world.level.block.Blocks.COBBLESTONE;
            case STONE -> net.minecraft.world.level.block.Blocks.IRON_ORE;
            case IRON -> net.minecraft.world.level.block.Blocks.DIAMOND_ORE;
            case DIAMOND -> net.minecraft.world.level.block.Blocks.OBSIDIAN;
            default -> throw new IllegalStateException();
        };
        return item.getDefaultInstance().isCorrectToolForDrops(block.defaultBlockState());
    }

    public Item getMinimumPickaxe() {
        return _minPickaxe;
    }

}
