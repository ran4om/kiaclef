package adris.altoclef.mixins.baritonecompat;

import adris.altoclef.baritone.AltoClefSettings;
import baritone.api.event.events.TickEvent;
import baritone.api.utils.IPlayerContext;
import baritone.behavior.InventoryBehavior;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Predicate;

@Mixin(InventoryBehavior.class)
abstract class BaritoneInventoryBehaviorMixin {

    @Inject(method = "onTick", at = @At("HEAD"), cancellable = true)
    private void altoclef$pauseInventoryManagement(TickEvent event, CallbackInfo ci) {
        if (AltoClefSettings.getInstance().isInteractionPaused()) {
            ci.cancel();
        }
    }

    @Inject(method = "attemptToPutOnHotbar", at = @At("HEAD"), cancellable = true)
    private void altoclef$pauseHotbarMoves(int sourceSlot, Predicate<Integer> disallowedHotbar,
                                                        CallbackInfoReturnable<Boolean> cir) {
        AltoClefSettings settings = AltoClefSettings.getInstance();
        // Protection prevents consumption as scaffolding; tools still need to move
        // onto the hotbar so Baritone can select the correct mining tool.
        if (settings.isInteractionPaused()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "selectThrowawayForLocation", at = @At("HEAD"), cancellable = true)
    private void altoclef$protectPlacementTarget(boolean select, int x, int y, int z,
                                                 CallbackInfoReturnable<Boolean> cir) {
        AltoClefSettings settings = AltoClefSettings.getInstance();
        if (settings.isInteractionPaused() || settings.shouldAvoidPlacingAt(x, y, z)) {
            cir.setReturnValue(false);
        }
    }

    @ModifyArg(method = "hasGenericThrowaway",
            at = @At(value = "INVOKE", target = "Lbaritone/behavior/InventoryBehavior;throwaway(ZLjava/util/function/Predicate;)Z"),
            index = 1)
    private Predicate<? super ItemStack> altoclef$filterProtectedGenericThrowaways(Predicate<? super ItemStack> requested) {
        return stack -> !AltoClefSettings.getInstance().isItemProtected(stack.getItem()) && requested.test(stack);
    }

    // The bundled Baritone 1.19.0 bytecode has three throwaway calls here:
    // ordinals 0 and 1 test the schematic's exact requested state; ordinal 2
    // tests generic acceptableThrowawayItems. Keep explicit schematic blocks
    // placeable even when the same item is protected from generic consumption.
    @ModifyArg(method = "selectThrowawayForLocation",
            at = @At(value = "INVOKE", target = "Lbaritone/behavior/InventoryBehavior;throwaway(ZLjava/util/function/Predicate;)Z", ordinal = 2),
            index = 1)
    private Predicate<? super ItemStack> altoclef$filterProtectedGenericFallback(Predicate<? super ItemStack> requested) {
        return stack -> !AltoClefSettings.getInstance().isItemProtected(stack.getItem()) && requested.test(stack);
    }
}
