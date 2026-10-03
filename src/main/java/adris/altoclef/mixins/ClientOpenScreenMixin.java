package adris.altoclef.mixins;

import adris.altoclef.Debug;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.ScreenOpenEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


@Mixin(Gui.class)
public final class ClientOpenScreenMixin {
    @Unique private static String altoclef$lastRequestedScreen = "<unobserved>";
    @Unique private static long altoclef$lastCloseDiagnosticNanos;
    @Unique private static long altoclef$lastOpenDiagnosticNanos;
    @Unique private static int altoclef$diagnosticLines;

    @Inject(
            method = "setScreen",
            at = @At("HEAD")
    )
    private void onScreenOpenBegin(@Nullable Screen screen, CallbackInfo ci) {
        altoclef$logScreenTransition(screen);
        EventBus.publish(new ScreenOpenEvent(screen, true));
    }

    @Inject(
            method = "setScreen",
            at = @At("TAIL")
    )
    private void onScreenOpenEnd(@Nullable Screen screen, CallbackInfo ci) {
        EventBus.publish(new ScreenOpenEvent(screen, false));
    }

    @Unique
    private static synchronized void altoclef$logScreenTransition(@Nullable Screen requestedScreen) {
        if (!Boolean.getBoolean("altoclef.builderPlacementDiagnostics")) return;

        String requestedName = requestedScreen == null ? "<null>" : requestedScreen.getClass().getName();
        String previousName = altoclef$lastRequestedScreen;
        altoclef$lastRequestedScreen = requestedName;
        if (requestedName.equals(previousName) || altoclef$diagnosticLines >= 120) return;

        long now = System.nanoTime();
        if (requestedScreen == null) {
            if (altoclef$lastCloseDiagnosticNanos != 0L
                    && now - altoclef$lastCloseDiagnosticNanos < 1_000_000_000L) return;
            altoclef$lastCloseDiagnosticNanos = now;
        } else {
            if (altoclef$lastOpenDiagnosticNanos != 0L
                    && now - altoclef$lastOpenDiagnosticNanos < 1_000_000_000L) return;
            altoclef$lastOpenDiagnosticNanos = now;
        }
        altoclef$diagnosticLines++;

        Minecraft minecraft = Minecraft.getInstance();
        AbstractContainerMenu menu = minecraft.player == null ? null : minecraft.player.containerMenu;
        ItemStack carried = menu == null ? ItemStack.EMPTY : menu.getCarried();
        String menuState = menu == null
                ? "menu=<none>"
                : "menuId=" + menu.containerId + ", carried=" + carried;
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        StringBuilder callers = new StringBuilder();
        for (int i = 3; i < Math.min(stack.length, 7); i++) {
            if (!callers.isEmpty()) callers.append(" <- ");
            callers.append(stack[i].getClassName()).append('#').append(stack[i].getMethodName());
        }
        Debug.logInternal("[SCREEN-DIAG] request " + previousName + " -> " + requestedName
                + ", " + menuState + ", callers=" + callers);
    }
}
