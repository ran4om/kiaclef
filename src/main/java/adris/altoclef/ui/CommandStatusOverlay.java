package adris.altoclef.ui;

import adris.altoclef.AltoClef;
import adris.altoclef.chains.UserTaskChain;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskChain;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.Collections;
import java.util.List;

/**
 * Compact task panel drawn in the top-left corner of the HUD. Shows which chain is
 * in control, how long the user task has been running, and the tail of the task chain.
 */
public class CommandStatusOverlay {
    private static final int MAX_LINES = 8;
    private static final int MAX_WIDTH = 300;
    private static final int PAD = 5;
    private static boolean visible = true;

    public static void toggleVisible() {
        visible = !visible;
    }

    public static boolean isVisible() {
        return visible;
    }

    public void render(AltoClef mod, GuiGraphicsExtractor graphics) {
        if (!visible || !mod.getModSettings().shouldShowTaskChain()) return;
        Minecraft minecraft = Minecraft.getInstance();
        // The control panel shows the same information in more detail.
        if (minecraft.gui.screen() instanceof AltoClefScreen) return;

        TaskChain chain = mod.getTaskRunner().getCurrentTaskChain();
        List<Task> tasks = chain == null ? Collections.emptyList() : chain.getTasks();
        Font font = minecraft.font;
        int lineHeight = font.lineHeight + 2;
        int x = 4, y = 4;

        if (tasks.isEmpty()) {
            String hint = idleHint();
            int width = PAD * 2 + 9 + font.width("Alto Clef") + 6 + font.width(hint);
            UiTheme.panel(graphics, x, y, width, lineHeight + PAD * 2 - 2, UiTheme.PANEL, 0);
            int ty = y + PAD;
            UiTheme.dot(graphics, x + PAD, ty + 1, UiTheme.IDLE);
            graphics.text(font, "Alto Clef", x + PAD + 9, ty, UiTheme.TEXT, false);
            graphics.text(font, hint, x + PAD + 9 + font.width("Alto Clef") + 6, ty, UiTheme.TEXT_MUTED, false);
            return;
        }

        int start = Math.max(0, tasks.size() - MAX_LINES);
        int rows = 1 + (start > 0 ? 1 : 0) + (tasks.size() - start);
        int innerWidth = font.width("Alto Clef");
        for (int i = start; i < tasks.size(); i++) {
            innerWidth = Math.max(innerWidth, font.width(tasks.get(i).toString()) + Math.min(i - start, 6) * 4 + 6);
        }
        String header = headerText(mod, chain);
        innerWidth = Math.max(innerWidth, font.width("Alto Clef") + 6 + font.width(header) + 9);
        innerWidth = Math.min(innerWidth, MAX_WIDTH);
        int height = rows * lineHeight + PAD * 2 - 2;
        UiTheme.panel(graphics, x, y, innerWidth + PAD * 2, height, UiTheme.PANEL, UiTheme.ACCENT);

        int tx = x + PAD + 1, ty = y + PAD;
        UiTheme.dot(graphics, tx, ty + 1, UiTheme.RUNNING);
        graphics.text(font, "Alto Clef", tx + 9, ty, UiTheme.TEXT, false);
        graphics.text(font, UiTheme.fit(font, header, innerWidth - font.width("Alto Clef") - 15),
                tx + 9 + font.width("Alto Clef") + 6, ty, UiTheme.TEXT_MUTED, false);
        ty += lineHeight;

        if (start > 0) {
            graphics.text(font, "... " + start + " more", tx, ty, UiTheme.TEXT_MUTED, false);
            ty += lineHeight;
        }
        for (int i = start; i < tasks.size(); i++) {
            int indent = Math.min(i - start, 6) * 4;
            boolean leaf = i == tasks.size() - 1;
            String prefix = leaf ? "> " : "- ";
            String text = UiTheme.fit(font, prefix + tasks.get(i), innerWidth - indent);
            graphics.text(font, text, tx + indent, ty, leaf ? UiTheme.WARN : UiTheme.TEXT, false);
            ty += lineHeight;
        }
    }

    private static String headerText(AltoClef mod, TaskChain chain) {
        StringBuilder sb = new StringBuilder(chain.getName());
        UserTaskChain user = mod.getUserTaskChain();
        if (user.isActive()) sb.append("  ").append(UiTheme.formatDuration(user.getTaskElapsedSeconds()));
        return sb.toString();
    }

    private static String idleHint() {
        KeyMapping key = AltoClefKeybinds.getOpenMenuKey();
        if (key == null || key.isUnbound()) return "idle";
        return "idle · " + key.getTranslatedKeyMessage().getString() + " for menu";
    }
}
