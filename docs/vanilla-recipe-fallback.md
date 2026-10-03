# Vanilla recipe fallback index

`VanillaRecipeFallback` provides fallback crafting paths for vanilla Minecraft 26.2 items. It
loads `src/main/resources/altoclef/recipes26.2.json`, which is generated from the recipe and item
tag resources embedded in the 26.2 game jar.

Regenerate it with:

```sh
python3 scripts/generate-vanilla-recipes.py ~/.gradle/caches/fabric-loom/26.2/minecraft-merged.jar
```

Call `registerSupported(known, registrar)` after explicit catalogue resources have been set up.
`known` should return true for items that already have an explicit collector or crafting task;
those catalogue entries remain authoritative. The registrar receives an output item and a
`CraftingRecipe` whose ingredient alternatives have been restricted to supported items. The
method repeatedly adds recipes whose inputs are all obtainable, so chains such as logs → planks
→ sticks → tools resolve while recipes with no collectible base input and dependency cycles are
left out. It returns the output items it registered.

The index includes only ordinary shaped and shapeless crafting recipes with plain item results.
Tag ingredients are recursively expanded when the index is generated. Shapeless ingredients are
placed in a deterministic row-major grid because AltoClef's crafting model currently represents
both forms through grid slots. Two-by-two recipes retain four slots so they remain craftable in
the player inventory; larger recipes use nine slots. Smelting, smithing, stonecutting, special serializers, and results
whose components affect the output are omitted. The index is the vanilla 26.2 data set: recipes
added by a server datapack or mod are not included. At runtime, ingredient alternatives with a
vanilla crafting remainder (such as buckets, bowls, or bottles) are excluded because AltoClef's
recipe model does not track returned containers. This can make some otherwise valid recipes
unavailable as fallback paths. Actual placement continues to depend on a
matching recipe display being synced to the client.
