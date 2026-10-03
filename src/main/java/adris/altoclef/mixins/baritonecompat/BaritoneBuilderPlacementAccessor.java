package adris.altoclef.mixins.baritonecompat;

import baritone.api.utils.Rotation;
import baritone.process.BuilderProcess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only access to the selected placement candidate for opt-in diagnostics. */
@Mixin(BuilderProcess.Placement.class)
public interface BaritoneBuilderPlacementAccessor {
    @Accessor("placeAgainst")
    BlockPos altoclef$getPlaceAgainst();

    @Accessor("side")
    Direction altoclef$getSide();

    @Accessor("rot")
    Rotation altoclef$getRotation();
}
