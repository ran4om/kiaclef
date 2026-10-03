package adris.altoclef.ui;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.chains.UserTaskChain;
import adris.altoclef.commandsystem.CommandException;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskChain;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * In-game control panel for Alto Clef. Every action is routed through the regular
 * command executor, so the panel behaves exactly like typing the command in chat.
 */
public class AltoClefScreen extends Screen {
    private enum Tab { TASKS, BUILD, CONSOLE }

    private static final int PAD = 8;
    private static final int ROW = 18;
    private static final int GAP = 4;
    private static final int MAX_HISTORY = 20;

    // Persist between openings of the panel.
    private static Tab tab = Tab.TASKS;
    private static String itemText = "";
    private static String countText = "1";
    private static String fileText = "";
    private static String commandText = "";
    private static final Map<String, Integer> requestList = new LinkedHashMap<>();
    private static final List<String> history = new ArrayList<>();

    private final AltoClef mod;
    private int left, top, panelWidth, panelHeight, contentTop;
    private EditBox itemBox, countBox, fileBox, commandBox;
    private List<String> itemSuggestions = Collections.emptyList();
    private List<String> fileSuggestions = Collections.emptyList();
    private String feedback = "";
    private int feedbackColor = UiTheme.TEXT_MUTED;
    private int historyIndex = -1;
    private Button hudButton;

    public AltoClefScreen(AltoClef mod) {
        super(Component.literal("Alto Clef"));
        this.mod = mod;
    }

    /** Cancels the user task and stops whichever chain is currently running. */
    public static void stopEverything(AltoClef mod) {
        mod.getUserTaskChain().cancel(mod);
        TaskChain chain = mod.getTaskRunner().getCurrentTaskChain();
        if (chain != null) chain.stop(mod);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(440, width - 16);
        panelHeight = Math.min(236, height - 16);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        int x = left + PAD;
        int innerWidth = panelWidth - PAD * 2;

        addRenderableWidget(Button.builder(Component.literal("Stop"), b -> {
                    stopEverything(mod);
                    setFeedback("Stopped all automation.", UiTheme.WARN);
                })
                .bounds(left + panelWidth - PAD - 50, top + 6, 50, 16)
                .tooltip(Tooltip.create(Component.literal("Cancel the current task (Ctrl+K works anywhere)")))
                .build());

        int tabY = top + 28;
        int tabWidth = (innerWidth - GAP * 2) / 3;
        Tab[] tabs = Tab.values();
        String[] labels = {"Tasks", "Build", "Console"};
        for (int i = 0; i < tabs.length; i++) {
            Tab t = tabs[i];
            Button button = addRenderableWidget(Button.builder(Component.literal(labels[i]), b -> switchTab(t))
                    .bounds(x + i * (tabWidth + GAP), tabY, tabWidth, ROW).build());
            button.active = t != tab;
        }
        contentTop = tabY + ROW + 10;

        switch (tab) {
            case TASKS -> initTasksTab(x, innerWidth);
            case BUILD -> initBuildTab(x, innerWidth);
            case CONSOLE -> initConsoleTab(x, innerWidth);
        }
    }

    private void switchTab(Tab next) {
        tab = next;
        feedback = "";
        rebuildWidgets();
    }

    // ---- Tasks tab: live status, item requests, presets ----

    private void initTasksTab(int x, int innerWidth) {
        int y = contentTop + 58;
        int countWidth = 36, buttonWidth = 50;
        int itemWidth = innerWidth - countWidth - buttonWidth * 2 - GAP * 3;
        itemBox = new EditBox(font, x, y, itemWidth, ROW, Component.literal("Item"));
        itemBox.setMaxLength(64);
        itemBox.setValue(itemText);
        itemBox.setHint(Component.literal("item, e.g. diamond (Tab completes)"));
        itemBox.setResponder(value -> {
            itemText = value;
            itemSuggestions = suggestItems(value);
        });
        addRenderableWidget(itemBox);
        countBox = new EditBox(font, x + itemWidth + GAP, y, countWidth, ROW, Component.literal("Count"));
        countBox.setMaxLength(5);
        countBox.setValue(countText);
        countBox.setResponder(value -> countText = value);
        addRenderableWidget(countBox);
        int bx = x + itemWidth + countWidth + GAP * 2;
        addRenderableWidget(Button.builder(Component.literal("Get"), b -> getTypedItem())
                .bounds(bx, y, buttonWidth, ROW)
                .tooltip(Tooltip.create(Component.literal("Gather or craft this item from scratch")))
                .build());
        addRenderableWidget(Button.builder(Component.literal("+ List"), b -> addTypedItemToList())
                .bounds(bx + buttonWidth + GAP, y, buttonWidth, ROW)
                .tooltip(Tooltip.create(Component.literal("Add to a request list and collect everything at once")))
                .build());
        itemSuggestions = suggestItems(itemText);

        y += ROW + 14;
        Button getList = addRenderableWidget(Button.builder(Component.literal("Get list"), b -> getRequestList())
                .bounds(x + innerWidth - 50 - GAP - 60, y, 60, ROW).build());
        getList.active = !requestList.isEmpty();
        Button clear = addRenderableWidget(Button.builder(Component.literal("Clear"), b -> {
                    requestList.clear();
                    rebuildWidgets();
                })
                .bounds(x + innerWidth - 50, y, 50, ROW).build());
        clear.active = !requestList.isEmpty();

        y += ROW + 16;
        String[][] presets = {
                {"Wood", "get log 16"},
                {"Iron", "get iron_ingot 8"},
                {"Diamonds", "get diamond 3"},
                {"Food", "food 20"},
                {"Iron gear", "equip [iron_helmet, iron_chestplate, iron_leggings, iron_boots]"},
                {"Beat game", "gamer"},
        };
        int presetWidth = (innerWidth - GAP * (presets.length - 1)) / presets.length;
        for (int i = 0; i < presets.length; i++) {
            String command = presets[i][1];
            addRenderableWidget(Button.builder(Component.literal(presets[i][0]), b -> runCommand(command))
                    .bounds(x + i * (presetWidth + GAP), y, presetWidth, ROW)
                    .tooltip(Tooltip.create(Component.literal("@" + command)))
                    .build());
        }
        setInitialFocus(itemBox);
    }

    private void getTypedItem() {
        String item = itemText.trim();
        if (item.isEmpty()) {
            setFeedback("Type an item name first.", UiTheme.WARN);
            return;
        }
        runCommand("get " + item + " " + parseCount());
    }

    private void addTypedItemToList() {
        String item = itemText.trim().toLowerCase(Locale.ROOT);
        if (item.isEmpty()) return;
        if (!TaskCatalogue.taskExists(item)) {
            setFeedback("\"" + item + "\" is not an obtainable item.", UiTheme.DANGER);
            return;
        }
        requestList.merge(item, parseCount(), Integer::sum);
        itemText = "";
        countText = "1";
        rebuildWidgets();
    }

    private void getRequestList() {
        if (requestList.isEmpty()) return;
        List<String> parts = new ArrayList<>();
        requestList.forEach((item, count) -> parts.add(item + " " + count));
        runCommand("get [" + String.join(", ", parts) + "]");
    }

    private int parseCount() {
        try {
            return Math.max(1, Integer.parseInt(countText.trim()));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static List<String> suggestItems(String typed) {
        String query = typed.trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) return Collections.emptyList();
        List<String> starts = new ArrayList<>(), contains = new ArrayList<>();
        for (String name : TaskCatalogue.resourceNames()) {
            if (name.startsWith(query)) starts.add(name);
            else if (name.contains(query)) contains.add(name);
        }
        Collections.sort(starts);
        Collections.sort(contains);
        starts.addAll(contains);
        return starts.size() > 8 ? starts.subList(0, 8) : starts;
    }

    // ---- Build tab: schematic files and Litematica placements ----

    private void initBuildTab(int x, int innerWidth) {
        int y = contentTop + 12;
        fileBox = new EditBox(font, x, y, innerWidth - 50 - GAP, ROW, Component.literal("Schematic"));
        fileBox.setMaxLength(256);
        fileBox.setValue(fileText);
        fileBox.setHint(Component.literal("file in .minecraft/schematics (Tab completes)"));
        fileBox.setResponder(value -> {
            fileText = value;
            fileSuggestions = suggestSchematics(value);
        });
        addRenderableWidget(fileBox);
        addRenderableWidget(Button.builder(Component.literal("Build"), b -> buildTypedFile())
                .bounds(x + innerWidth - 50, y, 50, ROW)
                .tooltip(Tooltip.create(Component.literal("Gather every material, then build it at your position")))
                .build());
        fileSuggestions = suggestSchematics(fileText);

        y += ROW + 6;
        int listed = 0;
        for (String file : suggestSchematics(fileText.isEmpty() ? "" : fileText)) {
            if (listed == 4) break;
            int rowY = y + listed * (ROW - 2);
            addRenderableWidget(Button.builder(Component.literal(UiTheme.fit(font, file, innerWidth - 12)), b -> {
                        fileText = file;
                        rebuildWidgets();
                    })
                    .bounds(x, rowY, innerWidth, ROW - 2).build());
            listed++;
        }

        int placementY = contentTop + 12 + ROW + 6 + 4 * (ROW - 2) + 18;
        addRenderableWidget(Button.builder(Component.literal("Build active Litematica placement"),
                        b -> runCommand("build placement"))
                .bounds(x, placementY, innerWidth, ROW)
                .tooltip(Tooltip.create(Component.literal("Uses the first placement loaded in Litematica")))
                .build());
        setInitialFocus(fileBox);
    }

    private void buildTypedFile() {
        String file = fileText.trim();
        if (file.isEmpty()) {
            setFeedback("Pick or type a schematic file first.", UiTheme.WARN);
            return;
        }
        runCommand("build " + (file.contains(" ") ? "\"" + file + "\"" : file));
    }

    private List<String> suggestSchematics(String typed) {
        Path dir = minecraft.gameDirectory.toPath().resolve("schematics");
        if (!Files.isDirectory(dir)) return Collections.emptyList();
        String query = typed.trim().toLowerCase(Locale.ROOT);
        try (Stream<Path> files = Files.walk(dir, 3)) {
            return files.filter(Files::isRegularFile)
                    .map(p -> dir.relativize(p).toString().replace('\\', '/'))
                    .filter(name -> {
                        String lower = name.toLowerCase(Locale.ROOT);
                        return (lower.endsWith(".litematic") || lower.endsWith(".schem") || lower.endsWith(".schematic"))
                                && lower.contains(query);
                    })
                    .sorted()
                    .limit(16)
                    .toList();
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    // ---- Console tab: raw commands, history, tools ----

    private void initConsoleTab(int x, int innerWidth) {
        int y = contentTop + 12;
        commandBox = new EditBox(font, x, y, innerWidth - 50 - GAP, ROW, Component.literal("Command"));
        commandBox.setMaxLength(512);
        commandBox.setValue(commandText);
        commandBox.setHint(Component.literal("any command, e.g. get [diamond 3, iron_ingot 5]"));
        commandBox.setResponder(value -> commandText = value);
        addRenderableWidget(commandBox);
        addRenderableWidget(Button.builder(Component.literal("Run"), b -> runCommand(commandText))
                .bounds(x + innerWidth - 50, y, 50, ROW).build());

        y += ROW + 18;
        int recentWidth = (innerWidth - GAP) / 2;
        for (int i = 0; i < Math.min(4, history.size()); i++) {
            String command = history.get(history.size() - 1 - i);
            addRenderableWidget(Button.builder(Component.literal(UiTheme.fit(font, command, recentWidth - 10)),
                            b -> {
                                commandText = command;
                                rebuildWidgets();
                            })
                    .bounds(x + (i % 2) * (recentWidth + GAP), y + (i / 2) * (ROW + 2), recentWidth, ROW)
                    .tooltip(Tooltip.create(Component.literal("Click to edit, then Run")))
                    .build());
        }

        y += (ROW + 2) * 2 + 14;
        String[][] tools = {
                {"Status", "status"}, {"Inventory", "inventory"}, {"Coords", "coords"}, {"Follow me", "follow"},
                {"Idle", "idle"}, {"Deposit", "deposit"}, {"Reload", "reload_settings"}, {"Help", "help"},
        };
        int toolWidth = (innerWidth - GAP * 3) / 4;
        for (int i = 0; i < tools.length; i++) {
            String command = tools[i][1];
            addRenderableWidget(Button.builder(Component.literal(tools[i][0]), b -> runCommand(command))
                    .bounds(x + (i % 4) * (toolWidth + GAP), y + (i / 4) * (ROW + 2), toolWidth, ROW)
                    .tooltip(Tooltip.create(Component.literal("@" + command)))
                    .build());
        }
        hudButton = addRenderableWidget(Button.builder(hudLabel(), b -> {
                    CommandStatusOverlay.toggleVisible();
                    hudButton.setMessage(hudLabel());
                })
                .bounds(x, y + (ROW + 2) * 2, innerWidth, ROW).build());
        setInitialFocus(commandBox);
    }

    private static Component hudLabel() {
        return Component.literal("Task HUD: " + (CommandStatusOverlay.isVisible() ? "shown" : "hidden"));
    }

    // ---- Command execution ----

    private void runCommand(String raw) {
        String line = raw == null ? "" : raw.trim();
        String prefix = mod.getModSettings().getCommandPrefix();
        if (line.startsWith(prefix)) line = line.substring(prefix.length()).trim();
        if (line.isEmpty()) return;
        history.remove(line);
        history.add(line);
        if (history.size() > MAX_HISTORY) history.remove(0);
        historyIndex = -1;

        final String[] error = {null};
        AltoClef.getCommandExecutor().execute(prefix + line, () -> { }, (CommandException e) -> error[0] = e.getMessage());
        if (error[0] != null) {
            setFeedback(error[0].split("\n")[0], UiTheme.DANGER);
            return;
        }
        if (tab == Tab.CONSOLE) commandText = "";
        // Hand control back to the game so the bot can act and chat output is visible.
        onClose();
    }

    private void setFeedback(String text, int color) {
        feedback = text;
        feedbackColor = color;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            if (itemBox != null && itemBox.isFocused()) {
                getTypedItem();
                return true;
            }
            if (countBox != null && countBox.isFocused()) {
                getTypedItem();
                return true;
            }
            if (fileBox != null && fileBox.isFocused()) {
                buildTypedFile();
                return true;
            }
            if (commandBox != null && commandBox.isFocused()) {
                runCommand(commandText);
                return true;
            }
        }
        if (key == GLFW.GLFW_KEY_TAB) {
            if (itemBox != null && itemBox.isFocused() && !itemSuggestions.isEmpty()) {
                itemBox.setValue(itemSuggestions.get(0));
                return true;
            }
            if (fileBox != null && fileBox.isFocused() && !fileSuggestions.isEmpty()) {
                fileBox.setValue(fileSuggestions.get(0));
                return true;
            }
            if (commandBox != null && commandBox.isFocused() && completeCommand()) return true;
        }
        if (commandBox != null && commandBox.isFocused() && !history.isEmpty()
                && (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN)) {
            if (key == GLFW.GLFW_KEY_UP) historyIndex = Math.min(history.size() - 1, historyIndex + 1);
            else historyIndex = Math.max(-1, historyIndex - 1);
            commandBox.setValue(historyIndex < 0 ? "" : history.get(history.size() - 1 - historyIndex));
            return true;
        }
        return super.keyPressed(event);
    }

    /** Completes the command name, or the item name after "get"/"give". */
    private boolean completeCommand() {
        String value = commandBox.getValue();
        int space = value.lastIndexOf(' ');
        String last = value.substring(space + 1);
        if (last.isEmpty()) return false;
        List<String> options;
        if (space < 0) {
            options = AltoClef.getCommandExecutor().allCommands().stream()
                    .map(c -> c.getName()).filter(n -> n.startsWith(last)).sorted().toList();
        } else {
            options = suggestItems(last.replace("[", ""));
        }
        if (options.isEmpty()) return false;
        String bracket = last.startsWith("[") ? "[" : "";
        commandBox.setValue(value.substring(0, space + 1) + bracket + options.get(0));
        return true;
    }

    // ---- Rendering ----

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        UiTheme.panel(graphics, left, top, panelWidth, panelHeight, UiTheme.PANEL, UiTheme.ACCENT);
        graphics.horizontalLine(left + 1, left + panelWidth - 2, top + 25, UiTheme.BORDER);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int x = left + PAD;
        int innerWidth = panelWidth - PAD * 2;

        // Header: title and status.
        graphics.text(font, "Alto Clef", x + 2, top + 10, UiTheme.ACCENT, false);
        UserTaskChain user = mod.getUserTaskChain();
        TaskChain chain = mod.getTaskRunner().getCurrentTaskChain();
        boolean running = chain != null && !chain.getTasks().isEmpty();
        String status = running
                ? chain.getName() + (user.isActive() ? "  " + UiTheme.formatDuration(user.getTaskElapsedSeconds()) : "")
                : "Idle";
        int statusX = left + panelWidth - PAD - 50 - 8 - font.width(status);
        UiTheme.dot(graphics, statusX - 9, top + 11, running ? UiTheme.RUNNING : UiTheme.IDLE);
        graphics.text(font, status, statusX, top + 10, running ? UiTheme.TEXT : UiTheme.TEXT_MUTED, false);

        switch (tab) {
            case TASKS -> renderTasksTab(graphics, x, innerWidth, chain, user);
            case BUILD -> renderBuildTab(graphics, x, innerWidth);
            case CONSOLE -> renderConsoleTab(graphics, x, innerWidth);
        }

        // Footer: feedback or key hints.
        int footerY = top + panelHeight - font.lineHeight - 5;
        if (!feedback.isEmpty()) {
            graphics.text(font, UiTheme.fit(font, feedback, innerWidth), x, footerY, feedbackColor, false);
        } else {
            graphics.text(font, "Enter: run  ·  Tab: complete  ·  Ctrl+K: stop anywhere  ·  Esc: close",
                    x, footerY, UiTheme.TEXT_MUTED, false);
        }
    }

    private void renderTasksTab(GuiGraphicsExtractor graphics, int x, int innerWidth, TaskChain chain, UserTaskChain user) {
        int y = contentTop;
        UiTheme.heading(graphics, font, "Current task", x, y, innerWidth);
        y += 12;
        List<Task> tasks = chain == null ? Collections.emptyList() : chain.getTasks();
        if (tasks.isEmpty()) {
            graphics.text(font, "Nothing running. Request an item below or pick a preset.", x, y, UiTheme.TEXT_MUTED, false);
        } else {
            graphics.text(font, UiTheme.fit(font, tasks.get(0).toString(), innerWidth), x, y, UiTheme.TEXT, false);
            if (tasks.size() > 1) {
                graphics.text(font, UiTheme.fit(font, "> " + tasks.get(tasks.size() - 1), innerWidth),
                        x, y + 10, UiTheme.WARN, false);
            }
        }
        UserTaskChain.CompletionSnapshot last = user.getLastCompletionSnapshot();
        if (last != null && last.task() != null) {
            String outcome;
            int color;
            if (last.failure() != null) {
                outcome = "failed: " + last.failure().reason();
                color = UiTheme.DANGER;
            } else if (last.cancelled()) {
                outcome = "cancelled";
                color = UiTheme.TEXT_MUTED;
            } else {
                outcome = "done in " + UiTheme.formatDuration(last.durationSeconds());
                color = UiTheme.ACCENT_DIM;
            }
            graphics.text(font, UiTheme.fit(font, "Last: " + last.task() + " - " + outcome, innerWidth),
                    x, y + 20, color, false);
        }

        int getY = contentTop + 46;
        UiTheme.heading(graphics, font, "Get items", x, getY, innerWidth);
        if (itemBox != null && itemBox.isFocused() && !itemSuggestions.isEmpty()) {
            String line = String.join("  ", itemSuggestions);
            graphics.text(font, UiTheme.fit(font, line, innerWidth), x + 2, getY + 12 + ROW + 3, UiTheme.TEXT_MUTED, false);
        } else if (!itemText.isBlank() && !TaskCatalogue.taskExists(itemText.trim().toLowerCase(Locale.ROOT))) {
            graphics.text(font, "Not a catalogued item yet", x + 2, getY + 12 + ROW + 3, UiTheme.WARN, false);
        }

        int listY = getY + 12 + ROW + 14;
        String listText;
        if (requestList.isEmpty()) {
            listText = "Request list is empty. Use + List to queue several items.";
        } else {
            List<String> parts = new ArrayList<>();
            requestList.forEach((item, count) -> parts.add(item + " x" + count));
            listText = String.join(", ", parts);
        }
        graphics.text(font, UiTheme.fit(font, listText, innerWidth - 50 - 60 - GAP * 2 - 4), x + 2, listY + 5,
                requestList.isEmpty() ? UiTheme.TEXT_MUTED : UiTheme.TEXT, false);

        UiTheme.heading(graphics, font, "Presets", x, listY + ROW + 4, innerWidth);
    }

    private void renderBuildTab(GuiGraphicsExtractor graphics, int x, int innerWidth) {
        UiTheme.heading(graphics, font, "Schematic file", x, contentTop, innerWidth);
        int listY = contentTop + 12 + ROW + 6;
        if (fileSuggestions.isEmpty()) {
            Path dir = minecraft.gameDirectory.toPath().resolve("schematics");
            String hint = Files.isDirectory(dir)
                    ? "No .litematic, .schem, or .schematic files match."
                    : "Put schematics in " + dir.getFileName() + "/ inside your game folder.";
            graphics.text(font, hint, x + 2, listY + 4, UiTheme.TEXT_MUTED, false);
        }
        int placementY = contentTop + 12 + ROW + 6 + 4 * (ROW - 2) + 18;
        UiTheme.heading(graphics, font, "Litematica", x, placementY - 12, innerWidth);
        graphics.text(font, "Materials are gathered and crafted automatically before building.",
                x + 2, placementY + ROW + 5, UiTheme.TEXT_MUTED, false);
    }

    private void renderConsoleTab(GuiGraphicsExtractor graphics, int x, int innerWidth) {
        UiTheme.heading(graphics, font, "Command", x, contentTop, innerWidth);
        int recentY = contentTop + 12 + ROW + 18;
        UiTheme.heading(graphics, font, "Recent", x, recentY - 12, innerWidth);
        if (history.isEmpty()) {
            graphics.text(font, "Commands you run appear here. Up/Down cycles history.", x + 2, recentY + 4,
                    UiTheme.TEXT_MUTED, false);
        }
        UiTheme.heading(graphics, font, "Tools", x, recentY + (ROW + 2) * 2 + 2, innerWidth);
    }
}
