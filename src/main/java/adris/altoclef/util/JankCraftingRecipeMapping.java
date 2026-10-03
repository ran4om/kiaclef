package adris.altoclef.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Maps AltoClef's recipe shape to the synced recipe-book display id used by the client API. */
public final class JankCraftingRecipeMapping {
    private JankCraftingRecipeMapping() {}

    /**
     * Searches recipes known to the client's recipe book. Minecraft 26.2 no longer exposes
     * Recipe.getResultItem()/getIngredients(); the recipe-book entry carries both its display
     * result and placement ingredients, while the controller accepts its RecipeDisplayId.
     */
    public static Optional<RecipeDisplayId> getMinecraftMappedRecipe(CraftingRecipe recipe, Item output) {
        Minecraft minecraft = Minecraft.getInstance();
        if (recipe == null || output == null || minecraft.player == null || minecraft.level == null) {
            return Optional.empty();
        }

        List<ItemTarget> requestedIngredients = new ArrayList<>();
        for (ItemTarget target : recipe.getSlots()) {
            if (target != null && !target.isEmpty()) {
                requestedIngredients.add(target);
            }
        }
        if (requestedIngredients.isEmpty()) {
            return Optional.empty();
        }

        ClientRecipeBook recipeBook = minecraft.player.getRecipeBook();
        var displayContext = SlotDisplayContext.fromLevel(minecraft.level);
        for (RecipeCollection collection : recipeBook.getCollections()) {
            for (RecipeDisplayEntry entry : collection.getRecipes()) {
                Optional<List<Ingredient>> placement = entry.craftingRequirements();
                if (placement.isEmpty() || !ingredientsMatch(requestedIngredients, placement.get())) {
                    continue;
                }

                boolean outputMatches = entry.resultItems(displayContext).stream()
                        .filter(stack -> !stack.isEmpty())
                        .map(ItemStack::getItem)
                        .anyMatch(output::equals);
                if (outputMatches) {
                    return Optional.of(entry.id());
                }
            }
        }
        return Optional.empty();
    }

    /** Match ingredient multisets without depending on shaped-grid coordinates. */
    private static boolean ingredientsMatch(List<ItemTarget> requested, List<Ingredient> available) {
        List<Ingredient> nonEmpty = available.stream().filter(ingredient -> !ingredient.isEmpty()).toList();
        if (requested.size() != nonEmpty.size()) {
            return false;
        }
        return matchIngredient(0, requested, nonEmpty, new boolean[requested.size()]);
    }

    private static boolean matchIngredient(int ingredientIndex, List<ItemTarget> requested,
                                           List<Ingredient> available, boolean[] usedTargets) {
        if (ingredientIndex == available.size()) {
            return true;
        }

        Ingredient ingredient = available.get(ingredientIndex);
        for (int targetIndex = 0; targetIndex < requested.size(); targetIndex++) {
            if (usedTargets[targetIndex] || !ingredientMatches(ingredient, requested.get(targetIndex))) {
                continue;
            }
            usedTargets[targetIndex] = true;
            if (matchIngredient(ingredientIndex + 1, requested, available, usedTargets)) {
                return true;
            }
            usedTargets[targetIndex] = false;
        }
        return false;
    }

    private static boolean ingredientMatches(Ingredient ingredient, ItemTarget target) {
        return ingredient.items().anyMatch(holder -> target.matches(holder.value()));
    }
}
