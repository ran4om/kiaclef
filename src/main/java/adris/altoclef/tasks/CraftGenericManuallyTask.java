package adris.altoclef.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.slot.ClickSlotTask;
import adris.altoclef.tasks.slot.MoveItemToSlotTask;
import adris.altoclef.tasks.slot.ReceiveCraftingOutputSlotTask;
import adris.altoclef.tasks.slot.ThrowCursorTask;
import adris.altoclef.tasksystem.ITaskUsesCraftingGrid;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.RecipeTarget;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.CraftingTableSlot;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.slots.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Optional;
import java.util.List;
import java.util.ArrayList;

/**
 * Assuming a crafting screen is open, crafts a recipe.
 *
 * Not useful for custom tasks.
 */
public class CraftGenericManuallyTask extends Task implements ITaskUsesCraftingGrid {

    private final RecipeTarget _target;

    public CraftGenericManuallyTask(RecipeTarget target) {
        _target = target;
    }

    @Override
    protected void onStart(AltoClef mod) {

    }

    @Override
    protected Task onTick(AltoClef mod) {

        boolean bigCrafting = StorageHelper.isBigCraftingOpen();

        if (!bigCrafting && !StorageHelper.isPlayerInventoryOpen()) {
            // Make sure we're not in another screen before we craft,
            // otherwise crafting won't work
            StorageHelper.closeScreen();
            // Just to be safe
        }

        Slot outputSlot = bigCrafting ? CraftingTableSlot.OUTPUT_SLOT : PlayerSlot.CRAFT_OUTPUT_SLOT;

        ItemStack output = StorageHelper.getItemStackInSlot(outputSlot);
        ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
        int matchingOutputInputsInGrid = countMatchingInputsInGrid(bigCrafting, _target.getOutputItem());
        int outputCursorCount = cursor.getItem() == _target.getOutputItem() ? cursor.getCount() : 0;
        int inventoryOutputCount = mod.getItemStorage().getItemCountInventoryOnly(_target.getOutputItem())
                - outputCursorCount
                // The player crafting grid is included by InventorySubTracker while the inventory screen is open.
                - (!bigCrafting ? matchingOutputInputsInGrid : 0);
        // The output slot only previews one craft from whatever is already in the grid.
        // Counting it as produced output makes a fully loaded grid look over-filled, and the
        // over-satisfied right-click below then cycles items between cursor and slot forever.
        int requiredCraftCount = getRequiredCraftCount(_target,
                inventoryOutputCount,
                outputCursorCount,
                0,
                matchingOutputInputsInGrid);
        int requiredPerSlot = requiredCraftCount;

        if (requiredCraftCount == 0) {
            if (!output.isEmpty() && output.getItem() == _target.getOutputItem()) {
                return new ReceiveCraftingOutputSlotTask(outputSlot, _target.getTargetCount());
            }
            return null;
        }

        // For each slot in table
        for (int craftSlot = 0; craftSlot < _target.getRecipe().getSlotCount(); ++craftSlot) {
            ItemTarget toFill = _target.getRecipe().getSlot(craftSlot);
            Slot currentCraftSlot;
            if (bigCrafting) {
                // Craft in table
                currentCraftSlot = CraftingTableSlot.getInputSlot(craftSlot, _target.getRecipe().isBig());
            } else {
                // Craft in window
                currentCraftSlot = PlayerSlot.getCraftInputSlot(craftSlot);
            }
            ItemStack present = StorageHelper.getItemStackInSlot(currentCraftSlot);
            if (toFill == null || toFill.isEmpty()) {
                if (present.getItem() != Items.AIR) {
                    // Move this item OUT if it should be empty
                    setDebugState("Found INVALID slot");
                    return new ClickSlotTask(currentCraftSlot);
                }
            } else {
                boolean correctItem = toFill.matches(present.getItem());
                if (!present.isEmpty() && !correctItem) {
                    setDebugState("Clearing an incorrect crafting ingredient");
                    return new ClickSlotTask(currentCraftSlot);
                }
                boolean isSatisfied = correctItem && present.getCount() >= requiredPerSlot;
                if (!isSatisfied) {
                    int alreadyInSlot = correctItem ? present.getCount() : 0;
                    int missingForSlot = Math.max(0, requiredPerSlot - alreadyInSlot);
                    List<Slot> ingredientInventorySlots = getIngredientInventorySlots(mod, toFill, bigCrafting);
                    boolean matchingItemInInventory = !ingredientInventorySlots.isEmpty();
                    if (missingForSlot > 0 && !hasMatchingIngredientAvailable(toFill, matchingItemInInventory, cursor)) {
                        if (!output.isEmpty()) {
                            setDebugState("NO MORE to fit: grabbing from output.");
                            return new ReceiveCraftingOutputSlotTask(outputSlot, _target.getTargetCount());
                        } else {
                            // Move on to the NEXT slot, we can't fill this one anymore.
                            continue;
                        }
                    }

                    setDebugState("Moving item to slot...");
                    ItemTarget ingredientTarget = new ItemTarget(toFill, requiredPerSlot);
                    return new MoveItemToSlotTask(ingredientTarget, currentCraftSlot, ignored -> ingredientInventorySlots);
                }
                // We could be OVER satisfied
                boolean oversatisfies = present.getCount() > requiredPerSlot;
                if (oversatisfies) {
                    // Right-clicking with a held stack deposits one item instead of taking half,
                    // which never converges. Put the cursor away first.
                    if (!cursor.isEmpty()) {
                        setDebugState("OVER SATISFIED slot: emptying cursor before splitting.");
                        return storeCursorTask(mod, cursor);
                    }
                    setDebugState("OVER SATISFIED slot! Right clicking slot to extract half and spread it out more.");
                    return new ClickSlotTask(currentCraftSlot, 1);
                }
            }
        }

        // Ensure our cursor is empty/can receive our item
        if (!ItemHelper.canStackTogether(StorageHelper.getItemStackInSlot(outputSlot), cursor)) {
            return storeCursorTask(mod, cursor);
        }

        if (!StorageHelper.getItemStackInSlot(outputSlot).isEmpty()) {
            return new ReceiveCraftingOutputSlotTask(outputSlot, _target.getTargetCount());
        } else {
            // Wait
            return null;
        }
    }

    private static Task storeCursorTask(AltoClef mod, ItemStack cursor) {
        Optional<Slot> toFit = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursor, false).or(() -> StorageHelper.getGarbageSlot(mod));
        if (toFit.isPresent()) {
            return new ClickSlotTask(toFit.get());
        }
        // Eh screw it
        return new ThrowCursorTask();
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {

    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof CraftGenericManuallyTask task) {
            return task._target.equals(_target);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Crafting: " + _target;
    }

    static int getRequiredCraftCount(RecipeTarget target, int outputInventoryCount,
                                     int outputCursorCount, int outputSlotCount,
                                     int matchingOutputInputsInGrid) {
        int availableOutput = outputInventoryCount + outputCursorCount + outputSlotCount
                + matchingOutputInputsInGrid;
        int missingOutput = Math.max(0, target.getTargetCount() - availableOutput);
        if (missingOutput == 0) return 0;

        int outputItemsConsumedPerCraft = 0;
        for (ItemTarget slot : target.getRecipe().getSlots()) {
            if (slot != null && slot.matches(target.getOutputItem())) {
                outputItemsConsumedPerCraft++;
            }
        }
        int netOutputPerCraft = target.getRecipe().outputCount() - outputItemsConsumedPerCraft;
        if (netOutputPerCraft <= 0) {
            throw new IllegalArgumentException("Recipe cannot increase its requested output: " + target);
        }
        int requiredCrafts = 1 + (missingOutput - 1) / netOutputPerCraft;

        // Every populated recipe slot needs one ingredient per batch. Keep the requested
        // batch count within the smallest stack that could satisfy each slot, including
        // non-stackable recipe inputs such as bows.
        int maxCraftsPerSlot = Integer.MAX_VALUE;
        for (ItemTarget slot : target.getRecipe().getSlots()) {
            if (slot == null || slot.isEmpty()) continue;
            int slotMaxStackSize = Integer.MAX_VALUE;
            for (var item : slot.getMatches()) {
                if (item != null) {
                    slotMaxStackSize = Math.min(slotMaxStackSize, new ItemStack(item, 1).getMaxStackSize());
                }
            }
            if (slotMaxStackSize != Integer.MAX_VALUE) {
                maxCraftsPerSlot = Math.min(maxCraftsPerSlot, slotMaxStackSize);
            }
        }
        return Math.min(requiredCrafts, maxCraftsPerSlot);
    }

    static boolean hasMatchingIngredientAvailable(ItemTarget ingredient, boolean inventoryHasMatch, ItemStack cursor) {
        return inventoryHasMatch || (!cursor.isEmpty() && ingredient.matches(cursor.getItem()));
    }

    private int countMatchingInputsInGrid(boolean bigCrafting, net.minecraft.world.item.Item outputItem) {
        int count = 0;
        for (int craftSlot = 0; craftSlot < _target.getRecipe().getSlotCount(); craftSlot++) {
            ItemTarget expected = _target.getRecipe().getSlot(craftSlot);
            if (expected == null || !expected.matches(outputItem)) continue;
            Slot slot = bigCrafting
                    ? CraftingTableSlot.getInputSlot(craftSlot, _target.getRecipe().isBig())
                    : PlayerSlot.getCraftInputSlot(craftSlot);
            ItemStack present = StorageHelper.getItemStackInSlot(slot);
            if (expected.matches(present.getItem())) count += present.getCount();
        }
        return count;
    }

    private List<Slot> getIngredientInventorySlots(AltoClef mod, ItemTarget ingredient, boolean bigCrafting) {
        List<Slot> craftingInputs = new ArrayList<>();
        for (int i = 0; i < _target.getRecipe().getSlotCount(); i++) {
            craftingInputs.add(bigCrafting
                    ? CraftingTableSlot.getInputSlot(i, _target.getRecipe().isBig())
                    : PlayerSlot.getCraftInputSlot(i));
        }
        return mod.getItemStorage().getSlotsWithItemPlayerInventory(false, ingredient.getMatches()).stream()
                .filter(slot -> !Slot.isCursor(slot) && !craftingInputs.contains(slot))
                .toList();
    }
}
