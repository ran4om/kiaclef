# Minecraft 26.2 port plan

## Definition of done

The port is complete when the original Altoclef modules build into a Fabric mod for Minecraft 26.2 and a launched 26.2 client can execute the user-facing paths below:

1. A request for diamond expands into missing tool/resource prerequisites, then mines diamond and stops at the requested count.
2. A craftable request gathers ingredients and crafts the requested output.
3. A multi-item request tracks every target and shares compatible prerequisite work.
4. A `.litematic` or active Litematica placement is analyzed for missing materials, those materials are gathered through Altoclef's resource tasks, and Baritone starts the build after the material goals are met.
5. Existing commands, safety chains, task scheduling, trackers, and other upstream modules compile and pass focused checks. Version-sensitive mixins are exercised in a 26.2 client.

Build success alone does not satisfy this definition. Gameplay checks must cover the actual request-to-world-action path. If a feature cannot be exercised in this environment, its status remains open in the handoff.

## Scope and known constraints

- Baseline: 328 Java source files, 246 importing Minecraft classes, 42 importing Baritone classes, 20 mixin classes, and no existing `src/test` tree.
- The source pins Minecraft/Yarn 1.18.2, Fabric Loader 0.13.3, Loom 0.10-SNAPSHOT, Gradle 7.3.3, and an old 1.18.2 Baritone fork. The baseline build fails before Java compilation on the installed JDK 26 because Gradle's bundled Groovy cannot read class file version 70.
- Minecraft 26.2 is released. Yarn has no 26.2 mapping; current 26.2 mods use Mojang names. Baritone v1.19.0 publishes a Fabric build for exactly 26.2.
- The 26.2 AutoBuilder reference builds with Loom 1.15.5 and Gradle 9.6.1. Its checked-in Baritone jar has metadata for 26.1 despite its filename, so it is not the Baritone dependency for this port.
- Porting all 328 files and validating every supported task is a broad compatibility migration. Recipe/datapack differences, server-only resources, mixin targets, and block-state placement need direct 26.2 checks.

## Phases

- [x] Ground the source and record the pre-change build result.
- [x] Compare the preservation port with a clean 26.2 rewrite and choose module boundaries.
- [x] Upgrade the build and establish a minimal 26.2 client launch with the correct Baritone release.
- [x] Port upstream game and Baritone references while retaining the task tree, catalog, commands, and trackers.
- [x] Add schematic material planning and gather-then-build execution using the existing resource tasks.
- [x] Add automated checks for dependency planning, crafting, list aggregation, and schematic material counts.
- [x] Build and launch the packaged mod on 26.2; exercise selected command, gather, craft, container, crop, and schematic-placement paths in client worlds.
- [ ] Complete natural-world progression through resource-list collection and a full gather-before-build schematic.
- [ ] Verify every crafter placement orientation and remaining task/runtime gaps; review the complete diff and decision trail.

## Verification record

The untouched source build was run with `./gradlew build --no-daemon`. It failed while compiling `settings.gradle` with `Unsupported class file major version 70`, before it reached source compilation. The full output is in `.audit/upstream-build.log`.

Build 138 passed 1,334 fresh unit tests. Build 139 passed 1,335 fresh unit tests and is the current build; jar SHA-256 `50f0db4bd59ff0a110658bed6df4def4a206c9e8737d14547eadda4bcae21165`. Build 139 includes stable fuel selection, monotonic material-target planning, and an explicit new-run reset test. The current placeable-block report remains 761/1,054 wired (293 unwired). Run 118 failed during kelp fixture setup before `@get`; Run 119 was aborted before timeout/verdict after coal target counts oscillated between 1 and 2, interrupting pickaxe/stick crafting under `CollectFuelTask x9`. Build 139 contains the correction for that behavior. Run 120 was aborted as a diagnostic before timeout or verdict: generic mining's `plausibleToBreak` target predicate rejected kelp beside source water, causing continuous wandering before the fuel stage. The Build 139 fuel fix therefore remains unverified in gameplay. Evidence: `.audit/gameplay/kelp-adjacent-water-target-rejection-aborted-run120.json` (SHA-256 `7cd63f057b38cabd413feaef29f390a654888c724f1f1af1756d1f70924f5cce`), log `.audit/gameplay/kelp-adjacent-water-target-rejection-aborted-run120.log` (SHA-256 `73e48f400f61fe374613e6ad7e78662fbf02936cd53c0a03f39b4c14fd11cfc6`), PNG `.audit/gameplay/kelp-adjacent-water-target-rejection-aborted-run120.png` (SHA-256 `8e234e48cbf1b07cf2c3f4d7f8dc88a1d238613c383d2fa47e15e2ea6dcd35f1`). See the gameplay audit for historical runs and open acceptance gaps.

Run 111 failed final UI cleanup on Build 131 after all eight schematic cells matched and file/active empty-chest payload acceptance plus custom metadata rejection passed. Server and client retained one item on the cursor. Evidence: `.audit/gameplay/active-empty-chest-build-cursor-cleanup-fail-run111.tsv` (SHA-256 `89999e99de5a182d938d04d3c0989673799dffed59dc61ae56c31657d8148fe1`); normal-quit log SHA-256 `9d5a459e16dec68d6b0e84e2eb50f0ec50f798fa532d4a4ed8b8b32da37a07d8`. This cleanup failure is historical and is superseded by later cleanup passes in Runs 114–116. Run 112 passed mud/root command acceptance; TSV SHA-256 `5827351e401bb7fc5c0e7e6b94db30d50a1ed3eca195138878513aa9cd458e5b`, log SHA-256 `0caf5437f214dc630df53677f8eff4861879a12f9eb432eb5fa7445b5cca72fb`. Run 113 formally reproduced the cursor bug on archived Build 131 plus harness 134: an already-matching stone schematic reported completion while a custom-named stone remained on the server/client cursor. Evidence: `.audit/gameplay/build-completion-occupied-cursor-reproduction-fail-run113.tsv` (SHA-256 `6e0ef14b21f12be1ff9aebc1b7eb63698b3cbd9b1949894f61594dfb36af78fa`), PNG SHA-256 `bb3ac6d5c029e081cc8d028fb146475303c254e2d2996dd90be3899d27834471`. Run 114 passed on the saved Run 113 world using the occupied-cursor harness: BuildSchematicTask preserved the custom-named stone and delayed completion until the cursor and crafting grid were clear, with client/server state synchronized. Evidence: `.audit/gameplay/build-completion-cursor-grid-components-conserved-pass-run114.tsv` (SHA-256 `bd607cd4a2dc1713429f1560da46866e0e0cecac16093a901bc35e957dd5fdc3`), PNG SHA-256 `f5613466022df7f9c0394b21c7b4f6b3df0fcad002e60b8fae0c050c24cd0a05`. Run 115 passed a fresh active-Litematica gather/craft/build on seed `3323938809014745914`, using production jar SHA-256 `c50f2288397d478f8f6c4d7eae35daa0a7bde4b6ea85ac7776f590f1845a143e` and harness 134. Starting from empty inventory with prepared oak logs, stone floor, and coal ore, AltoClef gathered/crafted the chest and torches, placed the active two-region schematic, matched all eight cells including AIR, and ended with synchronized inventories and clean cursor/grid/UI. File and active adapters accepted canonical empty chest metadata and rejected custom chest metadata. Evidence: `.audit/gameplay/active-empty-chest-build-cleanup-pass-run115.tsv` (SHA-256 `b14345639654a9b86321571c61495af42a90aeff414d5796c6911b3550f97762`), PNG SHA-256 `41ad7f0a04f375bac60c851010e72649f83e92c6cda96cc7539d4ceb6912fd38`.
