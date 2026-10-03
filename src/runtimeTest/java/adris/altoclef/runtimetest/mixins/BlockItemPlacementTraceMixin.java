package adris.altoclef.runtimetest.mixins;

import adris.altoclef.runtimetest.LitematicaRecoveryAcceptanceScenario;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Records successful server-side block placements for the natural Litematica verifier. */
@Mixin(BlockItem.class)
public abstract class BlockItemPlacementTraceMixin {
    @Inject(method = "placeBlock", at = @At("RETURN"))
    private void altoclef$recordSuccessfulServerPlacement(BlockPlaceContext context, BlockState state,
                                                           CallbackInfoReturnable<Boolean> cir) {
        if (!Boolean.TRUE.equals(cir.getReturnValue())
                || !(context.getLevel() instanceof ServerLevel)
                || !(context.getPlayer() instanceof ServerPlayer player)) return;
        LitematicaRecoveryAcceptanceScenario.onSuccessfulAuthoredBlockPlacement(
                player, context.getClickedPos().immutable(), state);
    }
}
