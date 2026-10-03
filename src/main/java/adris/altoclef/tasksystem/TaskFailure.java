package adris.altoclef.tasksystem;

/** Exposes a terminal task failure and a user-facing explanation for it. */
public interface TaskFailure {
    boolean hasFailed();

    String getFailureReason();

    /** Immutable reason snapshot, safe to retain after task cleanup. */
    record Snapshot(String reason) {
    }
}
