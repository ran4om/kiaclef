package adris.altoclef.util.slots;

import net.minecraft.world.inventory.SmithingMenu;

public class SmithingTableSlot extends Slot {

    public static final SmithingTableSlot INPUT_SLOT_TEMPLATE = new SmithingTableSlot(SmithingMenu.TEMPLATE_SLOT);
    public static final SmithingTableSlot INPUT_SLOT_TOOL = new SmithingTableSlot(SmithingMenu.BASE_SLOT);
    public static final SmithingTableSlot INPUT_SLOT_MATERIALS = new SmithingTableSlot(SmithingMenu.ADDITIONAL_SLOT);
    public static final SmithingTableSlot OUTPUT_SLOT = new SmithingTableSlot(SmithingMenu.RESULT_SLOT);

    public SmithingTableSlot(int slot) {
        this(slot, false);
    }
    SmithingTableSlot(int slot, boolean inventory) {
        super(slot, inventory);
    }
    @Override
    public int inventorySlotToWindowSlot(int inventorySlot) {
        if (inventorySlot < 9) {
            return inventorySlot + 31;
        }
        return inventorySlot - 5;
    }

    @Override
    protected int windowSlotToInventorySlot(int windowSlot) {
        if (windowSlot >= 31) {
            return windowSlot - 31;
        }
        return windowSlot + 5;
    }

    @Override
    protected String getName() {
        return "Smithing Table";
    }
}
