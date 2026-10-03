package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.tasksystem.TaskFailure;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.util.time.Stopwatch;

public abstract class SingleTaskChain extends TaskChain {

    private final Stopwatch _taskStopwatch = new Stopwatch();
    protected Task _mainTask = null;
    private boolean _interrupted = false;

    private AltoClef _mod;

    public SingleTaskChain(TaskRunner runner) {
        super(runner);
        _mod = runner.getMod();
    }

    @Override
    protected void onTick(AltoClef mod) {
        if (!isActive()) return;

        if (_interrupted) {
            _interrupted = false;
            if (_mainTask != null) {
                _mainTask.reset();
            }
        }

        if (_mainTask != null) {
            if (_mainTask.needsInitialTickBeforeTerminalCheck()) {
                _mainTask.tick(mod, this);
                return;
            }

            // isFinished implementations may discover and record failures while
            // checking their completion condition, so snapshot only afterward.
            boolean finished = _mainTask.isFinished(mod);
            TaskFailure.Snapshot failure = _mainTask.getFailureSnapshot();
            boolean failedByRoot = _mainTask.getOwnFailureSnapshot() != null;
            // The root goal wins if it became satisfied on the same tick as a
            // descendant failure. A task's own terminal failure also makes many
            // implementations report isFinished(), so preserve that failure.
            if (finished && !failedByRoot) {
                onTaskFinish(mod, null, false);
            } else if (failure != null && !_mainTask.shouldDeferFailure(mod)) {
                onTaskFinish(mod, failure, false);
            } else if (_mainTask.stopped()) {
                onTaskFinish(mod, failure, false);
            } else {
                _mainTask.tick(mod, this);
            }
        }
    }

    protected void onStop(AltoClef mod) {
        if (isActive() && _mainTask != null) {
            _mainTask.stop(mod);
            _mainTask = null;
        }
    }

    public void setTask(Task task) {
        if (_mainTask == null || !_mainTask.equals(task)) {
            if (_mainTask != null) {
                _mainTask.stop(_mod, task);
            }
            _mainTask = task;
            if (task != null) task.restartForNewRun();
        }
    }


    @Override
    public boolean isActive() {
        return _mainTask != null;
    }

    protected abstract void onTaskFinish(AltoClef mod);

    /** Completion hook carrying failure state captured before task cleanup. */
    protected void onTaskFinish(AltoClef mod, TaskFailure.Snapshot failure, boolean cancelled) {
        onTaskFinish(mod);
    }

    @Override
    public void onInterrupt(AltoClef mod, TaskChain other) {
        Debug.logInternal("Chain Interrupted: " + this + " by " + other.toString());
        // Stop our task. When we're started up again, let our task know we need to run.
        _interrupted = true;
        if (_mainTask != null && _mainTask.isActive()) {
            _mainTask.interrupt(mod, null);
        }
    }

    protected boolean isCurrentlyRunning(AltoClef mod) {
        return !_interrupted && _mainTask.isActive() && !_mainTask.isFinished(mod);
    }

    public Task getCurrentTask() {
        return _mainTask;
    }
}
