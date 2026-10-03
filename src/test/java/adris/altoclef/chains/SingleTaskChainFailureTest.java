package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.ITaskCanForce;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.tasksystem.TaskFailure;
import adris.altoclef.tasksystem.TaskRunner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SingleTaskChainFailureTest {

    @Test
    void finishedRootTaskFailureIsReportedInsteadOfSuccess() {
        AltoClef mod = new AltoClef();
        TestChain chain = new TestChain(new TaskRunner(mod));
        FailedFinishedTask task = new FailedFinishedTask();
        chain.setTask(task);

        chain.tick(mod);
        assertEquals(0, chain.finishCount);
        chain.tick(mod);

        assertEquals(1, chain.finishCount);
        assertEquals("terminal root failure", chain.failure.reason());
    }

    @Test
    void finishedCheckCanDiscoverDirectRootFailure() {
        AltoClef mod = new AltoClef();
        TestChain chain = new TestChain(new TaskRunner(mod));
        FailureDiscoveredByFinishedCheck task = new FailureDiscoveredByFinishedCheck();
        chain.setTask(task);

        chain.tick(mod);
        assertEquals(0, chain.finishCount);
        chain.tick(mod);

        assertEquals(1, chain.finishCount);
        assertEquals("discovered while checking completion", chain.failure.reason());
    }

    @Test
    void newRunStartsBeforeReadingCachedFailureState() {
        AltoClef mod = new AltoClef();
        TestChain chain = new TestChain(new TaskRunner(mod));
        ResetOnStartTask task = new ResetOnStartTask();
        chain.setTask(task);

        chain.tick(mod);
        chain.tick(mod);

        assertEquals(0, chain.finishCount);
        assertNull(chain.failure);
    }

    @Test
    void directFailureWaitsForRootForcedCleanup() {
        AltoClef mod = new AltoClef();
        TestChain chain = new TestChain(new TaskRunner(mod));
        ForcedFinishedTask task = new ForcedFinishedTask();
        chain.setTask(task);

        chain.tick(mod);
        assertEquals(1, task.ticks);
        assertNull(chain.failure);
        assertEquals(0, chain.finishCount);

        chain.tick(mod);
        assertEquals(2, task.ticks);
        assertNull(chain.failure);

        task.force = false;
        chain.tick(mod);
        assertEquals(1, chain.finishCount);
        assertEquals("forced cleanup failure", chain.failure.reason());
    }

    private static final class TestChain extends SingleTaskChain {
        private TaskFailure.Snapshot failure;
        private int finishCount;

        TestChain(TaskRunner runner) { super(runner); }

        @Override protected void onStop(AltoClef mod) { }
        @Override public float getPriority(AltoClef mod) { return 1; }
        @Override public String getName() { return "test"; }
        @Override protected void onTaskFinish(AltoClef mod) { finishCount++; }
        @Override protected void onTaskFinish(AltoClef mod, TaskFailure.Snapshot failure, boolean cancelled) {
            this.failure = failure;
            finishCount++;
        }
    }

    private static class FailedFinishedTask extends Task implements TaskFailure {
        @Override public boolean isFinished(AltoClef mod) { return true; }
        @Override public boolean hasFailed() { return true; }
        @Override public String getFailureReason() { return "terminal root failure"; }
        @Override protected void onStart(AltoClef mod) { }
        @Override protected Task onTick(AltoClef mod) { return null; }
        @Override protected void onStop(AltoClef mod, Task interruptTask) { }
        @Override protected boolean isEqual(Task other) { return this == other; }
        @Override protected String toDebugString() { return "failed finished task"; }
    }

    private static final class FailureDiscoveredByFinishedCheck extends Task implements TaskFailure {
        private boolean failed;
        @Override public boolean isFinished(AltoClef mod) {
            failed = true;
            return true;
        }
        @Override public boolean hasFailed() { return failed; }
        @Override public String getFailureReason() { return "discovered while checking completion"; }
        @Override protected void onStart(AltoClef mod) { }
        @Override protected Task onTick(AltoClef mod) { return null; }
        @Override protected void onStop(AltoClef mod, Task interruptTask) { }
        @Override protected boolean isEqual(Task other) { return this == other; }
        @Override protected String toDebugString() { return "late failure task"; }
    }

    private static final class ResetOnStartTask extends Task implements TaskFailure {
        private boolean failed = true;
        @Override public boolean isFinished(AltoClef mod) { return failed; }
        @Override public boolean hasFailed() { return failed; }
        @Override public String getFailureReason() { return "cached failure"; }
        @Override protected void onStart(AltoClef mod) { failed = false; }
        @Override protected Task onTick(AltoClef mod) { return null; }
        @Override protected void onStop(AltoClef mod, Task interruptTask) { }
        @Override protected boolean isEqual(Task other) { return this == other; }
        @Override protected String toDebugString() { return "reset on start task"; }
    }

    private static final class ForcedFinishedTask extends FailedFinishedTask implements ITaskCanForce {
        private boolean force = true;
        private int ticks;

        @Override public String getFailureReason() { return "forced cleanup failure"; }
        @Override public boolean shouldForce(AltoClef mod, Task interruptingCandidate) { return force; }
        @Override protected Task onTick(AltoClef mod) { ticks++; return null; }
    }
}
