package adris.altoclef.runtimetest.mixins;

import adris.altoclef.runtimetest.LitematicaRecoveryAcceptanceScenario;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records successful server menu initialization during natural Litematica recovery acceptance. */
@Mixin(ServerPlayer.class)
public abstract class ServerMenuOpenTraceMixin {
    @Inject(method = "initMenu", at = @At("HEAD"))
    private void altoclef$recordInitializedServerMenu(AbstractContainerMenu menu, CallbackInfo ci) {
        LitematicaRecoveryAcceptanceScenario.recordServerMenuOpen(
                (ServerPlayer) (Object) this, menu);
    }
}
