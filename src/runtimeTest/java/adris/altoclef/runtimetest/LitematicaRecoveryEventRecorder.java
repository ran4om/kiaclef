package adris.altoclef.runtimetest;

import adris.altoclef.tasks.construction.BuildSchematicTask;
import adris.altoclef.tasks.movement.DodgeProjectilesTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.tasksystem.TaskRunner;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

/**
 * Bounded, opt-in event buffer for a Litematica interruption/recovery acceptance run.
 * No event is retained unless an acceptance owner explicitly opens a capture.
 */
public final class LitematicaRecoveryEventRecorder {
    private static final int MAX_EVENTS = 4096;
    private static final int MAX_PENDING_PRIORITIES = 64;
    private static final ArrayDeque<Event> EVENTS = new ArrayDeque<>();
    private static final ArrayList<PendingPriority> PENDING_PRIORITIES = new ArrayList<>();

    private static boolean capturing;
    private static String captureId;
    private static long nextSequence;
    private static long droppedEvents;
    private static long nextSchedulerTick;
    private static long schedulerTick = -1;
    private static boolean overflowed;
    private static boolean pendingPrioritiesOverflowed;

    private record PendingPriority(String subjectType, String subjectIdentity, String detail) { }

    private LitematicaRecoveryEventRecorder() {
    }

    /** Opens and resets one explicitly named capture. Throws if a capture is already open. */
    public static synchronized void openCapture(String id) {
        if (capturing) {
            throw new IllegalStateException("Litematica recovery capture is already open: " + captureId);
        }
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Capture id must be nonblank");
        }
        EVENTS.clear();
        captureId = id;
        nextSequence = 0;
        droppedEvents = 0;
        overflowed = false;
        schedulerTick = -1;
        PENDING_PRIORITIES.clear();
        pendingPrioritiesOverflowed = false;
        capturing = true;
    }

    /** Closes collection while leaving the captured events available to the acceptance owner. */
    public static synchronized void closeCapture() {
        capturing = false;
    }

    public static synchronized boolean isCapturing() {
        return capturing;
    }

    /** Returns an immutable copy suitable for scenario assertions and evidence serialization. */
    public static synchronized Snapshot snapshot() {
        return new Snapshot(captureId, capturing, droppedEvents, overflowed, List.copyOf(EVENTS));
    }

    /** Atomically closes and detaches the completed bounded capture for durable archiving. */
    public static synchronized Snapshot closeAndSnapshot() {
        capturing = false;
        return new Snapshot(captureId, false, droppedEvents, overflowed, List.copyOf(EVENTS));
    }

    public static void recordBuildStart(BuildSchematicTask task) {
        record("build.onStart", "BuildSchematicTask", identity(task), null, null, null);
    }

    public static void recordBuildStop(BuildSchematicTask task, Task interruptedBy, boolean currentUserTask) {
        record("build.onStop", "BuildSchematicTask", identity(task), type(interruptedBy),
                identityOrNull(interruptedBy), "currentUserTask=" + currentUserTask);
    }

    public static void recordPrepareBatch(BuildSchematicTask task, boolean entering) {
        record(entering ? "build.prepareBatch.enter" : "build.prepareBatch.exit",
                "BuildSchematicTask", identity(task), null, null, null);
    }

    /** Records world-state material requirements for cells not yet satisfied. */
    public static void recordRemainingWorldMaterials(BuildSchematicTask task, Map<Item, Integer> remaining) {
        String materialCounts = formatMaterials(remaining);
        record("build.remainingWorldMaterials.return", "BuildSchematicTask", identity(task), null, null,
                "remaining=" + materialCounts);
    }

    /** Records the inventory-aware material budget selected for the next build batch. */
    public static void recordInventoryMaterialBudget(BuildSchematicTask task, Map<Item, Integer> budget) {
        record("build.inventoryMaterialBudget.return", "BuildSchematicTask", identity(task), null, null,
                "batch=" + formatMaterials(budget));
    }

    private static String formatMaterials(Map<Item, Integer> materials) {
        return materials.entrySet().stream()
                .sorted(Map.Entry.comparingByKey((left, right) ->
                        BuiltInRegistries.ITEM.getKey(left).compareTo(BuiltInRegistries.ITEM.getKey(right))))
                .map(entry -> BuiltInRegistries.ITEM.getKey(entry.getKey()) + "=" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    public static void recordDodgeTick(DodgeProjectilesTask task, Task returnedTask) {
        record("dodge.onTick.return", "DodgeProjectilesTask", identity(task), type(returnedTask),
                identityOrNull(returnedTask), null);
    }

    /** Records the already-computed priority value from the TaskRunner tick. */
    public static synchronized void recordChainPriority(TaskChain chain, float returnedPriority) {
        String name;
        try {
            name = chain.getName();
        } catch (RuntimeException exception) {
            name = "<getName threw " + exception.getClass().getSimpleName() + ">";
        }
        if (!capturing) return;
        if (PENDING_PRIORITIES.size() == MAX_PENDING_PRIORITIES) {
            pendingPrioritiesOverflowed = true;
            return;
        }
        PENDING_PRIORITIES.add(new PendingPriority("TaskChain", identity(chain),
                "name=" + name + ";returnedPriority=" + returnedPriority));
    }

    /** Opens one scheduler epoch so the already-computed priorities can be tied to its selection. */
    public static synchronized void beginTaskRunnerTick() {
        schedulerTick = ++nextSchedulerTick;
        PENDING_PRIORITIES.clear();
        pendingPrioritiesOverflowed = false;
    }

    /** Records the chain selected by the scheduler at the end of its actual tick. */
    public static synchronized void recordSelectedTaskChain(TaskRunner runner) {
        TaskChain selected = runner.getCurrentTaskChain();
        String name;
        try {
            name = selected == null ? "<none>" : selected.getName();
        } catch (RuntimeException exception) {
            name = "<getName threw " + exception.getClass().getSimpleName() + ">";
        }
        if (capturing && selected != null && type(selected).endsWith("MobDefenseChain")) {
            if (pendingPrioritiesOverflowed) {
                overflowed = true;
                droppedEvents++;
            }
            for (PendingPriority priority : PENDING_PRIORITIES)
                record("taskRunner.priority", priority.subjectType(), priority.subjectIdentity(), null, null, priority.detail());
            record("taskRunner.selectedChain", "TaskRunner", "scheduler",
                    type(selected), identityOrNull(selected), "name=" + name);
        }
        PENDING_PRIORITIES.clear();
        pendingPrioritiesOverflowed = false;
    }

    public static synchronized void endTaskRunnerTick() {
        PENDING_PRIORITIES.clear();
        pendingPrioritiesOverflowed = false;
        schedulerTick = -1;
    }

    private static synchronized void record(String kind, String subjectType, String subjectIdentity,
                                           String relatedType, String relatedIdentity, String detail) {
        if (!capturing) return;
        if (EVENTS.size() == MAX_EVENTS) {
            droppedEvents++;
            overflowed = true;
            return;
        }
        EVENTS.addLast(new Event(captureId, nextSequence++, System.nanoTime(), schedulerTick, kind,
                subjectType, subjectIdentity, relatedType, relatedIdentity, detail));
    }

    private static String type(Object value) {
        return value == null ? null : value.getClass().getName();
    }

    private static String identity(Object value) {
        return Integer.toUnsignedString(System.identityHashCode(value), 16);
    }

    private static String identityOrNull(Object value) {
        return value == null ? null : identity(value);
    }

    public record Event(String captureId, long sequence, long monotonicNanos, long schedulerTick, String kind,
                        String subjectType, String subjectIdentity, String relatedType,
                        String relatedIdentity, String detail) {
    }

    public record Snapshot(String captureId, boolean capturing, long droppedEvents, boolean overflowed, List<Event> events) {
        public Snapshot {
            events = List.copyOf(events);
        }
    }
}
