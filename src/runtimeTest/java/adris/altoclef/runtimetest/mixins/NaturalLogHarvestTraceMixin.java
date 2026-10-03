package adris.altoclef.runtimetest.mixins;

import adris.altoclef.runtimetest.RuntimeAcceptanceMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Runtime-test-only proof of successful natural log block breaks. */
@Mixin(Block.class)
public abstract class NaturalLogHarvestTraceMixin {
    @Inject(method = "playerDestroy", at = @At("TAIL"))
    private void altoclef$recordConfirmedNaturalLogBreak(Level level, Player player, BlockPos pos,
                                                         BlockState state, BlockEntity blockEntity,
                                                         ItemStack tool, CallbackInfo ci) {
        if (level instanceof ServerLevel serverLevel && player instanceof ServerPlayer serverPlayer) {
            RuntimeAcceptanceMod.recordConfirmedNaturalLogBreak(serverLevel, serverPlayer, pos, state);
            adris.altoclef.runtimetest.LitematicaRecoveryAcceptanceScenario.recordConfirmedSourceBreak(
                    serverLevel, serverPlayer, pos, state);
        }
    }
}
