package adris.altoclef.mixins;

import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.ChatMessageEvent;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.multiplayer.chat.ChatListener;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import net.minecraft.network.chat.PlayerChatMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.time.Instant;

@Mixin(ChatListener.class)
public final class ChatReadMixin {
    @Inject(method = "showMessageToPlayer", at = @At("HEAD"))
    private void altoclef$onPlayerChat(ChatType.Bound type, PlayerChatMessage signedMessage,
                                       Component decoratedMessage, GameProfile sender,
                                       boolean onlyShowSecureChat, Instant receivedAt,
                                       org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        EventBus.publish(new ChatMessageEvent(type, decoratedMessage));
    }

    @Inject(method = "handleDisguisedChatMessage", at = @At("HEAD"))
    private void altoclef$onDisguisedChat(Component message, ChatType.Bound type, CallbackInfo ci) {
        EventBus.publish(new ChatMessageEvent(type, message));
    }

    @Inject(method = "handleSystemMessage", at = @At("HEAD"))
    private void altoclef$onSystemMessage(Component message, boolean overlay, CallbackInfo ci) {
        EventBus.publish(new ChatMessageEvent(null, message));
    }
}
