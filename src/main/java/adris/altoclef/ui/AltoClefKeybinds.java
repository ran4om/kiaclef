package adris.altoclef.ui;

import adris.altoclef.AltoClef;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * Rebindable keys (Options > Controls > Alto Clef): open the control panel, stop
 * the current task, and toggle the task HUD.
 */
public final class AltoClefKeybinds {
    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("altoclef", "main"));

    private static KeyMapping openMenu;
    private static KeyMapping stopTask;
    private static KeyMapping toggleHud;

    private AltoClefKeybinds() {
    }

    public static void register(AltoClef mod) {
        openMenu = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.altoclef.open_menu", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, CATEGORY));
        stopTask = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.altoclef.stop", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), CATEGORY));
        toggleHud = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.altoclef.toggle_hud", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(client -> onTick(mod, client));
    }

    public static KeyMapping getOpenMenuKey() {
        return openMenu;
    }

    private static void onTick(AltoClef mod, Minecraft client) {
        if (openMenu == null) return;
        boolean ready = client.player != null && mod.isLoadInitializationComplete();
        while (openMenu.consumeClick()) {
            if (ready && client.gui.screen() == null) client.gui.setScreen(new AltoClefScreen(mod));
        }
        while (stopTask.consumeClick()) {
            if (ready) AltoClefScreen.stopEverything(mod);
        }
        while (toggleHud.consumeClick()) {
            CommandStatusOverlay.toggleVisible();
        }
    }
}
