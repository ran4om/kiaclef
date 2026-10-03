package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.Settings;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.CommandExecutor;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.TaskFinishedEvent;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserTaskChainNaturalFinishTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        adris.altoclef.testing.MinecraftTestBootstrap.initialize();
    }

    @Test
    void stopsCompletedTaskAndPublishesItWhenIdleModeIsDisabled() throws Exception {
        Fixture fixture = new Fixture();
        CleanupTask finished = fixture.startFinishedTask();
        AtomicReference<TaskFinishedEvent> event = new AtomicReference<>();
        var subscription = EventBus.subscribe(TaskFinishedEvent.class, event::set);
        try {
            fixture.chain.onTaskFinish(fixture.mod);

            assertTrue(((CleanupTask) finished).cleanedUp);
            assertFalse(finished.isActive());
            assertSame(finished, event.get().lastTaskRan);
        } finally {
            EventBus.unsubscribe(subscription);
        }
    }

    @Test
    void stopsCompletedTaskBeforeIdleAndPublishesTheCompletedTask() throws Exception {
        Fixture fixture = new Fixture();
        fixture.setIdleCommand("idle");
        fixture.installIdleCommand();

        CleanupTask finished = fixture.startFinishedTask();
        AtomicReference<TaskFinishedEvent> event = new AtomicReference<>();
        var subscription = EventBus.subscribe(TaskFinishedEvent.class, event::set);
        try {
            fixture.chain.onTaskFinish(fixture.mod);

            assertTrue(((CleanupTask) finished).cleanedUp);
            assertFalse(finished.isActive());
            assertSame(finished, event.get().lastTaskRan);
            assertTrue(fixture.idleCommandRan);
        } finally {
            EventBus.unsubscribe(subscription);
            fixture.restoreCommandExecutor();
        }
    }

    @Test
    void terminalFailureStillPublishesTaskFinishedEvent() throws Exception {
        Fixture fixture = new Fixture();
        FailedCleanupTask failed = new FailedCleanupTask();
        fixture.chain.setTask(failed);
        failed.tick(fixture.mod, fixture.chain);
        AtomicReference<TaskFinishedEvent> event = new AtomicReference<>();
        var subscription = EventBus.subscribe(TaskFinishedEvent.class, event::set);
        try {
            fixture.chain.onTaskFinish(fixture.mod);

            assertTrue(((CleanupTask) failed).cleanedUp);
            assertSame(failed, event.get().lastTaskRan);
            assertTrue(event.get().failure != null);
            assertTrue(event.get().failure.reason().equals("test failure"));
            assertFalse(event.get().cancelled);
        } finally {
            EventBus.unsubscribe(subscription);
        }
    }

    @Test
    void explicitCancellationPublishesCancelledWithoutFailure() throws Exception {
        Fixture fixture = new Fixture();
        FailedCleanupTask task = new FailedCleanupTask();
        fixture.chain.setTask(task);
        task.tick(fixture.mod, fixture.chain);
        AtomicReference<TaskFinishedEvent> event = new AtomicReference<>();
        var subscription = EventBus.subscribe(TaskFinishedEvent.class, event::set);
        try {
            fixture.chain.cancel(fixture.mod);

            assertTrue(((CleanupTask) task).cleanedUp);
            assertSame(task, event.get().lastTaskRan);
            assertTrue(event.get().cancelled);
            assertTrue(event.get().failure == null);
        } finally {
            EventBus.unsubscribe(subscription);
        }
    }

    @Test
    void reentrantCompletionCallbackCanScheduleTaskAndKeepItsCallback() throws Exception {
        Fixture fixture = new Fixture();
        fixture.setIdleCommand("idle");
        fixture.installIdleCommand();

        FailedCleanupTask finished = new FailedCleanupTask();
        fixture.chain.setTask(finished);
        finished.tick(fixture.mod, fixture.chain);
        CleanupTask next = new CleanupTask();
        Runnable nextCallback = () -> { };
        AtomicReference<UserTaskChain.CompletionSnapshot> callbackSnapshot = new AtomicReference<>();
        Runnable callback = () -> {
            callbackSnapshot.set(fixture.chain.getLastCompletionSnapshot());
            fixture.chain.setTask(next);
            fixture.setCurrentCallback(nextCallback);
        };
        fixture.setCurrentCallback(callback);
        AtomicReference<TaskFinishedEvent> event = new AtomicReference<>();
        var subscription = EventBus.subscribe(TaskFinishedEvent.class, event::set);

        try {
            fixture.chain.onTaskFinish(fixture.mod);

            assertTrue(((CleanupTask) finished).cleanedUp);
            assertSame(next, fixture.chain.getCurrentTask());
            assertSame(nextCallback, fixture.currentCallback());
            assertSame(finished, callbackSnapshot.get().task());
            assertEquals("test failure", callbackSnapshot.get().failure().reason());
            assertSame(finished, event.get().lastTaskRan);
            assertEquals("test failure", event.get().failure.reason());
        } finally {
            EventBus.unsubscribe(subscription);
            fixture.restoreCommandExecutor();
        }
    }

    private static final class Fixture {
        private final Settings settings = new Settings();
        private final StubAltoClef mod = new StubAltoClef(settings);
        private final TaskRunner runner = new TaskRunner(mod);
        private final UserTaskChain chain = new TestUserTaskChain(runner);
        private boolean idleCommandRan;
        private Object previousCommandExecutor;
        private Field commandExecutorField;

        private Fixture() {
            mod.runner = runner;
        }

        private CleanupTask startFinishedTask() {
            CleanupTask task = new CleanupTask();
            chain.setTask(task);
            task.tick(mod, chain);
            return task;
        }

        private void installIdleCommand() throws Exception {
            CommandExecutor executor = new CommandExecutor(mod);
            executor.registerNewCommand(new Command("idle", "test idle command") {
                @Override
                protected void call(AltoClef mod, ArgParser parser) {
                    idleCommandRan = true;
                }
            });
            commandExecutorField = AltoClef.class.getDeclaredField("_commandExecutor");
            commandExecutorField.setAccessible(true);
            previousCommandExecutor = commandExecutorField.get(null);
            commandExecutorField.set(null, executor);
        }

        private void restoreCommandExecutor() throws Exception {
            if (commandExecutorField != null) {
                commandExecutorField.set(null, previousCommandExecutor);
            }
        }

        private void setIdleCommand(String command) throws Exception {
            Field field = Settings.class.getDeclaredField("idleCommand");
            field.setAccessible(true);
            field.set(settings, command);
        }

        private void setCurrentCallback(Runnable callback) {
            try {
                Field field = UserTaskChain.class.getDeclaredField("_currentOnFinish");
                field.setAccessible(true);
                field.set(chain, callback);
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError(exception);
            }
        }

        private Runnable currentCallback() throws Exception {
            Field field = UserTaskChain.class.getDeclaredField("_currentOnFinish");
            field.setAccessible(true);
            return (Runnable) field.get(chain);
        }
    }

    private static final class TestUserTaskChain extends UserTaskChain {
        private TestUserTaskChain(TaskRunner runner) {
            super(runner);
        }

        @Override
        protected void clearAllInputKeys(AltoClef mod) {
        }
    }

    private static final class StubAltoClef extends AltoClef {
        private final Settings settings;
        private TaskRunner runner;

        private StubAltoClef(Settings settings) {
            this.settings = settings;
        }

        @Override
        public Settings getModSettings() {
            return settings;
        }

        @Override
        public TaskRunner getTaskRunner() {
            return runner;
        }
    }

    private static class CleanupTask extends Task {
        private boolean cleanedUp;

        @Override
        protected String toDebugString() {
            return "Cleanup test task";
        }

        @Override
        public boolean isFinished(AltoClef mod) {
            return true;
        }

        @Override
        protected void onStart(AltoClef mod) {
        }

        @Override
        protected Task onTick(AltoClef mod) {
            return null;
        }

        @Override
        protected void onStop(AltoClef mod, Task interruptTask) {
            cleanedUp = true;
        }

        @Override
        protected boolean isEqual(Task other) {
            return this == other;
        }
    }

    private static final class FailedCleanupTask extends CleanupTask implements adris.altoclef.tasksystem.TaskFailure {
        @Override public boolean hasFailed() { return true; }
        @Override public String getFailureReason() { return "test failure"; }
    }
}
