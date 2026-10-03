package adris.altoclef.tasks.squashed;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.container.CraftInTableTask;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.container.UpgradeInSmithingTableTask;
import adris.altoclef.tasks.resources.CollectWheatTask;
import adris.altoclef.tasks.squashed.CataloguedResourceTask.TaskSquasher;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskFailure;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.StorageHelper;
import net.minecraft.world.item.ItemStack;
import org.apache.commons.lang3.ArrayUtils;

import java.util.*;

public class CataloguedResourceTask extends ResourceTask implements TaskFailure {


    private final TaskSquasher _squasher;
    private final ItemTarget[] _targets;
    private final List<ResourceTask> _tasksToComplete;
    private String _failureReason;
    private ResourceTask _currentResourceTask;

    public CataloguedResourceTask(boolean squash, ItemTarget... targets) {
        super(targets);
        _squasher = new TaskSquasher();
        _targets = targets;
        _tasksToComplete = new ArrayList<>(targets.length);

        for (ItemTarget target : targets) {
            if (target == null) continue;
            if (target.isEmpty()) {
                if (target.getTargetCount() > 0) {
                    throw new IllegalArgumentException("Cannot collect a positive count for an empty item target: " + target);
                }
                continue;
            }
            _tasksToComplete.add(TaskCatalogue.getItemTask(target));
        }

        if (squash) {
            squashTasks(_tasksToComplete);
        }
    }

    public CataloguedResourceTask(ItemTarget... targets) {
        this(true, targets);
    }

    @Override
    protected void onResourceStart(AltoClef mod) {

    }

    @Override
    protected void onResetForNewRun() {
        _failureReason = null;
        _currentResourceTask = null;
        for (ResourceTask task : _tasksToComplete) {
            task.restartForNewRun();
        }
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        if (updateFailureState(mod)) return null;
        // Finish the current recipe before reacquiring earlier list entries that
        // it consumes as ingredients. Interrupting it here also clears its
        // conversion-slot ownership while ingredients remain in the grid.
        if (_currentResourceTask != null && !_currentResourceTask.isFinished(mod)) {
            return _currentResourceTask;
        }
        _currentResourceTask = null;
        for (ResourceTask task : _tasksToComplete) {
            for (ItemTarget target : task.getItemTargets()) {
                if (!itemTargetMetWithAccessibleCount(target,
                        StorageHelper.getAccessibleInventoryItemCount(mod, target))) {
                    _currentResourceTask = task;
                    return task;
                }
            }
            if (task instanceof CollectWheatTask wheatTask && wheatTask.hasPendingCropReplant(mod)) {
                return wheatTask.getPendingCropReplantTask(mod);
            }
        }
        return null;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        if (updateFailureState(mod)) return true;
        for (ResourceTask task : _tasksToComplete) {
            for (ItemTarget target : task.getItemTargets()) {
                if (!itemTargetMetWithAccessibleCount(target,
                        StorageHelper.getAccessibleInventoryItemCount(mod, target))) return false;
            }
            if (shouldDeferForPendingWheatReplant(task, mod)) return false;
        }
        // All targets are met.
        return true;
    }

    static boolean shouldDeferForPendingWheatReplant(ResourceTask task, AltoClef mod) {
        return task instanceof CollectWheatTask wheatTask && wheatTask.hasPendingCropReplant(mod);
    }

    private boolean updateFailureState(AltoClef mod) {
        if (_failureReason != null) return true;
        for (ResourceTask task : _tasksToComplete) {
            TaskFailure.Snapshot failure = task.getFailureSnapshot();
            if (failure == null || task.shouldDeferFailure(mod)) continue;
            for (ItemTarget target : task.getItemTargets()) {
                int count = StorageHelper.getAccessibleInventoryItemCount(mod, target);
                if (count < target.getTargetCount()) {
                    String reason = failure.reason();
                    _failureReason = "Could not collect " + target + ": "
                            + (reason == null || reason.isBlank() ? "the resource task failed." : reason);
                    return true;
                }
            }
        }
        return false;
    }

    static boolean isIncompleteFailedTarget(ItemTarget target, int accessibleCount, TaskFailure failure) {
        return failure.hasFailed() && !itemTargetMetWithAccessibleCount(target, accessibleCount);
    }

    @Override
    public boolean hasFailed() {
        return _failureReason != null;
    }

    @Override
    public String getFailureReason() {
        return _failureReason;
    }

    static boolean itemTargetMetWithoutCursor(ItemTarget target, int inventoryCountIncludingCursor,
                                               ItemStack cursorStack) {
        int count = inventoryCountIncludingCursor;
        if (target.matches(cursorStack.getItem())) {
            count -= cursorStack.getCount();
        }
        return count >= target.getTargetCount();
    }

    static boolean itemTargetMetWithAccessibleCount(ItemTarget target, int accessibleCount) {
        return accessibleCount >= target.getTargetCount();
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        // Useless
        return false;
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {

    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        if (other instanceof CataloguedResourceTask task) {
            return Arrays.equals(task._targets, _targets);
        }
        return false;
    }

    @Override
    protected String toDebugStringName() {
        return "Get catalogued: " + ArrayUtils.toString(_targets);
    }

    private void squashTasks(List<ResourceTask> tasks) {
        _squasher.addTasks(tasks);
        tasks.clear();
        tasks.addAll(_squasher.getSquashed());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static class TaskSquasher {

        private final Map<Class, adris.altoclef.tasks.squashed.TypeSquasher> _squashMap = new HashMap<>();

        private final List<ResourceTask> _unSquashableTasks = new ArrayList<>();

        public TaskSquasher() {
            _squashMap.put(CraftInTableTask.class, new CraftSquasher());
            _squashMap.put(UpgradeInSmithingTableTask.class, new SmithingSquasher());
            //_squashMap.put(MineAndCollectTask.class)
        }

        public void addTask(ResourceTask t) {
            Class type = t.getClass();
            if (_squashMap.containsKey(type)) {
                _squashMap.get(type).add(t);
            } else {
                //Debug.logMessage("Unsquashable: " + type + ": " + t);
                _unSquashableTasks.add(t);
            }
        }

        public void addTasks(List<ResourceTask> tasks) {
            for (ResourceTask task : tasks) {
                addTask(task);
            }
        }

        public List<ResourceTask> getSquashed() {
            List<ResourceTask> result = new ArrayList<>();

            for (Class type : _squashMap.keySet()) {
                result.addAll(_squashMap.get(type).getSquashed());
            }
            result.addAll(_unSquashableTasks);

            return result;
        }
    }


}
