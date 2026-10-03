package adris.altoclef.mixins.baritonecompat;

import adris.altoclef.baritone.AltoClefSettings;
import baritone.utils.ToolSet;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ToolSet.class)
abstract class BaritoneToolSetMixin {
    @Inject(method = "calculateSpeedVsBlock", at = @At("HEAD"), cancellable = true)
    private static void altoclef$honorForcedTool(ItemStack item, BlockState state,
                                                 CallbackInfoReturnable<Double> cir) {
        if (AltoClefSettings.getInstance().shouldAvoidUseTool(state, item)) {
            cir.setReturnValue(0.0);
            return;
        }
        if (AltoClefSettings.getInstance().shouldForceUseTool(state, item)) {
            cir.setReturnValue(Double.POSITIVE_INFINITY);
        }
    }
}
