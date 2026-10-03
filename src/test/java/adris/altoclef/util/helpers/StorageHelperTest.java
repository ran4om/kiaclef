package adris.altoclef.util.helpers;

import adris.altoclef.testing.MinecraftTestBootstrap;
import adris.altoclef.util.ItemTarget;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageHelperTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    void accessibleCountExcludesCursorAndPlayerCraftingGrid() {
        ItemTarget planks = new ItemTarget(Items.OAK_PLANKS, 5);

        int accessibleCount = StorageHelper.getAccessibleInventoryItemCount(10, planks,
                new ItemStack(Items.OAK_PLANKS, 2),
                new ItemStack(Items.OAK_PLANKS, 3),
                new ItemStack(Items.DIAMOND, 1));

        assertEquals(5, accessibleCount);
    }

    @Test
    void accessibleCountDoesNotGoNegativeWhenAllItemsAreInCraftingGrid() {
        ItemTarget planks = new ItemTarget(Items.OAK_PLANKS, 1);

        int accessibleCount = StorageHelper.getAccessibleInventoryItemCount(2, planks,
                ItemStack.EMPTY,
                new ItemStack(Items.OAK_PLANKS, 2));

        assertEquals(0, accessibleCount);
    }

    @Test
    void unrelatedCursorAndCraftingItemsDoNotReduceCount() {
        ItemTarget planks = new ItemTarget(Items.OAK_PLANKS, 4);

        int accessibleCount = StorageHelper.getAccessibleInventoryItemCount(4, planks,
                new ItemStack(Items.DIAMOND, 1),
                new ItemStack(Items.STICK, 1));

        assertEquals(4, accessibleCount);
    }

    @Test
    void inaccessibleCleanupPreservesOnlyActiveConversionIngredients() {
        assertFalse(StorageHelper.shouldMoveInaccessibleSlot(true, true));
        assertTrue(StorageHelper.shouldMoveInaccessibleSlot(true, false));
        assertFalse(StorageHelper.shouldMoveInaccessibleSlot(false, false));
    }

    @Test
    void safeFallbackPrefersAnEmptyHandAndSkipsForbiddenStacks() {
        Map<String, ItemStack> stacks = Map.of(
                "forbidden", new ItemStack(Items.SHEARS),
                "safe", new ItemStack(Items.DIAMOND_AXE),
                "empty", ItemStack.EMPTY);

        var selected = StorageHelper.chooseSafeFallback(List.of("forbidden", "safe", "empty"),
                stacks::get, stack -> stack.is(Items.SHEARS));

        assertTrue(selected.isPresent());
        assertEquals("empty", selected.get());
    }

    @Test
    void fallbackUsesASafeStackWhenNoEmptyHandSlotExists() {
        Map<String, ItemStack> stacks = Map.of(
                "forbidden", new ItemStack(Items.SHEARS),
                "safe", new ItemStack(Items.DIAMOND_AXE));

        var selected = StorageHelper.chooseSafeFallback(List.of("forbidden", "safe"), stacks::get,
                stack -> stack.is(Items.SHEARS));

        assertEquals("safe", selected.orElseThrow());
    }

    @Test
    void toolReplacementComparesComponentsEvenWhenItemTypeMatches() {
        ItemStack currentAxeWithComponent = new ItemStack(Items.DIAMOND_AXE);
        currentAxeWithComponent.set(DataComponents.CUSTOM_NAME, Component.literal("modified axe"));
        ItemStack safeAxe = new ItemStack(Items.DIAMOND_AXE);

        assertTrue(StorageHelper.shouldReplaceEquippedTool(currentAxeWithComponent, safeAxe));
        assertFalse(StorageHelper.shouldReplaceEquippedTool(safeAxe, safeAxe.copy()));
    }

}
