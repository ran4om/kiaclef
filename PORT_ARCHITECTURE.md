# Minecraft 26.2 port architecture

## Caller flows

```text
@get diamond 3
  -> existing TaskCatalogue resolves diamond
  -> existing ResourceTask checks inventory, drops, containers, and mining sources
  -> existing SatisfyMiningRequirementTask acquires the needed pickaxe recursively
  -> Baritone mines the ore

@get [iron_ingot 12, oak_planks 64]
  -> existing CataloguedResourceTask aggregates item targets and shares supported crafting work
  -> task runner completes each target and reports completion

@build house.litematic
  -> load validated Litematica NBT or use Baritone's registry for other formats
  -> count required placeable items and subtract the live inventory
  -> create catalogued resource tasks for the deficits
  -> run Baritone's builder only when those tasks finish
```

## Architecture choices

### Preserve AltoClef's reactive task tree

Keep `AltoClef`, `TaskRunner`, `TaskChain`, `Task`, `TaskCatalogue`, `ResourceTask`, `CataloguedResourceTask`, trackers, and existing commands as the owners of their current work. Update their Minecraft bindings to 26.2 Mojang names. Use the 26.2 Fabric build and Baritone v1.19.0 as the runtime layer. Add a small schematic material analyzer and a build task that sequences existing resource tasks before Baritone's builder process.

This keeps the behaviors users asked for in the project instead of replacing them with a narrower command workflow. The 26.2 game boundary remains in Minecraft-facing imports, mixins, and a few Baritone helpers. Existing tasks continue to observe live game state and can be interrupted/replanned by the scheduler.

### Rejected: rebuild from AutoBuilder

AutoBuilder is the best source for 26.2 Gradle, Fabric, schematic parsing, and builder API examples. Its resource manager only mines blocks directly, so it lacks AltoClef's tool prerequisites and recursive crafting. Replacing AltoClef with it would also drop most existing task chains, trackers, combat/survival behavior, and commands.

### Rejected: replace all tasks with a new goal graph

A new planner graph could unify item, list, crafting, and build requests, but the existing task system already owns recursive acquisition, interruptions, retries, and shared crafting behavior. A wholesale rewrite would add migration risk and make it harder to preserve upstream behavior. Keep the established task tree and add the smallest build/material sequence it needs.

## Implemented modules

- `SchematicLoader` validates compressed Litematica versions 4–7 and uses the bundled Baritone decoder. Other schematic formats use Baritone's format registry.
- `SchematicLoader.loadActiveLitematica` captures active placements, including region transforms, into an immutable `SchematicSnapshot`.
- `SchematicMaterialCounter` maps block states to placement items. Doors and beds consume one item per complete pair; double slabs consume two. It creates masked inventory-bounded batches and subtracts satisfied world positions.
- `SubstitutedStaticSchematic` applies supported configured block substitutions consistently to material planning and world verification.
- `MultiblockRepairPlanner` detects partial existing doors, beds and tall plants that require removing the remaining anchor before replacing the complete pair.
- `BuildSchematicTask` gathers each batch through `CataloguedResourceTask`, runs Baritone, and checks actual world states before completing or replanning.
- `BuildCommand` accepts a schematic file or `placement` and reports task failures.

Resource completion requires materials in accessible inventory slots. Cursor contents and player crafting inputs remain available to recipe planning but do not satisfy a completed gathering goal.

The Java toolchain, Mojang game APIs, mixins, and Baritone integration form the game boundary. Recipe and resource policies remain in AltoClef's task catalogue.

## Synthesis decision

Preserve the full AltoClef source and task hierarchy; use AutoBuilder only as a reference for the 26.2 build and schematic APIs. Bundle the official Baritone 1.19.0 Fabric mod as a nested Fabric dependency so its own metadata and mixins remain intact. Do not copy AutoBuilder's mismatched checked-in Baritone 26.1 jar or transplant its literal-only gathering logic.

## Current verification status

Build 138 passed 1,334 fresh unit tests. Build 139 passed 1,335 fresh unit tests and is the current build; jar SHA-256 `50f0db4bd59ff0a110658bed6df4def4a206c9e8737d14547eadda4bcae21165`. Build 139 includes stable fuel selection, monotonic material-target planning, and an explicit new-run reset test. The current placeable-block report remains 761/1,054 wired (293 unwired). Run 118 failed during kelp fixture setup before `@get`; Run 119 was aborted before timeout/verdict after coal target counts oscillated between 1 and 2, interrupting pickaxe/stick crafting under `CollectFuelTask x9`. Build 139 contains the correction for that behavior. Run 120 was aborted as a diagnostic before timeout or verdict: generic mining's `plausibleToBreak` target predicate rejected kelp beside source water, causing continuous wandering before the fuel stage. The Build 139 fuel fix therefore remains unverified in gameplay. Evidence: `.audit/gameplay/kelp-adjacent-water-target-rejection-aborted-run120.json` (SHA-256 `7cd63f057b38cabd413feaef29f390a654888c724f1f1af1756d1f70924f5cce`), log `.audit/gameplay/kelp-adjacent-water-target-rejection-aborted-run120.log` (SHA-256 `73e48f400f61fe374613e6ad7e78662fbf02936cd53c0a03f39b4c14fd11cfc6`), PNG `.audit/gameplay/kelp-adjacent-water-target-rejection-aborted-run120.png` (SHA-256 `8e234e48cbf1b07cf2c3f4d7f8dc88a1d238613c383d2fa47e15e2ea6dcd35f1`). The narrow schematic metadata policy and remaining constraints are summarized below.

Run 111 failed final UI cleanup on Build 131 after all eight schematic cells matched and file/active empty-chest payload acceptance plus custom metadata rejection passed. Server and client retained one item on the cursor. Evidence: `.audit/gameplay/active-empty-chest-build-cursor-cleanup-fail-run111.tsv` (SHA-256 `89999e99de5a182d938d04d3c0989673799dffed59dc61ae56c31657d8148fe1`); normal-quit log SHA-256 `9d5a459e16dec68d6b0e84e2eb50f0ec50f798fa532d4a4ed8b8b32da37a07d8`. This cleanup failure is historical and is superseded by later cleanup passes in Runs 114–116. Run 112 passed mud/root command acceptance; TSV SHA-256 `5827351e401bb7fc5c0e7e6b94db30d50a1ed3eca195138878513aa9cd458e5b`, log SHA-256 `0caf5437f214dc630df53677f8eff4861879a12f9eb432eb5fa7445b5cca72fb`. Run 113 formally reproduced the cursor bug on archived Build 131 plus harness 134: an already-matching stone schematic reported completion while a custom-named stone remained on the server/client cursor. Evidence: `.audit/gameplay/build-completion-occupied-cursor-reproduction-fail-run113.tsv` (SHA-256 `6e0ef14b21f12be1ff9aebc1b7eb63698b3cbd9b1949894f61594dfb36af78fa`), PNG SHA-256 `bb3ac6d5c029e081cc8d028fb146475303c254e2d2996dd90be3899d27834471`. Run 114 passed on the saved Run 113 world using the occupied-cursor harness: BuildSchematicTask preserved the custom-named stone and delayed completion until the cursor and crafting grid were clear, with client/server state synchronized. Evidence: `.audit/gameplay/build-completion-cursor-grid-components-conserved-pass-run114.tsv` (SHA-256 `bd607cd4a2dc1713429f1560da46866e0e0cecac16093a901bc35e957dd5fdc3`), PNG SHA-256 `f5613466022df7f9c0394b21c7b4f6b3df0fcad002e60b8fae0c050c24cd0a05`. Run 115 passed a fresh active-Litematica gather/craft/build on seed `3323938809014745914`, using production jar SHA-256 `c50f2288397d478f8f6c4d7eae35daa0a7bde4b6ea85ac7776f590f1845a143e` and harness 134. Starting from empty inventory with prepared oak logs, stone floor, and coal ore, AltoClef gathered/crafted the chest and torches, placed the active two-region schematic, matched all eight cells including AIR, and ended with synchronized inventories and clean cursor/grid/UI. File and active adapters accepted canonical empty chest metadata and rejected custom chest metadata. Evidence: `.audit/gameplay/active-empty-chest-build-cleanup-pass-run115.tsv` (SHA-256 `b14345639654a9b86321571c61495af42a90aeff414d5796c6911b3550f97762`), PNG SHA-256 `41ad7f0a04f375bac60c851010e72649f83e92c6cda96cc7539d4ceb6912fd38`.
