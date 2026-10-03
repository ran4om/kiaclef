package adris.altoclef.tasks.construction;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.SignEditScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;

public class PlaceSignTask extends Task {

    private final BlockPos _target;
    private final String _message;

    private boolean _finished;

    public PlaceSignTask(BlockPos pos, String message) {
        _target = pos;
        _message = message;
    }

    public PlaceSignTask(String message) {
        this(null, message);
    }

    private static boolean isSign(Block block) {
        for (Block check : ItemHelper.WOOD_SIGNS_ALL) {
            if (check == block) return true;
        }
        return false;
    }

    @Override
    protected void onStart(AltoClef mod) {
        _finished = false;
    }

    @Override
    protected Task onTick(AltoClef mod) {

        if (editingSign()) {
            return editSign(mod);
        }

        // Make sure we have a sign to place
        if (!StorageHelper.hasCataloguedItem(mod, "sign")) {
            return TaskCatalogue.getItemTask("sign", 1);
        }

        // Place sign
        if (placeAnywhere()) {
            return new PlaceBlockNearbyTask(ItemHelper.WOOD_SIGNS_ALL);
        } else {

            assert Minecraft.getInstance().level != null;
            BlockState b = Minecraft.getInstance().level.getBlockState(_target);

            if (!isSign(b.getBlock()) && !b.isAir() && b.getBlock() != Blocks.WATER && b.getBlock() != Blocks.LAVA) {
                return new DestroyBlockTask(_target);
            }

            return new InteractWithBlockTask(new ItemTarget("sign", 1), Direction.UP, _target.below(), true);
        }
    }

    private Task editSign(AltoClef mod) {
        SignEditScreen screen = (SignEditScreen) Minecraft.getInstance().gui.screen();
        assert screen != null;

        StringBuilder currentLine = new StringBuilder();

        int lines = 0;

        final int SIGN_TEXT_MAX_WIDTH = 90;

        for (int codePoint : _message.codePoints().toArray()) {
            currentLine.appendCodePoint(codePoint);

            if (codePoint == '\n' || Minecraft.getInstance().font.width(currentLine.toString()) > SIGN_TEXT_MAX_WIDTH) {
                currentLine.delete(0, currentLine.length());
                if (codePoint != '\n') {
                    currentLine.appendCodePoint(codePoint);
                }
                lines++;
                if (lines >= 4) {
                    Debug.logWarning("Too much text to fit on sign! Got Cut off.");
                    break;
                }

                // Add newline
                screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0));
                //Debug.logMessage("NEW LINE ADDED BEFORE: " + c);
            }
            // keycode don't matter
            //int keyCode = java.awt.event.KeyEvent.getExtendedKeyCodeForChar(c);
            if (codePoint != '\n') {
                screen.charTyped(new CharacterEvent(codePoint));
            }
            //screen.keyPressed(keyCode, -1, )
        }
        screen.onClose();
        _finished = true;

        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        StorageHelper.closeScreen();
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return _finished;
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof PlaceSignTask task) {
            if (!task._message.equals(_message)) return false;
            if ((task._target == null) != (_target == null)) return false;
            if (task._target != null) {
                return task._target.equals(_target);
            }
            return true;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        if (placeAnywhere()) {
            return "Place Sign Anywhere";
        }
        return "Place Sign at " + _target.toShortString();
    }

    private boolean placeAnywhere() {
        return _target == null;
    }

    private boolean editingSign() {
        return Minecraft.getInstance().gui.screen() instanceof SignEditScreen;
    }
}
