package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.tasks.container.StoreInAnyContainerTask;
import adris.altoclef.util.ItemTarget;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Stores two spare diamond armor and tool kits in the player's Ender Chest. */
public final class StoreGearInEnderChestCommand extends Command {
    private static final ItemTarget[] SPARE_GEAR = {
            new ItemTarget("diamond_helmet", 2),
            new ItemTarget("diamond_chestplate", 2),
            new ItemTarget("diamond_leggings", 2),
            new ItemTarget("diamond_boots", 2),
            new ItemTarget("diamond_pickaxe", 2),
            new ItemTarget("diamond_axe", 2),
            new ItemTarget("diamond_shovel", 2),
            new ItemTarget("diamond_sword", 2),
            new ItemTarget("diamond_hoe", 2)
    };

    public StoreGearInEnderChestCommand() {
        super("store_gear_in_ender_chest", "Store two spare diamond kits in the Ender Chest");
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) {
        mod.runUserTask(
                StoreInAnyContainerTask.forContainerBlocks(
                        true,
                        new Block[]{Blocks.ENDER_CHEST},
                        SPARE_GEAR),
                this::finish);
    }
}
