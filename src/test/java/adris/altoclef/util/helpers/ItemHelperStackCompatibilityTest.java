package adris.altoclef.util.helpers;

import adris.altoclef.testing.MinecraftTestBootstrap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ItemHelperStackCompatibilityTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    void allowsStacksWithMatchingItemAndComponentsToMerge() {
        ItemStack from = new ItemStack(Items.DIAMOND, 1);
        ItemStack to = new ItemStack(Items.DIAMOND, 1);

        assertTrue(ItemHelper.canStackTogether(from, to));
    }

    @Test
    void rejectsStacksWithDifferentComponents() {
        ItemStack plain = new ItemStack(Items.DIAMOND, 1);
        ItemStack named = new ItemStack(Items.DIAMOND, 1);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Named diamond"));

        assertFalse(ItemHelper.canStackTogether(plain, named));
    }

    @Test
    void permitsStacksToReachCapacityButNotExceedIt() {
        ItemStack from = new ItemStack(Items.DIAMOND, 32);
        ItemStack to = new ItemStack(Items.DIAMOND, 32);

        assertTrue(ItemHelper.canStackTogether(from, to));
        assertFalse(ItemHelper.canStackTogether(new ItemStack(Items.DIAMOND, 33), to));
        assertTrue(ItemHelper.canStackTogether(new ItemStack(Items.DIAMOND, 64), ItemStack.EMPTY));
        assertFalse(ItemHelper.canStackTogether(new ItemStack(Items.DIAMOND, 65), ItemStack.EMPTY));
    }
}
