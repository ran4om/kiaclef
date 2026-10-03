package adris.altoclef.mixins.baritonecompat;

import adris.altoclef.baritone.AltoClefSettings;
import baritone.api.IBaritone;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import baritone.pathing.movement.MovementHelper.PlaceResult;
import baritone.utils.BlockStateInterface;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MovementHelper.class)
interface BaritoneMovementHelperMixin {
    @Inject(method = "avoidBreaking", at = @At("HEAD"), cancellable = true)
    private static void altoclef$avoidConfiguredBreaks(BlockStateInterface bsi, int x, int y, int z,
                                                        BlockState state, CallbackInfoReturnable<Boolean> cir) {
        if (AltoClefSettings.getInstance().shouldAvoidBreaking(x, y, z)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "canWalkThrough(Lbaritone/pathing/movement/CalculationContext;IIILnet/minecraft/world/level/block/state/BlockState;)Z",
            at = @At("HEAD"), cancellable = true)
    private static void altoclef$avoidConfiguredWalkThrough(CalculationContext context, int x, int y, int z,
                                                            BlockState state, CallbackInfoReturnable<Boolean> cir) {
        AltoClefSettings settings = AltoClefSettings.getInstance();
        if (settings.shouldAvoidWalkThroughForce(x, y, z)) {
            cir.setReturnValue(false);
        } else if (settings.canWalkThroughEndPortal(state, x, y, z)) {
            cir.setReturnValue(true);
        } else if (settings.canSwimThroughLava() && state.is(Blocks.LAVA)
                && context.get(x, y + 1, z).getFluidState().isEmpty()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "canWalkThrough(Lbaritone/utils/BlockStateInterface;IIILnet/minecraft/world/level/block/state/BlockState;)Z",
            at = @At("HEAD"), cancellable = true)
    private static void altoclef$avoidConfiguredWalkThrough(BlockStateInterface bsi, int x, int y, int z,
                                                            BlockState state, CallbackInfoReturnable<Boolean> cir) {
        AltoClefSettings settings = AltoClefSettings.getInstance();
        if (settings.shouldAvoidWalkThroughForce(x, y, z)) {
            cir.setReturnValue(false);
        } else if (settings.canWalkThroughEndPortal(state, x, y, z)) {
            cir.setReturnValue(true);
        } else if (settings.canSwimThroughLava() && state.is(Blocks.LAVA)
                && bsi.get0(x, y + 1, z).getFluidState().isEmpty()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "canWalkOn(Lbaritone/pathing/movement/CalculationContext;IIILnet/minecraft/world/level/block/state/BlockState;)Z",
            at = @At("HEAD"), cancellable = true)
    private static void altoclef$applyForcedWalkingSurface(CalculationContext context, int x, int y, int z,
                                                           BlockState state, CallbackInfoReturnable<Boolean> cir) {
        AltoClefSettings settings = AltoClefSettings.getInstance();
        if (settings.canWalkOnForce(x, y, z)
                || settings.shouldTreatSoulSandAsOrdinaryBlock() && state.is(Blocks.SOUL_SAND)) {
            cir.setReturnValue(true);
        } else if (settings.shouldAvoidWalkThroughForce(x, y + 1, z)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "canWalkOn(Lbaritone/utils/BlockStateInterface;IIILnet/minecraft/world/level/block/state/BlockState;)Z",
            at = @At("HEAD"), cancellable = true)
    private static void altoclef$applyForcedWalkingSurface(BlockStateInterface bsi, int x, int y, int z,
                                                           BlockState state, CallbackInfoReturnable<Boolean> cir) {
        AltoClefSettings settings = AltoClefSettings.getInstance();
        if (settings.canWalkOnForce(x, y, z)
                || settings.shouldTreatSoulSandAsOrdinaryBlock() && state.is(Blocks.SOUL_SAND)) {
            cir.setReturnValue(true);
        } else if (settings.shouldAvoidWalkThroughForce(x, y + 1, z)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "canPlaceAgainst(Lbaritone/utils/BlockStateInterface;IIILnet/minecraft/world/level/block/state/BlockState;)Z",
            at = @At("HEAD"), cancellable = true)
    private static void altoclef$avoidConfiguredPlacement(BlockStateInterface bsi, int x, int y, int z,
                                                           BlockState state, CallbackInfoReturnable<Boolean> cir) {
        if (AltoClefSettings.getInstance().shouldAvoidPlacingAt(x, y, z)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "attemptToPlaceABlock", at = @At("HEAD"), cancellable = true)
    private static void altoclef$stopForbiddenPlacement(MovementState movement, IBaritone baritone,
                                                        BlockPos target, boolean preferDown, boolean wouldSneak,
                                                        CallbackInfoReturnable<PlaceResult> cir) {
        AltoClefSettings settings = AltoClefSettings.getInstance();
        if (settings.isInteractionPaused() || settings.shouldAvoidPlacingAt(target)) {
            cir.setReturnValue(PlaceResult.NO_OPTION);
        }
    }
}
