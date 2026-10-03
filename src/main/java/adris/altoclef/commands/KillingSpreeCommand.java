package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.tasks.stupid.TerminatorTask;

/** Gears up for PvP, then repeatedly targets the nearest eligible player. */
public final class KillingSpreeCommand extends Command {
    private static final double SCAN_RADIUS = 900;

    public KillingSpreeCommand() {
        super("killing_spree", "Gear up and repeatedly punk the nearest eligible player");
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) {
        mod.runUserTask(new TerminatorTask(mod.getPlayer().blockPosition(), SCAN_RADIUS), this::finish);
    }
}
