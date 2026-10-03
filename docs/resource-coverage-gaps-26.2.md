# Resource collection gaps in Minecraft 26.2

This is a source review of `.audit/gameplay/placeable-block-coverage-26.2.md`. The latest generated inventory report in this checkout, from Build 150, counts 1,054 distinct block items: 763 have catalogue or recipe-fallback collection-task wiring and 291 do not. This is registry breadth, not proof of runtime collection or placement. A separate literal-key source comparison against baseline commit `af22e3bc2f03dde45da703f5f7535baae18ea486` found 411 baseline keys and 438 current keys, with no baseline key absent from current source; see `.audit/catalogue-key-comparison-2026-09-30.md`. The 291 unsupported entries are not a count of baseline regressions. The current acceptance checklist is `.audit/upstream-acceptance-matrix.md`.

The 291 unsupported entries describe current block-item coverage, not survival success or baseline regressions. Some are intentionally unobtainable in ordinary survival; others lack an implemented source or recipe path. A task can also exist because a recipe fallback can craft an item even if its first ingredient has no working gatherer. Keep these distinctions visible when using the inventory to track coverage.

## What the current task helpers do

`TaskCatalogue.mine(...)` creates a `MineAndCollectTask`. It tracks the listed blocks, satisfies the configured mining level, breaks them, and picks up matching drops. This is a good fit for ordinary block drops and items such as saplings that occasionally fall from leaves. It does not preserve a block when mining changes it into a different drop. In particular, it cannot produce an ore block or ice block without Silk Touch.

`TaskCatalogue.mob(...)` creates a `KillAndLootTask`. That task kills tracked entities and wanders while waiting for one to appear. The catalogue wrapper forces the Overworld by default. Registrations for Shulkers or Ghasts must override that setting to the End or Nether. The helper does not get armor, food, water breathing, or other combat gear before it hunts, so a task registration alone does not make a dangerous hunt reliable.

The mapped 26.2 game jar confirms that oak, spruce, birch, jungle, acacia, dark oak, cherry, and pale oak leaves can drop their matching sapling. The drop is chance-based. The leaf item wins instead when the tool has shears or Silk Touch. The catalogue now uses `CollectSaplingTask`, which marks shears and Silk Touch tools as unsuitable while mining leaves, and a separate mature-state collector for mangrove propagules. Run 63 passed scoped sapling and mature-propagule collection, but natural discovery and prerequisite acquisition remain unverified. The `packed_ice` and `blue_ice` tables require Silk Touch. Current direct-drop registrations include moss block and End stone, with End stone restricted to the End. Other newly added source collectors are included in the 763/1,054 count, which does not imply runtime proof.

The recipe fallback in `VanillaRecipeFallback` only adds crafting recipes whose ingredients already have collection paths. It removes ingredients with crafting remainders because the recipe runner cannot yet model returned buckets, bottles, and similar items. Adding a source task for one missing ingredient can unlock many derived block shapes without adding separate collectors for each slab and stair.

`BuildSchematicTask` checks `TaskCatalogue.taskExists` for each needed item. It fails with a missing-resource-task reason when a schematic includes an item outside the catalogue. When the item is registered through a fallback recipe, the build still depends on every ingredient path succeeding at runtime.

## Survival gaps that block common builds

| Missing group | Count in report | Why it matters | Feasible reuse or next step |
| --- | ---: | --- | --- |
| Saplings and mangrove propagule | 9 | Trees are a core early-game source, and the catalogue now has dedicated task paths for these resources. | Eight matching tree-sapling paths use a leaf collector that avoids shears and Silk Touch; mangrove propagule uses a mature-state filter. Confirm each drop and acquisition loop in survival before treating these paths as runtime-supported. |
| Shulker boxes | 17 | The plain shulker-box crafting recipe is in the fallback index, but its `shulker_shell` ingredient has no task. Dyed variants also need a dyeing path. | Add a shell source through `mob(...)`, then explicitly force `Dimension.END`; the generic catalogue wrapper otherwise sends it to the Overworld. Add a separate base-box plus dye operation for colors. Test shell pickup and End travel before treating this as supported. |
| Prismarine family and sea lantern | Several block shapes | Fallback recipes for prismarine, dark prismarine, prismarine bricks, and sea lantern cannot resolve because prismarine shards or crystals have no source task. | Guardian drops can use `mob(...)` in the Overworld. The helper does not prepare for underwater combat or locate monuments, so this needs underwater movement and survivability work before it is dependable. Fallback recipes can then cover many shapes. |
| Directly harvestable plants and natural blocks | Dozens | Older gap lists included small dripleaf and hanging roots; both now have shears collectors and prepared-source runtime passes (Runs 134 and 130). Those runs supplied shears and plants, so discovery and tool acquisition remain open. Chorus, glow berries, flowers and other sources still need separate checks. | Use `mine(...)` only when the drop matches the target. Use shears or `CollectCropTask` when 26.2 loot rules require it, then verify each source in Survival. |
| Ice and packed or blue ice | 3 | The fallback index has `ice -> packed_ice -> blue_ice`, but none has a collection path. All three block loot tables require Silk Touch. | Add a Silk Touch aware collector that verifies the enchanted tool and confirms the block item drops. The existing mining-level helper does not guarantee Silk Touch. Once ice is collectible, fallback crafting can also produce packed and blue ice. |
| End stone and chorus fruit | Several | End-stone bricks and purpur blocks have indexed recipes; End stone now has a direct collection path, but chorus fruit still lacks a source path. | End-stone mining is registered with an explicit `Dimension.END` override; verify it in live play. Chorus fruit needs a source task for Chorus Plants and a smelting step for popped fruit before fallback can craft purpur blocks. |
| Ghast tears and dried ghast | 1 block plus ingredient | The dried-ghast recipe is indexed, but no Ghast-tear task feeds it. | Use the existing mob collection path for Ghasts, explicitly force the Nether, and verify that the death drop is found and picked up. |
| Mud and mud bricks | Several shapes | Mud and muddy mangrove-root task paths exist. Run 112 passed `@get mud 2` and recursive `@get muddy_mangrove_roots 2` from supplied mud and mangrove roots, with exact output and clean inventory. Natural source discovery was skipped; packed mud, mud bricks and bottle-based dirt-to-mud conversion remain unverified. | Verify natural mud/root discovery, then run the recursive `packed_mud` → `mud_bricks` path from those gathered resources. Add dirt plus water-bottle conversion only as an explicitly supported alternate mud source; it is not required for the natural-mud recipe chain. |
| Exposed, weathered, oxidized, and waxed copper | 92 | Raw copper, smelting, honeycomb collection and unaffected copper recipes are wired, but this does not establish aging or waxing of requested copper block states. | Add or verify state-aware aging and honeycomb waxing, then test common block variants in a schematic. |
| Cinnabar, sulfur, resin and pale-garden materials | Several | Cinnabar and sulfur-spike drop mechanics and task wiring are checked. Run 108 passed recursive cinnabar/sulfur-brick collection from supplied blocks, but did not inspect rendered `@list` contents or prove natural discovery. Resin and pale-garden source locations and acquisition mechanics remain unverified. | Verify natural cinnabar and sulfur-spike discovery. Check resin and pale-garden spawn locations, drops, harvest tools and dimensions before claiming natural support; add a collector only where the survival path is known. |

The counts above overlap by design. For example, a plant family may also have a recipe-derived carpet or stair entry.

## Conditional and non-survival entries

Several missing block items need a distinct collection mode rather than an ordinary mine task:

- Ore blocks, grass blocks, mycelium, podzol, ice, packed ice, blue ice, and some mushroom blocks need Silk Touch or a state-preserving harvest. Their ordinary item drops do not satisfy a schematic target for the original block item.
- Living coral and coral fans need the right tool and water handling. Breaking or transporting them can change their block state, so a task should verify the collected item and final placed state.
- Amethyst buds and clusters have age and Silk Touch rules. Budding amethyst itself cannot be collected in survival. The existing `CollectAmethystBlockTask` is a useful example of avoiding budding blocks while collecting shards or crafting amethyst blocks, but it is not a collector for every amethyst block state.
- Dragon eggs, heads, suspicious blocks, spawners, vaults, trial spawners, and structure loot do not follow a generic block-drop path. Check each 26.2 survival acquisition rule before adding a task. Some need a boss or mob event, structure search, brushing, Silk Touch, or may have no survival drop at all.
- Bedrock, barriers, command blocks, jigsaw and structure blocks, light and test blocks, end portal frames, and reinforced deepslate are not normal survival resources. They should remain unsupported by ordinary gather commands unless a separate creative-mode workflow is added.

The report also lists blocks whose items exist in the registry but whose intended survival acquisition has not been confirmed for this port. Cinnabar and sulfur-spike drop mechanics and task wiring have been checked, but natural discovery remains open. Resin and pale-garden source locations and acquisition mechanics still need verification.

## Best next work

1. Extend natural discovery coverage beyond the scoped passes for saplings/propagules (Run 63), mud and roots (Run 112), hanging roots (Run 130), and small dripleaf (Run 134).
2. Add and test a reusable Silk Touch collector. It should verify the equipped enchantment, acquire it if the supported progression allows, mine the requested block, and confirm the block item arrived. This unlocks several survival-available schematic materials at once.
3. Add missing recipe leaves with explicit dimensions and requirements. Shulker shells need the End, Ghast tears need the Nether, and Guardian drops need Overworld travel plus underwater movement and combat handling. `mob(...)` is only the kill-and-wander shell for these tasks.
4. Implement missing conversion tasks for chorus-fruit smelting, copper aging and waxing, and other materials that the recipe-only fallback cannot express. Treat dirt plus water-bottle mud conversion as an optional alternate source path.
5. Add source collectors for 26.2-specific materials only after confirming their survival spawn, harvest tool, drops, and dimension in the actual game.
6. Keep operator-only and currently unconfirmed block items out of a blanket coverage claim. The build command should report a useful unsupported-item reason when a schematic requests one.

## Sources in this checkout

- `.audit/gameplay/placeable-block-coverage-26.2.md` for the registry inventory.
- `/home/kiarad/.gradle/caches/fabric-loom/26.2/minecraft-merged.jar` for `data/minecraft/loot_table/blocks/*.json` drop-table verification.
- `src/main/java/adris/altoclef/TaskCatalogue.java` for `mine`, `mob`, dimension defaults, and catalogue reachability.
- `src/main/java/adris/altoclef/tasks/resources/MineAndCollectTask.java` for block tracking, mining-level preparation, and drop pickup.
- `src/main/java/adris/altoclef/tasks/resources/KillAndLootTask.java` for entity search and kill behavior.
- `src/main/java/adris/altoclef/util/recipes/VanillaRecipeFallback.java` for recursive recipe reachability and remainder filtering.
- `src/main/java/adris/altoclef/tasks/resources/CollectCropTask.java` and `CollectAmethystBlockTask.java` for specialized collection patterns.
