package adris.altoclef.tasks.resources;

import net.minecraft.world.item.Items;

/** Compatibility wrapper for callers that explicitly request stripped bamboo blocks. */
public final class CollectStrippedBambooBlockTask extends CollectStrippedBlockTask {
    public CollectStrippedBambooBlockTask(int count) {
        super(Items.BAMBOO_BLOCK, Items.STRIPPED_BAMBOO_BLOCK, count);
    }

    @Override
    protected String toDebugStringName() {
        return "Stripping bamboo blocks";
    }
}
