package adris.altoclef.tasksystem;

import adris.altoclef.AltoClef;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskFailurePropagationTest {

    @Test
    void bubblesFailureWhenParentKeepsTheSameChild() {
        Fixture fixture = new Fixture();
        FailedTask failedChild = new FailedTask("missing resources");
        ParentTask parent = new ParentTask(failedChild);

        parent.tick(fixture.mod, fixture.chain);
        assertNull(parent.getFailureSnapshot(), "parent gets one tick to react");

        parent.tick(fixture.mod, fixture.chain);
        assertEquals("missing resources", parent.getFailureSnapshot().reason());
    }

    @Test
    void replacingFailedChildRecoversInsteadOfPropagating() {
        Fixture fixture = new Fixture();
        ParentTask parent = new ParentTask(new FailedTask("first route failed"));
        parent.tick(fixture.mod, fixture.chain);

        parent.child = new PlainTask();
        parent.tick(fixture.mod, fixture.chain);

        assertNull(parent.getFailureSnapshot());
        assertTrue(parent.child.isActive());
    }

    @Test
    void forceTaskCanFinishCleanupBeforeFailureBubbles() {
        Fixture fixture = new Fixture();
        DeferredFailureTask failedChild = new DeferredFailureTask();
        ParentTask parent = new ParentTask(failedChild);
        parent.tick(fixture.mod, fixture.chain);

        parent.tick(fixture.mod, fixture.chain);
        assertNull(parent.getFailureSnapshot());
        assertEquals(2, failedChild.ticks);

        failedChild.force = false;
        parent.tick(fixture.mod, fixture.chain);
        assertEquals("cleanup complete", parent.getFailureSnapshot().reason());
    }

    @Test
    void latchedFailureKeepsTickingIfForcedCleanupBecomesNecessary() {
        Fixture fixture = new Fixture();
        DeferredFailureTask failedChild = new DeferredFailureTask();
        failedChild.force = false;
        ParentTask parent = new ParentTask(failedChild);
        parent.tick(fixture.mod, fixture.chain);
        parent.tick(fixture.mod, fixture.chain);
        assertEquals("cleanup complete", parent.getFailureSnapshot().reason());

        failedChild.force = true;
        parent.tick(fixture.mod, fixture.chain);

        assertTrue(parent.shouldDeferFailure(fixture.mod));
        assertEquals(2, failedChild.ticks);
        failedChild.force = false;
        assertFalse(parent.shouldDeferFailure(fixture.mod));
    }

    @Test
    void forcingGrandchildPreventsAncestorFromInterruptingItsSubtree() {
        Fixture fixture = new Fixture();
        ForcedTask forced = new ForcedTask();
        ParentTask inner = new ParentTask(forced);
        ParentTask outer = new ParentTask(inner);
        outer.tick(fixture.mod, fixture.chain);

        outer.child = new PlainTask();
        outer.tick(fixture.mod, fixture.chain);

        assertFalse(forced.stopped());
        assertTrue(fixture.chain.getTasks().contains(forced));
    }

    @Test
    void retainedForcedChildContinuesTickingWhenParentStopsRequestingIt() {
        Fixture fixture = new Fixture();
        ForcedTask forced = new ForcedTask();
        ParentTask parent = new ParentTask(forced);
        parent.tick(fixture.mod, fixture.chain);
        int ticksBeforeParentDropsChild = forced.ticks;

        parent.child = null;
        parent.tick(fixture.mod, fixture.chain);

        assertEquals(ticksBeforeParentDropsChild + 1, forced.ticks);
        assertFalse(forced.stopped());

        forced.force = false;
        parent.tick(fixture.mod, fixture.chain);
        assertTrue(forced.stopped());
    }

    @Test
    void stoppedForcedDescendantDoesNotBlockReplacementOrDeferFailure() {
        Fixture fixture = new Fixture();
        ForcedTask forced = new ForcedTask();
        ParentTask parent = new ParentTask(forced);
        parent.tick(fixture.mod, fixture.chain);
        forced.stop(fixture.mod);

        assertFalse(parent.shouldDeferFailure(fixture.mod));
        parent.child = new PlainTask();
        parent.tick(fixture.mod, fixture.chain);

        assertTrue(parent.child.isActive());
        assertFalse(parent.shouldDeferFailure(fixture.mod));
    }

    @Test
    void newRunClearsNestedFailureLatchesAndResetsFailureTask() {
        Fixture fixture = new Fixture();
        FailedTask failedChild = new FailedTask("old run failed");
        ParentTask parent = new ParentTask(failedChild);
        parent.tick(fixture.mod, fixture.chain);
        parent.tick(fixture.mod, fixture.chain);
        assertEquals("old run failed", parent.getFailureSnapshot().reason());

        parent.restartForNewRun();

        assertNull(parent.getFailureSnapshot());
        assertFalse(failedChild.hasFailed());
        parent.tick(fixture.mod, fixture.chain);
        assertNull(parent.getFailureSnapshot());
    }

    @Test
    void resumeResetPreservesTaskFailureState() {
        Fixture fixture = new Fixture();
        FailedTask failed = new FailedTask("preserve across interruption");
        failed.tick(fixture.mod, fixture.chain);

        failed.interrupt(fixture.mod, null);
        failed.reset();

        assertTrue(failed.hasFailed());
        assertEquals("preserve across interruption", failed.getFailureSnapshot().reason());
    }

    private static final class Fixture {
        final AltoClef mod = new AltoClef();
        final ManualChain chain = new ManualChain(new TaskRunner(mod));
    }

    private static final class ManualChain extends TaskChain {
        ManualChain(TaskRunner runner) { super(runner); }
        @Override protected void onStop(AltoClef mod) { }
        @Override public void onInterrupt(AltoClef mod, TaskChain other) { }
        @Override protected void onTick(AltoClef mod) { }
        @Override public float getPriority(AltoClef mod) { return 0; }
        @Override public boolean isActive() { return true; }
        @Override public String getName() { return "test"; }
    }

    private static class ParentTask extends Task {
        Task child;
        ParentTask(Task child) { this.child = child; }
        @Override protected void onStart(AltoClef mod) { }
        @Override protected Task onTick(AltoClef mod) { return child; }
        @Override protected void onStop(AltoClef mod, Task interruptTask) { }
        @Override protected boolean isEqual(Task other) { return this == other; }
        @Override protected String toDebugString() { return "parent"; }
    }

    private static class PlainTask extends Task {
        int ticks;
        @Override protected void onStart(AltoClef mod) { }
        @Override protected Task onTick(AltoClef mod) { ticks++; return null; }
        @Override protected void onStop(AltoClef mod, Task interruptTask) { }
        @Override protected boolean isEqual(Task other) { return this == other; }
        @Override protected String toDebugString() { return "plain"; }
    }

    private static class FailedTask extends PlainTask implements TaskFailure {
        private final String reason;
        private boolean failed = true;
        FailedTask(String reason) { this.reason = reason; }
        @Override public boolean hasFailed() { return failed; }
        @Override public String getFailureReason() { return reason; }
        @Override protected void onResetForNewRun() { failed = false; }
    }

    private static final class DeferredFailureTask extends FailedTask implements ITaskCanForce {
        boolean force = true;
        int ticks;
        DeferredFailureTask() { super("cleanup complete"); }
        @Override protected Task onTick(AltoClef mod) { ticks++; return null; }
        @Override public boolean shouldForce(AltoClef mod, Task interruptingCandidate) { return force; }
    }

    private static final class ForcedTask extends PlainTask implements ITaskCanForce {
        boolean force = true;
        @Override public boolean shouldForce(AltoClef mod, Task interruptingCandidate) { return force; }
    }
}
