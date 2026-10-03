package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Mines matching leaves with a drop-preserving tool policy. */
public final class CollectSaplingTask extends MineAndCollectTask {

    public CollectSaplingTask(ItemTarget target, Block... leafBlocks) {
        super(target, leafBlocks, MiningRequirement.HAND);
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
        super.onResourceStart(mod);
        mod.getBehaviour().avoidUseTool(CollectSaplingTask::shouldAvoidTool);
    }

    public static boolean shouldAvoidTool(BlockState state, ItemStack stack) {
        if (!state.is(BlockTags.LEAVES)) return false;
        if (stack.isEmpty()) return false;
        if (stack.is(Items.SHEARS)) return true;
        for (var enchantment : stack.getEnchantments().entrySet()) {
            if (enchantment.getIntValue() > 0 && enchantment.getKey().is(Enchantments.SILK_TOUCH)) {
                return true;
            }
        }
        return false;
    }
}
