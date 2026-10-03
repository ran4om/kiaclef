package adris.altoclef.runtimetest.mixins;

import adris.altoclef.chains.SingleTaskChain;
import adris.altoclef.runtimetest.MobDefenseCombatCapacityEventRecorder;
import adris.altoclef.tasksystem.Task;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures MobDefense's assigned root at the exact setter boundary without changing production code. */
@Mixin(SingleTaskChain.class)
public abstract class SingleTaskChainAssignmentTraceMixin {
    @Inject(method = "setTask", at = @At("HEAD"))
    private void altoclef$recordMobDefenseTaskAssignment(Task task, CallbackInfo ci) {
        MobDefenseCombatCapacityEventRecorder.recordMobDefenseTaskAssignment(
                (SingleTaskChain) (Object) this, task);
    }
}
