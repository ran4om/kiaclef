package adris.altoclef.runtimetest.mixins;

import adris.altoclef.AltoClef;
import adris.altoclef.runtimetest.MobDefenseCombatCapacityEventRecorder;
import adris.altoclef.runtimetest.LitematicaRecoveryEventRecorder;
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.tasksystem.TaskRunner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures TaskRunner's real priority result without a second call to getPriority. */
@Mixin(TaskRunner.class)
public abstract class TaskRunnerPriorityTraceMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void altoclef$beginPriorityCapture(CallbackInfo ci) {
        LitematicaRecoveryEventRecorder.beginTaskRunnerTick();
        MobDefenseCombatCapacityEventRecorder.beginTaskRunnerTick();
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Ladris/altoclef/tasksystem/TaskChain;getPriority(Ladris/altoclef/AltoClef;)F"))
    private float altoclef$recordReturnedPriority(TaskChain chain, AltoClef mod) {
        float priority = chain.getPriority(mod);
        LitematicaRecoveryEventRecorder.recordChainPriority(chain, priority);
        MobDefenseCombatCapacityEventRecorder.recordChainPriority(chain, priority, mod);
        return priority;
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Ladris/altoclef/tasksystem/TaskChain;tick(Ladris/altoclef/AltoClef;)V"))
    private void altoclef$captureSelectionBeforeTaskTick(TaskChain selected, AltoClef mod) {
        TaskRunner runner = (TaskRunner) (Object) this;
        if (!MobDefenseCombatCapacityEventRecorder.recordSelectionBeforeTaskTick(runner, selected)) {
            selected.tick(mod);
        }
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void altoclef$recordSelectedChain(CallbackInfo ci) {
        TaskRunner runner = (TaskRunner) (Object) this;
        MobDefenseCombatCapacityEventRecorder.recordTaskRunnerReturn(runner);
        MobDefenseCombatCapacityEventRecorder.endTaskRunnerTick();
        LitematicaRecoveryEventRecorder.recordSelectedTaskChain(runner);
        LitematicaRecoveryEventRecorder.endTaskRunnerTick();
    }
}
