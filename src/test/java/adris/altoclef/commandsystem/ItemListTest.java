package adris.altoclef.commandsystem;

import adris.altoclef.testing.MinecraftTestBootstrap;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ItemListTest {
    @BeforeAll static void bootstrap() { MinecraftTestBootstrap.initialize(); }
    @Test void duplicatesAreAddedAndWhitespaceAccepted() throws Exception {
        var parsed = ItemList.parseRemainder("[diamond   2, iron_ingot 8, diamond 3]");
        assertEquals(2, parsed.items.length);
        for (var item : parsed.items) {
            assertEquals(item.matches(Items.DIAMOND) ? 5 : 8, item.getTargetCount());
        }
    }
    @Test void singleResourceParses() throws Exception {
        var parsed = ItemList.parseRemainder("diamond   3");
        assertEquals(1, parsed.items.length);
        assertTrue(parsed.items[0].matches(Items.DIAMOND));
        assertEquals(3, parsed.items[0].getTargetCount());
    }
    @Test void invalidRequestsHaveClearErrors() {
        assertThrows(CommandException.class, () -> ItemList.parseRemainder("diamond -1"));
        assertThrows(CommandException.class, () -> ItemList.parseRemainder("[diamond 0]"));
        assertThrows(CommandException.class, () -> ItemList.parseRemainder("[unknown_resource 1]"));
        assertThrows(CommandException.class, () -> ItemList.parseRemainder("[diamond nope]"));
    }
}
