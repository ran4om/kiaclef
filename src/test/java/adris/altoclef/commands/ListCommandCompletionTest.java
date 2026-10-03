package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.Settings;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.CommandException;
import adris.altoclef.commandsystem.CommandExecutor;
import adris.altoclef.testing.MinecraftTestBootstrap;
import adris.altoclef.ui.MessagePriority;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ListCommandCompletionTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    void listCompletesBeforeTheNextSemicolonCommand() throws Exception {
        AltoClef mod = new AltoClef() {
            @Override
            public Settings getModSettings() {
                return new Settings();
            }

            @Override
            public void log(String message, MessagePriority priority) {
                // The test only verifies the command completion callback.
            }
        };
        CommandExecutor executor = new CommandExecutor(mod);
        AtomicBoolean followingCommandRan = new AtomicBoolean();
        AtomicBoolean sequenceFinished = new AtomicBoolean();
        executor.registerNewCommand(new ListCommand(), new Command("after_list", "") {
            @Override
            protected void call(AltoClef ignored, ArgParser parser) {
                followingCommandRan.set(true);
                finish();
            }
        });

        executor.execute("@list;after_list", () -> sequenceFinished.set(true), exception -> {
            throw new AssertionError(exception);
        });

        assertTrue(followingCommandRan.get());
        assertTrue(sequenceFinished.get());
    }
}
