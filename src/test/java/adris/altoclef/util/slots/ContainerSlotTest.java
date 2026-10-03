package adris.altoclef.util.slots;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContainerSlotTest {
    @Test void smallRecipeMapsToTopLeftTwoByTwoTableInputs() {
        int[] expected = {1, 2, 4, 5};
        for (int index = 0; index < expected.length; index++) {
            assertEquals(expected[index], CraftingTableSlot.getInputSlot(index, false).getWindowSlot());
        }
        for (int index = 0; index < 9; index++) {
            assertEquals(index + 1, CraftingTableSlot.getInputSlot(index, true).getWindowSlot());
        }
    }
    @Test void chestInventoryMappingsRoundTripForEveryRowCount() {
        for (int rows = 1; rows <= 6; rows++) {
            for (int inv = 0; inv < 36; inv++) {
                ChestSlot fromInventory = new ChestSlot(inv, rows, true);
                int expectedWindow = inv < 9 ? rows * 9 + 27 + inv : rows * 9 + inv - 9;
                assertEquals(expectedWindow, fromInventory.getWindowSlot());
                ChestSlot fromWindow = new ChestSlot(expectedWindow, rows, false);
                assertEquals(inv, fromWindow.getInventorySlot());
            }
        }
    }
    @Test void hopperDispenserAndShulkerInventoryMappingsRoundTrip() {
        for (int count : new int[]{5, 9, 27}) {
            for (int inv = 0; inv < 36; inv++) {
                ContainerSlot slot = new ContainerSlot(inv, count, true);
                int expected = inv < 9 ? count + 27 + inv : count + inv - 9;
                assertEquals(expected, slot.getWindowSlot());
                assertEquals(inv, new ContainerSlot(expected, count, false).getInventorySlot());
            }
        }
    }
    @Test void modernSmithingHasTemplateBaseAdditionAndResult() {
        assertEquals(0, SmithingTableSlot.INPUT_SLOT_TEMPLATE.getWindowSlot());
        assertEquals(1, SmithingTableSlot.INPUT_SLOT_TOOL.getWindowSlot());
        assertEquals(2, SmithingTableSlot.INPUT_SLOT_MATERIALS.getWindowSlot());
        assertEquals(3, SmithingTableSlot.OUTPUT_SLOT.getWindowSlot());
        for (int inv = 0; inv < 36; inv++) {
            SmithingTableSlot mapped = new SmithingTableSlot(inv, true);
            assertEquals(inv, new SmithingTableSlot(mapped.getWindowSlot(), false).getInventorySlot());
        }
    }
}
