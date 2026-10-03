package adris.altoclef.tasks.container;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.container.SmeltInFurnaceTask.DoSmeltInFurnaceTask;
import adris.altoclef.tasks.container.SmeltInFurnaceTask.FurnaceCache;
import adris.altoclef.tasks.resources.CollectFuelTask;
import adris.altoclef.tasks.slot.*;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.SmeltTarget;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.FurnaceSlot;
import adris.altoclef.util.slots.Slot;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.ToIntFunction;
import java.util.stream.Stream;


// Ref
// https://minecraft.gamepedia.com/Smelting

/**
 * Smelt in a furnace, placing a furnace and collecting fuel as needed.
 */
public class SmeltInFurnaceTask extends ResourceTask {

    private final SmeltTarget[] _targets;

    private final DoSmeltInFurnaceTask[] _doTasks;
    private boolean _ignoreMaterials;

    public SmeltInFurnaceTask(SmeltTarget[] targets) {
        super(extractItemTargets(targets));
        if (targets.length == 0) {
            throw new IllegalArgumentException("At least one smelt target is required");
        }
        _targets = targets;
        _doTasks = Arrays.stream(targets).map(DoSmeltInFurnaceTask::new).toArray(DoSmeltInFurnaceTask[]::new);
    }

    public SmeltInFurnaceTask(SmeltTarget target) {
        this(new SmeltTarget[]{target});
    }

    private static ItemTarget[] extractItemTargets(SmeltTarget[] recipeTargets) {
        List<ItemTarget> result = new ArrayList<>(recipeTargets.length);
        for (SmeltTarget target : recipeTargets) {
            result.add(target.getItem());
        }
        return result.toArray(ItemTarget[]::new);
    }

    public void ignoreMaterials() {
        _ignoreMaterials = true;
        Arrays.stream(_doTasks).forEach(DoSmeltInFurnaceTask::ignoreMaterials);
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
        if (!cursor.isEmpty() && matchesAnyTargetOutput(_targets, cursor.getItem())) {
            Optional<Slot> destination = mod.getItemStorage()
                    .getSlotThatCanFitInPlayerInventory(cursor, false);
            if (destination.isPresent()) {
                setDebugState("Returning partial smelt output from cursor");
                return new ClickSlotTask(destination.get());
            }
            setDebugState("Freeing inventory for smelt output");
            return new EnsureFreeInventorySlotTask();
        }
        return super.onTick(mod);
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
        mod.getBehaviour().markSlotAsConversionSlot(FurnaceSlot.INPUT_SLOT_MATERIALS,
                stack -> matchesAnyTargetMaterial(_targets, stack.getItem()));
        mod.getBehaviour().markSlotAsConversionSlot(FurnaceSlot.INPUT_SLOT_FUEL, stack -> ItemHelper.isFuel(stack.getItem()));
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        int nextTarget = firstIncompleteTargetIndex(_targets,
                target -> StorageHelper.getAccessibleInventoryItemCount(mod, target));
        return nextTarget < 0 ? null : _doTasks[nextTarget];
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
        // Close furnace screen
        StorageHelper.closeScreen();
    }

    @Override
    protected void onResetForNewRun() {
        for (DoSmeltInFurnaceTask task : _doTasks) {
            task.restartForNewRun();
        }
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        // A multi-target request is complete only when every output is available in
        // the player's accessible inventory. `ignoreMaterials` changes fuel planning
        // for furnace contents; it never waives an output target.
        return super.isFinished(mod);
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        if (other instanceof SmeltInFurnaceTask task) {
            return task._ignoreMaterials == _ignoreMaterials && Arrays.equals(task._targets, _targets);
        }
        return false;
    }

    @Override
    protected String toDebugStringName() {
        return "Smelting " + Arrays.toString(extractItemTargets(_targets));
    }

    public SmeltTarget[] getTargets() {
        return _targets;
    }

    static int firstIncompleteTargetIndex(SmeltTarget[] targets, ToIntFunction<ItemTarget> inventoryCount) {
        for (int i = 0; i < targets.length; i++) {
            ItemTarget output = targets[i].getItem();
            if (inventoryCount.applyAsInt(output) < output.getTargetCount()) return i;
        }
        return -1;
    }

    static boolean matchesAnyTargetMaterial(SmeltTarget[] targets, Item item) {
        for (SmeltTarget target : targets) {
            if (target.getMaterial().matches(item)
                    || Arrays.stream(target.getOptionalMaterials()).anyMatch(optional -> optional == item)) {
                return true;
            }
        }
        return false;
    }

    static boolean matchesAnyTargetOutput(SmeltTarget[] targets, Item item) {
        for (SmeltTarget target : targets) {
            if (target.getItem().matches(item)) return true;
        }
        return false;
    }

    static boolean shouldCollectFuelNow(boolean furnaceAcquired, double inventoryFuel, double fuelNeeded) {
        return shouldCollectFuelNow(furnaceAcquired, inventoryFuel, fuelNeeded, false);
    }

    static boolean shouldCollectFuelNow(boolean furnaceAcquired, double inventoryFuel,
                                        double fuelNeeded, boolean fuelTransferPending) {
        return furnaceAcquired && !fuelTransferPending && inventoryFuel < fuelNeeded;
    }

    static boolean isSlotTransferAcknowledged(int requestedItems, int sourceItemsRemoved,
                                              double requiredDestinationAmount,
                                              double receivedDestinationAmount,
                                              boolean cursorEmpty) {
        return hasReceivedExpectedTransfer(requestedItems, sourceItemsRemoved,
                requiredDestinationAmount, receivedDestinationAmount) && cursorEmpty;
    }

    static boolean hasReceivedExpectedTransfer(int requestedItems, int sourceItemsRemoved,
                                               double requiredDestinationAmount,
                                               double receivedDestinationAmount) {
        return requestedItems > 0 && sourceItemsRemoved >= requestedItems
                // Furnace burn time is reported in whole ticks (1/200 of a smelt).
                // The first tick can consume fuel between insertion and our first
                // observation, so allow exactly that bounded quantization loss.
                && receivedDestinationAmount + (1.0 / 200.0) + 1.0e-6 >= requiredDestinationAmount;
    }

    static boolean hasFuelCapacityIncrease(boolean receiptPreviouslyObserved,
                                           double previousCapacity, double currentCapacity) {
        return receiptPreviouslyObserved || currentCapacity > previousCapacity + 1.0e-3;
    }

    static double observedFuelTransferReceipt(double initialFuelCapacity, double currentFuelCapacity,
                                             int initialOutputCount, int currentOutputCount,
                                             double initialCookProgress, double currentCookProgress) {
        return Math.max(0, currentFuelCapacity - initialFuelCapacity
                + currentOutputCount - initialOutputCount
                + currentCookProgress - initialCookProgress);
    }

    static int materialTransferReceipt(int initialInputCount, int currentInputCount,
                                       int initialOutputCount, int currentOutputCount) {
        return Math.max(0, currentInputCount + currentOutputCount
                - initialInputCount - initialOutputCount);
    }

    static ItemTarget materialSlotTransferTarget(ItemTarget materialTarget, int neededMaterialsInSlot) {
        return new ItemTarget(materialTarget, neededMaterialsInSlot);
    }

    static boolean shouldAcquireFurnace(boolean furnaceOpen, boolean furnaceAlreadyAcquired) {
        return !furnaceOpen && !furnaceAlreadyAcquired;
    }

    @SuppressWarnings("ConditionCoveredByFurtherCondition")
    static class DoSmeltInFurnaceTask extends DoStuffInContainerTask {

        private final SmeltTarget _target;
        private boolean _ignoreMaterials;
        private boolean _furnaceAlreadyAcquired;
        // Preserve an in-flight inventory -> cursor -> furnace transfer across
        // parent ticks and menu close/reopen cycles.
        private FurnaceTransferSpec _pendingTransferSpec;
        private PendingFurnaceSlotTransferTask _pendingTransferTask;

        private FurnaceCache _furnaceCache = new FurnaceCache();

        private final ItemTarget _allMaterials;

        public DoSmeltInFurnaceTask(SmeltTarget target) {
            super(Blocks.FURNACE, new ItemTarget(Items.FURNACE));
            _target = target;
            _allMaterials = new ItemTarget(Stream.concat(Arrays.stream(_target.getMaterial().getMatches()), Arrays.stream(_target.getOptionalMaterials())).toArray(Item[]::new), _target.getMaterial().getTargetCount());
        }

        public void ignoreMaterials() {
            _ignoreMaterials = true;
        }

        @Override
        protected void onStart(AltoClef mod) {
            super.onStart(mod);
            _furnaceAlreadyAcquired = false;
        }

        @Override
        protected void onResetForNewRun() {
            super.onResetForNewRun();
            _furnaceAlreadyAcquired = false;
            _pendingTransferSpec = null;
            _pendingTransferTask = null;
            _furnaceCache = new FurnaceCache();
        }

        @Override
        protected boolean isSubTaskEqual(DoStuffInContainerTask other) {
            if (other instanceof DoSmeltInFurnaceTask task) {
                return task._target.equals(_target) && task._ignoreMaterials == _ignoreMaterials;
            }
            return false;
        }

        @Override
        protected boolean isContainerOpen(AltoClef mod) {
            return (mod.getPlayer().containerMenu instanceof AbstractFurnaceMenu);
        }

        @Override
        protected Task onTick(AltoClef mod) {
            tryUpdateOpenFurnace(mod);

            // Slot transfers are a critical section: physical fuel temporarily
            // leaves inventory while it is held on the cursor. Resume the same
            // task before resource collection or other furnace planning can replace it.
            if (_pendingTransferSpec != null) {
                if (!isContainerOpen(mod)) {
                    _pendingTransferTask = null;
                    setDebugState("Reopening furnace to finish slot transfer");
                    return super.onTick(mod);
                }
                PendingFurnaceSlotTransferTask transfer = getPendingTransferTask(mod);
                if (transfer.isFinished(mod)) {
                    _pendingTransferSpec = null;
                    _pendingTransferTask = null;
                } else {
                    setDebugState("Finishing " + (_pendingTransferSpec.fuel() ? "fuel" : "material") + " transfer");
                    return transfer;
                }
            }

            // Include both regular + optional items
            ItemTarget materialTarget = _allMaterials;
            ItemTarget outputTarget = _target.getItem();
            // Materials needed = (mat_target (- 0*mat_in_inventory) - out_in_inventory - mat_in_furnace - out_in_furnace)
            // ^ 0 * mat_in_inventory because we always care aobut the TARGET materials, not how many LEFT there are.
            int materialsNeeded = materialTarget.getTargetCount()
                    /*- mod.getItemStorage().getItemCountInventoryOnly(materialTarget.getMatches())*/ // See comment above
                    - mod.getItemStorage().getItemCountInventoryOnly(outputTarget.getMatches())
                    - (materialTarget.matches(_furnaceCache.materialSlot.getItem()) ? _furnaceCache.materialSlot.getCount() : 0)
                    - (outputTarget.matches(_furnaceCache.outputSlot.getItem()) ? _furnaceCache.outputSlot.getCount() : 0);
            double totalFuelInFurnace = ItemHelper.getFuelAmount(_furnaceCache.fuelSlot) + _furnaceCache.burningFuelCount + _furnaceCache.burnPercentage;
            // Fuel needed = (mat_target - out_in_inventory - out_in_furnace - totalFuelInFurnace)
            double fuelNeeded = _ignoreMaterials
                        ? Math.min(materialTarget.matches(_furnaceCache.materialSlot.getItem()) ? _furnaceCache.materialSlot.getCount() : 0, materialTarget.getTargetCount())
                        : materialTarget.getTargetCount()
                    /* - mod.getItemStorage().getItemCountInventoryOnly(materialTarget.getMatches()) */
                    - mod.getItemStorage().getItemCountInventoryOnly(outputTarget.getMatches())
                    - (outputTarget.matches(_furnaceCache.outputSlot.getItem()) ? _furnaceCache.outputSlot.getCount() : 0)
                    - totalFuelInFurnace;

            // We don't have enough materials...
            if (mod.getItemStorage().getItemCountInventoryOnly(materialTarget.getMatches()) < materialsNeeded) {
                setDebugState("Getting Materials");
                return getMaterialTask(_target.getMaterial());
            }

            // Establish the furnace before recursively collecting fuel. A fuel route can
            // itself need a pickaxe and crafting table; asking for fuel first lets that
            // nested crafting work continually preempt container acquisition.
            boolean furnaceOpen = isContainerOpen(mod);
            if (furnaceOpen) _furnaceAlreadyAcquired = true;
            if (shouldAcquireFurnace(furnaceOpen, _furnaceAlreadyAcquired)) {
                return super.onTick(mod);
            }

            // We don't have enough fuel...
            if (shouldCollectFuelNow(_furnaceAlreadyAcquired,
                    CollectFuelTask.getAccessibleInventoryFuelCapacity(mod), fuelNeeded,
                    false)) {
                setDebugState("Getting Fuel");
                return new CollectFuelTask(fuelNeeded);
            }

            // Make sure our materials are accessible in our inventory
            if (StorageHelper.isItemInaccessibleToContainer(mod, _allMaterials)) {
                return new MoveInaccessibleItemToInventoryTask(_allMaterials);
            }

           // Make sure we have room for the output in our inventory
            EnsureFreeInventorySlotTask _freeInventoryTask = new EnsureFreeInventorySlotTask();
            if (_freeInventoryTask.isActive() && !_freeInventoryTask.isFinished(mod) && !mod.getItemStorage().hasEmptyInventorySlot()) {
                setDebugState("Freeing inventory.");
                return _freeInventoryTask;
            }
            // We have fuel and materials and there is a free space in the inventory. Get to our container and smelt!
            return super.onTick(mod);
        }

        // Override this if our materials must be acquired in a special way.
        // virtual
        protected Task getMaterialTask(ItemTarget target) {
            return TaskCatalogue.getItemTask(target);
        }

        @Override
        protected Task containerSubTask(AltoClef mod) {
            // We have appropriate materials/fuel.
            /*
             * - If output slot has something, receive it.
             * - Calculate needed material input. If we don't have, put it in.
             * - Calculate needed fuel input. If we don't have, put it in.
             * - Wait lol
             */
            ItemStack output   = StorageHelper.getItemStackInSlot(FurnaceSlot.OUTPUT_SLOT);
            ItemStack material = StorageHelper.getItemStackInSlot(FurnaceSlot.INPUT_SLOT_MATERIALS);
            ItemStack fuel     = StorageHelper.getItemStackInSlot(FurnaceSlot.INPUT_SLOT_FUEL);

            if (_pendingTransferSpec != null) {
                setDebugState("Resuming furnace slot transfer");
                return getPendingTransferTask(mod);
            }

            // Receive from output if present
            if (!output.isEmpty()) {
                setDebugState("Receiving Output");
                // Ensure our cursor is empty/can receive our item
                ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
                if (!ItemHelper.canStackTogether(output, cursor)) {
                    Optional<Slot> toFit = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursor, false).or(() -> StorageHelper.getGarbageSlot(mod));
                    if (toFit.isPresent()) {
                        return new ClickSlotTask(toFit.get());
                    } else {
                        // Eh screw it
                        return new ThrowCursorTask();
                    }
                }
                // Pick up
                return new ClickSlotTask(FurnaceSlot.OUTPUT_SLOT, ContainerInput.PICKUP);
                // return new MoveItemToSlotTask(new ItemTarget(output.getItem(), output.getCount()), toMoveTo.get(), mod -> FurnaceSlot.OUTPUT_SLOT);
            }

            // Fill in input if needed
            // Materials needed in slot = (mat_target - out_in_inventory - out_in_furnace)
            ItemTarget materialTarget = _allMaterials;

            int neededMaterialsInSlot = materialTarget.getTargetCount()
                    - mod.getItemStorage().getItemCountInventoryOnly(_target.getItem().getMatches())
                    - (_target.getItem().matches(output.getItem()) ? output.getCount() : 0);
            // We don't have the right material or we need more
            if (!_allMaterials.matches(material.getItem()) || neededMaterialsInSlot > material.getCount()) {
                setDebugState("Moving Materials");
                _pendingTransferSpec = new FurnaceTransferSpec(
                        materialSlotTransferTarget(materialTarget, neededMaterialsInSlot),
                        FurnaceSlot.INPUT_SLOT_MATERIALS, _target.getItem(), false);
                return getPendingTransferTask(mod);
            }

            /*
            double currentFuel = _ignoreMaterials
                    ? (Math.min(materialTarget.matches(_furnaceCache.materialSlot.getItem()) ? _furnaceCache.materialSlot.getCount() : 0, materialTarget.getTargetCount())
                    : materialTarget.getTargetCount()
                    - mod.getItemStorage().getItemCountInventoryOnly(materialTarget.getMatches())
                    - mod.getItemStorage().getItemCountInventoryOnly(outputTarget.getMatches())
                    - (outputTarget.matches(_furnaceCache.outputSlot.getItem()) ? _furnaceCache.outputSlot.getCount() : 0)
                    - totalFuelInFurnace;
             */
            // Fill in fuel if needed
            if (fuel.isEmpty() || !ItemHelper.isFuel(fuel.getItem())) {
                double currentlyCached = StorageHelper.getFurnaceFuel() + StorageHelper.getFurnaceCookPercent();
                double needs = material.getCount() - currentlyCached;
                if (needs > 0) {
                    // Get best fuel to fill
                    double closestDelta = Double.NEGATIVE_INFINITY;
                    ItemStack bestStack = null;
                    for (ItemStack stack : mod.getItemStorage().getItemStacksPlayerInventory(true)) {
                        if (mod.getModSettings().isSupportedFuel(stack.getItem())) {
                            double fuelAmount = ItemHelper.getFuelAmount(stack.getItem()) * stack.getCount();
                            double delta = needs - fuelAmount;
                            if (
                                    (bestStack == null) ||
                                    // If our best is above, prioritize lower values
                                    (closestDelta > 0 && delta < closestDelta) ||
                                    // If our best is below, prioritize higher below values
                                    (closestDelta < 0 && delta < 0 && delta > closestDelta)
                            ) {
                                bestStack = stack;
                                closestDelta = delta;
                            }
                        }
                    }
                    if (bestStack != null) {
                        setDebugState("Filling fuel");
                        _pendingTransferSpec = new FurnaceTransferSpec(
                                new ItemTarget(bestStack.getItem(), bestStack.getCount()),
                                FurnaceSlot.INPUT_SLOT_FUEL, _target.getItem(), true);
                        return getPendingTransferTask(mod);
                    }
                }
            }

            setDebugState("Waiting...");
            return null;
        }

        @Override
        protected double getCostToMakeNew(AltoClef mod) {
            if (_furnaceCache != null) {
                // TODO: If we're already smelting, get cost for materials (for now just set to really high number or something)
                return 9999999;
            }
            // We got stone
            if (mod.getItemStorage().getItemCount(Items.COBBLESTONE) > 8) {
                double cost = 100 - (90 * (double) mod.getItemStorage().getItemCount(Items.COBBLESTONE) / 8);
                return Math.max(cost, 10);
            }
            // We got pick
            if (StorageHelper.miningRequirementMetInventory(mod, MiningRequirement.WOOD)) {
                return 50;
            }
            // We gotta make pick and mine stone
            return 100;
        }

        @Override
        protected BlockPos overrideContainerPosition(AltoClef mod) {
            // If we have a valid container position, KEEP it.
            return getTargetContainerPosition();
        }

        private void tryUpdateOpenFurnace(AltoClef mod) {
            if (isContainerOpen(mod)) {
                // Update current furnace cache
                _furnaceCache.burnPercentage = StorageHelper.getFurnaceCookPercent();
                _furnaceCache.burningFuelCount = StorageHelper.getFurnaceFuel();
                _furnaceCache.fuelSlot = StorageHelper.getItemStackInSlot(FurnaceSlot.INPUT_SLOT_FUEL);
                _furnaceCache.materialSlot = StorageHelper.getItemStackInSlot(FurnaceSlot.INPUT_SLOT_MATERIALS);
                _furnaceCache.outputSlot = StorageHelper.getItemStackInSlot(FurnaceSlot.OUTPUT_SLOT);
            }
        }

        private PendingFurnaceSlotTransferTask getPendingTransferTask(AltoClef mod) {
            int menuId = mod.getPlayer().containerMenu.containerId;
            if (_pendingTransferTask == null || _pendingTransferTask.getMenuId() != menuId) {
                _pendingTransferTask = new PendingFurnaceSlotTransferTask(_pendingTransferSpec, menuId);
            }
            return _pendingTransferTask;
        }

        private record FurnaceTransferSpec(ItemTarget target, Slot destination,
                                           ItemTarget producedOutput, boolean fuel,
                                           TransferProgress progress) {
            private FurnaceTransferSpec(ItemTarget target, Slot destination,
                                        ItemTarget producedOutput, boolean fuel) {
                this(target, destination, producedOutput, fuel, new TransferProgress());
            }
        }

        private static final class TransferProgress {
            private boolean snapshotInitialized;
            private int baselineInventoryCount;
            private int initialCursorCount;
            private int initialDestinationCount;
            private int requiredItemCount;
            private int baselineOutputCount;
            private double baselineCookProgress;
            private double baselineFuelCapacity;
            private double lastFuelCapacity;
            private double maxDestinationReceipt;
        }

        private final class PendingFurnaceSlotTransferTask extends MoveItemToSlotFromInventoryTask {
            private final FurnaceTransferSpec _spec;
            private final int _menuId;
            private final TransferProgress _progress;

            private PendingFurnaceSlotTransferTask(FurnaceTransferSpec spec, int menuId) {
                super(spec.target(), spec.destination());
                _spec = spec;
                _menuId = menuId;
                _progress = spec.progress();
            }

            private int getMenuId() {
                return _menuId;
            }

            @Override
            protected boolean isEqual(Task other) {
                return other instanceof PendingFurnaceSlotTransferTask task
                        && task._menuId == _menuId && task._spec.equals(_spec);
            }

            @Override
            protected void onStart(AltoClef mod) {
                super.onStart(mod);
                if (_progress.snapshotInitialized) return;
                _progress.snapshotInitialized = true;
                ItemStack destination = StorageHelper.getItemStackInSlot(_spec.destination());
                _progress.initialDestinationCount = _spec.target().matches(destination.getItem()) ? destination.getCount() : 0;
                _progress.requiredItemCount = Math.max(0, _spec.target().getTargetCount() - _progress.initialDestinationCount);
                _progress.baselineInventoryCount = StorageHelper.getAccessibleInventoryItemCount(mod, _spec.target());
                ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
                _progress.initialCursorCount = _spec.target().matches(cursor.getItem()) ? cursor.getCount() : 0;
                _progress.baselineFuelCapacity = getFuelCapacityInFurnace(destination)
                        + Math.max(0, StorageHelper.getFurnaceFuel());
                _progress.baselineOutputCount = countProducedOutput(mod);
                _progress.baselineCookProgress = StorageHelper.getFurnaceCookPercent();
                _progress.baselineFuelCapacity += _progress.baselineOutputCount + _progress.baselineCookProgress;
                _progress.lastFuelCapacity = _progress.baselineFuelCapacity;
            }

            @Override
            protected Task onTick(AltoClef mod) {
                observeDestinationReceipt(mod);
                if (hasReceivedExpectedTransfer(mod)) {
                    ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
                    if (!cursor.isEmpty()) {
                        Optional<Slot> toMove = mod.getItemStorage()
                                .getSlotThatCanFitInPlayerInventory(cursor, false)
                                .or(() -> StorageHelper.getGarbageSlot(mod));
                        if (toMove.isPresent()) return new ClickSlotTask(toMove.get());
                        return new EnsureFreeInventorySlotTask();
                    }
                }
                return super.onTick(mod);
            }

            @Override
            public boolean isFinished(AltoClef mod) {
                if (!_progress.snapshotInitialized || mod.getPlayer() == null
                        || mod.getPlayer().containerMenu.containerId != _menuId
                        || !isContainerOpen(mod)) return false;
                observeDestinationReceipt(mod);
                if (!StorageHelper.getItemStackInCursorSlot().isEmpty()) return false;
                return hasReceivedExpectedTransfer(mod);
            }

            private void observeDestinationReceipt(AltoClef mod) {
                if (!_progress.snapshotInitialized || mod.getPlayer() == null
                        || mod.getPlayer().containerMenu.containerId != _menuId
                        || !isContainerOpen(mod)) return;
                if (_spec.fuel()) {
                    ItemStack fuel = StorageHelper.getItemStackInSlot(_spec.destination());
                    double currentCapacity = getFuelCapacityInFurnace(fuel)
                            + Math.max(0, StorageHelper.getFurnaceFuel());
                    int currentOutputCount = countProducedOutput(mod);
                    double currentTotalCapacity = currentCapacity + currentOutputCount
                            + StorageHelper.getFurnaceCookPercent();
                    _progress.maxDestinationReceipt += observedFuelTransferReceipt(
                            _progress.lastFuelCapacity, currentTotalCapacity, 0, 0, 0, 0);
                    _progress.lastFuelCapacity = currentTotalCapacity;
                } else {
                    ItemStack input = StorageHelper.getItemStackInSlot(_spec.destination());
                    int inputCount = _spec.target().matches(input.getItem()) ? input.getCount() : 0;
                    _progress.maxDestinationReceipt = Math.max(_progress.maxDestinationReceipt,
                            materialTransferReceipt(_progress.initialDestinationCount, inputCount,
                                    _progress.baselineOutputCount, countProducedOutput(mod)));
                }
            }

            private boolean hasReceivedExpectedTransfer(AltoClef mod) {
                if (_progress.requiredItemCount == 0) {
                    ItemStack destination = StorageHelper.getItemStackInSlot(_spec.destination());
                    return _spec.target().matches(destination.getItem())
                            && destination.getCount() >= _spec.target().getTargetCount();
                }
                int currentInventoryCount = StorageHelper.getAccessibleInventoryItemCount(mod, _spec.target());
                ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
                int currentCursorCount = _spec.target().matches(cursor.getItem()) ? cursor.getCount() : 0;
                int sourceItemsRemoved = Math.max(0, _progress.baselineInventoryCount - currentInventoryCount)
                        + Math.max(0, _progress.initialCursorCount - currentCursorCount);
                double requiredReceipt = _spec.fuel()
                        ? _progress.requiredItemCount * ItemHelper.getFuelAmount(_spec.target().getMatches()[0])
                        : _progress.requiredItemCount;
                // Receipt and source removal determine whether excess cursor items can be
                // returned. Cursor emptiness is a separate final completion condition.
                return SmeltInFurnaceTask.hasReceivedExpectedTransfer(_progress.requiredItemCount, sourceItemsRemoved,
                        requiredReceipt, _progress.maxDestinationReceipt);
            }

            private int countProducedOutput(AltoClef mod) {
                if (_spec.producedOutput() == null) return 0;
                int count = mod.getItemStorage().getItemCountInventoryOnly(_spec.producedOutput().getMatches());
                ItemStack output = StorageHelper.getItemStackInSlot(FurnaceSlot.OUTPUT_SLOT);
                if (_spec.producedOutput().matches(output.getItem())) count += output.getCount();
                return count;
            }

            private double getFuelCapacityInFurnace(ItemStack fuel) {
                return fuel.isEmpty() ? 0 : ItemHelper.getFuelAmount(fuel.getItem()) * fuel.getCount();
            }
        }

    }

    static class FurnaceCache {
        public ItemStack materialSlot = ItemStack.EMPTY;
        public ItemStack fuelSlot = ItemStack.EMPTY;
        public ItemStack outputSlot = ItemStack.EMPTY;
        public double burningFuelCount;
        public double burnPercentage;
    }
}
