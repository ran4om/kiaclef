package adris.altoclef.mixins;

// InteractionResult MultiPlayerGameMode.interactBlock(LocalPlayer player, ClientLevel world, InteractionHand hand, BlockHitResult hitResult);

import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.BlockInteractEvent;
import adris.altoclef.eventbus.events.NonBlockInteractEvent;
import adris.altoclef.util.helpers.BaritoneBuilderStateCompatibility;
import adris.altoclef.util.helpers.BaritoneCrafterPlacementIntent;
import baritone.process.BuilderProcess;
import net.minecraft.client.multiplayer.prediction.PredictiveAction;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public final class ClientInteractWithBlockMixin {
    @Shadow
    private void startPrediction(ClientLevel level, PredictiveAction action) {
        throw new AssertionError();
    }

    @Unique
    private CrafterPlacementTransaction altoclef$crafterTransaction;

    @Unique
    private record CrafterPlacementTransaction(LocalPlayer player, float oldYaw, float oldPitch,
                                               float requestedYaw, float requestedPitch) {}

    @Inject(
            method = "useItemOn",
            at = @At("HEAD"),
            cancellable = true
    )
    private void onClientBlockInteract(LocalPlayer player, InteractionHand hand, BlockHitResult hitResult, CallbackInfoReturnable<InteractionResult> ci) {
        altoclef$crafterTransaction = null;
        if (hitResult != null) {
            BaritoneCrafterPlacementIntent.Intent intent = BaritoneCrafterPlacementIntent.consume(player);
            if (intent != null && intent.owner() instanceof BuilderProcess builder) {
                if (hand != InteractionHand.MAIN_HAND
                        || !(player.getMainHandItem().getItem() instanceof BlockItem blockItem)
                        || !(blockItem.getBlock() instanceof CrafterBlock crafter)
                        || Minecraft.getInstance().level == null) {
                    ci.setReturnValue(InteractionResult.FAIL);
                    return;
                }
                float oldYaw = player.getYRot();
                float oldPitch = player.getXRot();
                BlockPlaceContext placement = null;
                BlockState predicted = null;
                BlockState wanted = null;
                BlockPos target = null;
                boolean allowed = false;
                try {
                    player.setYRot(intent.yaw());
                    player.setXRot(intent.pitch());
                    placement = new BlockPlaceContext(player, hand, player.getMainHandItem(), hitResult);
                    target = placement.getClickedPos().immutable();
                    predicted = crafter.getStateForPlacement(placement);
                    wanted = builder.placeAt(target.getX(), target.getY(), target.getZ(),
                            Minecraft.getInstance().level.getBlockState(target));
                    allowed = BaritoneCrafterPlacementIntent.matchesHit(
                                    intent, hitResult.getBlockPos(), hitResult.getDirection())
                            && BaritoneCrafterPlacementIntent.matchesTarget(intent, target)
                            && placement.canPlace()
                            && BaritoneBuilderStateCompatibility.crafterPlacementMatches(predicted, wanted);
                } finally {
                    player.setYRot(oldYaw);
                    player.setXRot(oldPitch);
                }
                if (placement != null) {
                    BaritoneBuilderStateCompatibility.diagnoseCrafterClick(placement, hitResult,
                            predicted, wanted, intent.yaw(), intent.pitch(), allowed);
                }
                if (!allowed) {
                    ci.setReturnValue(InteractionResult.FAIL);
                    return;
                }
                altoclef$crafterTransaction = new CrafterPlacementTransaction(
                        player, oldYaw, oldPitch, intent.yaw(), intent.pitch());
            }
        }
    }

    @Inject(method = "useItemOn", at = @At("RETURN"))
    private void altoclef$publishSuccessfulBlockInteract(LocalPlayer player, InteractionHand hand,
                                                         BlockHitResult hitResult,
                                                         CallbackInfoReturnable<InteractionResult> ci) {
        InteractionResult result = ci.getReturnValue();
        if (hitResult == null || result == null || !result.consumesAction()) return;
        ClientLevel world = Minecraft.getInstance().level;
        if (world != null) EventBus.publish(new BlockInteractEvent(hitResult, world));
    }

    @Inject(method = "interact", at = @At("HEAD"))
    private void altoclef$clearPendingBlockInteractionForEntityUse(Player player, Entity entity,
                                                                   EntityHitResult hitResult,
                                                                   InteractionHand hand,
                                                                   CallbackInfoReturnable<InteractionResult> ci) {
        if (player == Minecraft.getInstance().player) EventBus.publish(new NonBlockInteractEvent());
    }

    @Inject(method = "useItem", at = @At("HEAD"))
    private void altoclef$clearPendingBlockInteractionForAirUse(Player player, InteractionHand hand,
                                                                CallbackInfoReturnable<InteractionResult> ci) {
        if (player == Minecraft.getInstance().player) EventBus.publish(new NonBlockInteractEvent());
    }

    @Redirect(method = "useItemOn",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;startPrediction(Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/minecraft/client/multiplayer/prediction/PredictiveAction;)V"))
    private void altoclef$sendBuilderCrafterRotationBeforeUsePacket(
            MultiPlayerGameMode gameMode, ClientLevel level, PredictiveAction action) {
        CrafterPlacementTransaction transaction = altoclef$crafterTransaction;
        altoclef$crafterTransaction = null;
        if (transaction == null) {
            startPrediction(level, action);
            return;
        }

        LocalPlayer player = transaction.player();
        player.setYRot(transaction.requestedYaw());
        player.setXRot(transaction.requestedPitch());
        try {
            player.connection.send(new ServerboundMovePlayerPacket.Rot(
                    transaction.requestedYaw(), transaction.requestedPitch(),
                    player.onGround(), player.horizontalCollision));
            startPrediction(level, action);
        } finally {
            player.setYRot(transaction.oldYaw());
            player.setXRot(transaction.oldPitch());
        }
    }

    @Inject(method = "useItemOn", at = @At("RETURN"))
    private void altoclef$clearCrafterTransactionAfterUse(
            LocalPlayer player, InteractionHand hand, BlockHitResult hitResult,
            CallbackInfoReturnable<InteractionResult> ci) {
        altoclef$crafterTransaction = null;
    }
}
