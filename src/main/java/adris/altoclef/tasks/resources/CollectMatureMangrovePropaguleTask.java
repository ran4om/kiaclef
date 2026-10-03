package adris.altoclef.tasks.resources;

import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.MangrovePropaguleBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Waits for hanging mangrove propagules to mature before harvesting them. */
public final class CollectMatureMangrovePropaguleTask extends MineAndCollectTask {

    public CollectMatureMangrovePropaguleTask(ItemTarget target) {
        super(new ItemTarget[]{target}, new Block[]{Blocks.MANGROVE_PROPAGULE},
                MiningRequirement.HAND, CollectMatureMangrovePropaguleTask::isMature);
    }

    public static boolean isMature(BlockState state) {
        return state.is(Blocks.MANGROVE_PROPAGULE)
                && state.hasProperty(MangrovePropaguleBlock.AGE)
                && state.getValue(MangrovePropaguleBlock.AGE) == 4;
    }
}
