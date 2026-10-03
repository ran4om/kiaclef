package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.commandsystem.Arg;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.CommandException;
import adris.altoclef.tasks.construction.BuildSchematicTask;
import adris.altoclef.util.schematic.SchematicLoader;
import adris.altoclef.util.schematic.SchematicSnapshot;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;

/** User command for building a schematic file or the first active Litematica placement. */
public final class BuildCommand extends Command {
    public BuildCommand() throws CommandException {
        super("build", "Gather materials and build a .litematic, .schem, or .schematic file (or placement)",
                new Arg<>(String.class, "file|placement"));
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        String source = parser.get(String.class).trim();
        try {
            SchematicSnapshot snapshot;
            if (source.equalsIgnoreCase("placement") || source.equalsIgnoreCase("litematica")) {
                snapshot = SchematicLoader.loadActiveLitematica(0);
            } else {
                Path requested = Path.of(source);
                if (!requested.isAbsolute()) {
                    requested = Minecraft.getInstance().gameDirectory.toPath()
                            .resolve("schematics").resolve(requested);
                }
                snapshot = SchematicLoader.load(requested);
                snapshot = new SchematicSnapshot(snapshot.name(), snapshot.schematic(), mod.getPlayer().blockPosition());
            }
            mod.runUserTask(new BuildSchematicTask(snapshot), this::finish);
        } catch (Exception e) {
            Debug.logError("Could not load schematic: " + e.getMessage());
            finish();
        }
    }
}
