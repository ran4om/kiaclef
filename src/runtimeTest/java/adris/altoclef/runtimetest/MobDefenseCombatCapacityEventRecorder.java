package adris.altoclef.runtimetest;

import adris.altoclef.AltoClef;
import adris.altoclef.chains.MobDefenseChain;
import adris.altoclef.chains.SingleTaskChain;
import adris.altoclef.mixins.ClientConnectionAccessor;
import adris.altoclef.runtimetest.mixins.RunAwayFromHostilesTaskAccessor;
import adris.altoclef.tasks.movement.RunAwayFromHostilesTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.tasksystem.TaskRunner;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.monster.skeleton.Skeleton;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.List;

/** Runtime-only, bounded observation of the actual TaskRunner result for the Run 144 check. */
public final class MobDefenseCombatCapacityEventRecorder {
    private static final int MAX_EPOCHS = 256;
    private static final ArrayList<Epoch> EPOCHS = new ArrayList<>();
    private static boolean capturing;
    private static boolean suppressSelectedChainTick;
    private static boolean overflowed;
    private static String captureId;
    private static long nextEpoch;
    private static MutableEpoch current;

    private MobDefenseCombatCapacityEventRecorder() {
    }

    public static synchronized void openCapture(String id) {
        if (capturing) throw new IllegalStateException("Mob-defense capture is already open: " + captureId);
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Capture id must be nonblank");
        EPOCHS.clear();
        captureId = id;
        nextEpoch = 0;
        current = null;
        overflowed = false;
        capturing = true;
        suppressSelectedChainTick = true;
    }

    public static synchronized void closeCapture() {
        capturing = false;
        suppressSelectedChainTick = false;
        current = null;
    }

    /** Freezes the recorded epochs while retaining task suppression until terminal state is checked. */
    public static synchronized Snapshot closeAndSnapshot() {
        capturing = false;
        current = null;
        return new Snapshot(captureId, false, overflowed, List.copyOf(EPOCHS));
    }

    public static synchronized Snapshot snapshot() {
        return new Snapshot(captureId, capturing, overflowed, List.copyOf(EPOCHS));
    }

    public static synchronized boolean isCapturing() {
        return capturing;
    }

    /** Keeps the selected task tick suppressed while the packaged runner archives a failure. */
    public static synchronized void holdTaskTicksForDiagnostics() {
        capturing = false;
        current = null;
        suppressSelectedChainTick = true;
    }

    /** Opens the epoch before TaskRunner evaluates its active chains. */
    public static synchronized void beginTaskRunnerTick() {
        if (!capturing) return;
        if (current != null) overflowed = true;
        current = new MutableEpoch(++nextEpoch, connectionTick());
    }

    /** Records a priority value returned by the actual scheduler call, without reevaluating it. */
    public static synchronized void recordChainPriority(TaskChain chain, float priority, AltoClef mod) {
        if (!capturing || current == null) return;
        String name;
        try {
            name = chain.getName();
        } catch (RuntimeException error) {
            name = "<getName threw " + error.getClass().getSimpleName() + ">";
        }
        current.priorities.add(new ChainPriority(name, type(chain), identity(chain), priority));
        if (chain instanceof MobDefenseChain && mod != null && mod.getPlayer() != null) {
            current.mobDefenseHostiles.clear();
            for (Entity hostile : mod.getEntityTracker().getHostiles()) {
                int range = hostile instanceof Skeleton || hostile instanceof Witch ? 18 : 5;
                current.mobDefenseHostiles.add(new HostileObservation(hostile.getUUID().toString(),
                        hostile.getClass().getName(), hostile.isAlive(), hostile.distanceTo(mod.getPlayer()),
                        hostile.closerThan(mod.getPlayer(), range)));
            }
        }
    }

    /** Records the actual sword input and returned score used by MobDefense's capacity branch. */
    public static synchronized void recordCombatCapacityDamage(Item sword, float damage) {
        if (!capturing || current == null) return;
        String swordId = sword == null ? "<null>" : BuiltInRegistries.ITEM.getKey(sword).toString();
        Minecraft client = Minecraft.getInstance();
        int armor = client.player == null ? -1 : client.player.getArmorValue();
        current.combatCapacityDecisions.add(new CombatCapacityDecision(swordId, damage, armor));
    }

    /** Records which defense root was assigned by the production priority branch this epoch. */
    public static synchronized void recordMobDefenseTaskAssignment(SingleTaskChain chain, Task task) {
        if (!capturing || current == null || !(chain instanceof MobDefenseChain)) return;
        String detail = "";
        if (task instanceof RunAwayFromHostilesTask away) {
            try {
                RunAwayFromHostilesTaskAccessor accessor = (RunAwayFromHostilesTaskAccessor) (Object) away;
                detail = "distance=" + accessor.altoclef$getDistanceToRun()
                        + ";includeSkeletons=" + accessor.altoclef$getIncludeSkeletons();
            } catch (ClassCastException | LinkageError error) {
                detail = "runAwayAccessor=unavailable:" + error.getClass().getSimpleName();
            }
        }
        current.assignments.add(new TaskAssignment(identity(chain), type(task), identity(task), detail));
    }

    /**
     * Captures the chain and root selected by TaskRunner immediately before execution. Once the
     * diagnostic arms, suppresses every selected chain tick until cleanup explicitly closes it, so
     * the task cannot move the player, attack fixture mobs, or change terminal state while snapshots
     * are collected.
     */
    public static synchronized boolean recordSelectionBeforeTaskTick(TaskRunner runner, TaskChain selected) {
        if (!suppressSelectedChainTick) return false;
        if (!capturing) return true;
        if (current == null) {
            overflowed = true;
            current = new MutableEpoch(++nextEpoch, connectionTick());
        }
        current.preSelection = describe(selected);
        current.selectionIntercepted = true;
        current.selectedPointerMatches = runner.getCurrentTaskChain() == selected;
        return true;
    }

    /** Records the scheduler's post-selection pointer as corroboration after the tick body returns. */
    public static synchronized void recordTaskRunnerReturn(TaskRunner runner) {
        if (!capturing) return;
        if (current == null) {
            overflowed = true;
            current = new MutableEpoch(++nextEpoch, connectionTick());
        }
        current.postSelection = describe(runner.getCurrentTaskChain());
        current.returnObserved = true;
        if (EPOCHS.size() >= MAX_EPOCHS) {
            overflowed = true;
        } else {
            EPOCHS.add(current.freeze());
        }
    }

    public static synchronized void endTaskRunnerTick() {
        current = null;
    }

    private static Selection describe(TaskChain chain) {
        if (chain == null) return new Selection("<none>", "<none>", "<none>",
                "<none>", "<none>", false);
        Task root = chain instanceof SingleTaskChain single ? single.getCurrentTask() : null;
        String name;
        try {
            name = chain.getName();
        } catch (RuntimeException error) {
            name = "<getName threw " + error.getClass().getSimpleName() + ">";
        }
        return new Selection(name, type(chain), identity(chain), type(root), identity(root),
                root != null && root.isActive());
    }

    private static String type(Object value) {
        return value == null ? "<none>" : value.getClass().getName();
    }

    private static String identity(Object value) {
        return value == null ? "<none>" : Integer.toUnsignedString(System.identityHashCode(value), 16);
    }

    private static int connectionTick() {
        Minecraft client = Minecraft.getInstance();
        if (client.getConnection() == null || client.getConnection().getConnection() == null) return -1;
        try {
            return ((ClientConnectionAccessor) client.getConnection().getConnection()).getTicks();
        } catch (ClassCastException | LinkageError error) {
            return -1;
        }
    }

    private static final class MutableEpoch {
        private final long schedulerEpoch;
        private final int connectionTick;
        private final ArrayList<ChainPriority> priorities = new ArrayList<>();
        private final ArrayList<TaskAssignment> assignments = new ArrayList<>();
        private final ArrayList<HostileObservation> mobDefenseHostiles = new ArrayList<>();
        private final ArrayList<CombatCapacityDecision> combatCapacityDecisions = new ArrayList<>();
        private Selection preSelection;
        private Selection postSelection;
        private boolean selectionIntercepted;
        private boolean selectedPointerMatches;
        private boolean returnObserved;

        private MutableEpoch(long schedulerEpoch, int connectionTick) {
            this.schedulerEpoch = schedulerEpoch;
            this.connectionTick = connectionTick;
        }

        private Epoch freeze() {
            return new Epoch(schedulerEpoch, List.copyOf(priorities), List.copyOf(assignments),
                    List.copyOf(mobDefenseHostiles), List.copyOf(combatCapacityDecisions), connectionTick,
                    preSelection, postSelection, selectionIntercepted, selectedPointerMatches, returnObserved);
        }
    }

    public record ChainPriority(String name, String chainType, String chainIdentity, float returnedPriority) {
    }

    public record TaskAssignment(String chainIdentity, String taskType, String taskIdentity, String detail) {
    }

    public record HostileObservation(String entityId, String entityType, boolean alive,
                                     double distance, boolean withinAnnoyingRange) {
    }

    public record CombatCapacityDecision(String swordId, float damage, int armorValue) {
    }

    public record Selection(String chainName, String chainType, String chainIdentity, String rootTaskType,
                            String rootTaskIdentity, boolean rootTaskStarted) {
    }

    public record Epoch(long schedulerEpoch, List<ChainPriority> priorities, List<TaskAssignment> assignments,
                       List<HostileObservation> mobDefenseHostiles,
                       List<CombatCapacityDecision> combatCapacityDecisions, int connectionTick,
                       Selection preSelection, Selection postSelection, boolean selectionIntercepted,
                       boolean selectedPointerMatches, boolean returnObserved) {
        public Epoch {
            priorities = List.copyOf(priorities);
            assignments = List.copyOf(assignments);
            mobDefenseHostiles = List.copyOf(mobDefenseHostiles);
            combatCapacityDecisions = List.copyOf(combatCapacityDecisions);
        }
    }

    public record Snapshot(String captureId, boolean capturing, boolean overflowed, List<Epoch> epochs) {
        public Snapshot {
            epochs = List.copyOf(epochs);
        }
    }
}
