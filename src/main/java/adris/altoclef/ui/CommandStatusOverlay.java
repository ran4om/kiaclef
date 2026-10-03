package adris.altoclef.ui;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

public class CommandStatusOverlay {
    private long timeRunning;
    private long lastTime;
    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    public void render(AltoClef mod, GuiGraphicsExtractor graphics) {
        if (!mod.getModSettings().shouldShowTaskChain()) return;

        List<Task> tasks = mod.getTaskRunner().getCurrentTaskChain() == null
                ? Collections.emptyList()
                : mod.getTaskRunner().getCurrentTaskChain().getTasks();
        Font font = Minecraft.getInstance().font;
        int color = 0xFFFFFFFF;
        drawTaskChain(font, graphics, 0, 0, color, 10, tasks, mod);
    }

    private void drawTaskChain(Font font, GuiGraphicsExtractor graphics, int x, int y, int color,
                               int maxLines, List<Task> tasks, AltoClef mod) {
        if (tasks.isEmpty()) {
            graphics.text(font, Component.literal(" (no task running) "), x, y, color);
            long now = Instant.now().toEpochMilli();
            if (lastTime + 10_000 < now && mod.getModSettings().shouldShowTimer()) timeRunning = now;
            return;
        }

        int fontHeight = font.lineHeight;
        if (mod.getModSettings().shouldShowTimer()) {
            long now = Instant.now().toEpochMilli();
            lastTime = now;
            String elapsed = DATE_TIME_FORMATTER.format(Instant.ofEpochMilli(now - timeRunning));
            graphics.text(font, Component.literal("<" + elapsed + ">"), x, y, color);
            y += fontHeight + 2;
        }

        int start = Math.max(0, tasks.size() - maxLines);
        if (start > 0) {
            graphics.text(font, Component.literal(" ... "), x, y, color);
            y += fontHeight + 2;
        }
        for (int i = start; i < tasks.size(); i++) {
            graphics.text(font, Component.literal(tasks.get(i).toString()), x, y, color);
            y += fontHeight + 2;
        }
    }
}
