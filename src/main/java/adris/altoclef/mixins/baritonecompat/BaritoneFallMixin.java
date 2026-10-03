package adris.altoclef.mixins.baritonecompat;

import adris.altoclef.baritone.AltoClefSettings;
import baritone.pathing.movement.movements.MovementFall;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MovementFall.class)
abstract class BaritoneFallMixin {
    @Inject(method = "willPlaceBucket", at = @At("HEAD"), cancellable = true)
    private void altoclef$fallWithoutPlacingBucket(CallbackInfoReturnable<Boolean> cir) {
        if (AltoClefSettings.getInstance().shouldNotPlaceBucketButStillFall()) {
            cir.setReturnValue(false);
        }
    }
}
