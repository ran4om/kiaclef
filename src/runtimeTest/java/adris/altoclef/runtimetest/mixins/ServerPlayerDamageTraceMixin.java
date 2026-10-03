package adris.altoclef.runtimetest.mixins;

import adris.altoclef.runtimetest.LitematicaRecoveryAcceptanceScenario;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayDeque;

/** Runtime-test-only proof of accepted server-side damage to the recovery player. */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerDamageTraceMixin {
    @Unique
    private static final ThreadLocal<ArrayDeque<Float>> ALTOCLEF_HEALTH_BEFORE_HURT =
            ThreadLocal.withInitial(ArrayDeque::new);

    @Inject(method = "hurtServer", at = @At("HEAD"))
    private void altoclef$captureHealthBeforeHurt(ServerLevel level, DamageSource source, float amount,
                                                  CallbackInfoReturnable<Boolean> cir) {
        ALTOCLEF_HEALTH_BEFORE_HURT.get().push(((ServerPlayer) (Object) this).getHealth());
    }

    @Inject(method = "hurtServer", at = @At("RETURN"))
    private void altoclef$recordAcceptedHurt(ServerLevel level, DamageSource source, float amount,
                                              CallbackInfoReturnable<Boolean> cir) {
        ArrayDeque<Float> samples = ALTOCLEF_HEALTH_BEFORE_HURT.get();
        if (samples.isEmpty()) return;
        float before = samples.pop();
        ServerPlayer player = (ServerPlayer) (Object) this;
        float after = player.getHealth();
        if (Boolean.TRUE.equals(cir.getReturnValue()) || after < before) {
            LitematicaRecoveryAcceptanceScenario.recordServerHealthDamage(player, String.valueOf(source),
                    before, after, level.getServer().getTickCount());
        }
        if (samples.isEmpty()) ALTOCLEF_HEALTH_BEFORE_HURT.remove();
    }
}
