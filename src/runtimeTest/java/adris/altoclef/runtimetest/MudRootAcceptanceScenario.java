package adris.altoclef.runtimetest;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.CraftInInventoryTask;
import adris.altoclef.tasks.resources.MineAndCollectTask;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Acceptance for recursive mud and muddy mangrove roots acquisition through normal @get.
 * The fixture supplies only two mud blocks and two mangrove-root blocks; no tools or output
 * items are seeded. Run only in a disposable singleplayer world.
 */
public final class MudRootAcceptanceScenario {
    private enum Phase { NEW, PREPARING, WAIT_CLIENT_SYNC, GET_MUD, VERIFY_MUD,
        GET_MUDDY_ROOTS, VERIFY_FINAL, DONE, FAILED }

    private static final int TARGET_COUNT = 2;
    private static final int PHASE_TIMEOUT_TICKS = 6000;
    private static final int VERIFY_TIMEOUT_TICKS = 300;
    private static final int CLIENT_SYNC_TIMEOUT_TICKS = 200;
    private static final int POLL_INTERVAL_TICKS = 5;
    private static final int FLOOR_RADIUS = 12;

    private final AltoClef mod;
    private final Consumer<String> append;
    private final Consumer<String> failure;
    private final Runnable success;

    private volatile Phase phase = Phase.NEW;
    private long phaseStarted;
    private long nextPollAt;
    private UUID playerId;
    private AABB fixtureBounds;
    private List<BlockPos> mudSourcePositions = List.of();
    private List<BlockPos> rootsSourcePositions = List.of();
    private Item muddyRoots;

    private volatile boolean setupReady;
    private volatile String setupState = "not-started";
    private volatile String commandCompletion;
    private volatile String commandFailure;
    private volatile boolean pollOutstanding;
    private volatile boolean pollReady;
    private volatile String pollState = "not-polled";
    private volatile int serverMud;
    private volatile int serverMangroveRoots;
    private volatile int serverMuddyRoots;
    private volatile int serverMudBlocks;
    private volatile int serverRootsBlocks;
    private volatile float serverHealth;
    private volatile int serverFood;
    private volatile String serverInventory;
    private volatile List<ItemStack> serverStacks = List.of();
    private volatile boolean serverUiClean;
    private volatile boolean serverAlive;
    private volatile boolean serverSurvival;
    private boolean sawMudMining;
    private boolean sawRootsMining;
    private boolean sawInventoryCraft;

    public MudRootAcceptanceScenario(AltoClef mod, Consumer<String> append,
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
                    fail("Mud/root fixture setup failed: " + setupState);
                } else {
                    append.accept("MUD_ROOT_SETUP\tok\t" + setupSnapshot());
                    phase = Phase.WAIT_CLIENT_SYNC;
                    phaseStarted = clientTickCount();
                }
            } else if (timedOut(PHASE_TIMEOUT_TICKS)) {
                fail("Timed out preparing mud/root fixture: " + setupState);
            }
            return;
        }
        if (phase == Phase.WAIT_CLIENT_SYNC) {
            if (clientFixtureIsSynchronized()) {
                append.accept("MUD_ROOT_CLIENT_SEED\tok\t" + clientSnapshot());
                startMudCommand();
            } else if (timedOut(CLIENT_SYNC_TIMEOUT_TICKS)) {
                fail("Timed out waiting for client source sync: " + clientSnapshot());
            }
            return;
        }

        observeTasks();
        if (commandFailure != null) {
            fail("Mud/root command failed during " + phase + ": " + commandFailure);
            return;
        }
        if (phase == Phase.GET_MUD) {
            if ("mud".equals(commandCompletion)) {
                commandCompletion = null;
                phase = Phase.VERIFY_MUD;
                phaseStarted = clientTickCount();
                nextPollAt = phaseStarted;
                requestServerPoll();
            } else if (timedOut(PHASE_TIMEOUT_TICKS)) {
                fail("Timed out during @get mud " + TARGET_COUNT + "; tasks="
                        + mod.getUserTaskChain().getTasks());
            }
            return;
        }
        if (phase == Phase.VERIFY_MUD) {
            if (pollReady) {
                pollReady = false;
                append.accept("MUD_ROOT_MUD_POLL\t" + pollState);
                if (mudStageIsValid() && clientMatchesServer()) {
                    append.accept("ASSERT\t@get mud " + TARGET_COUNT
                            + " mined supplied mud blocks with no tools and exact client/server inventory");
                    startMuddyRootsCommand();
                    return;
                }
                if (timedOut(VERIFY_TIMEOUT_TICKS)) {
                    fail("Mud source checkpoint mismatch: server=" + pollState
                            + ", client=" + clientSnapshot());
                } else {
                    nextPollAt = clientTickCount() + POLL_INTERVAL_TICKS;
                }
            } else if (timedOut(VERIFY_TIMEOUT_TICKS)) {
                fail("Timed out verifying mud source checkpoint: " + pollState
                        + ", client=" + clientSnapshot());
            } else if (!pollOutstanding && clientTickCount() >= nextPollAt) {
                requestServerPoll();
            }
            return;
        }
        if (phase == Phase.GET_MUDDY_ROOTS) {
            if ("muddy-roots".equals(commandCompletion)) {
                commandCompletion = null;
                phase = Phase.VERIFY_FINAL;
                phaseStarted = clientTickCount();
                nextPollAt = phaseStarted;
                requestServerPoll();
            } else if (timedOut(PHASE_TIMEOUT_TICKS)) {
                fail("Timed out during @get muddy_mangrove_roots " + TARGET_COUNT
                        + "; tasks=" + mod.getUserTaskChain().getTasks());
            }
            return;
        }
        if (phase == Phase.VERIFY_FINAL) {
            if (pollReady) {
                pollReady = false;
                append.accept("MUD_ROOT_FINAL_POLL\t" + pollState);
                if (finalStateIsValid() && clientMatchesServer()) {
                    finishSuccessfully();
                } else if (timedOut(VERIFY_TIMEOUT_TICKS)) {
                    fail("Final muddy mangrove roots mismatch: server=" + pollState
                            + ", client=" + clientSnapshot());
                } else {
                    nextPollAt = clientTickCount() + POLL_INTERVAL_TICKS;
                }
            } else if (timedOut(VERIFY_TIMEOUT_TICKS)) {
                fail("Timed out verifying final muddy mangrove roots: " + pollState
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
            fail("Mud/root acceptance requires an active integrated server and client player");
            return;
        }
        mud = item("mud");
        muddyRoots = item("muddy_mangrove_roots");
        if (!TaskCatalogue.taskExists("mud") || !TaskCatalogue.taskExists("muddy_mangrove_roots")
                || mud == null || muddyRoots == null) {
            fail("Mud/root acceptance requires catalogued mud and muddy_mangrove_roots tasks");
            return;
        }
        playerId = client.player.getUUID();
        mod.cancelUserTask();
        phase = Phase.PREPARING;
        phaseStarted = clientTickCount();
        server.execute(() -> prepareServerFixture(server));
        append.accept("MUD_ROOT_SETUP\tqueued\tempty inventory; source blocks=mud:" + TARGET_COUNT
                + ",mangrove_roots:" + TARGET_COUNT + "; no tools or outputs seeded");
    }

    private Item mud;

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
        int floorY = playerPos.getY() - 1;
        fixtureBounds = new AABB(playerPos.getX() - FLOOR_RADIUS - 2, floorY - 1,
                playerPos.getZ() - FLOOR_RADIUS - 2, playerPos.getX() + FLOOR_RADIUS + 3,
                playerPos.getY() + 13, playerPos.getZ() + FLOOR_RADIUS + 3);

        player.closeContainer();
        player.getInventory().clearContent();
        for (int slot = 1; slot <= 4; slot++) player.inventoryMenu.getSlot(slot).set(ItemStack.EMPTY);
        player.inventoryMenu.setCarried(ItemStack.EMPTY);
        player.inventoryMenu.broadcastChanges();
        player.setGameMode(GameType.SURVIVAL);
        player.setHealth(player.getMaxHealth());
        player.getFoodData().setFoodLevel(20);
        player.getFoodData().setSaturation(5.0f);

        for (int x = playerPos.getX() - FLOOR_RADIUS; x <= playerPos.getX() + FLOOR_RADIUS; x++) {
            for (int z = playerPos.getZ() - FLOOR_RADIUS; z <= playerPos.getZ() + FLOOR_RADIUS; z++) {
                BlockPos floor = new BlockPos(x, floorY, z);
                level.getChunkAt(floor);
                level.setBlock(floor, Blocks.STONE.defaultBlockState(), 3);
                for (int y = playerPos.getY(); y <= playerPos.getY() + 12; y++) {
                    level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }

        mudSourcePositions = List.of(
                playerPos.offset(5, 0, -2).immutable(),
                playerPos.offset(5, 0, -1).immutable());
        rootsSourcePositions = List.of(
                playerPos.offset(5, 0, 2).immutable(),
                playerPos.offset(5, 0, 3).immutable());
        for (BlockPos pos : mudSourcePositions) level.setBlock(pos, Blocks.MUD.defaultBlockState(), 3);
        for (BlockPos pos : rootsSourcePositions) level.setBlock(pos, Blocks.MANGROVE_ROOTS.defaultBlockState(), 3);
        for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, fixtureBounds)) entity.discard();

        boolean valid = player.getInventory().isEmpty() && inventoryCount(player) == 0
                && uiClean(player) && player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL
                && player.getHealth() == player.getMaxHealth()
                && player.getFoodData().getFoodLevel() == 20
                && count(player, mud) == 0 && count(player, Items.MANGROVE_ROOTS) == 0
                && count(player, muddyRoots) == 0
                && countBlocks(level, mudSourcePositions, Blocks.MUD) == TARGET_COUNT
                && countBlocks(level, rootsSourcePositions, Blocks.MANGROVE_ROOTS) == TARGET_COUNT;
        setupState = valid ? "ok" : "invalid:" + serverSnapshot(player, level);
        setupReady = true;
    }

    private void startMudCommand() {
        resetMiningEvidence();
        phase = Phase.GET_MUD;
        phaseStarted = clientTickCount();
        runCommand("get mud " + TARGET_COUNT, "mud");
    }

    private void startMuddyRootsCommand() {
        sawRootsMining = false;
        sawInventoryCraft = false;
        phase = Phase.GET_MUDDY_ROOTS;
        phaseStarted = clientTickCount();
        runCommand("get muddy_mangrove_roots " + TARGET_COUNT, "muddy-roots");
    }

    private void runCommand(String body, String completionTag) {
        var previous = mod.getUserTaskChain().getLastCompletionSnapshot();
        String command = mod.getModSettings().getCommandPrefix() + body;
        append.accept("COMMAND\t" + command + "\tmanualInputsAfterSetup=none");
        try {
            AltoClef.getCommandExecutor().execute(command, () -> {
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

    private void observeTasks() {
        if (phase != Phase.GET_MUD && phase != Phase.GET_MUDDY_ROOTS) return;
        Task task = mod.getUserTaskChain().getCurrentTask();
        if (task == null) return;
        boolean mining = task.thisOrChildSatisfies(child -> child instanceof MineAndCollectTask);
        if (phase == Phase.GET_MUD) sawMudMining |= mining;
        else {
            sawRootsMining |= mining;
            sawInventoryCraft |= task.thisOrChildSatisfies(child -> child instanceof CraftInInventoryTask);
        }
    }

    private void resetMiningEvidence() {
        sawMudMining = false;
        sawRootsMining = false;
    }

    private void requestServerPoll() {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null || pollOutstanding) return;
        pollOutstanding = true;
        pollReady = false;
        nextPollAt = clientTickCount() + POLL_INTERVAL_TICKS;
        UUID checkingPlayer = playerId;
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(checkingPlayer);
            ServerLevel level = player == null ? null : (ServerLevel) player.level();
            if (player != null && level != null) {
                serverMud = count(player, mud);
                serverMangroveRoots = count(player, Items.MANGROVE_ROOTS);
                serverMuddyRoots = count(player, muddyRoots);
                serverMudBlocks = countBlocks(level, mudSourcePositions, Blocks.MUD);
                serverRootsBlocks = countBlocks(level, rootsSourcePositions, Blocks.MANGROVE_ROOTS);
                serverHealth = player.getHealth();
                serverFood = player.getFoodData().getFoodLevel();
                serverInventory = inventorySnapshot(player);
                List<ItemStack> copied = new ArrayList<>();
                for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
                    copied.add(player.getInventory().getItem(slot).copy());
                }
                serverStacks = List.copyOf(copied);
                serverUiClean = uiClean(player);
                serverAlive = player.isAlive() && player.getHealth() > 0;
                serverSurvival = player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL;
                pollState = serverSnapshot(player, level);
            } else {
                pollState = "player/level missing";
            }
            pollOutstanding = false;
            pollReady = true;
        });
    }

    private boolean mudStageIsValid() {
        Minecraft client = Minecraft.getInstance();
        return client.player != null && serverMud == TARGET_COUNT
                && serverMangroveRoots == 0 && serverMuddyRoots == 0
                && stackCount(serverStacks) == TARGET_COUNT
                && serverMudBlocks == 0 && serverRootsBlocks == TARGET_COUNT
                && serverUiClean && uiClean(client.player) && sawMudMining
                && count(client.player, mud) == TARGET_COUNT;
    }

    private boolean finalStateIsValid() {
        Minecraft client = Minecraft.getInstance();
        return client.player != null && serverMuddyRoots == TARGET_COUNT
                && serverMud == 0 && serverMangroveRoots == 0
                && stackCount(serverStacks) == TARGET_COUNT
                && serverMudBlocks == 0 && serverRootsBlocks == 0
                && serverUiClean && uiClean(client.player)
                && serverAlive && serverSurvival
                && sawMudMining && sawRootsMining && sawInventoryCraft
                && client.gameMode.getPlayerMode() == GameType.SURVIVAL;
    }

    private boolean clientMatchesServer() {
        Minecraft client = Minecraft.getInstance();
        return client.player != null && client.level != null
                && uiClean(client.player) && client.gameMode.getPlayerMode() == GameType.SURVIVAL
                && client.player.isAlive() && client.player.getHealth() > 0
                && client.player.getHealth() == serverHealth
                && client.player.getFoodData().getFoodLevel() == serverFood
                && count(client.player, mud) == serverMud
                && count(client.player, Items.MANGROVE_ROOTS) == serverMangroveRoots
                && count(client.player, muddyRoots) == serverMuddyRoots
                && inventoryMatches(client.player)
                && countBlocks(client.level, mudSourcePositions, Blocks.MUD) == serverMudBlocks
                && countBlocks(client.level, rootsSourcePositions, Blocks.MANGROVE_ROOTS) == serverRootsBlocks;
    }

    private static int stackCount(List<ItemStack> stacks) {
        return stacks.stream().mapToInt(ItemStack::getCount).sum();
    }

    private boolean inventoryMatches(Player player) {
        if (serverStacks.size() != player.getInventory().getContainerSize()) return false;
        for (int slot = 0; slot < serverStacks.size(); slot++) {
            if (!ItemStack.matches(serverStacks.get(slot), player.getInventory().getItem(slot))) return false;
        }
        return true;
    }

    private boolean clientFixtureIsSynchronized() {
        Minecraft client = Minecraft.getInstance();
        return client.player != null && client.level != null
                && client.player.getInventory().isEmpty() && uiClean(client.player)
                && client.gameMode.getPlayerMode() == GameType.SURVIVAL
                && client.player.isAlive() && client.player.getFoodData().getFoodLevel() == 20
                && count(client.player, mud) == 0
                && count(client.player, Items.MANGROVE_ROOTS) == 0
                && count(client.player, muddyRoots) == 0
                && countBlocks(client.level, mudSourcePositions, Blocks.MUD) == TARGET_COUNT
                && countBlocks(client.level, rootsSourcePositions, Blocks.MANGROVE_ROOTS) == TARGET_COUNT;
    }

    private static int countBlocks(net.minecraft.world.level.Level level, List<BlockPos> positions, Block block) {
        int result = 0;
        for (BlockPos pos : positions) if (level.getBlockState(pos).is(block)) result++;
        return result;
    }

    private static int count(Player player, Item item) {
        if (player == null || item == null) return 0;
        int result = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) result += stack.getCount();
        }
        return result;
    }

    private static int inventoryCount(Player player) {
        int result = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            result += player.getInventory().getItem(slot).getCount();
        }
        return result;
    }

    private static boolean uiClean(Player player) {
        if (player.containerMenu != player.inventoryMenu || !player.containerMenu.getCarried().isEmpty()) return false;
        for (int slot = 1; slot <= 4; slot++) {
            if (!player.inventoryMenu.getSlot(slot).getItem().isEmpty()) return false;
        }
        return true;
    }

    private static String inventorySnapshot(Player player) {
        List<String> stacks = new ArrayList<>();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty()) {
                stacks.add(slot + "=" + stack.getCount() + "x" + stack.getItem()
                        + ",damage=" + stack.getDamageValue() + ",components=" + stack.getComponents());
            }
        }
        return stacks.toString();
    }

    private String setupSnapshot() {
        Minecraft client = Minecraft.getInstance();
        return "inventory=" + (client.player == null ? "missing" : inventorySnapshot(client.player))
                + ",mudSources=" + mudSourcePositions + ",mangroveRootsSources=" + rootsSourcePositions
                + ",muddyRootsItem=" + muddyRoots + ",seededTools=none,seededOutputs=none";
    }

    private String serverSnapshot(ServerPlayer player, ServerLevel level) {
        return "mud=" + serverMud + ",mangroveRoots=" + serverMangroveRoots
                + ",muddyRoots=" + serverMuddyRoots + ",mudBlocks=" + serverMudBlocks
                + ",mangroveRootsBlocks=" + serverRootsBlocks + ",inventory=" + serverInventory
                + ",uiClean=" + serverUiClean + ",alive=" + player.isAlive()
                + ",health=" + player.getHealth() + ",food=" + player.getFoodData().getFoodLevel()
                + ",survival=" + serverSurvival
                + ",level=" + level.dimension();
    }

    private String clientSnapshot() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return "player/level missing";
        return "mud=" + count(client.player, mud)
                + ",mangroveRoots=" + count(client.player, Items.MANGROVE_ROOTS)
                + ",muddyRoots=" + count(client.player, muddyRoots)
                + ",inventory=" + inventorySnapshot(client.player)
                + ",uiClean=" + uiClean(client.player)
                + ",alive=" + client.player.isAlive() + ",health=" + client.player.getHealth()
                + ",food=" + client.player.getFoodData().getFoodLevel()
                + ",mudBlocks=" + countBlocks(client.level, mudSourcePositions, Blocks.MUD)
                + ",mangroveRootsBlocks=" + countBlocks(client.level, rootsSourcePositions, Blocks.MANGROVE_ROOTS);
    }

    private void finishSuccessfully() {
        phase = Phase.DONE;
        append.accept("ASSERT\t@get mud " + TARGET_COUNT
                + " mined the fixture source with an empty inventory and no tools");
        append.accept("ASSERT\t@get muddy_mangrove_roots " + TARGET_COUNT
                + " used mud and mined mangrove roots, crafted the exact output with no extra inventory items, and depleted both designated fixture sources");
        append.accept("ASSERT\tclient/server inventories including components match; Survival, alive state, health/food, and UI verified");
        append.accept("MUD_ROOT_ACCEPTANCE\tPASS\tprepared mud and mangrove-root sources; no tools or outputs seeded");
        success.run();
    }

    private void fail(String reason) {
        if (phase == Phase.FAILED || phase == Phase.DONE) return;
        phase = Phase.FAILED;
        mod.cancelUserTask();
        failure.accept(reason + "; server=" + pollState + "; client=" + clientSnapshot());
    }

    private void publishSetupFailure(String reason) {
        setupState = reason;
        setupReady = true;
    }

    private long clientTickCount() {
        return Minecraft.getInstance().level == null ? 0 : Minecraft.getInstance().level.getGameTime();
    }

    private boolean timedOut(int limit) {
        return clientTickCount() - phaseStarted > limit;
    }
}
