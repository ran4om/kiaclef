package adris.altoclef.util.slots;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractFurnaceScreen;
import net.minecraft.client.gui.screens.inventory.BrewingStandScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.HopperScreen;
import net.minecraft.client.gui.screens.inventory.DispenserScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.client.gui.screens.inventory.SmithingScreen;
import net.minecraft.world.inventory.ChestMenu;
import adris.altoclef.util.Pair;
import org.apache.commons.lang3.NotImplementedException;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Predicate;

@SuppressWarnings("rawtypes")
public class SlotScreenMapping {

    // Order here matters as whoever returns "true" in the predicate first is picked.
    private static final List<SlotScreenMappingEntry> _classList = List.of(
            e(CraftingTableSlot.class, screen -> screen instanceof CraftingScreen, CraftingTableSlot::new),
            e(FurnaceSlot.class, screen -> screen instanceof AbstractFurnaceScreen, FurnaceSlot::new),
            e(SmithingTableSlot.class, screen -> screen instanceof SmithingScreen, SmithingTableSlot::new),
            e(BrewingStandSlot.class, screen -> screen instanceof BrewingStandScreen, BrewingStandSlot::new),
            e(ChestSlot.class, SlotScreenMapping::isChestScreen,
                    (slot, inventory) -> new ChestSlot(slot, ((ChestMenu) Minecraft.getInstance().player.containerMenu).getRowCount(), inventory)),
            e(ContainerSlot.class, screen -> screen instanceof HopperScreen || screen instanceof DispenserScreen || screen instanceof ShulkerBoxScreen,
                    (slot, inventory) -> new ContainerSlot(slot, Minecraft.getInstance().player.containerMenu.slots.size() - 36, inventory)),
            e(PlayerSlot.class, screen -> true, PlayerSlot::new), // Order matters, leave this BEFORE the BACK!
            e(CursorSlot.class, screen -> true, (slot, inv) -> CursorSlot.SLOT) // Order matters, leave this in the BACK!
    );

    @SuppressWarnings("unchecked")
    public static boolean isScreenOpen(Class slotType) {
        Screen screen = Minecraft.getInstance().gui.screen();
        for (SlotScreenMappingEntry entry : _classList) {
            if (slotType == entry.type || slotType.isAssignableFrom(entry.type)) {
                return entry.inScreen.test(screen);
            }
        }
        throw new NotImplementedException("Slot type class not registered in SlotScreenMapping: " + slotType + ". Please register! (current screen = " + screen + ")");
    }

    public static Slot getFromScreen(int slot, boolean inventory) {
        Screen screen = Minecraft.getInstance().gui.screen();
        for (SlotScreenMappingEntry entry : _classList) {
            if (entry.inScreen.test(screen)) {
                return entry.getSlot.apply(slot, inventory);
            }
        }
        throw new NotImplementedException("We should never get here, _classList should be filled with a predicate that always returns true at the bottom (for PlayerSlot & CursorSlot)");
    }


    private static SlotScreenMappingEntry e(Class type, Predicate<Screen> inScreen, BiFunction<Integer, Boolean, Slot> getSlot) {
        return new SlotScreenMappingEntry(type, inScreen, getSlot);
    }

    private static boolean isChestScreen(Screen screen) {
        return screen instanceof ContainerScreen
                && Minecraft.getInstance().player != null
                && Minecraft.getInstance().player.containerMenu instanceof ChestMenu;
    }

    static class SlotScreenMappingEntry {
        public Class type;
        public Predicate<Screen> inScreen;
        public BiFunction<Integer, Boolean, Slot> getSlot;
        public SlotScreenMappingEntry(Class type, Predicate<Screen> inScreen, BiFunction<Integer, Boolean, Slot> getSlot) {
            this.type = type;
            this.inScreen = inScreen;
            this.getSlot = getSlot;
        }
    }
}
