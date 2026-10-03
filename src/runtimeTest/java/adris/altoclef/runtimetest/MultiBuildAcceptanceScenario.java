package adris.altoclef.runtimetest;

import adris.altoclef.AltoClef;
import adris.altoclef.util.schematic.SchematicLoader;
import adris.altoclef.util.schematic.SchematicSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/** Active multi-region Litematica build that gathers every authored material from ordinary fixtures. */
public final class MultiBuildAcceptanceScenario {
    private enum Phase { NEW, PREPARING, WAIT_CLIENT_SYNC, BUILDING, VERIFYING, DONE, FAILED }

    private static final int SETUP_TIMEOUT_TICKS = 300;
    private static final int BUILD_TIMEOUT_TICKS = 12 * 60 * 20;
    private static final int VERIFY_TIMEOUT_TICKS = 300;
    private static final int WIDTH = 4;
    private static final int HEIGHT = 2;
    private static final int LENGTH = 1;

    private final AltoClef mod;
    private final Consumer<String> append;
    private final Consumer<String> failure;
    private final Runnable success;

    private Phase phase = Phase.NEW;
    private long phaseStarted;
    private long lastPollTick;
    private UUID playerId;
    private BlockPos arenaFloor;
    private BlockPos placementOrigin;
    private Path fixturePath;
    private SchematicSnapshot snapshot;
    private volatile boolean setupReady;
    private volatile boolean setupValid;
    private volatile String setupState = "not-started";
    private volatile boolean pollOutstanding;
    private volatile boolean pollReady;
    private volatile boolean pollPassed;
    private volatile String pollState = "not-polled";
    private volatile String serverInventory = "not-polled";
    private volatile List<ItemStack> serverInventoryStacks = List.of();
    private boolean buildCallbackReady;
    private String buildCallbackState = "waiting";
    private boolean buildTaskSeen;
    private boolean emptyInventoryGatherSeen;
    private boolean miningTaskSeen;
    private boolean recipeResourceTaskSeen;
    private boolean chestCraftSeen;
    private boolean torchCraftSeen;

    public MultiBuildAcceptanceScenario(AltoClef mod, Consumer<String> append,
                                        Consumer<String> failure, Runnable success) {
        this.mod = Objects.requireNonNull(mod, "mod");
        this.append = Objects.requireNonNull(append, "append");
        this.failure = Objects.requireNonNull(failure, "failure");
        this.success = Objects.requireNonNull(success, "success");
    }

    public void tick(long gameTick) {
        if (phase == Phase.DONE || phase == Phase.FAILED) return;
        if (phase == Phase.NEW) {
            beginSetup(gameTick);
            return;
        }
        if (phase == Phase.PREPARING) {
            if (setupReady) {
                if (!setupValid) {
                    fail("Multi-build fixture setup failed: " + setupState);
                } else {
                    append.accept("MULTIBUILD_SETUP\t" + setupState);
                    phase = Phase.WAIT_CLIENT_SYNC;
                    phaseStarted = gameTick;
                }
            } else if (gameTick - phaseStarted > SETUP_TIMEOUT_TICKS) {
                fail("Timed out preparing multi-build fixture: " + setupState);
            }
            return;
        }
        if (phase == Phase.WAIT_CLIENT_SYNC) {
            if (clientSeedMatches()) startBuildCommand(gameTick);
            else if (gameTick - phaseStarted > SETUP_TIMEOUT_TICKS) {
                fail("Multi-build fixture did not synchronize: " + clientSnapshot());
            }
            return;
        }
        if (phase == Phase.BUILDING) {
            if (buildCallbackReady) {
                if (!"ok".equals(buildCallbackState)) {
                    fail("@build placement did not finish normally: " + buildCallbackState);
                } else if (!prerequisiteTraceComplete()) {
                    fail("@build placement completed without observed gather/crafting prerequisites: "
                            + traceSnapshot());
                } else {
                    append.accept("ASSERT\tactive Litematica build finished normally after empty-inventory gathering and chest/torch crafting");
                    phase = Phase.VERIFYING;
                    phaseStarted = gameTick;
                    lastPollTick = 0;
                }
                return;
            }
            if (gameTick - phaseStarted > BUILD_TIMEOUT_TICKS) {
                fail("Timed out building active multi-region Litematica placement: "
                        + traceSnapshot() + ", client=" + clientSnapshot());
                return;
            }
        }
        if (phase == Phase.VERIFYING) {
            if (pollReady) {
                pollReady = false;
                append.accept("MULTIBUILD_SERVER_VERIFY\t" + pollState);
                if (pollPassed && clientMatchesFinalState()) {
                    append.accept("ASSERT\tall active placement cells, including authored AIR, match on server/client; survival and inventory UI state are clean");
                    append.accept("MULTIBUILD_INVENTORY\tserver=" + serverInventory
                            + "\tclient=" + clientInventorySnapshot());
                    append.accept("SUMMARY\tPASS\t26.2 runtimeStart=multibuild built the active two-region Litematica placement through @build placement from an empty inventory; oak log/plank, chest, and torch resources were gathered/crafted; all 8 block/AIR cells, full per-slot ItemStack server/client equality, survival, empty placed chest, and clean UI verified; canonical empty chest metadata accepted by file and active adapters");
                    phase = Phase.DONE;
                    success.run();
                } else if (gameTick - phaseStarted > VERIFY_TIMEOUT_TICKS) {
                    fail("Active multi-region build result mismatch: server=" + pollState
                            + ", client=" + clientSnapshot());
                }
            } else if (gameTick - lastPollTick >= 10) {
                requestFinalPoll(gameTick);
            }
        }
    }

    /** Called by the acceptance harness while the real user command is running. */
    public void observeTaskTrace(List<String> entries, int clientInventoryCount) {
        if (phase != Phase.BUILDING) return;
        for (String entry : entries) {
            if (entry.contains("BuildSchematicTask")) buildTaskSeen = true;
            if (entry.contains("CataloguedResourceTask") && clientInventoryCount == 0) {
                emptyInventoryGatherSeen = true;
            }
            if (entry.contains("MineAndCollectTask")) miningTaskSeen = true;
            if (entry.contains("CollectRecipeCataloguedResourcesTask")) recipeResourceTaskSeen = true;
            if (entry.contains("CraftGenericWithRecipeBooksTask")) {
                if (entry.contains("CraftingRecipe{craft chest}")) chestCraftSeen = true;
                if (entry.contains("CraftingRecipe{craft torch}")) torchCraftSeen = true;
            }
        }
    }

    private void beginSetup(long gameTick) {
        Minecraft client = Minecraft.getInstance();
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null || client.player == null || client.level == null) {
            fail("Multi-build acceptance requires an active integrated server and client player");
            return;
        }
        playerId = client.player.getUUID();
        arenaFloor = client.player.blockPosition().below();
        placementOrigin = client.player.blockPosition().offset(8, 0, 8);
        fixturePath = client.gameDirectory.toPath().resolve("schematics/multibuild-active-" + UUID.randomUUID() + ".litematic");
        phase = Phase.PREPARING;
        phaseStarted = gameTick;
        try {
            Files.createDirectories(fixturePath.getParent());
            writeFixture(fixturePath);
            var fileSnapshot = SchematicLoader.load(fixturePath);
            validateSnapshot(new SchematicSnapshot(fileSnapshot.name(), fileSnapshot.schematic(), placementOrigin));
            append.accept("ASSERT\tLitematic file loader accepted canonical empty chest metadata and all 8 authored cells");
            verifyFileCustomChestRejected();
            installActivePlacement(client);
        } catch (Exception error) {
            fail("Could not create active multi-region Litematica fixture: " + error);
            return;
        }
        server.execute(() -> prepareServerFixture(server));
        append.accept("MULTIBUILD_SETUP\tqueued\tactive placement requires oak_log=1, oak_planks=1, chest=1, torch=2\torigin="
                + placementOrigin + "\tordinary source fixtures=oak logs, stone floor, coal ore");
    }

    private void installActivePlacement(Minecraft client) throws Exception {
        Class<?> schematicClass = Class.forName("fi.dy.masa.litematica.schematic.LitematicaSchematic");
        Object schematic = schematicClass.getMethod("createFromFile", Path.class, String.class)
                .invoke(null, fixturePath.getParent(), fixturePath.getFileName().toString());
        if (schematic == null) throw new IllegalStateException("Litematica rejected the two-region fixture");
        Class<?> placementClass = Class.forName("fi.dy.masa.litematica.schematic.placement.SchematicPlacement");
        Object placement = placementClass.getMethod("createFor", schematicClass, BlockPos.class,
                        String.class, boolean.class, boolean.class)
                .invoke(null, schematic, placementOrigin, "AltoClef multi-build acceptance", true, true);
        Object manager = Class.forName("fi.dy.masa.litematica.data.DataManager")
                .getMethod("getSchematicPlacementManager").invoke(null);
        @SuppressWarnings("unchecked")
        List<Object> existing = (List<Object>) manager.getClass().getMethod("getAllSchematicsPlacements")
                .invoke(manager);
        if (!existing.isEmpty()) {
            throw new IllegalStateException("multibuild requires a fresh Litematica placement list; found "
                    + existing.size() + " pre-existing placements");
        }
        manager.getClass().getMethod("addSchematicPlacement", placementClass, boolean.class)
                .invoke(manager, placement, true);
        @SuppressWarnings("unchecked")
        List<Object> placements = (List<Object>) manager.getClass().getMethod("getAllSchematicsPlacements")
                .invoke(manager);
        if (placements.size() != 1 || placements.get(0) != placement) {
            throw new IllegalStateException("Litematica did not install the acceptance fixture at placement index 0");
        }
        snapshot = SchematicLoader.loadActiveLitematica(0);
        validateSnapshot(snapshot);
        verifyActiveCustomChestRejected(schematic);
        append.accept("MULTIBUILD_PLACEMENT\tindex=0\tregions=2\torigin=" + snapshot.origin()
                + "\tdimensions=" + snapshot.schematic().widthX() + "x"
                + snapshot.schematic().heightY() + "x" + snapshot.schematic().lengthZ());
    }

    private void verifyFileCustomChestRejected() throws IOException {
        CompoundTag source;
        try (var input = Files.newInputStream(fixturePath)) {
            source = NbtIo.readCompressed(input, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
        }
        CompoundTag payload = source.getCompound("Regions").orElseThrow()
                .getCompound("Materials").orElseThrow().getList("TileEntities").orElseThrow()
                .getCompound(0).orElseThrow();
        payload.putString("CustomName", "unsupported acceptance payload");
        Path rejectedFile = fixturePath.resolveSibling("multibuild-custom-chest-rejection.litematic");
        try {
            NbtIo.writeCompressed(source, rejectedFile);
            boolean rejected = false;
            try {
                SchematicLoader.load(rejectedFile);
            } catch (IOException expected) {
                rejected = true;
                append.accept("MULTIBUILD_PAYLOAD_REJECT\tfile custom chest\t" + expected.getMessage());
            }
            if (!rejected) throw new IOException("File adapter accepted unsupported custom chest metadata");
        } finally {
            Files.deleteIfExists(rejectedFile);
        }
    }

    private void verifyActiveCustomChestRejected(Object schematic) throws Exception {
        Object raw = schematic.getClass().getMethod("getBlockEntityMapForRegion", String.class)
                .invoke(schematic, "Materials");
        if (!(raw instanceof java.util.Map<?, ?> payloads) || payloads.size() != 1) {
            throw new IllegalStateException("Active Litematica fixture must retain one empty chest payload");
        }
        Object payload = payloads.values().iterator().next();
        payload.getClass().getMethod("putString", String.class, String.class)
                .invoke(payload, "CustomName", "unsupported acceptance payload");
        try {
            boolean rejected = false;
            try {
                SchematicLoader.loadActiveLitematica(0);
            } catch (IOException expected) {
                rejected = true;
                append.accept("MULTIBUILD_PAYLOAD_REJECT\tactive custom chest\t" + expected.getMessage());
            }
            if (!rejected) throw new IOException("Active adapter accepted unsupported custom chest metadata");
        } finally {
            payload.getClass().getMethod("remove", String.class).invoke(payload, "CustomName");
        }
        validateSnapshot(SchematicLoader.loadActiveLitematica(0));
        append.accept("ASSERT\tactive Litematica adapter rejected custom chest metadata and accepted restored empty metadata");
    }

    private void prepareServerFixture(MinecraftServer server) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) {
            publishSetup(false, "player missing");
            return;
        }
        ServerLevel level = (ServerLevel) player.level();
        player.closeContainer();
        player.getInventory().clearContent();
        for (int slot = 1; slot <= 4; slot++) player.inventoryMenu.getSlot(slot).set(ItemStack.EMPTY);
        player.inventoryMenu.setCarried(ItemStack.EMPTY);
        player.inventoryMenu.broadcastChanges();
        player.setGameMode(GameType.SURVIVAL);

        for (int dx = -16; dx <= 16; dx++) {
            for (int dz = -16; dz <= 16; dz++) {
                BlockPos floor = arenaFloor.offset(dx, 0, dz);
                level.setBlock(floor, Blocks.STONE.defaultBlockState(), 3);
                for (int dy = 1; dy <= 12; dy++) level.setBlock(floor.above(dy), Blocks.AIR.defaultBlockState(), 3);
            }
        }
        int x = arenaFloor.getX(), y = arenaFloor.getY(), z = arenaFloor.getZ();
        for (int dx = 1; dx <= 2; dx++) {
            for (int dz : new int[]{-1, 1}) {
                for (int dy = 1; dy <= 8; dy++) {
                    level.setBlock(new BlockPos(x + dx, y + dy, z + dz), Blocks.OAK_LOG.defaultBlockState(), 3);
                }
            }
        }
        for (int i = 0; i < 8; i++) {
            level.setBlock(new BlockPos(x + 5 + i, y + 1, z + 2), Blocks.COAL_ORE.defaultBlockState(), 3);
            level.setBlock(new BlockPos(x + 5 + i, y + 1, z + 4), Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(new BlockPos(x + 5 + i, y + 1, z + 6), Blocks.AIR.defaultBlockState(), 3);
        }
        for (int dx = 0; dx < WIDTH; dx++) {
            for (int dy = 0; dy < HEIGHT; dy++) {
                for (int dz = 0; dz < LENGTH; dz++) {
                    level.setBlock(snapshot.origin().offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        boolean targetAreaClear = targetAreaIsClear(level);
        boolean valid = targetAreaClear && player.getInventory().isEmpty()
                && cleanUi(player) && player.isAlive()
                && player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL
                && level.getBlockState(arenaFloor).is(Blocks.STONE)
                && level.getBlockState(arenaFloor.offset(1, 1, 1)).is(Blocks.OAK_LOG)
                && level.getBlockState(arenaFloor.offset(5, 1, 2)).is(Blocks.COAL_ORE);
        publishSetup(valid, serverSnapshot(player, level));
    }

    private void startBuildCommand(long gameTick) {
        phase = Phase.BUILDING;
        phaseStarted = gameTick;
        append.accept("ASSERT\tserver/client inventory empty, survival, and all active placement cells initially AIR; no recipe output was seeded");
        append.accept("COMMAND\t@build placement\tmaterials=oak_log:1,oak_planks:1,chest:1,torch:2");
        var previous = mod.getUserTaskChain().getLastCompletionSnapshot();
        try {
            AltoClef.getCommandExecutor().execute(mod.getModSettings().getCommandPrefix() + "build placement", () -> {
                var completion = mod.getUserTaskChain().getLastCompletionSnapshot();
                if (completion == null || completion == previous) {
                    buildCallbackState = "missing new task completion snapshot";
                } else if (completion.failure() != null || completion.cancelled()) {
                    buildCallbackState = completion.failure() == null ? "cancelled" : completion.failure().reason();
                } else {
                    buildCallbackState = "ok";
                }
                buildCallbackReady = true;
            }, error -> {
                buildCallbackState = "command error: " + error.getMessage();
                buildCallbackReady = true;
            });
        } catch (Throwable error) {
            fail("Could not execute @build placement: " + error);
        }
    }

    private void requestFinalPoll(long gameTick) {
        Minecraft client = Minecraft.getInstance();
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null || pollOutstanding) return;
        pollOutstanding = true;
        pollReady = false;
        lastPollTick = gameTick;
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            ServerLevel level = player == null ? null : (ServerLevel) player.level();
            boolean cellsMatch = player != null && level != null && snapshotMatches(level, snapshot);
            boolean stateClean = player != null && player.isAlive()
                    && player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL && cleanUi(player);
            serverInventory = player == null ? "player missing" : inventorySnapshot(player);
            List<ItemStack> copiedStacks = new ArrayList<>();
            if (player != null) {
                for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
                    copiedStacks.add(player.getInventory().getItem(slot).copy());
                }
            }
            serverInventoryStacks = List.copyOf(copiedStacks);
            pollPassed = cellsMatch && stateClean;
            pollState = player == null || level == null ? "player/level missing"
                    : "cells=" + cellsMatch + ",cleanUI=" + cleanUi(player)
                    + ",survival=" + (player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL)
                    + ",alive=" + player.isAlive() + ",inventory=" + serverInventory
                    + ",world=" + worldSnapshot(level, snapshot) + ",valid=" + pollPassed;
            pollOutstanding = false;
            pollReady = true;
        });
    }

    private boolean clientSeedMatches() {
        Minecraft client = Minecraft.getInstance();
        return client.player != null && client.level != null && client.player.getInventory().isEmpty()
                && cleanUi(client.player) && targetAreaIsClear(client.level)
                && client.level.getBlockState(arenaFloor).is(Blocks.STONE);
    }

    private boolean clientMatchesFinalState() {
        Minecraft client = Minecraft.getInstance();
        return client.player != null && client.level != null && client.player.isAlive()
                && cleanUi(client.player) && snapshotMatches(client.level, snapshot)
                && inventoryMatches(client.player);
    }

    private boolean inventoryMatches(Player player) {
        if (serverInventoryStacks.size() != player.getInventory().getContainerSize()) return false;
        for (int slot = 0; slot < serverInventoryStacks.size(); slot++) {
            if (!ItemStack.matches(serverInventoryStacks.get(slot), player.getInventory().getItem(slot))) return false;
        }
        return true;
    }

    private boolean prerequisiteTraceComplete() {
        return buildTaskSeen && emptyInventoryGatherSeen && miningTaskSeen
                && recipeResourceTaskSeen && chestCraftSeen && torchCraftSeen;
    }

    private String traceSnapshot() {
        return "build=" + buildTaskSeen + ",emptyGather=" + emptyInventoryGatherSeen
                + ",mining=" + miningTaskSeen + ",recipeResources=" + recipeResourceTaskSeen
                + ",chestCraft=" + chestCraftSeen + ",torchCraft=" + torchCraftSeen;
    }

    private void validateSnapshot(SchematicSnapshot loaded) {
        if (loaded == null || loaded.schematic().widthX() != WIDTH
                || loaded.schematic().heightY() != HEIGHT || loaded.schematic().lengthZ() != LENGTH) {
            throw new IllegalStateException("active adapter returned unexpected dimensions: "
                    + (loaded == null ? "null" : loaded.schematic().widthX() + "x"
                    + loaded.schematic().heightY() + "x" + loaded.schematic().lengthZ()));
        }
        BlockState[][] expected = {
                {Blocks.OAK_LOG.defaultBlockState(), Blocks.OAK_PLANKS.defaultBlockState(),
                        Blocks.CHEST.defaultBlockState(), Blocks.AIR.defaultBlockState()},
                {Blocks.TORCH.defaultBlockState(), Blocks.TORCH.defaultBlockState(),
                        Blocks.AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()}
        };
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                BlockState actual = loaded.schematic().getDirect(x, y, 0);
                if (actual == null || !actual.equals(expected[y][x])) {
                    throw new IllegalStateException("active adapter state mismatch at local " + x + "," + y
                            + ": expected " + expected[y][x] + ", got " + actual);
                }
            }
        }
        if (!loaded.origin().equals(placementOrigin)) {
            throw new IllegalStateException("active adapter changed untransformed placement origin: expected="
                    + placementOrigin + ", actual=" + loaded.origin());
        }
    }

    private static boolean snapshotMatches(Level level, SchematicSnapshot expected) {
        if (level == null || expected == null) return false;
        for (int x = 0; x < expected.schematic().widthX(); x++) {
            for (int y = 0; y < expected.schematic().heightY(); y++) {
                for (int z = 0; z < expected.schematic().lengthZ(); z++) {
                    BlockState wanted = expected.schematic().getDirect(x, y, z);
                    if (wanted == null) return false;
                    BlockState actual = level.getBlockState(expected.origin().offset(x, y, z));
                    if (wanted.isAir() ? !actual.isAir() : !wanted.equals(actual)) return false;
                    if (wanted.is(Blocks.CHEST)) {
                        var entity = level.getBlockEntity(expected.origin().offset(x, y, z));
                        if (!(entity instanceof net.minecraft.world.level.block.entity.ChestBlockEntity chest)
                                || !chest.isEmpty()) return false;
                    }
                }
            }
        }
        return true;
    }

    private boolean targetAreaIsClear(Level level) {
        if (level == null || snapshot == null) return false;
        for (int x = 0; x < snapshot.schematic().widthX(); x++) {
            for (int y = 0; y < snapshot.schematic().heightY(); y++) {
                for (int z = 0; z < snapshot.schematic().lengthZ(); z++) {
                    if (!level.getBlockState(snapshot.origin().offset(x, y, z)).isAir()) return false;
                }
            }
        }
        return true;
    }

    private static boolean cleanUi(Player player) {
        if (player.containerMenu != player.inventoryMenu || !player.containerMenu.getCarried().isEmpty()) return false;
        for (int slot = 1; slot <= 4; slot++) {
            if (!player.containerMenu.getSlot(slot).getItem().isEmpty()) return false;
        }
        return true;
    }

    private static String worldSnapshot(Level level, SchematicSnapshot expected) {
        List<String> cells = new ArrayList<>();
        for (int y = 0; y < expected.schematic().heightY(); y++) {
            for (int x = 0; x < expected.schematic().widthX(); x++) {
                BlockPos position = expected.origin().offset(x, y, 0);
                cells.add("(" + x + "," + y + ",0)=" + level.getBlockState(position));
            }
        }
        return cells.toString();
    }

    private static String inventorySnapshot(Player player) {
        List<String> stacks = new ArrayList<>();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty()) stacks.add(stack.getItem() + "x" + stack.getCount());
        }
        return "{" + String.join(",", stacks) + "},menu=" + player.containerMenu.getClass().getSimpleName()
                + ",cursor=" + player.containerMenu.getCarried().getCount()
                + ",grid=" + craftingGridSnapshot(player);
    }

    private static String craftingGridSnapshot(Player player) {
        List<String> contents = new ArrayList<>();
        for (int slot = 1; slot <= 4; slot++) {
            ItemStack item = player.containerMenu.getSlot(slot).getItem();
            if (!item.isEmpty()) contents.add(item.getItem() + "x" + item.getCount());
        }
        return contents.toString();
    }

    private String clientInventorySnapshot() {
        Minecraft client = Minecraft.getInstance();
        return client.player == null ? "player missing" : inventorySnapshot(client.player);
    }

    private String clientSnapshot() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return "player/level missing";
        return "inventory=" + clientInventorySnapshot() + ",world=" + worldSnapshot(client.level, snapshot);
    }

    private String serverSnapshot(ServerPlayer player, ServerLevel level) {
        return "inventory=" + inventorySnapshot(player) + ",survival="
                + (player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL)
                + ",cleanUI=" + cleanUi(player) + ",sourceLogs="
                + level.getBlockState(arenaFloor.offset(1, 1, 1)) + ",sourceCoal="
                + level.getBlockState(arenaFloor.offset(5, 1, 2)) + ",target="
                + worldSnapshot(level, snapshot);
    }

    private void publishSetup(boolean valid, String state) {
        setupValid = valid;
        setupState = state + ",valid=" + valid;
        setupReady = true;
    }

    private void writeFixture(Path file) throws IOException {
        CompoundTag root = new CompoundTag();
        root.putInt("Version", 7);
        root.putInt("SubVersion", 1);
        root.putInt("MinecraftDataVersion", 0);
        CompoundTag metadata = new CompoundTag();
        metadata.putString("Name", "AltoClef runtime multi-build acceptance");
        metadata.putString("Author", "AltoClef runtime test");
        metadata.putString("Description", "Two regions: log, plank, chest and two torches; canonical empty chest metadata; no entities.");
        metadata.putInt("RegionCount", 2);
        metadata.putLong("TimeCreated", Instant.now().toEpochMilli());
        metadata.putLong("TimeModified", Instant.now().toEpochMilli());
        metadata.putLong("TotalBlocks", 5L);
        metadata.putLong("TotalVolume", WIDTH * HEIGHT * LENGTH);
        CompoundTag enclosing = new CompoundTag();
        enclosing.putInt("x", WIDTH);
        enclosing.putInt("y", HEIGHT);
        enclosing.putInt("z", LENGTH);
        metadata.put("EnclosingSize", enclosing);
        root.put("Metadata", metadata);

        CompoundTag regions = new CompoundTag();
        CompoundTag materials = region(0, 0, 0, WIDTH, 1, LENGTH,
                new BlockState[]{Blocks.AIR.defaultBlockState(), Blocks.OAK_LOG.defaultBlockState(),
                        Blocks.OAK_PLANKS.defaultBlockState(), Blocks.CHEST.defaultBlockState()},
                new int[]{1, 2, 3, 0});
        CompoundTag chestPayload = new CompoundTag();
        chestPayload.putString("id", "minecraft:chest");
        chestPayload.putInt("x", 2); chestPayload.putInt("y", 0); chestPayload.putInt("z", 0);
        chestPayload.put("Items", new ListTag());
        ListTag chestPayloads = new ListTag();
        chestPayloads.add(chestPayload);
        materials.put("TileEntities", chestPayloads);
        regions.put("Materials", materials);
        regions.put("Lights", region(0, 1, 0, WIDTH, 1, LENGTH,
                new BlockState[]{Blocks.AIR.defaultBlockState(), Blocks.TORCH.defaultBlockState()},
                new int[]{1, 1, 0, 0}));
        root.put("Regions", regions);
        NbtIo.writeCompressed(root, file);
    }

    private static CompoundTag region(int x, int y, int z, int sx, int sy, int sz,
                                      BlockState[] paletteStates, int[] indices) {
        CompoundTag region = new CompoundTag();
        CompoundTag position = new CompoundTag();
        position.putInt("x", x); position.putInt("y", y); position.putInt("z", z);
        region.put("Position", position);
        CompoundTag size = new CompoundTag();
        size.putInt("x", sx); size.putInt("y", sy); size.putInt("z", sz);
        region.put("Size", size);
        ListTag palette = new ListTag();
        for (BlockState state : paletteStates) palette.add(stateTag(state));
        region.put("BlockStatePalette", palette);
        int bitsPerBlock = Math.max(2, 32 - Integer.numberOfLeadingZeros(paletteStates.length - 1));
        long[] packed = new long[(indices.length * bitsPerBlock + 63) / 64];
        for (int index = 0; index < indices.length; index++) {
            int bit = index * bitsPerBlock;
            packed[bit >>> 6] |= ((long) indices[index]) << (bit & 63);
            if ((bit & 63) + bitsPerBlock > 64) {
                packed[(bit >>> 6) + 1] |= ((long) indices[index]) >>> (64 - (bit & 63));
            }
        }
        region.putLongArray("BlockStates", packed);
        region.put("Entities", new ListTag());
        region.put("TileEntities", new ListTag());
        region.put("BlockEntities", new ListTag());
        region.put("PendingBlockTicks", new ListTag());
        region.put("PendingFluidTicks", new ListTag());
        return region;
    }

    private static CompoundTag stateTag(BlockState state) {
        return net.minecraft.nbt.NbtUtils.writeBlockState(state);
    }

    private void fail(String reason) {
        if (phase == Phase.DONE || phase == Phase.FAILED) return;
        phase = Phase.FAILED;
        failure.accept(reason);
    }
}
