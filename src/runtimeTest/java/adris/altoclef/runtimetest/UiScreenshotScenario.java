package adris.altoclef.runtimetest;

import adris.altoclef.AltoClef;
import adris.altoclef.ui.AltoClefScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;

import java.util.function.Consumer;

/**
 * Opens the control panel, visits each tab and saves a screenshot of each, then closes
 * it and captures the HUD while an idle task chain is shown. Evidence is visual only.
 */
final class UiScreenshotScenario {
    private static final String[] TABS = {"Tasks", "Build", "Console"};
    private static final int SETTLE_TICKS = 20;

    private final AltoClef mod;
    private final Consumer<String> append;
    private final Consumer<String> fail;
    private final Runnable pass;
    private int step;
    private int wait;

    UiScreenshotScenario(AltoClef mod, Consumer<String> append, Consumer<String> fail, Runnable pass) {
        this.mod = mod;
        this.append = append;
        this.fail = fail;
        this.pass = pass;
    }

    void tick() {
        Minecraft client = Minecraft.getInstance();
        if (wait-- > 0) return;
        try {
            if (step == 0) {
                client.gui.setScreen(new AltoClefScreen(mod));
                wait = SETTLE_TICKS;
                step++;
                return;
            }
            int tabIndex = (step - 1) / 2;
            if (tabIndex < TABS.length) {
                if ((step - 1) % 2 == 0) {
                    if (!(client.gui.screen() instanceof AltoClefScreen screen)) {
                        fail.accept("UI_SCREENSHOT control panel was not open before tab " + TABS[tabIndex]);
                        return;
                    }
                    if (!pressButton(screen, TABS[tabIndex]) && tabIndex > 0) {
                        fail.accept("UI_SCREENSHOT could not find tab button " + TABS[tabIndex]);
                        return;
                    }
                } else {
                    grab(client, "altoclef-ui-" + TABS[tabIndex].toLowerCase());
                }
                wait = SETTLE_TICKS;
                step++;
                return;
            }
            if (step == 1 + TABS.length * 2) {
                client.gui.setScreen(null);
                wait = SETTLE_TICKS;
                step++;
                return;
            }
            if (step == 2 + TABS.length * 2) {
                grab(client, "altoclef-ui-hud");
                wait = SETTLE_TICKS;
                step++;
                return;
            }
            append.accept("UI_SCREENSHOT\tPASS\ttabs=" + String.join(",", TABS) + "\thud=captured");
            pass.run();
        } catch (RuntimeException error) {
            fail.accept("UI_SCREENSHOT exception: " + error);
        }
    }

    private static boolean pressButton(AltoClefScreen screen, String label) {
        for (GuiEventListener child : screen.children()) {
            if (child instanceof Button button && button.getMessage().getString().equals(label)) {
                if (!button.active) return true; // Already the selected tab.
                button.onPress(null);
                return true;
            }
        }
        return false;
    }

    private void grab(Minecraft client, String name) {
        // Same path as the F2 key: a timestamped PNG under screenshots/.
        Screenshot.grab(client, false);
        append.accept("UI_SCREENSHOT_REQUESTED\t" + name);
    }
}
