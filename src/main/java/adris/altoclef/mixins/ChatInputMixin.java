package adris.altoclef.mixins;

import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.SendChatEvent;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatScreen.class)
public final class ChatInputMixin {
    @Inject(method = "handleChatInput", at = @At("HEAD"), cancellable = true)
    private void altoclef$onChatInput(String message, boolean addToRecent, CallbackInfo ci) {
        SendChatEvent event = new SendChatEvent(message);
        EventBus.publish(event);
        if (event.isCancelled()) ci.cancel();
    }
}
