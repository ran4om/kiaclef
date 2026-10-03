package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.TaskFinishedEvent;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskFailure;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.util.time.Stopwatch;

// A task chain that runs a user defined task at the same priority.
// This basically replaces our old Task Runner.
@SuppressWarnings("ALL")
public class UserTaskChain extends SingleTaskChain {

    /** Stable outcome of the most recently completed user task. */
    public record CompletionSnapshot(Task task, double durationSeconds,
                                     TaskFailure.Snapshot failure, boolean cancelled) {
    }

    private final Stopwatch _taskStopwatch = new Stopwatch();
    private Runnable _currentOnFinish = null;

    private boolean _runningIdleTask;
    private boolean _nextTaskIdleFlag;
    private CompletionSnapshot _lastCompletion;

    public UserTaskChain(TaskRunner runner) {
        super(runner);
    }

    private static String prettyPrintTimeDuration(double seconds) {
        int minutes = (int) (seconds / 60);
        int hours = minutes / 60;
        int days = hours / 24;

        String result = "";
        if (days != 0) {
            result += days + " days ";
        }
        if (hours != 0) {
            result += (hours % 24) + " hours ";
        }
        if (minutes != 0) {
            result += (minutes % 60) + " minutes ";
        }
        if (!result.equals("")) {
            result += "and ";
        }
        result += String.format("%.3f", (seconds % 60));
        return result;
    }

    @Override
    protected void onTick(AltoClef mod) {

        // Pause if we're not loaded into a world.
        if (!mod.inGame()) return;

        super.onTick(mod);
    }

    public void cancel(AltoClef mod) {
        if (_mainTask != null) {
            finishTask(mod, _mainTask, null, true);
        }
    }

    @Override
    public float getPriority(AltoClef mod) {
        return 50;
    }

    @Override
    public String getName() {
        return "User Tasks";
    }

    public void runTask(AltoClef mod, Task task, Runnable onFinish) {
        _runningIdleTask = _nextTaskIdleFlag;
        _nextTaskIdleFlag = false;

        _currentOnFinish = onFinish;

        if (!_runningIdleTask) {
            Debug.logMessage("User Task Set: " + task.toString());
        }
        mod.getTaskRunner().enable();
        _taskStopwatch.begin();
        setTask(task);

        if (mod.getModSettings().failedToLoad()) {
            Debug.logWarning("Settings file failed to load at some point. Check logs for more info, or delete the" +
                    " file to re-load working settings.");
        }
    }

    @Override
    protected void onTaskFinish(AltoClef mod) {
        Task oldTask = _mainTask;
        TaskFailure.Snapshot failure = oldTask == null ? null : oldTask.getFailureSnapshot();
        finishTask(mod, oldTask, failure, false);
    }

    @Override
    protected void onTaskFinish(AltoClef mod, TaskFailure.Snapshot failure, boolean cancelled) {
        finishTask(mod, _mainTask, failure, cancelled);
    }

    private void finishTask(AltoClef mod, Task oldTask, TaskFailure.Snapshot failure, boolean cancelled) {
        Runnable onFinish = _currentOnFinish;
        boolean oldTaskWasIdle = _runningIdleTask;
        _mainTask = null;
        _currentOnFinish = null;

        // A finished task is still active until it is explicitly stopped. Run its
        // cleanup before invoking user callbacks or starting the idle command.
        if (oldTask != null) {
            oldTask.stop(mod);
        }

        double seconds = _taskStopwatch.time();
        _lastCompletion = new CompletionSnapshot(oldTask, seconds, failure, cancelled);

        boolean shouldIdle = mod.getModSettings().shouldRunIdleCommandWhenNotActive();
        if (!shouldIdle) {
            // Stop.
            mod.getTaskRunner().disable();
            // Extra reset. Sometimes baritone is laggy and doesn't properly reset our press
            clearAllInputKeys(mod);
        }
        if (onFinish != null) {
            //noinspection unchecked
            onFinish.run();
        }
        // A callback may already have scheduled the next task. The old task's
        // outcome still needs to be reported; only idle dispatch depends on the
        // chain remaining empty.
        if (!oldTaskWasIdle) {
            if (failure != null) {
                String reason = failure.reason();
                Debug.logError("User task FAILED%s. Took %s seconds.",
                        reason == null || reason.isBlank() ? "" : ": " + reason,
                        prettyPrintTimeDuration(seconds));
            } else if (cancelled) {
                Debug.logMessage("User task cancelled. Took %s seconds.", prettyPrintTimeDuration(seconds));
            } else {
                Debug.logMessage("User task FINISHED. Took %s seconds.", prettyPrintTimeDuration(seconds));
            }
            EventBus.publish(new TaskFinishedEvent(seconds, oldTask, failure, cancelled));
        }
        if (_mainTask == null) {
            if (shouldIdle) {
                AltoClef.getCommandExecutor().executeWithPrefix(mod.getModSettings().getIdleCommand());
                signalNextTaskToBeIdleTask();
                _runningIdleTask = true;
            }
        }
    }

    protected void clearAllInputKeys(AltoClef mod) {
        mod.getClientBaritone().getInputOverrideHandler().clearAllKeys();
    }

    public boolean isRunningIdleTask() {
        return isActive() && _runningIdleTask;
    }

    /** Returns the stable outcome recorded before the completion callback ran. */
    public CompletionSnapshot getLastCompletionSnapshot() {
        return _lastCompletion;
    }

    // The next task will be an idle task.
    public void signalNextTaskToBeIdleTask() {
        _nextTaskIdleFlag = true;
    }
}
