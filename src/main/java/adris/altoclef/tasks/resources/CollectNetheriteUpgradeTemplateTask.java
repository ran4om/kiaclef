package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.container.CraftInTableTask;
import adris.altoclef.tasks.container.LootContainerTask;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasks.movement.SearchChunkForBlockTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.CraftingRecipe;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.RecipeTarget;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Searches Nether chests for the seed template, then duplicates it as needed. */
public final class CollectNetheriteUpgradeTemplateTask extends ResourceTask {

    private static final int DIAMONDS_PER_DUPLICATION = 7;
    private static final int NETHERRACK_PER_DUPLICATION = 1;
    private static final RecipeTarget DUPLICATION_RECIPE = new RecipeTarget(
            Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE,
            1,
            CraftingRecipe.newShapedRecipe("netherite_upgrade_template_duplication", new ItemTarget[]{
                    new ItemTarget(Items.DIAMOND), new ItemTarget(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE), new ItemTarget(Items.DIAMOND),
                    new ItemTarget(Items.DIAMOND), new ItemTarget(Items.NETHERRACK), new ItemTarget(Items.DIAMOND),
                    new ItemTarget(Items.DIAMOND), new ItemTarget(Items.DIAMOND), new ItemTarget(Items.DIAMOND)
            }, 2)
    );

    private final int _count;
    private final Set<BlockPos> _inspectedChests = new HashSet<>();
    private final Task _chestSearcher = new SearchChunkForBlockTask(Blocks.CHEST);
    private Task _lootTask;
    private BlockPos _chestBeingInspected;

    public CollectNetheriteUpgradeTemplateTask(int count) {
        super(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, count);
        _count = count;
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
        _inspectedChests.clear();
        _lootTask = null;
        _chestBeingInspected = null;
        mod.getBlockTracker().trackBlock(Blocks.CHEST);
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        int templates = mod.getItemStorage().getItemCount(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE);
        if (templates == 0) {
            if (WorldHelper.getCurrentDimension() != Dimension.NETHER) {
                setDebugState("Going to the Nether to find a Bastion template");
                return new DefaultGoToDimensionTask(Dimension.NETHER);
            }

            if (_lootTask != null) {
                if (!_lootTask.isFinished(mod)) return _lootTask;
                // Only exclude it after the container tracker confirms that it was opened.
                if (_chestBeingInspected != null && !isUnopenedChest(mod, _chestBeingInspected)) {
                    _inspectedChests.add(_chestBeingInspected);
                }
                _lootTask = null;
                _chestBeingInspected = null;
            }

            Optional<BlockPos> chest = findUnopenedChest(mod);
            if (chest.isPresent()) {
                setDebugState("Checking an unopened Nether chest for the first upgrade template");
                _chestBeingInspected = chest.get();
                _lootTask = new LootContainerTask(chest.get(), List.of(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE));
                return _lootTask;
            }

            setDebugState("Exploring Nether chunks for unopened chests");
            return _chestSearcher;
        }

        int craftsNeeded = getNetCraftsNeeded(_count, templates);
        if (craftsNeeded == 0) return null;

        int diamondsNeeded = DIAMONDS_PER_DUPLICATION * craftsNeeded;
        int netherrackNeeded = NETHERRACK_PER_DUPLICATION * craftsNeeded;
        int diamonds = mod.getItemStorage().getItemCount(Items.DIAMOND);
        int netherrack = mod.getItemStorage().getItemCount(Items.NETHERRACK);

        if (diamonds < diamondsNeeded) {
            setDebugState("Gathering diamonds to duplicate upgrade templates");
            return TaskCatalogue.getItemTask(Items.DIAMOND, diamondsNeeded);
        }
        if (netherrack < netherrackNeeded) {
            setDebugState("Gathering netherrack to duplicate upgrade templates");
            return TaskCatalogue.getItemTask(Items.NETHERRACK, netherrackNeeded);
        }

        setDebugState("Duplicating netherite upgrade templates");
        RecipeTarget target = new RecipeTarget(
                Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE,
                _count,
                DUPLICATION_RECIPE.getRecipe());
        return new CraftInTableTask(target, false, true);
    }

    private Optional<BlockPos> findUnopenedChest(AltoClef mod) {
        return mod.getBlockTracker().getNearestTracking(pos ->
                !_inspectedChests.contains(pos)
                        && isUnopenedChest(mod, pos), Blocks.CHEST);
    }

    private boolean isUnopenedChest(AltoClef mod, BlockPos pos) {
        return mod.getItemStorage().getContainerAtPosition(pos).isEmpty();
    }

    public static int getNetCraftsNeeded(int requestedCount, int currentCount) {
        return Math.max(0, requestedCount - currentCount);
    }

    public static RecipeTarget getDuplicationRecipe() {
        return DUPLICATION_RECIPE;
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(Blocks.CHEST);
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        return other instanceof CollectNetheriteUpgradeTemplateTask task && task._count == _count;
    }

    @Override
    protected String toDebugStringName() {
        return "Collect " + _count + " netherite upgrade templates";
    }
}
