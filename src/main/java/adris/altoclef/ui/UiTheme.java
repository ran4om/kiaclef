package adris.altoclef.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Shared colors and drawing helpers for the HUD and the control panel.
 */
public final class UiTheme {
    public static final int PANEL = 0xC8101318;
    public static final int PANEL_LIGHT = 0xB01B2028;
    public static final int BORDER = 0xFF2C3440;
    public static final int ACCENT = 0xFF5FD1A5;
    public static final int ACCENT_DIM = 0xFF3A8F70;
    public static final int TEXT = 0xFFE8ECF1;
    public static final int TEXT_MUTED = 0xFF8E99A8;
    public static final int WARN = 0xFFF2C46D;
    public static final int DANGER = 0xFFF0716B;
    public static final int RUNNING = 0xFF5FD1A5;
    public static final int IDLE = 0xFF8E99A8;

    private UiTheme() {
    }

    /** Filled panel with a 1px border and a thin accent strip on the left edge. */
    public static void panel(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int fill, int accent) {
        graphics.fill(x, y, x + width, y + height, fill);
        graphics.outline(x, y, width, height, BORDER);
        if (accent != 0) graphics.fill(x, y, x + 2, y + height, accent);
    }

    /** Small filled square used as a status indicator. */
    public static void dot(GuiGraphicsExtractor graphics, int x, int y, int color) {
        graphics.fill(x, y, x + 5, y + 5, color);
    }

    /** Section heading: muted uppercase label with a rule extending to the right. */
    public static void heading(GuiGraphicsExtractor graphics, Font font, String label, int x, int y, int width) {
        String upper = label.toUpperCase();
        graphics.text(font, upper, x, y, TEXT_MUTED, false);
        int ruleX = x + font.width(upper) + 6;
        if (ruleX < x + width) graphics.horizontalLine(ruleX, x + width, y + font.lineHeight / 2, BORDER);
    }

    /** Truncates text to fit a pixel width, appending an ellipsis when cut. */
    public static String fit(Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;
        String ellipsis = "...";
        return font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width(ellipsis))) + ellipsis;
    }

    public static String formatDuration(double seconds) {
        long total = Math.max(0, (long) seconds);
        long h = total / 3600, m = (total / 60) % 60, s = total % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }
}
