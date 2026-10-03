package adris.altoclef.eventbus.events;

import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskFailure;

public class TaskFinishedEvent {
    public double durationSeconds;
    public Task lastTaskRan;
    public TaskFailure.Snapshot failure;
    public boolean cancelled;

    public TaskFinishedEvent(double durationSeconds, Task lastTaskRan) {
        this(durationSeconds, lastTaskRan, null, false);
    }

    public TaskFinishedEvent(double durationSeconds, Task lastTaskRan,
                             TaskFailure.Snapshot failure, boolean cancelled) {
        this.durationSeconds = durationSeconds;
        this.lastTaskRan = lastTaskRan;
        this.failure = failure;
        this.cancelled = cancelled;
    }
}
