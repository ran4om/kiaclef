package adris.altoclef.tasks.resources;

import adris.altoclef.testing.MinecraftTestBootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CollectAnyItemTaskTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    void candidateSelectionPrefersAnExistingVariant() {
        Item selected = CollectAnyItemTask.chooseCandidate(
                new Item[]{Items.OAK_PLANKS, Items.BIRCH_PLANKS},
                item -> item == Items.BIRCH_PLANKS ? 2 : 0);

        assertEquals(Items.BIRCH_PLANKS, selected);
    }

    @Test
    void candidateSelectionUsesTargetOrderForInventoryTies() {
        Item selected = CollectAnyItemTask.chooseCandidate(
                new Item[]{Items.OAK_PLANKS, Items.BIRCH_PLANKS}, item -> 1);

        assertEquals(Items.OAK_PLANKS, selected);
    }
}
