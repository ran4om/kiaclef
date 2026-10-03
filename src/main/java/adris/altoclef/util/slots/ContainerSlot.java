package adris.altoclef.util.slots;

/** Slot mapping for storage menus followed by the 27 main and 9 hotbar player slots. */
public final class ContainerSlot extends Slot {
    private final int containerSlots;

    public ContainerSlot(int slot, int containerSlots, boolean inventory) {
        super(slot, inventory);
        if (containerSlots < 1) throw new IllegalArgumentException("Container must have at least one slot");
        this.containerSlots = containerSlots;
    }
    @Override public int inventorySlotToWindowSlot(int inventorySlot) {
        return inventorySlot < 9 ? containerSlots + 27 + inventorySlot : containerSlots + inventorySlot - 9;
    }
    @Override protected int windowSlotToInventorySlot(int windowSlot) {
        return windowSlot >= containerSlots + 27 ? windowSlot - containerSlots - 27 : windowSlot - containerSlots + 9;
    }
    @Override protected String getName() { return "Container"; }
}
