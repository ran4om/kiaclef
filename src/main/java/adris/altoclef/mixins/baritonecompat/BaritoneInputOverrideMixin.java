package adris.altoclef.mixins.baritonecompat;

import adris.altoclef.baritone.AltoClefSettings;
import baritone.api.event.events.TickEvent;
import baritone.api.utils.input.Input;
import baritone.utils.InputOverrideHandler;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InputOverrideHandler.class)
abstract class BaritoneInputOverrideMixin {
    @Shadow public abstract void setInputForceState(Input input, boolean forced);

    @Inject(method = "onTick", at = @At("HEAD"))
    private void altoclef$pauseBaritoneInteractions(TickEvent event, CallbackInfo ci) {
        if (event.getType() != TickEvent.Type.OUT && AltoClefSettings.getInstance().isInteractionPaused()) {
            setInputForceState(Input.CLICK_LEFT, false);
            setInputForceState(Input.CLICK_RIGHT, false);
        }
    }
}
