package adris.altoclef.mixins.baritonecompat;

import adris.altoclef.baritone.AltoClefSettings;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.process.MineProcess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MineProcess.class)
abstract class BaritoneMineProcessMixin {
    @Inject(method = "onTick", at = @At("HEAD"), cancellable = true)
    private void altoclef$pauseMining(boolean calcFailed, boolean isSafeToCancel,
                                      CallbackInfoReturnable<PathingCommand> cir) {
        if (AltoClefSettings.getInstance().isInteractionPaused()) {
            cir.setReturnValue(new PathingCommand(null, PathingCommandType.REQUEST_PAUSE));
        }
    }
}
