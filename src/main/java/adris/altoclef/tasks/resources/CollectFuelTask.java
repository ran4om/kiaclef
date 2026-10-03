package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

// TODO: Add more safe fuel sources when neither coal nor planks can be gathered.
public class CollectFuelTask extends Task {

    private final double _targetFuel;
    // Keep the resource route and requested item count stable while child tasks
    // temporarily move inventory items into crafting slots or onto the cursor.
    private Item _selectedFuelItem;
    private int _plannedItemTarget;

    public CollectFuelTask(double targetFuel) {
        _targetFuel = targetFuel;
    }

    @Override
    protected void onStart(AltoClef mod) {
        // Nothing
    }

    @Override
    protected void onResetForNewRun() {
        _selectedFuelItem = null;
        _plannedItemTarget = 0;
    }

    @Override
    protected Task onTick(AltoClef mod) {

        switch (WorldHelper.getCurrentDimension()) {
            case OVERWORLD -> {
                _selectedFuelItem = retainSelectedFuelItem(_selectedFuelItem, getGatherableFuelItem(mod));
                Item fuelItem = _selectedFuelItem;
                if (fuelItem == null) {
                    setDebugState("No supported fuel has a safe collection task.");
                    return null;
                }
                setDebugState("Collecting " + fuelItem + " for fuel.");
                int currentCount = StorageHelper.getAccessibleInventoryItemCount(mod, new ItemTarget(fuelItem));
                int itemTarget = getItemTargetForFuel(_targetFuel,
                        getAccessibleInventoryFuelCapacity(mod), currentCount,
                        ItemHelper.getFuelAmount(fuelItem));
                _plannedItemTarget = retainPlannedItemTarget(_plannedItemTarget, itemTarget);
                if (_plannedItemTarget <= currentCount) {
                    return null;
                }
                return TaskCatalogue.getItemTask(fuelItem, _plannedItemTarget);
            }
            case END -> {
                setDebugState("Going to overworld, since, well, no more fuel can be found here.");
                return new DefaultGoToDimensionTask(Dimension.OVERWORLD);
            }
            case NETHER -> {
                setDebugState("Going to overworld, since we COULD use wood but wood confuses the bot. A bug at the moment.");
                return new DefaultGoToDimensionTask(Dimension.OVERWORLD);
            }
            //return TaskCatalogue.getItemTask("planks", (int) Math.ceil(_targetFuel));
        }
        setDebugState("INVALID DIMENSION: " + WorldHelper.getCurrentDimension());
        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        // Nothing
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof CollectFuelTask task) {
            return Math.abs(task._targetFuel - _targetFuel) < 0.01;
        }
        return false;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return hasEnoughFuel(_targetFuel, getAccessibleInventoryFuelCapacity(mod));
    }

    static boolean hasEnoughFuel(double targetFuel, double availableFuel) {
        return availableFuel >= targetFuel;
    }

    /** Returns the total item goal needed to reach the requested fuel capacity. */
    static int getItemTargetForFuel(double targetFuel, double availableFuel,
                                    int currentItemCount, double fuelPerItem) {
        if (fuelPerItem <= 0 || currentItemCount < 0) {
            throw new IllegalArgumentException("fuelPerItem must be positive and currentItemCount nonnegative");
        }
        if (hasEnoughFuel(targetFuel, availableFuel)) {
            return currentItemCount;
        }
        int additionalItems = (int) Math.ceil((targetFuel - availableFuel) / fuelPerItem);
        return Math.addExact(currentItemCount, additionalItems);
    }

    /** Keep a chosen fuel source fixed for this task, including child-task restarts. */
    static Item retainSelectedFuelItem(Item selectedFuelItem, Item candidateFuelItem) {
        return selectedFuelItem != null ? selectedFuelItem : candidateFuelItem;
    }

    /** A changing inventory view may raise a plan, but must never lower it. */
    static int retainPlannedItemTarget(int previousTarget, int computedTarget) {
        if (previousTarget < 0 || computedTarget < 0) {
            throw new IllegalArgumentException("fuel item targets must be nonnegative");
        }
        return Math.max(previousTarget, computedTarget);
    }

    private static net.minecraft.world.item.Item getGatherableFuelItem(AltoClef mod) {
        return chooseGatherableFuelItem(
                StorageHelper.miningRequirementMetInventory(mod, MiningRequirement.WOOD),
                mod.getModSettings()::isSupportedFuel,
                TaskCatalogue::taskExists,
                item -> StorageHelper.getAccessibleInventoryItemCount(mod, new ItemTarget(item)));
    }

    /** Prefer coal only when its ore can be mined without recursively crafting a pickaxe. */
    static Item chooseGatherableFuelItem(boolean canMineCoal,
                                         Predicate<Item> supportedFuel,
                                         Predicate<Item> taskExists,
                                         ToDoubleFunction<Item> inventoryCount) {
        boolean coalAvailable = supportedFuel.test(Items.COAL) && taskExists.test(Items.COAL);
        if (canMineCoal && coalAvailable) return Items.COAL;

        Item plankFuel = java.util.Arrays.stream(ItemHelper.PLANKS)
                .filter(supportedFuel)
                .filter(taskExists)
                .max(java.util.Comparator.comparingDouble(inventoryCount))
                .orElse(null);
        if (plankFuel != null) return plankFuel;
        return coalAvailable ? Items.COAL : null;
    }

    /** Fuel available in ordinary player inventory slots, excluding cursor, crafting, armor, and offhand. */
    public static double getAccessibleInventoryFuelCapacity(AltoClef mod) {
        if (mod.getPlayer() == null) return 0;
        List<ItemStack> accessibleStacks = new ArrayList<>(36);
        for (int slot = 0; slot < 36; slot++) {
            accessibleStacks.add(mod.getPlayer().getInventory().getItem(slot));
        }
        return calculateFuelCapacity(accessibleStacks, mod.getModSettings()::isSupportedFuel,
                item -> ItemHelper.getFuelAmount(item));
    }

    static double calculateFuelCapacity(Iterable<ItemStack> stacks,
                                        Predicate<Item> supportedFuel,
                                        ToDoubleFunction<Item> fuelPerItem) {
        double capacity = 0;
        int slot = 0;
        for (ItemStack stack : stacks) {
            // The iterable represents player inventory slots in order. Only the
            // first 36 slots are accessible for fuel collection; cursor, crafting,
            // armor, and offhand stacks must not satisfy a furnace fuel check.
            if (slot++ >= 36) break;
            if (!stack.isEmpty() && supportedFuel.test(stack.getItem())) {
                capacity += fuelPerItem.applyAsDouble(stack.getItem()) * stack.getCount();
            }
        }
        return capacity;
    }

    @Override
    protected String toDebugString() {
        return "Collect Fuel: x" + _targetFuel;
    }
}
