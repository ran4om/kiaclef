package adris.altoclef.runtimetest.mixins;

import adris.altoclef.AltoClef;
import adris.altoclef.runtimetest.LitematicaRecoveryAcceptanceScenario;
import adris.altoclef.runtimetest.LitematicaRecoveryEventRecorder;
import adris.altoclef.tasks.construction.BuildSchematicTask;
import adris.altoclef.tasksystem.Task;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/** Runtime-test-only observations of the schematic task's production lifecycle and batch planner. */
@Mixin(BuildSchematicTask.class)
public abstract class BuildSchematicLifecycleTraceMixin {
    @Inject(method = "onStart", at = @At("HEAD"))
    private void altoclef$recordBuildStart(AltoClef mod, CallbackInfo ci) {
        BuildSchematicTask task = (BuildSchematicTask) (Object) this;
        LitematicaRecoveryEventRecorder.recordBuildStart(task);
        LitematicaRecoveryAcceptanceScenario.onBuildStarting(task);
    }

    @Inject(method = "onStart", at = @At("RETURN"))
    private void altoclef$recordBuildStartResult(AltoClef mod, CallbackInfo ci) {
        LitematicaRecoveryAcceptanceScenario.onBuildStarted((BuildSchematicTask) (Object) this);
    }

    @Inject(method = "onStop", at = @At("HEAD"))
    private void altoclef$recordBuildStop(AltoClef mod, Task interruptTask, CallbackInfo ci) {
        BuildSchematicTask task = (BuildSchematicTask) (Object) this;
        boolean currentUserTask = mod.getUserTaskChain().getCurrentTask() == task;
        LitematicaRecoveryEventRecorder.recordBuildStop(task, interruptTask, currentUserTask);
    }

    @Inject(method = "onStop", at = @At("RETURN"))
    private void altoclef$recordBuildStopResult(AltoClef mod, Task interruptTask, CallbackInfo ci) {
        LitematicaRecoveryAcceptanceScenario.onBuildStopped((BuildSchematicTask) (Object) this);
    }

    @Inject(method = "prepareBatch", at = @At("HEAD"))
    private void altoclef$recordPrepareBatchStart(AltoClef mod, CallbackInfo ci) {
        LitematicaRecoveryEventRecorder.recordPrepareBatch(
                (BuildSchematicTask) (Object) this, true);
    }

    @Inject(method = "prepareBatch", at = @At("RETURN"))
    private void altoclef$recordPrepareBatchEnd(AltoClef mod, CallbackInfo ci) {
        LitematicaRecoveryEventRecorder.recordPrepareBatch(
                (BuildSchematicTask) (Object) this, false);
    }

    @Inject(method = "getRemainingMaterials", at = @At("RETURN"))
    private void altoclef$recordLiveMaterialRecount(
            AltoClef mod, CallbackInfoReturnable<Map<net.minecraft.world.item.Item, Integer>> cir) {
        BuildSchematicTask task = (BuildSchematicTask) (Object) this;
        LitematicaRecoveryEventRecorder.recordRemainingWorldMaterials(task, cir.getReturnValue());
        LitematicaRecoveryAcceptanceScenario.onLiveMaterialRecount(task);
    }

    @Inject(method = "fitCurrentInventory", at = @At("RETURN"))
    private void altoclef$recordInventoryMaterialBudget(
            AltoClef mod, Map<net.minecraft.world.item.Item, Integer> remaining,
            CallbackInfoReturnable<Map<net.minecraft.world.item.Item, Integer>> cir) {
        BuildSchematicTask task = (BuildSchematicTask) (Object) this;
        LitematicaRecoveryEventRecorder.recordInventoryMaterialBudget(task, cir.getReturnValue());
        LitematicaRecoveryAcceptanceScenario.onInventoryAwareBatchBudget(task);
    }
}
