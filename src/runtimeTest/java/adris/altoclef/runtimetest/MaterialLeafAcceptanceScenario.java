package adris.altoclef.runtimetest;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.CraftInInventoryTask;
import adris.altoclef.tasks.container.CraftInTableTask;
import adris.altoclef.tasks.resources.MineAndCollectTask;
import adris.altoclef.tasks.resources.SatisfyMiningRequirementTask;
import adris.altoclef.tasksystem.Task;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/** Exercises catalogued material recipes through the normal @list and @get command paths. */
public final class MaterialLeafAcceptanceScenario {
    private enum Phase { NEW, PREPARING, WAIT_CLIENT_SYNC, LIST, GET_CINNABAR, VERIFY_CINNABAR,
        GET_SULFUR, VERIFY_FINAL, DONE, FAILED }

    // Each brick recipe outputs four. The polished variants also output four: four cinnabar
    // blocks make four polished cinnabar and then four bricks. Sulfur requires four spikes per
    // sulfur, four sulfur per polished batch, and four polished sulfur per brick batch.
    private static final int OUTPUT_BATCH = 4;
    private static final int OAK_LOG_SOURCES = 8;
    private static final int CINNABAR_BLOCK_SOURCES = 4;
    private static final int SULFUR_SPIKE_SOURCES = 16;
    private static final int PHASE_TIMEOUT_TICKS = 12000;
    private static final int VERIFY_TIMEOUT_TICKS = 400;
    private static final int POLL_INTERVAL_TICKS = 10;
    private static final int FLOOR_RADIUS = 24;

    private final AltoClef mod;
    private final Consumer<String> append;
    private final Consumer<String> failure;
    private final Runnable success;

    private volatile Phase phase = Phase.NEW;
    private long phaseStarted;
    private long nextPollAt;
    private UUID playerId;
    private BlockPos origin;
    private BlockPos floorOrigin;
    private AABB fixtureBounds;
    private int oakLogsBefore;
    private int cinnabarBlocksBefore;
    private int sulfurSpikesBefore;
    private Item cinnabarBricks;
    private Item sulfurBricks;
    private Item cinnabar;
    private Item sulfurSpike;
    private volatile boolean setupReady;
    private volatile String setupState = "not-started";
    private volatile String commandCompletion;
    private volatile String commandFailure;
    private volatile boolean pollOutstanding;
    private volatile boolean pollReady;
    private volatile String pollState = "not-polled";
    private volatile int serverCinnabarBricks;
    private volatile int serverSulfurBricks;
    private volatile int serverCinnabar;
    private volatile int serverSulfurSpikes;
    private volatile int serverOakLogs;
    private volatile int serverOakLogBlocks;
    private volatile int serverCinnabarBlocks;
    private volatile int serverSulfurSpikeBlocks;
    private volatile boolean serverUiClean;
    private volatile java.util.List<ItemStack> serverInventory = java.util.List.of();
    private boolean sawMiningTask;
    private boolean sawCraftingTableTask;
    private boolean sawInventoryCraftTask;
    private boolean sawWoodRequirementTask;

    public MaterialLeafAcceptanceScenario(AltoClef mod, Consumer<String> append,
                                          Consumer<String> failure, Runnable success) {
        this.mod = Objects.requireNonNull(mod, "mod");
        this.append = Objects.requireNonNull(append, "append");
        this.failure = Objects.requireNonNull(failure, "failure");
        this.success = Objects.requireNonNull(success, "success");
    }

    /** Call once per client tick until the supplied success or failure callback runs. */
    public void tick() {
        if (phase == Phase.DONE || phase == Phase.FAILED) return;
        if (phase == Phase.NEW) {
            beginSetup();
            return;
        }
        if (phase == Phase.PREPARING) {
            if (setupReady) {
                if (!"ok".equals(setupState)) {
                    fail("Material leaf fixture setup failed: " + setupState);
                } else {
                    append.accept("MATERIAL_LEAF_SETUP\tok\t" + setupSnapshot());
                    phase = Phase.WAIT_CLIENT_SYNC;
                    phaseStarted = clientTickCount();
                }
            } else if (timedOut(PHASE_TIMEOUT_TICKS)) {
                fail("Timed out preparing material leaf fixture: " + setupState);
            }
            return;
        }

        observeTaskTree();
        if (commandFailure != null) {
            fail("Material leaf command failed during " + phase + ": " + commandFailure);
            return;
        }
        if (phase == Phase.WAIT_CLIENT_SYNC) {
            if (clientFixtureIsSynchronized()) startListCommand();
            else if (timedOut(VERIFY_TIMEOUT_TICKS)) {
                fail("Material source fixture did not synchronize: " + clientSnapshot());
            }
            return;
        }
        if (phase == Phase.LIST) {
            if ("list".equals(commandCompletion)) {
                commandCompletion = null;
                if (!catalogueIsReady()) {
                    fail("@list completed, but one or more new material source/recipe mappings are missing");
                } else {
                    append.accept("ASSERT\t@list success callback observed; catalogue entries cinnabar, sulfur_spike, cinnabar_bricks, and sulfur_bricks exist; rendered list output not inspected");
                    startCinnabarCommand();
                }
            } else if (timedOut(PHASE_TIMEOUT_TICKS)) {
                fail("Timed out waiting for normal @list completion");
            }
            return;
        }
        if (phase == Phase.GET_CINNABAR) {
            if ("cinnabar".equals(commandCompletion)) {
                commandCompletion = null;
                phase = Phase.VERIFY_CINNABAR;
                phaseStarted = clientTickCount();
                nextPollAt = phaseStarted;
                requestServerPoll();
            } else if (timedOut(PHASE_TIMEOUT_TICKS)) {
                fail("Timed out during @get cinnabar_bricks " + OUTPUT_BATCH
                        + "; tasks=" + mod.getUserTaskChain().getTasks());
            }
            return;
        }
        if (phase == Phase.VERIFY_CINNABAR) {
            if (pollReady) {
                pollReady = false;
                append.accept("MATERIAL_LEAF_CINNABAR_POLL\t" + pollState);
                if (cinnabarStageIsValid()) {
                    append.accept("ASSERT\t@get cinnabar_bricks " + OUTPUT_BATCH
                            + " recursively gathered wood, mined cinnabar, and crafted one exact recipe batch");
                    startSulfurCommand();
                    return;
                }
                if (timedOut(VERIFY_TIMEOUT_TICKS)) {
                    fail("Cinnabar recipe batch did not reach its exact client/server checkpoint: server="
                            + pollState + ", client=" + clientSnapshot() + ", tasks="
                            + mod.getUserTaskChain().getTasks());
                } else {
                    nextPollAt = clientTickCount() + POLL_INTERVAL_TICKS;
                }
            } else if (timedOut(VERIFY_TIMEOUT_TICKS)) {
                fail("Timed out verifying cinnabar output: " + pollState + ", client=" + clientSnapshot());
            } else if (!pollOutstanding && clientTickCount() >= nextPollAt) {
                requestServerPoll();
            }
            return;
        }
        if (phase == Phase.GET_SULFUR) {
            if ("sulfur".equals(commandCompletion)) {
                commandCompletion = null;
                phase = Phase.VERIFY_FINAL;
                phaseStarted = clientTickCount();
                nextPollAt = phaseStarted;
                requestServerPoll();
            } else if (timedOut(PHASE_TIMEOUT_TICKS)) {
                fail("Timed out during @get sulfur_bricks " + OUTPUT_BATCH
                        + "; tasks=" + mod.getUserTaskChain().getTasks());
            }
            return;
        }
        if (phase == Phase.VERIFY_FINAL) {
            if (pollReady) {
                pollReady = false;
                append.accept("MATERIAL_LEAF_FINAL_POLL\t" + pollState);
                if (finalStageIsValid() && clientMatchesExpected()) {
                    finishSuccessfully();
                } else if (timedOut(VERIFY_TIMEOUT_TICKS)) {
                    fail("Final material recipe state mismatch: server=" + pollState
                            + ", client=" + clientSnapshot());
                } else {
                    nextPollAt = clientTickCount() + POLL_INTERVAL_TICKS;
                }
            } else if (timedOut(VERIFY_TIMEOUT_TICKS)) {
                fail("Timed out verifying final material recipes: server=" + pollState
                        + ", client=" + clientSnapshot());
            } else if (!pollOutstanding && clientTickCount() >= nextPollAt) {
                requestServerPoll();
            }
        }
    }

    private void beginSetup() {
        Minecraft client = Minecraft.getInstance();
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null || client.player == null || client.level == null) {
            fail("Material leaf acceptance requires an active integrated server and client player");
            return;
        }
        if (!catalogueIsReady()) {
            fail("Material leaf acceptance requires the cinnabar and sulfur catalog/recipe mappings");
            return;
        }
        playerId = client.player.getUUID();
        cinnabarBricks = item("cinnabar_bricks");
        sulfurBricks = item("sulfur_bricks");
        cinnabar = item("cinnabar");
        sulfurSpike = item("sulfur_spike");
        mod.cancelUserTask();
        phase = Phase.PREPARING;
        phaseStarted = clientTickCount();
        server.execute(() -> prepareServerFixture(server));
        append.accept("SKIP\truntimeStart=materialleaf\tnatural resource discovery and default world generation skipped; normal @get recursively gathers supplied oak logs, cinnabar blocks, and sulfur spikes");
        append.accept("MATERIAL_LEAF_SETUP\tqueued\tplayer inventory empty; no tools or recipe outputs supplied; exact output batch="
                + OUTPUT_BATCH);
    }

    private boolean catalogueIsReady() {
        return TaskCatalogue.taskExists("cinnabar") && TaskCatalogue.taskExists("sulfur_spike")
                && TaskCatalogue.taskExists("cinnabar_bricks") && TaskCatalogue.taskExists("sulfur_bricks")
                && item("cinnabar_bricks") != null && item("sulfur_bricks") != null
                && item("cinnabar") != null && item("sulfur_spike") != null;
    }

    private static Item item(String name) {
        Item[] matches = TaskCatalogue.getItemMatches(name);
        return matches.length == 0 ? null : matches[0];
    }

    private void prepareServerFixture(MinecraftServer server) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) {
            publishSetupFailure("player=null");
            return;
        }
        ServerLevel level = (ServerLevel) player.level();
        BlockPos playerPos = player.blockPosition();
        floorOrigin = playerPos.below().immutable();
        origin = playerPos.immutable();
        int floorY = floorOrigin.getY();
        AABB bounds = new AABB(playerPos.getX() - FLOOR_RADIUS, floorY - 1, playerPos.getZ() - FLOOR_RADIUS,
                playerPos.getX() + FLOOR_RADIUS + 1, floorY + 13,
                playerPos.getZ() + FLOOR_RADIUS + 1);
        fixtureBounds = bounds;

        player.closeContainer();
        player.getInventory().clearContent();
        for (int slot = 1; slot <= 4; slot++) player.inventoryMenu.getSlot(slot).set(ItemStack.EMPTY);
        player.inventoryMenu.setCarried(ItemStack.EMPTY);
        player.inventoryMenu.broadcastChanges();
        player.setGameMode(GameType.SURVIVAL);
        player.setHealth(player.getMaxHealth());
        player.getFoodData().setFoodLevel(20);
        player.getFoodData().setSaturation(5.0f);

        int minX = playerPos.getX() - FLOOR_RADIUS;
        int maxX = playerPos.getX() + FLOOR_RADIUS;
        int minZ = playerPos.getZ() - FLOOR_RADIUS;
        int maxZ = playerPos.getZ() + FLOOR_RADIUS;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                level.getChunkAt(new BlockPos(x, floorY, z));
                level.setBlock(new BlockPos(x, floorY, z), Blocks.STONE.defaultBlockState(), 3);
                for (int y = floorY + 1; y <= floorY + 12; y++) {
                    level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }

        // A real, mineable oak-log source supports the automatic wooden-pickaxe prerequisite.
        for (int dx = -4; dx <= -2; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == -3 && dz == 0) continue;
                level.setBlock(new BlockPos(playerPos.getX() + dx, floorY + 1,
                        playerPos.getZ() + dz), Blocks.OAK_LOG.defaultBlockState(), 3);
            }
        }

        // Exact batch fixtures: 4 cinnabar blocks -> 4 polished cinnabar -> 4 bricks;
        // 16 sulfur spikes -> 4 sulfur -> 4 polished sulfur -> 4 bricks.
        int cinnabarX = playerPos.getX() + 8;
        int cinnabarZ = playerPos.getZ() - 2;
        for (int dx = 0; dx < 2; dx++) {
            for (int dz = 0; dz < 2; dz++) {
                level.setBlock(new BlockPos(cinnabarX + dx, floorY + 1, cinnabarZ + dz),
                        Blocks.CINNABAR.defaultBlockState(), 3);
            }
        }
        int sulfurX = playerPos.getX() + 8;
        int sulfurZ = playerPos.getZ() + 4;
        for (int dx = 0; dx < 4; dx++) {
            for (int dz = 0; dz < 4; dz++) {
                level.setBlock(new BlockPos(sulfurX + dx, floorY + 1, sulfurZ + dz),
                        Blocks.SULFUR_SPIKE.defaultBlockState(), 3);
            }
        }

        for (ItemEntity drop : level.getEntitiesOfClass(ItemEntity.class, bounds)) drop.discard();
        oakLogsBefore = countBlocks(level, bounds, Blocks.OAK_LOG);
        cinnabarBlocksBefore = countBlocks(level, bounds, Blocks.CINNABAR);
        sulfurSpikesBefore = countBlocks(level, bounds, Blocks.SULFUR_SPIKE);
        boolean valid = player.getInventory().isEmpty() && cleanUi(player)
                && count(player, cinnabarBricks) == 0 && count(player, sulfurBricks) == 0
                && count(player, cinnabar) == 0 && count(player, sulfurSpike) == 0
                && oakLogsBefore == OAK_LOG_SOURCES
                && cinnabarBlocksBefore == CINNABAR_BLOCK_SOURCES
                && sulfurSpikesBefore == SULFUR_SPIKE_SOURCES;
        setupState = valid ? "ok" : "seed-invalid:" + serverSnapshot(player, level);
        setupReady = true;
    }

    private void startListCommand() {
        phase = Phase.LIST;
        phaseStarted = clientTickCount();
        runCommand("list", "list");
    }

    private void startCinnabarCommand() {
        resetTaskEvidence();
        phase = Phase.GET_CINNABAR;
        phaseStarted = clientTickCount();
        runCommand("get cinnabar_bricks " + OUTPUT_BATCH, "cinnabar");
    }

    private void startSulfurCommand() {
        resetTaskEvidence();
        phase = Phase.GET_SULFUR;
        phaseStarted = clientTickCount();
        runCommand("get sulfur_bricks " + OUTPUT_BATCH, "sulfur");
    }

    private void runCommand(String body, String completionTag) {
        var previous = mod.getUserTaskChain().getLastCompletionSnapshot();
        String command = mod.getModSettings().getCommandPrefix() + body;
        append.accept("COMMAND\t" + command + "\tseededTools=none\tmanualInputsAfterSetup=none");
        try {
            AltoClef.getCommandExecutor().execute(command, () -> {
                if (completionTag.equals("list")) {
                    commandCompletion = completionTag;
                    return;
                }
                var completion = mod.getUserTaskChain().getLastCompletionSnapshot();
                if (completion == null || completion == previous) {
                    commandFailure = command + " callback had no new completion snapshot";
                } else if (completion.failure() != null || completion.cancelled()) {
                    commandFailure = completion.failure() == null ? "cancelled" : completion.failure().reason();
                } else {
                    commandCompletion = completionTag;
                }
            }, error -> commandFailure = command + ": " + error.getMessage());
        } catch (Throwable error) {
            commandFailure = "could not execute " + command + ": " + error;
        }
    }

    private void observeTaskTree() {
        if (phase != Phase.GET_CINNABAR && phase != Phase.GET_SULFUR) return;
        Task task = mod.getUserTaskChain().getCurrentTask();
        if (task == null) return;
        sawMiningTask |= task.thisOrChildSatisfies(child -> child instanceof MineAndCollectTask);
        sawCraftingTableTask |= task.thisOrChildSatisfies(child -> child instanceof CraftInTableTask);
        sawInventoryCraftTask |= task.thisOrChildSatisfies(child -> child instanceof CraftInInventoryTask);
        sawWoodRequirementTask |= task.thisOrChildSatisfies(child -> child instanceof SatisfyMiningRequirementTask);
    }

    private void resetTaskEvidence() {
        sawMiningTask = false;
        sawCraftingTableTask = false;
        sawInventoryCraftTask = false;
        sawWoodRequirementTask = false;
    }

    private void requestServerPoll() {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null || pollOutstanding) return;
        pollOutstanding = true;
        pollReady = false;
        nextPollAt = clientTickCount() + POLL_INTERVAL_TICKS;
        UUID checkingPlayer = playerId;
        AABB bounds = fixtureBounds;
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(checkingPlayer);
            ServerLevel level = player == null ? null : (ServerLevel) player.level();
            serverCinnabarBricks = player == null ? 0 : count(player, cinnabarBricks);
            serverSulfurBricks = player == null ? 0 : count(player, sulfurBricks);
            serverCinnabar = player == null ? 0 : count(player, cinnabar);
            serverSulfurSpikes = player == null ? 0 : count(player, sulfurSpike);
            serverOakLogs = player == null ? 0 : count(player, Items.OAK_LOG);
            serverUiClean = player != null && cleanUi(player) && player.isAlive()
                    && player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL;
            java.util.List<ItemStack> inventory = new java.util.ArrayList<>();
            if (player != null) for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
                inventory.add(player.getInventory().getItem(slot).copy());
            }
            serverInventory = java.util.List.copyOf(inventory);
            serverOakLogBlocks = level == null ? 0 : countBlocks(level, bounds, Blocks.OAK_LOG);
            serverCinnabarBlocks = level == null ? 0 : countBlocks(level, bounds, Blocks.CINNABAR);
            serverSulfurSpikeBlocks = level == null ? 0 : countBlocks(level, bounds, Blocks.SULFUR_SPIKE);
            pollState = player == null || level == null ? "player/level missing" : serverSnapshot(player, level);
            pollOutstanding = false;
            pollReady = true;
        });
    }

    private boolean cinnabarStageIsValid() {
        Minecraft client = Minecraft.getInstance();
        return client.player != null && client.level != null
                && serverCinnabarBricks == OUTPUT_BATCH
                && count(client.player, cinnabarBricks) == serverCinnabarBricks
                && serverCinnabarBlocks < cinnabarBlocksBefore
                && serverOakLogBlocks < oakLogsBefore
                && serverUiClean && cleanUi(client.player)
                && sawMiningTask && sawCraftingTableTask && sawInventoryCraftTask && sawWoodRequirementTask;
    }

    private boolean finalStageIsValid() {
        Minecraft client = Minecraft.getInstance();
        return client.player != null && client.level != null
                && serverCinnabarBricks == OUTPUT_BATCH && serverSulfurBricks == OUTPUT_BATCH
                && count(client.player, cinnabarBricks) == serverCinnabarBricks
                && count(client.player, sulfurBricks) == serverSulfurBricks
                && serverCinnabarBlocks < cinnabarBlocksBefore
                && serverSulfurSpikeBlocks < sulfurSpikesBefore
                && serverOakLogBlocks < oakLogsBefore
                && serverUiClean && cleanUi(client.player)
                && sawMiningTask && sawInventoryCraftTask;
    }

    private boolean clientMatchesExpected() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || !cleanUi(client.player)
                || count(client.player, cinnabarBricks) != OUTPUT_BATCH
                || count(client.player, sulfurBricks) != OUTPUT_BATCH) return false;
        if (client.player.getInventory().getContainerSize() != serverInventory.size()) return false;
        for (int slot = 0; slot < serverInventory.size(); slot++) {
            if (!ItemStack.matches(client.player.getInventory().getItem(slot), serverInventory.get(slot))) return false;
        }
        return true;
    }

    private boolean clientFixtureIsSynchronized() {
        Minecraft client = Minecraft.getInstance();
        return client.player != null && client.level != null
                && client.player.getInventory().isEmpty() && cleanUi(client.player)
                && count(client.player, cinnabarBricks) == 0 && count(client.player, sulfurBricks) == 0
                && countBlocks(client.level, fixtureBounds, Blocks.OAK_LOG) == oakLogsBefore
                && countBlocks(client.level, fixtureBounds, Blocks.CINNABAR) == cinnabarBlocksBefore
                && countBlocks(client.level, fixtureBounds, Blocks.SULFUR_SPIKE) == sulfurSpikesBefore;
    }

    private static int countBlocks(net.minecraft.world.level.Level level, AABB bounds, Block block) {
        int count = 0;
        BlockPos min = BlockPos.containing(bounds.minX, bounds.minY, bounds.minZ);
        BlockPos max = BlockPos.containing(Math.nextDown(bounds.maxX), Math.nextDown(bounds.maxY), Math.nextDown(bounds.maxZ));
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (level.getBlockState(pos).is(block)) count++;
        }
        return count;
    }

    private static int count(Player player, Item item) {
        if (item == null) return 0;
        int result = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) result += stack.getCount();
        }
        return result;
    }

    private static boolean cleanUi(Player player) {
        if (player.containerMenu != player.inventoryMenu || !player.containerMenu.getCarried().isEmpty()) return false;
        for (int slot = 1; slot <= 4; slot++) {
            if (!player.inventoryMenu.getSlot(slot).getItem().isEmpty()) return false;
        }
        return true;
    }

    private long clientTickCount() {
        return Minecraft.getInstance().level == null ? 0 : Minecraft.getInstance().level.getGameTime();
    }

    private boolean timedOut(int limit) {
        return clientTickCount() - phaseStarted > limit;
    }

    private String setupSnapshot() {
        return "inventory=empty,oakLogSources=" + oakLogsBefore + ",cinnabarBlockSources="
                + cinnabarBlocksBefore + ",sulfurSpikeSources=" + sulfurSpikesBefore
                + ",seededOutputs=none,seededTools=none,naturalDiscovery=skipped,commands=@list then @get cinnabar_bricks "
                + OUTPUT_BATCH + " then @get sulfur_bricks " + OUTPUT_BATCH;
    }

    private String serverSnapshot(ServerPlayer player, ServerLevel level) {
        return "cinnabarBricks=" + count(player, cinnabarBricks) + ",sulfurBricks=" + count(player, sulfurBricks)
                + ",cinnabar=" + count(player, cinnabar) + ",sulfurSpikes=" + count(player, sulfurSpike)
                + ",oakLogs=" + count(player, Items.OAK_LOG) + ",blocks{oak="
                + countBlocks(level, fixtureBounds, Blocks.OAK_LOG) + ",cinnabar="
                + countBlocks(level, fixtureBounds, Blocks.CINNABAR) + ",sulfurSpike="
                + countBlocks(level, fixtureBounds, Blocks.SULFUR_SPIKE) + "},uiClean=" + cleanUi(player);
    }

    private String clientSnapshot() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return "player/level missing";
        return "cinnabarBricks=" + count(client.player, cinnabarBricks)
                + ",sulfurBricks=" + count(client.player, sulfurBricks)
                + ",cinnabar=" + count(client.player, cinnabar)
                + ",sulfurSpikes=" + count(client.player, sulfurSpike)
                + ",oakLogs=" + count(client.player, Items.OAK_LOG)
                + ",uiClean=" + cleanUi(client.player);
    }

    private void finishSuccessfully() {
        phase = Phase.DONE;
        append.accept("ASSERT\t@list and recursive @get commands consumed ordinary oak-log/cinnabar/sulfur-spike fixture sources with no tools or outputs seeded");
        append.accept("ASSERT\tclient/server inventories exactly match: cinnabar_bricks=" + OUTPUT_BATCH
                + ", sulfur_bricks=" + OUTPUT_BATCH + "; clean inventory menu/cursor/crafting grid");
        append.accept("MATERIAL_LEAF_ACCEPTANCE\tPASS\tnew cinnabar and sulfur base mappings were gathered and crafted through normal commands in exact recipe batches");
        success.run();
    }

    private void fail(String reason) {
        if (phase == Phase.FAILED || phase == Phase.DONE) return;
        phase = Phase.FAILED;
        mod.cancelUserTask();
        failure.accept(reason);
    }

    private void publishSetupFailure(String reason) {
        setupState = reason;
        setupReady = true;
    }
}
