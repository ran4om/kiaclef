package adris.altoclef.runtimetest.mixins;

import adris.altoclef.tasks.movement.RunAwayFromHostilesTask;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads the branch-specific RunAwayFromHostilesTask constructor state for acceptance evidence. */
@Mixin(RunAwayFromHostilesTask.class)
public interface RunAwayFromHostilesTaskAccessor {
    @Accessor("_distanceToRun")
    double altoclef$getDistanceToRun();

    @Accessor("_includeSkeletons")
    boolean altoclef$getIncludeSkeletons();
}
