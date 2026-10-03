package adris.altoclef.util.recipes;

import adris.altoclef.testing.MinecraftTestBootstrap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VanillaRecipeFallbackTest {
    @BeforeAll static void bootstrap() { MinecraftTestBootstrap.initialize(); }

    @Test
    void registersRecipesOnlyWhenTheirInputsAreSupported() {
        Set<net.minecraft.world.item.Item> available = new HashSet<>(
                Set.of(Items.OAK_LOG, Items.BIRCH_LOG, Items.DIAMOND));
        Map<net.minecraft.world.item.Item, adris.altoclef.util.CraftingRecipe> registeredRecipes = new HashMap<>();
        Set<net.minecraft.world.item.Item> outputs = VanillaRecipeFallback.registerSupported(
                available::contains, (item, recipe) -> {
                    assertNotNull(recipe);
                    registeredRecipes.put(item, recipe);
                });
        assertTrue(outputs.contains(Items.OAK_PLANKS));
        assertTrue(outputs.contains(Items.STICK));
        assertTrue(outputs.contains(Items.DIAMOND_PICKAXE));
        assertFalse(outputs.contains(Items.NETHERITE_INGOT));
        assertEquals(4, registeredRecipes.get(Items.CRAFTING_TABLE).getSlotCount());
        assertFalse(registeredRecipes.get(Items.CRAFTING_TABLE).isBig());
        assertEquals(Set.of(Items.OAK_PLANKS, Items.BIRCH_PLANKS),
                Set.of(registeredRecipes.get(Items.CRAFTING_TABLE).getSlot(0).getMatches()));
        assertEquals(4, registeredRecipes.get(Items.STICK).outputCount());
        assertTrue(outputs.contains(Items.OAK_DOOR));
        assertArrayEquals(new net.minecraft.world.item.Item[]{Items.OAK_PLANKS},
                registeredRecipes.get(Items.OAK_DOOR).getSlot(0).getMatches(),
                "oak door recipe must not accept another species' planks");
    }

    @Test
    void unseededRecipeCyclesDoNotBecomeCollectible() {
        var cycle = List.of(
                recipe("test:string_from_bone", Items.STRING, Items.BONE),
                recipe("test:bone_from_string", Items.BONE, Items.STRING));
        Set<net.minecraft.world.item.Item> outputs = VanillaRecipeFallback.registerSupported(
                item -> false, (item, craftingRecipe) -> fail("unseeded cycle registered " + item), cycle);
        assertTrue(outputs.isEmpty());
    }

    @Test
    void fallbackDoesNotUseIngredientsWithCraftingRemainders() {
        Set<net.minecraft.world.item.Item> outputs = VanillaRecipeFallback.registerSupported(
                item -> item == Items.HONEY_BOTTLE,
                (item, recipe) -> fail("unsafe remainder recipe registered " + item));
        assertFalse(outputs.contains(Items.HONEY_BLOCK));
    }

    @Test
    void waxedCopperNeedsBothCraftableCopperAndAnObtainableHoneycombLeaf() {
        Set<net.minecraft.world.item.Item> copperOnly = VanillaRecipeFallback.registerSupported(
                item -> item == Items.COPPER_INGOT, (item, recipe) -> {});
        assertFalse(copperOnly.contains(BuiltInRegistries.ITEM.getOptional(Identifier.parse("minecraft:waxed_copper_bars")).orElseThrow()));

        Set<net.minecraft.world.item.Item> copperAndHoneycomb = VanillaRecipeFallback.registerSupported(
                item -> item == Items.COPPER_INGOT || item == Items.HONEYCOMB, (item, recipe) -> {});
        assertTrue(copperAndHoneycomb.contains(BuiltInRegistries.ITEM.getOptional(Identifier.parse("minecraft:waxed_copper_bars")).orElseThrow()));
    }

    @Test
    void cinnabarLeafMakesItsRecipeDerivedBuildingMaterialsReachable() {
        Map<net.minecraft.world.item.Item, adris.altoclef.util.CraftingRecipe> registeredRecipes = new HashMap<>();
        Set<net.minecraft.world.item.Item> outputs = VanillaRecipeFallback.registerSupported(
                item -> item == Items.CINNABAR,
                (item, recipe) -> registeredRecipes.put(item, recipe));

        assertTrue(outputs.contains(Items.POLISHED_CINNABAR));
        assertTrue(outputs.contains(Items.CINNABAR_BRICKS));
        assertTrue(registeredRecipes.get(Items.POLISHED_CINNABAR).getSlot(0).matches(Items.CINNABAR));
    }

    @Test
    void sulfurSpikeLeafFeedsSulfurAndItsRecipeDerivedBuildingMaterials() {
        Map<net.minecraft.world.item.Item, adris.altoclef.util.CraftingRecipe> registeredRecipes = new HashMap<>();
        Set<net.minecraft.world.item.Item> outputs = VanillaRecipeFallback.registerSupported(
                item -> item == Items.SULFUR_SPIKE,
                (item, recipe) -> registeredRecipes.put(item, recipe));

        assertTrue(outputs.contains(Items.SULFUR));
        assertTrue(outputs.contains(Items.POTENT_SULFUR));
        assertTrue(outputs.contains(Items.SULFUR_BRICKS));
        assertTrue(registeredRecipes.get(Items.SULFUR).getSlot(0).matches(Items.SULFUR_SPIKE));
    }

    @Test
    void mudFeedsPackedMudAndMudBricksThroughFallbackRecipes() {
        Map<net.minecraft.world.item.Item, adris.altoclef.util.CraftingRecipe> registeredRecipes = new HashMap<>();
        Set<net.minecraft.world.item.Item> outputs = VanillaRecipeFallback.registerSupported(
                item -> item == Items.MUD || item == Items.WHEAT,
                (item, recipe) -> registeredRecipes.put(item, recipe));

        assertTrue(outputs.contains(Items.PACKED_MUD));
        assertTrue(outputs.contains(Items.MUD_BRICKS));
        assertTrue(registeredRecipes.get(Items.PACKED_MUD).getSlot(0).matches(Items.MUD));
    }

    @Test
    void mudAndMangroveRootsMakeMuddyMangroveRootsCraftable() {
        Map<net.minecraft.world.item.Item, adris.altoclef.util.CraftingRecipe> registeredRecipes = new HashMap<>();
        Set<net.minecraft.world.item.Item> outputs = VanillaRecipeFallback.registerSupported(
                item -> item == Items.MUD || item == Items.MANGROVE_ROOTS,
                (item, recipe) -> registeredRecipes.put(item, recipe));
        var muddyRoots = BuiltInRegistries.ITEM.getOptional(Identifier.parse("minecraft:muddy_mangrove_roots"))
                .orElseThrow();

        assertTrue(outputs.contains(muddyRoots));
        assertTrue(registeredRecipes.get(muddyRoots).getSlot(0).matches(Items.MUD));
        assertTrue(registeredRecipes.get(muddyRoots).getSlot(1).matches(Items.MANGROVE_ROOTS));
    }

    @Test
    void bundledIndexTargets262AndContainsRecipeAlternativesAndCounts() throws Exception {
        try (InputStream stream = VanillaRecipeFallback.class.getResourceAsStream("/altoclef/recipes26.2.json")) {
            assertNotNull(stream);
            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(json.contains("\"minecraft\": \"26.2\""));
            assertTrue(json.contains("\"output\": \"minecraft:diamond_pickaxe\""));
            assertTrue(json.contains("\"count\": 4"));
            assertTrue(json.contains("minecraft:oak_planks"));
        }
        assertNotNull(BuiltInRegistries.ITEM.getOptional(Identifier.parse("minecraft:diamond_pickaxe")).orElse(null));
    }

    private static VanillaRecipeFallback.IndexedRecipe recipe(String id,
                                                               net.minecraft.world.item.Item output,
                                                               net.minecraft.world.item.Item ingredient) {
        List<List<net.minecraft.world.item.Item>> grid = Collections.unmodifiableList(Arrays.asList(
                List.of(ingredient), null, null, null));
        return new VanillaRecipeFallback.IndexedRecipe(id, output, 1, grid);
    }
}
