package adris.altoclef.util.schematic;

import baritone.api.schematic.IStaticSchematic;
import net.minecraft.core.BlockPos;

import java.util.Objects;

/** A parsed schematic and the world-space position Baritone should use as its origin. */
public record SchematicSnapshot(String name, IStaticSchematic schematic, BlockPos origin) {
    public SchematicSnapshot {
        name = Objects.requireNonNull(name, "name");
        schematic = Objects.requireNonNull(schematic, "schematic");
        origin = Objects.requireNonNull(origin, "origin");
    }
}
