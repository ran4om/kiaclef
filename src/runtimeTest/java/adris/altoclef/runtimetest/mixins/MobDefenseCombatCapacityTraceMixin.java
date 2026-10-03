package adris.altoclef.runtimetest.mixins;

import adris.altoclef.runtimetest.MobDefenseCombatCapacityEventRecorder;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Records the exact sword and score passed through MobDefense's live capacity branch. */
@Mixin(targets = "adris.altoclef.chains.MobDefenseChain")
public abstract class MobDefenseCombatCapacityTraceMixin {
    @Inject(method = "combatCapacityDamage(Lnet/minecraft/world/item/Item;)F", at = @At("RETURN"))
    private static void altoclef$recordCombatCapacityDecision(Item sword,
                                                               CallbackInfoReturnable<Float> cir) {
        MobDefenseCombatCapacityEventRecorder.recordCombatCapacityDamage(sword, cir.getReturnValue());
    }
}
