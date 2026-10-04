# Kiaclef

**Kiaclef is a fork of [AltoClef](https://github.com/gaucho-matrero/altoclef)**, the client-side Minecraft bot that plays survival on its own, ported to **Minecraft 26.2** (Fabric) with an in-game control panel and many bug fixes.

Tell it what you want and it works out how to get it: ask for diamonds from an empty inventory and it gathers wood, crafts tools, mines and smelts iron, finds fuel, and mines the diamonds. It can collect whole lists of items and build Litematica schematics, gathering and crafting the materials first.

All credit for the original bot goes to the AltoClef authors (TacoTechnica and contributors). Pathing and building run on [Baritone](https://github.com/cabaletta/baritone), which is bundled.

## What's different from AltoClef

- **Runs on Minecraft 26.2** with Fabric Loader 0.19.5, Fabric API 0.154.2+26.2, Mojang mappings and Baritone 1.19.0. Upstream AltoClef targets Minecraft 1.18.2.
- **Control panel.** Press **J** in-game:
  - **Tasks** shows what the bot is doing, how long it has been running, and how the last task ended. Request any item (Tab completes names), queue several items into one request, or use presets (wood, iron, diamonds, food, iron gear, beat the game).
  - **Build** lists schematics from your `schematics` folder and can build the active Litematica placement.
  - **Console** runs any command, with history and one-click tools.
  - **Stop** cancels the current task. `Ctrl+K` does the same from anywhere.
- **Task HUD** redrawn as a compact panel with elapsed time and the current step highlighted.
- **Litematica building** of `.litematic`, `.schem` and `.schematic` files and active placements, with the bot gathering and crafting materials before building and resuming after interruptions.
- **Fixes** found by running the bot in packaged game sessions. Container tracking survived double clicks and server lag. Crafting no longer loops forever on over-filled grids, cursor items, or recipe-book output. Leftover crafting ingredients are returned to the inventory. Partly full stacks get topped up. Baritone no longer pauses because it thinks a carried chest is missing.

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/) **0.19.5+** for Minecraft **26.2** and Java **25+**.
2. Put [Fabric API](https://modrinth.com/mod/fabric-api) **0.154.2+26.2** in your `mods` folder.
3. Download `kiaclef-1.0.0.jar` from [Releases](https://github.com/ran4om/kiaclef/releases) and put it in `mods`.
4. Optional, for `@build placement`: [Litematica](https://modrinth.com/mod/litematica) 0.28.8 and MaLiLib 0.29.6.

Baritone is bundled inside Kiaclef. **Don't install a separate Baritone or AltoClef jar alongside it.** Kiaclef keeps AltoClef's internal mod id, so its settings stay in the same `altoclef/` folder and it can't be loaded next to AltoClef.

## Usage

Press **J** for the control panel, or type commands in chat with the `@` prefix:

```text
@get diamond 3
@get [chest 2, stick 16]
@list
@build myhouse.litematic
@build placement
@stop
```

See [usage.md](usage.md) for all commands and settings, and [INSTALL_26.2.md](INSTALL_26.2.md) for build instructions, test modes, and detailed verification notes.

## Status and limits

Version 1.0.0 was checked by unit tests (1,383 passing) and by automated packaged game sessions on freshly generated worlds. These passed:

- **Diamonds from an empty inventory on untouched terrain.** Wood, tools, iron, smelting, and diamond, followed by an item list request and a schematic build.
- **Resource lists** (`@get [chest 2, stick 4]`) in a natural world.
- **Litematica building** from naturally gathered materials, including recovery after Mob Defense interrupts the build.
- **Containers:** looting, depositing, stashing, and overflow stacking.
- **Kelp crafting chain, Mob Defense task selection, and the control panel.**

Known limits:

- **Night survival is weak.** Mob Defense reacts late to creepers and Drowned, so long tasks in the open at night can end in death and lost items.
- **Not verified:** speedrunning (`@gamer`), Nether/End travel on natural terrain, real combat, multiplayer servers, large schematics, and most of the catalogue's items. Task wiring exists for 763 of 1,054 placeable items, but most have never been gathered in a test.

Bug reports are welcome in [Issues](https://github.com/ran4om/kiaclef/issues).

## Building from source

```sh
./gradlew build
```

This needs a Java 26 JDK for the Gradle toolchain; the mod itself targets Java 25. The jar is written to `build/libs/kiaclef-1.0.0.jar`.

## Credits and licenses

- **AltoClef**, © 2020 Adris Jautakas (TacoTechnica) and contributors, MIT License. Kiaclef is a modified version of it and is released under the same [MIT License](LICENSE).
- **Baritone** 1.19.0 by leijurv, Brady and contributors, bundled unmodified, [LGPL-3.0](LICENSE-BARITONE). Source: https://github.com/cabaletta/baritone
- **Jackson** (core, databind, annotations) 2.20 and **Apache Commons Lang** 3.20.0, bundled, Apache License 2.0.

[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) lists every bundled and optional component.

Kiaclef is not affiliated with Mojang or Microsoft, or with the AltoClef or Baritone projects.
