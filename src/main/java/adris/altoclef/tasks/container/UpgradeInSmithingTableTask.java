package adris.altoclef.tasks.container;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.slot.ClickSlotTask;
import adris.altoclef.tasks.slot.EnsureFreeInventorySlotTask;
import adris.altoclef.tasks.slot.MoveItemToSlotFromInventoryTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.time.TimerGame;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.slots.Slot;
import adris.altoclef.util.slots.SmithingTableSlot;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.inventory.ContainerInput;

public class UpgradeInSmithingTableTask extends ResourceTask {

    private final ItemTarget _template;
    private final ItemTarget _tool;
    private final ItemTarget _material;
    private final ItemTarget _output;

    private final Task _innerTask;

    public UpgradeInSmithingTableTask(ItemTarget template, ItemTarget tool, ItemTarget material, ItemTarget output) {
        super(output);
        // Netherite upgrade templates are consumed. Keep one seed after this upgrade
        // so later smithing tasks can reuse it without looting another Bastion.
        _template = new ItemTarget(template, output.getTargetCount() + 1);
        _tool = new ItemTarget(tool, output.getTargetCount());
        _material = new ItemTarget(material, output.getTargetCount());
        _output = output;
        _innerTask = new UpgradeInSmithingTableInternalTask();
    }

    /** Compatibility constructor for callers that upgrade using the current vanilla netherite template. */
    public UpgradeInSmithingTableTask(ItemTarget tool, ItemTarget material, ItemTarget output) {
        this(new ItemTarget(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, output.getTargetCount()),
                tool, material, output);
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
        mod.getBehaviour().push();
        mod.getBehaviour().markSlotAsConversionSlot(SmithingTableSlot.INPUT_SLOT_TEMPLATE, stack -> _template.matches(stack.getItem()));
        mod.getBehaviour().markSlotAsConversionSlot(SmithingTableSlot.INPUT_SLOT_TOOL, stack -> _tool.matches(stack.getItem()));
        mod.getBehaviour().markSlotAsConversionSlot(SmithingTableSlot.INPUT_SLOT_MATERIALS, stack -> _material.matches(stack.getItem()));
    }

    private int getItemsInSlot(AltoClef mod, Slot slot, ItemTarget match) {
        ItemStack stack = StorageHelper.getItemStackInSlot(slot);
        if (!stack.isEmpty() && match.matches(stack.getItem())) {
            return stack.getCount();
        }
        return 0;
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        // if we don't have tools + materials, get them.

        boolean inSmithingTable = (mod.getPlayer().containerMenu instanceof SmithingMenu);

        int templatesInSlot = inSmithingTable ? getItemsInSlot(mod, SmithingTableSlot.INPUT_SLOT_TEMPLATE, _template) : 0;
        int materialsInSlot = inSmithingTable ? getItemsInSlot(mod, SmithingTableSlot.INPUT_SLOT_MATERIALS, _material) : 0;
        int toolsInSlot = inSmithingTable ? getItemsInSlot(mod, SmithingTableSlot.INPUT_SLOT_TOOL, _tool) : 0;
        int ouputInSlot = inSmithingTable ? getItemsInSlot(mod, SmithingTableSlot.OUTPUT_SLOT, _output) : 0;

        int desiredOutput = getRemainingUpgradeCount(_output.getTargetCount(),
                StorageHelper.getAccessibleInventoryItemCount(mod, _output), ouputInSlot);

        if (mod.getItemStorage().getItemCountInventoryOnly(_template.getMatches()) + templatesInSlot < desiredOutput + 1 ||
                mod.getItemStorage().getItemCountInventoryOnly(_tool.getMatches()) + toolsInSlot < desiredOutput ||
                mod.getItemStorage().getItemCountInventoryOnly(_material.getMatches()) + materialsInSlot < desiredOutput) {
            setDebugState("Getting smithing templates, base items, and materials");
            return TaskCatalogue.getSquashedItemTask(new ItemTarget(_template, desiredOutput + 1),
                    new ItemTarget(_tool, desiredOutput), new ItemTarget(_material, desiredOutput));
        }

        // Edge case: We are wearing the armor we want to upgrade. If so, remove it.
        if (StorageHelper.isArmorEquipped(mod, _tool.getMatches())) {
            // Exit out of any screen so we can move our armor
            if (!(mod.getPlayer().containerMenu instanceof InventoryMenu)) {
                StorageHelper.closeScreen();
                setDebugState("Quickly removing equipped armor");
                return null;
            }
            // Take off our armor
            if (!mod.getItemStorage().hasEmptyInventorySlot()) {
                return new EnsureFreeInventorySlotTask();
            }
            for (Slot armorSlot : PlayerSlot.ARMOR_SLOTS) {
                if (_tool.matches(StorageHelper.getItemStackInSlot(armorSlot).getItem())) {
                    setDebugState("Quickly removing equipped armor");
                    return new ClickSlotTask(armorSlot, 0, ContainerInput.QUICK_MOVE);
                }
            }
        }

        setDebugState("Smithing...");
        return _innerTask;
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
        if (mod.getPlayer() != null && mod.getPlayer().containerMenu instanceof SmithingMenu) {
            StorageHelper.closeScreen();
        }
        mod.getBehaviour().pop();
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        if (other instanceof UpgradeInSmithingTableTask task) {
            return task._template.equals(_template) && task._tool.equals(_tool)
                    && task._output.equals(_output) && task._material.equals(_material);
        }
        return false;
    }

    @Override
    protected String toDebugStringName() {
        return "Upgrading " + _template + " + " + _tool + " + " + _material + " -> " + _output;
    }

    private class UpgradeInSmithingTableInternalTask extends DoStuffInContainerTask {

        private final TimerGame _invTimer;

        public UpgradeInSmithingTableInternalTask() {
            super(Blocks.SMITHING_TABLE, new ItemTarget("smithing_table"));
            _invTimer = new TimerGame(0);
        }

        @Override
        protected boolean isSubTaskEqual(DoStuffInContainerTask other) {
            // inner part, don't care
            return true;
        }

        @Override
        protected boolean isContainerOpen(AltoClef mod) {
            return (mod.getPlayer().containerMenu instanceof SmithingMenu);
        }

        @Override
        protected Task containerSubTask(AltoClef mod) {
            setDebugState("Smithing...");
            // We have our tools + materials. Now, do the thing.
            _invTimer.setInterval(mod.getModSettings().getContainerItemMoveDelay());

            // Run once every
            if (!_invTimer.elapsed()) {
                return null;
            }
            _invTimer.reset();

            Slot materialSlot = SmithingTableSlot.INPUT_SLOT_MATERIALS;
            Slot templateSlot = SmithingTableSlot.INPUT_SLOT_TEMPLATE;
            Slot toolSlot = SmithingTableSlot.INPUT_SLOT_TOOL;
            Slot outputSlot = SmithingTableSlot.OUTPUT_SLOT;

            ItemStack currentMaterials = StorageHelper.getItemStackInSlot(materialSlot);
            ItemStack currentTemplate = StorageHelper.getItemStackInSlot(templateSlot);
            ItemStack currentTools = StorageHelper.getItemStackInSlot(toolSlot);
            ItemStack currentOutput = StorageHelper.getItemStackInSlot(outputSlot);
            // Grab from output
            if (!currentOutput.isEmpty()) {
                return new ClickSlotTask(outputSlot, ContainerInput.QUICK_MOVE);
            }
            // Put template in slot
            if (currentTemplate.isEmpty() || !_template.matches(currentTemplate.getItem())) {
                return new MoveItemToSlotFromInventoryTask(new ItemTarget(_template, 1), templateSlot);
            }
            // Put materials in slot
            if (currentMaterials.isEmpty() || !_material.matches(currentMaterials.getItem())) {
                return new MoveItemToSlotFromInventoryTask(new ItemTarget(_material, 1), materialSlot);
            }
            // Put tool in slot
            if (currentTools.isEmpty() || !_tool.matches(currentTools.getItem())) {
                return new MoveItemToSlotFromInventoryTask(new ItemTarget(_tool, 1), toolSlot);
            }

            setDebugState("PROBLEM: Nothing to do!");
            return null;
        }

        @Override
        protected double getCostToMakeNew(AltoClef mod) {
            int price = 400;
            if (mod.getItemStorage().hasItem(ItemHelper.LOG) || mod.getItemStorage().getItemCount(ItemHelper.PLANKS) >= 4) {
                price -= 125;
            }
            if (mod.getItemStorage().getItemCount(Items.FLINT) >= 2) {
                price -= 125;
            }
            return price;
        }
    }

    public ItemTarget getTools() {
        return _tool;
    }

    public ItemTarget getTemplate() {
        return _template;
    }

    public ItemTarget getMaterials() {
        return _material;
    }

    static int getRemainingUpgradeCount(int target, int storedOutput, int readyOutput) {
        return Math.max(0, target - storedOutput - readyOutput);
    }

}
