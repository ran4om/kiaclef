package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.StorageHelper;
import net.minecraft.world.item.Item;

import java.util.Arrays;
import java.util.Objects;

/** Collects enough of any registered item accepted by a multi-match target. */
public final class CollectAnyItemTask extends ResourceTask {
    private final ItemTarget _target;
    private final Item[] _registeredMatches;
    private ResourceTask _currentTask;

    public CollectAnyItemTask(ItemTarget target) {
        super(target);
        _target = Objects.requireNonNull(target, "target");
        _registeredMatches = Arrays.stream(target.getMatches())
                .filter(Objects::nonNull)
                .distinct()
                .filter(TaskCatalogue::taskExists)
                .toArray(Item[]::new);
        if (_registeredMatches.length == 0) {
            throw new IllegalArgumentException("No registered collection task matches " + target);
        }
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
        _currentTask = null;
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        int availableCount = StorageHelper.getAccessibleInventoryItemCount(mod, _target);
        int remainingCount = _target.getTargetCount() - availableCount;
        if (remainingCount <= 0) return null;

        if (_currentTask != null && !_currentTask.isFinished(mod)) {
            return _currentTask;
        }

        Item selected = chooseCandidate(_registeredMatches,
                item -> StorageHelper.getAccessibleInventoryItemCount(mod, new ItemTarget(item)));
        int selectedCount = StorageHelper.getAccessibleInventoryItemCount(mod, new ItemTarget(selected));
        _currentTask = TaskCatalogue.getItemTask(selected, selectedCount + remainingCount);
        if (_currentTask == null) {
            throw new IllegalStateException("Registered collection task disappeared for " + selected.getDescriptionId());
        }
        return _currentTask;
    }

    static Item chooseCandidate(Item[] candidates, java.util.function.ToIntFunction<Item> inventoryCount) {
        if (candidates.length == 0) throw new IllegalArgumentException("No collection candidates");
        Item selected = candidates[0];
        int selectedCount = inventoryCount.applyAsInt(selected);
        for (int i = 1; i < candidates.length; i++) {
            int count = inventoryCount.applyAsInt(candidates[i]);
            if (count > selectedCount) {
                selected = candidates[i];
                selectedCount = count;
            }
        }
        return selected;
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
        _currentTask = null;
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        return other instanceof CollectAnyItemTask task && _target.equals(task._target);
    }

    @Override
    protected String toDebugStringName() {
        return "Collect any accepted item: " + _target;
    }
}
