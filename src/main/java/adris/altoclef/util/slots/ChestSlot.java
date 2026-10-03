package adris.altoclef.util.slots;

public class ChestSlot extends Slot {

    private final int _containerSlots;

    public ChestSlot(int slot, boolean big) {
        this(slot, big, false);
    }

    public ChestSlot(int slot, boolean big, boolean inventory) {
        this(slot, big ? 6 : 3, inventory);
    }

    public ChestSlot(int slot, int rows, boolean inventory) {
        super(slot, inventory);
        if (rows < 1 || rows > 6) throw new IllegalArgumentException("Chest rows must be between 1 and 6");
        _containerSlots = rows * 9;
    }

    @Override
    public int inventorySlotToWindowSlot(int inventorySlot) {
        if (inventorySlot < 9) {
            return inventorySlot + (_containerSlots + 27);
        }
        return (inventorySlot - 9) + _containerSlots;
    }

    @Override
    protected int windowSlotToInventorySlot(int windowSlot) {
        int bottomStart = (_containerSlots + 27);
        if (windowSlot >= bottomStart) {
            return windowSlot - bottomStart;
        }
        return (windowSlot + 9) - _containerSlots;
    }

    @Override
    protected String getName() {
        return "Chest";
    }
}
