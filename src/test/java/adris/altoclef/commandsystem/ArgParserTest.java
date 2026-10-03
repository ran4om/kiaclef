package adris.altoclef.commandsystem;

import adris.altoclef.testing.MinecraftTestBootstrap;
import adris.altoclef.util.ItemTarget;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ArgParserTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    void acceptsAnItemRemainderAfterSixFixedArguments() throws Exception {
        ArgParser parser = stashParser("@stash -2 63 8 12 70 19 emerald 5");

        assertArrayEquals(new int[]{-2, 63, 8, 12, 70, 19}, readCoordinates(parser));
        ItemList items = parser.get(ItemList.class);
        assertEquals(1, items.items.length);
        assertEquals("emerald", items.items[0].getCatalogueName());
        assertEquals(5, items.items[0].getTargetCount());
    }

    @Test
    void preservesBracketedListsInTheTrailingRemainder() throws Exception {
        ArgParser parser = stashParser("@stash 1 2 3 4 5 6 [emerald 5, stick 16]");

        assertArrayEquals(new int[]{1, 2, 3, 4, 5, 6}, readCoordinates(parser));
        ItemList items = parser.get(ItemList.class);
        assertEquals(2, items.items.length);
        assertEquals(5, targetCount(items, "emerald"));
        assertEquals(16, targetCount(items, "stick"));
    }

    @Test
    void keepsTheDefaultForExactlyTheFixedArguments() throws Exception {
        ArgParser parser = stashParser("@stash 1 2 3 4 5 6");

        assertArrayEquals(new int[]{1, 2, 3, 4, 5, 6}, readCoordinates(parser));
        assertNull(parser.get(ItemList.class));
    }

    @Test
    void stillRejectsOverflowWhenEveryArgumentIsFixed() throws Exception {
        ArgParser parser = new ArgParser(
                new Arg<>(Integer.class, "x"),
                new Arg<>(Integer.class, "y"));
        parser.loadArgs("@fixed 1 2 3", true);

        CommandException error = assertThrows(CommandException.class, () -> parser.get(Integer.class));
        assertTrue(error.getMessage().contains("Too many arguments"));
    }

    private static ArgParser stashParser(String line) throws CommandException {
        ArgParser parser = new ArgParser(
                new Arg<>(Integer.class, "x_start"),
                new Arg<>(Integer.class, "y_start"),
                new Arg<>(Integer.class, "z_start"),
                new Arg<>(Integer.class, "x_end"),
                new Arg<>(Integer.class, "y_end"),
                new Arg<>(Integer.class, "z_end"),
                new Arg<>(ItemList.class, "items", null, 6, false));
        parser.loadArgs(line, true);
        return parser;
    }

    private static int[] readCoordinates(ArgParser parser) throws CommandException {
        return new int[]{parser.get(Integer.class), parser.get(Integer.class), parser.get(Integer.class),
                parser.get(Integer.class), parser.get(Integer.class), parser.get(Integer.class)};
    }

    private static int targetCount(ItemList items, String name) {
        for (ItemTarget target : items.items) {
            if (name.equals(target.getCatalogueName())) return target.getTargetCount();
        }
        fail("Missing item target: " + name);
        return -1;
    }
}
