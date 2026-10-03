# AltoClef for Minecraft 26.2

**Release candidate: Build 168.** `build/libs/altoclef-0.5.0.jar`, archived as `.audit/artifacts/altoclef-build168-ab330c6b.jar`, SHA-256 `ab330c6be84c893704b86ac0dda515eaf42b2e5c8917862f8e44cc208eb9c9dd`. It compiled production and runtime-test classes and passed 1,383 tests with zero failures, errors, or skips.

Packaged gameplay, each run on a freshly generated world:

| Run | Build | Mode | Verdict | What it shows |
| --- | --- | --- | --- | --- |
| 237 | 168 | `litematicarecovery` | PASS | A rotated Litematica placement built from naturally gathered oak, stone, and coal. Mob Defense interrupted it once to dodge an arrow, and the same build task resumed, replanned, and finished. |
| 192, 211 | 165, 166 | `natural` | PASS | On untouched terrain, `@get diamond` from an empty inventory gathered wood, tools, iron, fuel, and diamonds (about 5 minutes), then `@list`, `@get [chest, stick]` with exact server/client counts, and `@build`, which gathered a torch and built its schematic. |
| 230 | 167 | `naturalresource` | PASS | One `@get [chest 2, stick 4]` from an empty inventory in a natural world, with a clean final cursor, grid, and menu. |
| 231 | 167 | `containers` | PASS | Loot, single and multi-item deposits, stash, and overflow crafting (60 planks topped up to exact 64/4 stacks), with matching client cache and server state. |
| 232 | 167 | `kelp` | PASS | Recursive `@get dried_kelp_block` from an empty inventory using prepared logs, coal ore, and kelp. |
| 233 | 167 | `mobdefense` | PASS | Two zombies against a wooden sword select Mob Defense priority 80 and the run-away task, as upstream does. Scheduler selection only. |
| 234 | 167 | `ui` | PASS | Control panel tabs and HUD render without errors. |
| 229, 238 | 167, 168 | `natural` | FAIL | Both runs finished the gathering chain, and Run 238 also completed the build, but runs that took longer reached night: a creeper (229) and Drowned (238) killed the bot and its diamonds were lost. |

Builds 167 and 168 changed only crafting-output and builder-candidate code, which Runs 230–238 exercised. Earlier defects and the full decision trail are in [the acceptance matrix](.audit/upstream-acceptance-matrix.md) and [gameplay status](.audit/gameplay/status.md).

**Support limits.** These runs cover the main workflows on two seeds and a handful of fixtures. Survival at night is weak: Mob Defense reacts late to creepers and Drowned, so long tasks in open terrain can end in death and lost items. The runs don't cover every catalogued item: catalogue wiring covers 763 of 1,054 placeable items (Build 150 count), and most have never been gathered in a test. Speedrun (`@gamer`), Nether/End travel on natural terrain, combat beyond scheduler selection, multiplayer servers, and large schematics are not verified. Prepared-fixture passes do not prove natural discovery of the seeded inputs.

## Requirements

- Minecraft **26.2** with Fabric Loader **0.19.5** and Fabric API **0.154.2+26.2**.
- Java **25 or newer** to run the game. The Gradle build requests a Java 26 toolchain and compiles against Java 25.
- The development toolchain is Gradle **9.6.1** with Fabric Loom **1.15.5**.
- For `@build placement`, install Litematica **0.28.8** and MaLiLib **0.29.6**. These versions were loaded together in the 26.2 runtime check.

Baritone Fabric **1.19.0** is bundled inside AltoClef. Do not install a separate Baritone mod alongside it; duplicate copies can conflict.

The distributable jar includes AltoClef's MIT license and Baritone's LGPL-3.0 license. Bundled Jackson 2.20 and Commons Lang 3.20.0 dependencies include their license/notice files. Litematica 0.28.8 and MaLiLib 0.29.6 are separate optional mods; their Fabric metadata declares LGPLv3. The external mod jars are not bundled in AltoClef's distributable.

## Build

From the project directory, run:

```sh
./gradlew build
```

The distributable mod jar is `build/libs/altoclef-0.5.0.jar`. Install that jar in the `mods` folder of a Fabric 26.2 profile. Add the matching Fabric API jar. Add Litematica and MaLiLib when using active placement schematics.

## Control panel

Press **J** in-game to open the control panel (rebind under *Options > Controls > Alto Clef*). The **Tasks** tab shows the running task, its elapsed time, and how the last task ended, and has an item request box with Tab completion, a multi-item request list, and presets. **Build** lists schematics from the `schematics` folder and can build the active Litematica placement. **Console** runs any command with history and has one-click tools. **Stop** cancels the current task; `Ctrl+K` does the same anywhere. The task HUD in the top-left corner can be toggled from the panel or with an optional key binding. See [usage.md](usage.md#control-panel-minecraft-262).

## Commands

Commands are entered in Minecraft chat with the configured prefix, `@` by default.

```text
@get diamond
@get [chest 2, stick 16]
@list
@build myhouse.litematic
@build placement
@stop
```

`@get` accepts a catalogued resource name, optionally followed by a positive count. A bracketed list accepts comma-separated `name count` entries; omitted counts default to 1. For example, `@get [chest 2, stick 16]` asks for two chests and sixteen sticks. Use `@list` to see catalogued resources.

For a file build, put `.litematic`, `.schem`, or `.schematic` files in the Minecraft game's `schematics` folder and pass the filename to `@build`. A relative filename resolves under that folder. AltoClef anchors a file build at the player's block position when the command is issued. `@build placement` snapshots the first active Litematica placement and uses its configured origin and transforms, including its rotation. There is no separate rotation argument on the command.

The build task analyzes required materials, gathers missing catalogued resources, and then asks Baritone to place the schematic. The catalogue also adds vanilla 26.2 crafting recipes when every ingredient has a supported collection path. Existing special collectors remain authoritative. This fallback uses bundled vanilla recipe data; custom server recipes and loot-only items without a collector still need separate support.

Schematic preflight supports `.litematic` versions 4–7 and permits only canonical empty chest/trapped-chest metadata in files and active enabled Litematica regions. The policy requires x/y/z, permits Items only when absent or empty, and checks duplicate coordinates, region bounds, and exact ID/block-state matches. Run 115 passed an eight-cell active two-region build with canonical empty-chest metadata and rejected custom chest metadata; this does not cover large or natural-source builds. `.schem` uses Baritone's default Sponge v1/v2 decoder; `.schematic` uses its MCEdit decoder. File adapters reject nonempty or malformed entity/block-entity payloads before loading. Sponge v3's nested `Schematic.Blocks.BlockEntities` payload is checked, but Sponge v3 decoding itself is not supported. Populated/custom/loot block entities, signs, containers with contents, banner patterns, and schematic entities are not reproduced. Custom registered schematic adapters are not covered. Runs 114–115 verified cursor/grid cleanup and synchronized state after earlier completion-cleanup failures; natural-material acquisition and interrupted recovery during a meaningful active Litematica build remain open.

## Runtime acceptance harness

The opt-in harness waits for a single-player world. Prepared modes edit the world to create a test arena; `natural` and `naturalresource` preserve terrain and require a default world. Use a new disposable world; do not run it in a world you want to keep. Close Minecraft before running Gradle builds. Compile and launch the harness with:

```sh
./gradlew build runtimeTestClasses
./scripts/run-packaged-runtime.sh
```

For the harness's Litematica checks, place the 26.2 Litematica and MaLiLib jars in `run/runtimeTest/mods/` before launching. The harness exercises commands and gather-then-build flows, and writes results under the test game directory's `runtime-test-results/` folder. The default run starts with an empty-inventory diamond request. Narrower modes are available for diagnosis:

```sh
ALTOCLEF_RUNTIME_START=build ./scripts/run-packaged-runtime.sh
ALTOCLEF_RUNTIME_START=manual ./scripts/run-packaged-runtime.sh
ALTOCLEF_RUNTIME_START=mixed ./scripts/run-packaged-runtime.sh
ALTOCLEF_RUNTIME_START=repair ./scripts/run-packaged-runtime.sh
ALTOCLEF_RUNTIME_START=batch ./scripts/run-packaged-runtime.sh
ALTOCLEF_RUNTIME_START=smithing ./scripts/run-packaged-runtime.sh
ALTOCLEF_RUNTIME_START=smelt ./scripts/run-packaged-runtime.sh
ALTOCLEF_RUNTIME_START=multismelt ./scripts/run-packaged-runtime.sh
ALTOCLEF_RUNTIME_START=projectile ./scripts/run-packaged-runtime.sh
ALTOCLEF_RUNTIME_START=natural ./scripts/run-packaged-runtime.sh
ALTOCLEF_RUNTIME_START=dimensions ./scripts/run-packaged-runtime.sh
ALTOCLEF_RUNTIME_START=concrete ./scripts/run-packaged-runtime.sh
ALTOCLEF_RUNTIME_START=hingerepair ./scripts/run-packaged-runtime.sh
ALTOCLEF_RUNTIME_START=plankfuel ./scripts/run-packaged-runtime.sh
ALTOCLEF_RUNTIME_START=kelp ./scripts/run-packaged-runtime.sh
ALTOCLEF_RUNTIME_START=containers ./scripts/run-packaged-runtime.sh
ALTOCLEF_RUNTIME_START=crafterplacement ./scripts/run-packaged-runtime.sh
ALTOCLEF_RUNTIME_START=naturalresource ./scripts/run-packaged-runtime.sh
ALTOCLEF_RUNTIME_START=litematicarecovery ./scripts/run-packaged-runtime.sh
ALTOCLEF_RUNTIME_START=mobdefense ./scripts/run-packaged-runtime.sh
ALTOCLEF_RUNTIME_START=ui ./scripts/run-packaged-runtime.sh
```

`build` skips the diamond and resource-list phases, then checks file building, rotated active placement, stripped logs, mixed stairs/doors/slabs, and smithing. `manual` skips world gathering and building, seeds exact ingredients, disables recipe-book crafting temporarily, and checks stairs, template duplication, and a 2×2 recipe inside a crafting table. `mixed` gathers stairs, doors, and slabs, then checks every schematic cell on client and server, including AIR, a closed left-hinge door, and an open right-hinge door. `repair` seeds incomplete paired blocks and checks rebuilding after interruption. `hingerepair` gathers materials from an empty inventory and exercises repairing a hinge-only mismatch next to an authored double slab. `batch` supplies 29 distinct dropped materials and checks multiple inventory batches. `smithing` checks two successive upgrades from seeded inputs. `smelt` checks three raw iron smelts using one coal. `multismelt` checks one task producing three iron and three copper ingots using one coal. `plankfuel` produces three iron ingots from seeded raw iron and oak planks with coal absent and planks as the only supported fuel; it skips gathering and building. `kelp` requests one dried-kelp block from an empty inventory with prepared logs, coal ore and kelp; Run 195 passed on Build 165. In a reused world the bot may prefer closer natural coal or logs and leave the prepared ones, which the fixture reports as a failure, so use a fresh world. `containers` checks prepared chest loot, deposits, stash, overflow crafting and cache synchronization; Run 194 passed on Build 165. `projectile` spawns a vanilla arrow, observes its flight and impact, and checks the grounding accessor on client and server. `dimensions` uses linked Nether portals, the End exit portal, and a vanilla-feature End gateway to exercise prepared dimension travel; Run 66 passed this fixture after Run 62 exposed a pearl-trace matcher defect. The fixture does not establish natural portal/gateway discovery. `concrete` requests two white concrete from seeded powder and a stone pickaxe beside a fixture-built contained 2×2 source-water pool; it observes powder placement, hardening, mining, and exact inventory results. This fixture skips recursively gathering the powder and pickaxe and does not establish behavior beside naturally found water. `crafterplacement` runs normal `@build` against a small seeded schematic and verifies exact client/server placement and clean survival/UI state; gathering and crafting are skipped. Run 89 passed all 12 prepared crafter orientations with exact client/server placement and clean UI, using seeded materials. Set `ALTOCLEF_CRAFTER_ORIENTATION` to choose the expected state. The fixture accepts 12 enum strings, but that only parameterizes the fixture and is not evidence that all 12 work.

`naturalresource` runs one `@get [chest 2, stick 4]` command from empty inventory on a fresh default world; Run 193 passed on Build 165. `mobdefense` checks Mob Defense scheduler selection against two zombies. `ui` opens the control panel, switches its tabs, and saves screenshots under the test game directory's `screenshots/` folder.

`litematicarecovery` requires Litematica 0.28.8 and MaLiLib 0.29.6. It uses a fresh natural world, an active pinned rotated placement, natural oak/stone/coal acquisition, and one production MobDefense interruption followed by same-root recovery. It does not prove charcoal or furnace-fuel provisioning. Its arrow-based interruption needs a clear corridor near the build site, so hilly or forested spawns fail its preflight. Run 237 passed on Build 168. Fresh saves made by `scripts/create-fresh-save.sh` share the level name `world`, so move `run/productionProbe/config/litematica/litematica_world*.json` aside before a run, or the placement left by an earlier run makes the fixture preflight fail.

`natural` clears inventory, initializes health and food once, selects Survival and Normal difficulty, then requests diamond, lists resources, and requests chest/stick. It does not add resources or change terrain and rejects superflat worlds. Use a newly generated default world for this mode. Prepared fixtures may seed inventories, ores, portals, water pools, or block layouts and may edit terrain; each result records which phases it skipped. A pass in a prepared fixture does not establish natural-terrain discovery or gathering of fixture-seeded inputs. The full run and all new modes remain under acceptance; see the status matrix before treating any feature as verified.

Loom's `runClient` development launcher currently hits a Minecraft 26.2 development-renderer validation failure during early resource loading (a missing `Globals` uniform). The packaged-runtime script launches Fabric in production mode and is the relevant path for checking the distributable jar; this launcher caveat does not establish that all port behavior has been verified.

Run 111 failed final UI cleanup on Build 131 after all eight schematic cells matched and file/active empty-chest payload acceptance plus custom metadata rejection passed. Server and client retained one item on the cursor. Evidence: `.audit/gameplay/active-empty-chest-build-cursor-cleanup-fail-run111.tsv` (SHA-256 `89999e99de5a182d938d04d3c0989673799dffed59dc61ae56c31657d8148fe1`); normal-quit log SHA-256 `9d5a459e16dec68d6b0e84e2eb50f0ec50f798fa532d4a4ed8b8b32da37a07d8`. This cleanup failure is historical and is superseded by later cleanup passes in Runs 114–116. Run 112 passed mud/root command acceptance; TSV SHA-256 `5827351e401bb7fc5c0e7e6b94db30d50a1ed3eca195138878513aa9cd458e5b`, log SHA-256 `0caf5437f214dc630df53677f8eff4861879a12f9eb432eb5fa7445b5cca72fb`. Run 113 formally reproduced the cursor bug on archived Build 131 plus harness 134: an already-matching stone schematic reported completion while a custom-named stone remained on the server/client cursor. Evidence: `.audit/gameplay/build-completion-occupied-cursor-reproduction-fail-run113.tsv` (SHA-256 `6e0ef14b21f12be1ff9aebc1b7eb63698b3cbd9b1949894f61594dfb36af78fa`), PNG SHA-256 `bb3ac6d5c029e081cc8d028fb146475303c254e2d2996dd90be3899d27834471`. Run 114 passed on the saved Run 113 world using the occupied-cursor harness: BuildSchematicTask preserved the custom-named stone and delayed completion until the cursor and crafting grid were clear, with client/server state synchronized. Evidence: `.audit/gameplay/build-completion-cursor-grid-components-conserved-pass-run114.tsv` (SHA-256 `bd607cd4a2dc1713429f1560da46866e0e0cecac16093a901bc35e957dd5fdc3`), PNG SHA-256 `f5613466022df7f9c0394b21c7b4f6b3df0fcad002e60b8fae0c050c24cd0a05`. Run 115 passed a fresh active-Litematica gather/craft/build on seed `3323938809014745914`, using production jar SHA-256 `c50f2288397d478f8f6c4d7eae35daa0a7bde4b6ea85ac7776f590f1845a143e` and harness 134. Starting from empty inventory with prepared oak logs, stone floor, and coal ore, AltoClef gathered/crafted the chest and torches, placed the active two-region schematic, matched all eight cells including AIR, and ended with synchronized inventories and clean cursor/grid/UI. File and active adapters accepted canonical empty chest metadata and rejected custom chest metadata. Evidence: `.audit/gameplay/active-empty-chest-build-cleanup-pass-run115.tsv` (SHA-256 `b14345639654a9b86321571c61495af42a90aeff414d5796c6911b3550f97762`), PNG SHA-256 `41ad7f0a04f375bac60c851010e72649f83e92c6cda96cc7539d4ceb6912fd38`.
