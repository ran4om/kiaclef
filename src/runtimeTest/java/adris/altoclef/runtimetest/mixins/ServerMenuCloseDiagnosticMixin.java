package adris.altoclef.runtimetest.mixins;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Read-only diagnostics, only loaded by the opt-in acceptance harness. */
@Mixin(ServerPlayer.class)
public abstract class ServerMenuCloseDiagnosticMixin {
    @Unique private int altoclef$closeLines;

    @Inject(method = "closeContainer", at = @At("HEAD"))
    private void altoclef$observeClose(CallbackInfo ci) {
        if (!Boolean.getBoolean("altoclef.builderPlacementDiagnostics") || altoclef$closeLines++ >= 100) return;
        ServerPlayer player = (ServerPlayer) (Object) this;
        AbstractContainerMenu menu = player.containerMenu;
        String block = "not-block-backed";
        if (!menu.slots.isEmpty() && menu.slots.get(0).container instanceof BlockEntity entity) {
            block = "pos=" + entity.getBlockPos() + ",state=" + player.level().getBlockState(entity.getBlockPos())
                    + ",sameEntity=" + (player.level().getBlockEntity(entity.getBlockPos()) == entity)
                    + ",distanceSquared=" + player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(entity.getBlockPos()));
        }
        System.out.println("[SERVER-MENU-CLOSE-DIAG] menu=" + menu.getClass().getSimpleName()
                + ",id=" + menu.containerId + ",valid=" + menu.stillValid(player)
                + ",player=" + player.position() + "," + block
                + ",callers=" + java.util.Arrays.toString(Thread.currentThread().getStackTrace()));
    }
}
