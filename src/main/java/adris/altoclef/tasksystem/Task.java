package adris.altoclef.tasksystem;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.movement.TimeoutWanderTask;

import java.util.function.Predicate;

public abstract class Task {

    private String _oldDebugState = "";
    private String _debugState = "";

    private Task _sub = null;

    private boolean _first = true;

    private boolean _stopped = false;

    private boolean _active = false;
    private boolean _needsInitialTickBeforeTerminalCheck;

    private TaskFailure.Snapshot _pendingChildFailure;
    private TaskFailure.Snapshot _propagatedChildFailure;

    public void tick(AltoClef mod, TaskChain parentChain) {
        parentChain.addTaskToChain(this);
        if (_first) {
            Debug.logInternal("Task START: " + this);
            _active = true;
            onStart(mod);
            _first = false;
            _stopped = false;
            _needsInitialTickBeforeTerminalCheck = false;
        }
        if (_stopped) return;

        Task newSub = onTick(mod);
        // Debug state print
        if (!_oldDebugState.equals(_debugState)) {
            Debug.logInternal(toString());
            _oldDebugState = _debugState;
        }
        // We have a sub task
        if (newSub != null) {
            boolean sameSubtask = _sub != null && newSub.isEqual(_sub);
            if (!sameSubtask) {
                if (canBeInterrupted(mod, _sub, newSub)) {
                    // Our sub task is new
                    if (_sub != null) {
                        // Our previous sub must be interrupted.
                        _sub.stop(mod, newSub);
                    }

                    _sub = newSub;
                    _pendingChildFailure = null;
                    _propagatedChildFailure = null;
                }
            }

            // Give the parent one tick to replace a failed child. If it keeps the
            // same child, the failure is unhandled and can safely bubble upward.
            if (sameSubtask && _pendingChildFailure != null && !shouldDeferFailure(mod)) {
                _propagatedChildFailure = _pendingChildFailure;
                _pendingChildFailure = null;
            }

            // Run our child
            if (_propagatedChildFailure == null || shouldDeferFailure(mod)) {
                _sub.tick(mod, parentChain);
                if (_propagatedChildFailure == null) {
                    _pendingChildFailure = _sub.getFailureSnapshot();
                }
            }
        } else {
            // We are null
            if (_sub != null && canBeInterrupted(mod, _sub, null)) {
                // Our previous sub must be interrupted.
                _sub.stop(mod);
                _sub = null;
                _pendingChildFailure = null;
                _propagatedChildFailure = null;
            } else if (_sub != null) {
                // A forcing descendant blocked the stop, so keep ticking it until
                // its required cleanup is complete.
                _sub.tick(mod, parentChain);
                _pendingChildFailure = _sub.getFailureSnapshot();
            }
        }
    }

    public void reset() {
        _first = true;
        _active = false;
        _stopped = false;
        _pendingChildFailure = null;
        _propagatedChildFailure = null;
        if (_sub != null) {
            _sub.reset();
        }
    }

    /** Resets lifecycle and cached failure state for an explicitly new run. */
    public final void restartForNewRun() {
        reset();
        onResetForNewRun();
        _needsInitialTickBeforeTerminalCheck = true;
        if (_sub != null) {
            _sub.restartForNewRun();
        }
    }

    /** Whether a newly assigned run must execute onStart before terminal checks. */
    public boolean needsInitialTickBeforeTerminalCheck() {
        return _needsInitialTickBeforeTerminalCheck;
    }

    /** Clears task-specific terminal state before this object is run again. */
    protected void onResetForNewRun() {
    }

    public void stop(AltoClef mod) {
        stop(mod, null);
    }

    /**
     * Stops the task. Next time it's run it will run `onStart`
     */
    public void stop(AltoClef mod, Task interruptTask) {
        if (!_active) return;
        Debug.logInternal("Task STOP: " + this + ", interrupted by " + interruptTask);
        if (!_first) {
            onStop(mod, interruptTask);
        }

        if (_sub != null && !_sub.stopped()) {
            _sub.stop(mod, interruptTask);
        }

        _first = true;
        _active = false;
        _stopped = true;
    }

    /**
     * Lets the task know it's execution has been "suspended"
     *
     * STILL RUNS `onStop`
     *
     * Doesn't stop it all-together (meaning `isActive` still returns true)
     */
    public void interrupt(AltoClef mod, Task interruptTask) {
        if (!_active) return;
        if (!_first) {
            onStop(mod, interruptTask);
        }

        if (_sub != null && !_sub.stopped()) {
            _sub.interrupt(mod, interruptTask);
        }

        _pendingChildFailure = null;
        _propagatedChildFailure = null;
        _first = true;
    }

    protected void setDebugState(String state) {
        if (state == null) {
            state = "";
        }
        _debugState = state;
    }

    // Virtual
    public boolean isFinished(AltoClef mod) {
        return false;
    }

    public boolean isActive() {
        return _active;
    }

    public boolean stopped() {
        return _stopped;
    }

    protected abstract void onStart(AltoClef mod);

    protected abstract Task onTick(AltoClef mod);

    // interruptTask = null if the task stopped cleanly
    protected abstract void onStop(AltoClef mod, Task interruptTask);

    protected abstract boolean isEqual(Task other);

    protected abstract String toDebugString();

    @Override
    public String toString() {
        return "<" + toDebugString() + "> " + _debugState;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof Task task) {
            return isEqual(task);
        }
        return false;
    }

    public boolean thisOrChildSatisfies(Predicate<Task> pred) {
        Task t = this;
        while (t != null) {
            if (pred.test(t)) return true;
            t = t._sub;
        }
        return false;
    }

    public boolean thisOrChildAreTimedOut() {
        return thisOrChildSatisfies(task -> task instanceof TimeoutWanderTask);
    }

    /** Returns this task's failure or one from a child that it has latched. */
    public TaskFailure.Snapshot getFailureSnapshot() {
        TaskFailure.Snapshot ownFailure = getOwnFailureSnapshot();
        if (ownFailure != null) return ownFailure;
        return _propagatedChildFailure;
    }

    /** Returns failure reported by this task itself, without child failures. */
    public TaskFailure.Snapshot getOwnFailureSnapshot() {
        if (this instanceof TaskFailure failure && failure.hasFailed()) {
            return new TaskFailure.Snapshot(failure.getFailureReason());
        }
        return null;
    }

    /** True while this task tree contains work that must finish before stopping. */
    public boolean shouldDeferFailure(AltoClef mod) {
        for (Task task = this; task != null; task = task._sub) {
            if (task.isActive() && task instanceof ITaskCanForce force && force.shouldForce(mod, null)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Sometimes a task just can NOT be bothered to be interrupted right now.
     * For instance, if we're in mid air and MUST complete the parkour movement.
     */
    private boolean canBeInterrupted(AltoClef mod, Task subTask, Task toInterruptWith) {
        if (subTask == null) return true;
        // A single forcing descendant must be able to protect the subtree. An
        // ordinary ancestor cannot mask that request.
        return !subTask.thisOrChildSatisfies(task -> task.isActive() && task instanceof ITaskCanForce canForce
                && canForce.shouldForce(mod, toInterruptWith));
    }
}
