package adris.altoclef.tasks.container;

import adris.altoclef.testing.MinecraftTestBootstrap;
import adris.altoclef.util.CraftingRecipe;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.slots.Slot;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CraftInTableTaskTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    void smallRecipeRegistersOnlyItsFourCorrectGridSlots() {
        CraftingRecipe recipe = CraftingRecipe.newShapedRecipe("small", new ItemTarget[]{
                new ItemTarget(Items.OAK_PLANKS), new ItemTarget(Items.OAK_PLANKS),
                new ItemTarget(Items.STICK), new ItemTarget(Items.STICK)
        }, 1);

        List<Integer> windowSlots = CraftInTableTask.getRecipeInputSlots(recipe).stream()
                .map(Slot::getWindowSlot)
                .toList();

        assertEquals(List.of(1, 2, 4, 5), windowSlots);
    }

    @Test
    void largeRecipeRegistersAllNineGridSlots() {
        ItemTarget ingredient = new ItemTarget(Items.OAK_PLANKS);
        CraftingRecipe recipe = CraftingRecipe.newShapedRecipe("large", new ItemTarget[]{
                ingredient, ingredient, ingredient,
                ingredient, ingredient, ingredient,
                ingredient, ingredient, ingredient
        }, 1);

        List<Integer> windowSlots = CraftInTableTask.getRecipeInputSlots(recipe).stream()
                .map(Slot::getWindowSlot)
                .toList();

        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9), windowSlots);
    }
}
