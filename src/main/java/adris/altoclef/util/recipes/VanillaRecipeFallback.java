package adris.altoclef.util.recipes;

import adris.altoclef.util.CraftingRecipe;
import adris.altoclef.util.ItemTarget;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStackTemplate;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/** Vanilla 26.2 crafting recipes used only as fallback dependency paths. */
public final class VanillaRecipeFallback {
    private static final String RESOURCE = "/altoclef/recipes26.2.json";
    private static final List<IndexedRecipe> RECIPES = load();

    private VanillaRecipeFallback() {}

    /**
     * Registers fallback crafting recipes whose ingredients can all be collected directly or
     * through an earlier fallback recipe. Existing catalogue items are treated as authoritative
     * leaves and are not overwritten. The result contains output items that were registered.
     */
    public static Set<Item> registerSupported(Predicate<Item> known,
                                               BiConsumer<Item, CraftingRecipe> registrar) {
        return registerSupported(known, registrar, RECIPES);
    }

    static Set<Item> registerSupported(Predicate<Item> known,
                                        BiConsumer<Item, CraftingRecipe> registrar,
                                        Collection<IndexedRecipe> recipes) {
        Objects.requireNonNull(known, "known");
        Objects.requireNonNull(registrar, "registrar");
        Objects.requireNonNull(recipes, "recipes");
        Set<Item> supported = new HashSet<>();
        Set<Item> registered = new LinkedHashSet<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (known.test(item)) supported.add(item);
        }

        // Each successful pass adds at least one output, so this is naturally bounded by the
        // item registry size. Cycles with no known leaf never become supported.
        boolean changed;
        do {
            changed = false;
            // Resolve a whole dependency tier from the same snapshot so recipe-file
            // ordering cannot prune alternatives discovered within this pass.
            Set<Item> priorTier = Set.copyOf(supported);
            for (IndexedRecipe indexed : recipes) {
                Item output = indexed.output();
                if (output == null || supported.contains(output) || known.test(output)) continue;
                ItemTarget[] slots = new ItemTarget[indexed.grid().size()];
                boolean viable = true;
                for (int i = 0; i < indexed.grid().size(); i++) {
                    List<Item> alternatives = indexed.grid().get(i);
                    if (alternatives == null) continue;
                    if (alternatives.isEmpty()) {
                        viable = false;
                        break;
                    }
                    Item[] reachable = alternatives.stream().filter(priorTier::contains).toArray(Item[]::new);
                    if (reachable.length == 0) {
                        viable = false;
                        break;
                    }
                    slots[i] = new ItemTarget(reachable, 1);
                }
                if (!viable) continue;
                CraftingRecipe recipe = CraftingRecipe.newShapedRecipe(indexed.id(), slots, indexed.count());
                if (recipe == null) continue;
                registrar.accept(output, recipe);
                supported.add(output);
                registered.add(output);
                changed = true;
            }
        } while (changed);
        return Collections.unmodifiableSet(registered);
    }

    private static List<IndexedRecipe> load() {
        try (InputStream stream = VanillaRecipeFallback.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) throw new IllegalStateException("Missing recipe index " + RESOURCE);
            JsonObject root = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!"26.2".equals(root.get("minecraft").getAsString())) {
                throw new IllegalStateException("Recipe index targets Minecraft " + root.get("minecraft"));
            }
            List<IndexedRecipe> recipes = new ArrayList<>();
            for (JsonElement element : root.getAsJsonArray("recipes")) {
                JsonObject obj = element.getAsJsonObject();
                Item output = item(obj.get("output").getAsString());
                if (output == null) continue;
                List<List<Item>> grid = new ArrayList<>();
                for (JsonElement slotElement : obj.getAsJsonArray("grid")) {
                    if (slotElement == null || slotElement.isJsonNull()) {
                        grid.add(null);
                        continue;
                    }
                    List<Item> alternatives = new ArrayList<>();
                    for (JsonElement itemElement : slotElement.getAsJsonArray()) {
                        Item ingredient = item(itemElement.getAsString());
                        // Remainder handling is not represented by CraftingRecipe. Exclude
                        // such ingredients so fallback tasks never silently consume buckets,
                        // bowls, bottles, or tools that vanilla returns after crafting.
                        ItemStackTemplate remainder = ingredient == null ? null : ingredient.getCraftingRemainder();
                        if (ingredient != null && (remainder == null || remainder.count() == 0)) {
                            alternatives.add(ingredient);
                        }
                    }
                    grid.add(alternatives);
                }
                recipes.add(new IndexedRecipe(obj.get("id").getAsString(), output,
                        obj.get("count").getAsInt(), Collections.unmodifiableList(new ArrayList<>(grid))));
            }
            return List.copyOf(recipes);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static Item item(String id) {
        return BuiltInRegistries.ITEM.getOptional(Identifier.parse(id)).orElse(null);
    }

    record IndexedRecipe(String id, Item output, int count, List<List<Item>> grid) {}
}
