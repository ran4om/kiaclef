package adris.altoclef.runtimetest.mixins;

import adris.altoclef.AltoClef;
import adris.altoclef.runtimetest.LitematicaRecoveryEventRecorder;
import adris.altoclef.tasks.movement.DodgeProjectilesTask;
import adris.altoclef.tasksystem.Task;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Records that the actual DodgeProjectilesTask.onTick body returned. */
@Mixin(DodgeProjectilesTask.class)
public abstract class DodgeProjectilesTickTraceMixin {
    @Inject(method = "onTick", at = @At("RETURN"))
    private void altoclef$recordCompletedDodgeTick(AltoClef mod, CallbackInfoReturnable<Task> cir) {
        LitematicaRecoveryEventRecorder.recordDodgeTick(
                (DodgeProjectilesTask) (Object) this, cir.getReturnValue());
    }
}
