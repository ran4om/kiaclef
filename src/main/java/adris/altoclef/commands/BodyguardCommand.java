package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.Arg;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.CommandException;
import adris.altoclef.tasks.entity.BodyguardTask;

public final class BodyguardCommand extends Command {
    public BodyguardCommand() throws CommandException {
        super("bodyguard", "Follow a player and protect them from nearby mobs and players",
                new Arg(String.class, "playerName"));
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        String playerName = parser.get(String.class).trim();
        if (playerName.isEmpty()) {
            throw new CommandException("Provide a player name to bodyguard.");
        }
        mod.runUserTask(new BodyguardTask(playerName), this::finish);
    }
}
