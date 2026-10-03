package adris.altoclef.runtimetest;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.container.LootContainerTask;
import adris.altoclef.trackers.storage.ContainerCache;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Small integrated-server acceptance for chest looting, @deposit, and @stash.
 * The caller should run this only in a disposable prepared world with open floor
 * space around the player's current position.
 */
public final class ContainerAcceptanceScenario {
    private enum Phase {
        NEW,
        PREPARING,
        LOOT_DIAMONDS,
        VERIFY_LOOT_DIAMONDS,
        DEPOSIT_ONE_DIAMOND,
        VERIFY_DEPOSIT_ONE_DIAMOND,
        DEPOSIT_DIAMONDS,
        VERIFY_DEPOSIT_DIAMONDS,
        LOOT_EMERALDS,
        VERIFY_LOOT_EMERALDS,
        STASH_EMERALDS,
        VERIFY_STASH_EMERALDS,
        PREPARING_OVERFLOW,
        STASH_OVERFLOW,
        VERIFY_STASH_OVERFLOW,
        DONE,
        FAILED
    }

    private record ServerPoll(long id, Phase phase, boolean valid, String snapshot) { }

    private static final int DIAMONDS = 3;
    private static final int EMERALDS = 5;
    private static final int DIRT = 2;
    private static final int COBBLESTONE = 4;
    private static final int OVERFLOW_PLANKS_BEFORE = 60;
    private static final int OVERFLOW_PLANKS_TO_STORE = 8;
    private static final int OVERFLOW_DIRT_SENTINEL = 1;
    private static final int PHASE_TIMEOUT_TICKS = 2400;

    private final AltoClef mod;
    private final Consumer<String> append;
    private final Consumer<String> failure;
    private final Runnable success;

    private volatile Phase phase = Phase.NEW;
    private long phaseStarted;
    private BlockPos origin;
    private BlockPos lootChest;
    private BlockPos stashChest;
    private BlockPos overflowChest;
    private volatile boolean setupReady;
    private volatile String setupState = "not-started";
    private volatile boolean overflowSetupReady;
    private volatile String overflowSetupState = "not-started";
    private long nextPollId;
    private long outstandingPollId;
    private ServerPoll completedPoll;
    private String lastPollSnapshot = "not-polled";
    private volatile int diamondsInLootChestAfterDeposit;
    private volatile int diamondsInStashChestAfterDeposit;
    private long nextPollAt;
    private volatile String completedOperation;
    private String operationInFlight;

    public ContainerAcceptanceScenario(AltoClef mod, Consumer<String> append,
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
                if ("ok".equals(setupState)) {
                    append.accept("CONTAINER_SETUP\t" + setupState
                            + "\tplayer inventory empty; loot chest=" + lootChest
                            + "\tstash chest=" + stashChest);
                    startLootDiamonds();
                } else {
                    fail("Container fixture setup failed: " + setupState);
                }
            } else if (timedOut()) {
                fail("Timed out preparing container fixture: " + setupState);
            }
            return;
        }
        if (phase == Phase.PREPARING_OVERFLOW) {
            if (overflowSetupReady) {
                if ("ok".equals(overflowSetupState)) {
                    append.accept("CONTAINER_OVERFLOW_SETUP\t" + overflowSetupState
                            + "\tchest=" + overflowChest + "\tplanks=" + OVERFLOW_PLANKS_BEFORE
                            + "\tdirt=" + OVERFLOW_DIRT_SENTINEL + "\tdirtSlot=5\tplayer logs=2");
                    startOverflowStash();
                } else {
                    fail("Container overflow fixture setup failed: " + overflowSetupState);
                }
            } else if (timedOut()) {
                fail("Timed out preparing overflow fixture: " + overflowSetupState);
            }
            return;
        }

        if (completedOperation != null) {
            String completed = completedOperation;
            completedOperation = null;
            if (!Objects.equals(completed, operationInFlight)) {
                fail("Unexpected user-task completion: expected=" + operationInFlight + ", actual=" + completed);
                return;
            }
            operationInFlight = null;
            switch (phase) {
                case LOOT_DIAMONDS -> phase = Phase.VERIFY_LOOT_DIAMONDS;
                case DEPOSIT_ONE_DIAMOND -> phase = Phase.VERIFY_DEPOSIT_ONE_DIAMOND;
                case DEPOSIT_DIAMONDS -> phase = Phase.VERIFY_DEPOSIT_DIAMONDS;
                case LOOT_EMERALDS -> phase = Phase.VERIFY_LOOT_EMERALDS;
                case STASH_EMERALDS -> phase = Phase.VERIFY_STASH_EMERALDS;
                case STASH_OVERFLOW -> phase = Phase.VERIFY_STASH_OVERFLOW;
                default -> {
                    fail("Completion arrived in unexpected phase " + phase);
                    return;
                }
            }
            append.accept("CONTAINER_OPERATION_COMPLETE\tlabel=" + completed + "\tphase=" + phase.name());
            phaseStarted = clientTickCount();
            nextPollAt = phaseStarted;
            requestPoll();
            return;
        }

        if (phase == Phase.VERIFY_LOOT_DIAMONDS || phase == Phase.VERIFY_DEPOSIT_ONE_DIAMOND
                || phase == Phase.VERIFY_DEPOSIT_DIAMONDS
                || phase == Phase.VERIFY_LOOT_EMERALDS || phase == Phase.VERIFY_STASH_EMERALDS
                || phase == Phase.VERIFY_STASH_OVERFLOW) {
            if (completedPoll != null) {
                ServerPoll poll = completedPoll;
                completedPoll = null;
                if (poll.id() != outstandingPollId || poll.phase() != phase) {
                    fail("Stale container server poll: currentPhase=" + phase + ", currentPollId="
                            + outstandingPollId + ", receivedPhase=" + poll.phase() + ", receivedPollId=" + poll.id());
                    return;
                }
                outstandingPollId = 0;
                lastPollSnapshot = poll.snapshot();
                append.accept("CONTAINER_SERVER_POLL\tphase=" + poll.phase().name()
                        + "\tpollId=" + poll.id() + "\tvalid=" + poll.valid() + "\t" + poll.snapshot());
                boolean clientPassed = clientMatchesExpected(poll.phase());
                append.accept("CONTAINER_CLIENT_VERIFY\tphase=" + poll.phase().name()
                        + "\tpollId=" + poll.id() + "\tvalid=" + clientPassed + "\t" + clientSnapshot());
                if (poll.valid() && clientPassed) {
                    switch (phase) {
                        case VERIFY_LOOT_DIAMONDS -> startDepositOneDiamond();
                        case VERIFY_DEPOSIT_ONE_DIAMOND -> startDepositDiamonds();
                        case VERIFY_DEPOSIT_DIAMONDS -> startLootEmeralds();
                        case VERIFY_LOOT_EMERALDS -> startStashEmeralds();
                        case VERIFY_STASH_EMERALDS -> prepareOverflowFixture();
                        case VERIFY_STASH_OVERFLOW -> finishSuccessfully();
                        default -> throw new IllegalStateException("Unexpected verification phase " + phase);
                    }
                } else if (timedOut()) {
                    fail("Container operation state mismatch in " + phase + ": server=" + poll.snapshot()
                            + ", client=" + clientSnapshot());
                } else {
                    nextPollAt = clientTickCount() + 5;
                }
            } else if (timedOut()) {
                fail("Timed out verifying " + phase + ": pollId=" + outstandingPollId
                        + ", lastServerPoll=" + lastPollSnapshot
                        + ", client=" + clientSnapshot());
            } else if (outstandingPollId == 0 && clientTickCount() >= nextPollAt) {
                requestPoll();
            }
            return;
        }

        if (timedOut()) fail("Timed out during " + phase + "; active task=" + activeTaskDescription());
    }

    private void beginSetup() {
        Minecraft client = Minecraft.getInstance();
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null || client.player == null || client.level == null) {
            fail("Container acceptance requires an active integrated server and client player");
            return;
        }
        origin = client.player.blockPosition();
        lootChest = origin.offset(3, 0, -3);
        stashChest = origin.offset(6, 0, -3);
        overflowChest = origin.offset(9, 0, -3);
        java.util.UUID playerId = client.player.getUUID();
        phase = Phase.PREPARING;
        phaseStarted = clientTickCount();
        server.execute(() -> prepareServerFixture(server, playerId));
        append.accept("CONTAINER_SETUP\tqueued\tloot=" + lootChest + "\tstash=" + stashChest);
    }

    private void prepareServerFixture(MinecraftServer server, java.util.UUID playerId) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) {
            publishSetupFailure("player=null");
            return;
        }
        ServerLevel level = (ServerLevel) player.level();
        if (!fixtureCellAvailable(level, lootChest) || !fixtureCellAvailable(level, stashChest)
                || !fixtureCellAvailable(level, overflowChest)
                || !level.getBlockState(lootChest.below()).isRedstoneConductor(level, lootChest.below())
                || !level.getBlockState(stashChest.below()).isRedstoneConductor(level, stashChest.below())
                || !level.getBlockState(overflowChest.below()).isRedstoneConductor(level, overflowChest.below())) {
            publishSetupFailure("fixture positions must be air over solid support; loot="
                    + level.getBlockState(lootChest) + " over " + level.getBlockState(lootChest.below())
                    + ", stash=" + level.getBlockState(stashChest) + " over " + level.getBlockState(stashChest.below())
                    + ", overflow=" + level.getBlockState(overflowChest) + " over " + level.getBlockState(overflowChest.below()));
            return;
        }

        player.closeContainer();
        player.getInventory().clearContent();
        for (int slot = 1; slot <= 4; slot++) player.inventoryMenu.getSlot(slot).set(ItemStack.EMPTY);
        player.inventoryMenu.setCarried(ItemStack.EMPTY);
        player.inventoryMenu.broadcastChanges();
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);

        BlockState chestState = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH);
        level.setBlock(lootChest, chestState, 3);
        level.setBlock(stashChest, chestState, 3);
        Container loot = chestAt(level, lootChest);
        Container stash = chestAt(level, stashChest);
        if (loot == null || stash == null) {
            publishSetupFailure("chest block entity missing after placement");
            return;
        }
        loot.clearContent();
        loot.setItem(0, new ItemStack(Items.DIAMOND, DIAMONDS));
        loot.setItem(1, new ItemStack(Items.DIRT, DIRT));
        stash.clearContent();
        stash.setItem(0, new ItemStack(Items.EMERALD, EMERALDS));
        stash.setItem(1, new ItemStack(Items.COBBLESTONE, COBBLESTONE));
        loot.setChanged();
        stash.setChanged();
        player.inventoryMenu.broadcastChanges();

        boolean valid = player.containerMenu == player.inventoryMenu
                && player.containerMenu.getCarried().isEmpty()
                && emptyCraftingGrid(player)
                && inventoryCount(player) == 0
                && containerItemCount(loot) == DIAMONDS + DIRT
                && containerItemCount(stash) == EMERALDS + COBBLESTONE
                && chestCount(loot, Items.DIAMOND) == DIAMONDS
                && chestCount(loot, Items.DIRT) == DIRT
                && chestCount(stash, Items.EMERALD) == EMERALDS
                && chestCount(stash, Items.COBBLESTONE) == COBBLESTONE;
        setupState = valid ? "ok" : "seed-invalid:" + serverSnapshot(player, level);
        setupReady = true;
    }

    private static boolean fixtureCellAvailable(ServerLevel level, BlockPos position) {
        return level.getBlockState(position).isAir() && level.getBlockState(position.above()).isAir();
    }

    private void publishSetupFailure(String state) {
        setupState = state;
        setupReady = true;
    }

    private void startLootDiamonds() {
        startTask(new LootContainerTask(lootChest, java.util.List.of(Items.DIAMOND)), "loot-diamonds");
        phase = Phase.LOOT_DIAMONDS;
        append.accept("CONTAINER_TASK\tlabel=loot-diamonds\tLootContainerTask\tchest=" + lootChest
                + "\ttarget=diamondx" + DIAMONDS);
    }

    private void startDepositDiamonds() {
        phase = Phase.DEPOSIT_DIAMONDS;
        executeCommand("deposit diamond 2", "deposit-diamonds");
    }

    private void startDepositOneDiamond() {
        phase = Phase.DEPOSIT_ONE_DIAMOND;
        executeCommand("deposit diamond", "deposit-one-diamond");
    }

    private void startLootEmeralds() {
        startTask(new LootContainerTask(stashChest, java.util.List.of(Items.EMERALD)), "loot-emeralds");
        phase = Phase.LOOT_EMERALDS;
        append.accept("CONTAINER_TASK\tlabel=loot-emeralds\tLootContainerTask\tchest=" + stashChest
                + "\ttarget=emeraldx" + EMERALDS);
    }

    private void startStashEmeralds() {
        phase = Phase.STASH_EMERALDS;
        String command = "stash " + stashChest.getX() + " " + stashChest.getY() + " " + stashChest.getZ()
                + " " + stashChest.getX() + " " + stashChest.getY() + " " + stashChest.getZ() + " emerald 5";
        executeCommand(command, "stash-emeralds");
    }

    private void prepareOverflowFixture() {
        Minecraft client = Minecraft.getInstance();
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null || client.player == null) {
            fail("Container overflow fixture requires an active integrated server and client player");
            return;
        }
        java.util.UUID playerId = client.player.getUUID();
        phase = Phase.PREPARING_OVERFLOW;
        phaseStarted = clientTickCount();
        overflowSetupReady = false;
        overflowSetupState = "queued";
        server.execute(() -> prepareOverflowServerFixture(server, playerId));
        append.accept("CONTAINER_OVERFLOW_SETUP\tqueued\tchest=" + overflowChest);
    }

    private void prepareOverflowServerFixture(MinecraftServer server, java.util.UUID playerId) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) {
            publishOverflowSetupFailure("player=null");
            return;
        }
        ServerLevel level = (ServerLevel) player.level();
        Container loot = chestAt(level, lootChest);
        Container stash = chestAt(level, stashChest);
        if (!level.getBlockState(overflowChest).isAir() || !level.getBlockState(overflowChest.above()).isAir()
                || overflowChest.equals(lootChest) || overflowChest.equals(stashChest)
                || loot == null || stash == null
                || !uiClean(player) || inventoryCount(player) != 0
                || !existingChestsUnchanged(loot, stash)) {
            publishOverflowSetupFailure("pre-overflow state invalid: " + serverSnapshot(player, level));
            return;
        }
        level.setBlock(overflowChest, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH), 3);
        Container overflow = chestAt(level, overflowChest);
        if (overflow == null) {
            publishOverflowSetupFailure("chest block entity missing after placement");
            return;
        }
        overflow.clearContent();
        overflow.setItem(0, new ItemStack(Items.OAK_PLANKS, OVERFLOW_PLANKS_BEFORE));
        overflow.setItem(5, new ItemStack(Items.DIRT, OVERFLOW_DIRT_SENTINEL));
        overflow.setChanged();
        player.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 2));
        player.inventoryMenu.broadcastChanges();
        boolean valid = player.containerMenu == player.inventoryMenu
                && player.containerMenu.getCarried().isEmpty()
                && emptyCraftingGrid(player)
                && inventoryCount(player) == 2
                && count(player, Items.OAK_LOG) == 2
                && chestCount(overflow, Items.OAK_PLANKS) == OVERFLOW_PLANKS_BEFORE
                && chestCount(overflow, Items.DIRT) == OVERFLOW_DIRT_SENTINEL
                && containerItemCount(overflow) == OVERFLOW_PLANKS_BEFORE + OVERFLOW_DIRT_SENTINEL;
        overflowSetupState = valid ? "ok" : "seed-invalid:" + serverSnapshot(player, level);
        overflowSetupReady = true;
    }

    private void publishOverflowSetupFailure(String state) {
        overflowSetupState = state;
        overflowSetupReady = true;
    }

    private void startOverflowStash() {
        phase = Phase.STASH_OVERFLOW;
        String command = "stash " + overflowChest.getX() + " " + overflowChest.getY() + " " + overflowChest.getZ()
                + " " + overflowChest.getX() + " " + overflowChest.getY() + " " + overflowChest.getZ()
                + " oak_planks " + OVERFLOW_PLANKS_TO_STORE;
        executeCommand(command, "stash-overflow-oak-planks");
    }

    private void startTask(adris.altoclef.tasksystem.Task task, String label) {
        var previous = mod.getUserTaskChain().getLastCompletionSnapshot();
        operationInFlight = label;
        phaseStarted = clientTickCount();
        mod.runUserTask(task, () -> {
            var completion = mod.getUserTaskChain().getLastCompletionSnapshot();
            if (completion == null || completion == previous) {
                fail("User task callback had no new completion snapshot for " + label);
            } else if (completion.failure() != null || completion.cancelled()) {
                fail("User task " + label + " failed/cancelled: "
                        + (completion.failure() == null ? "cancelled" : completion.failure().reason()));
            } else {
                completedOperation = label;
            }
        });
    }

    private void executeCommand(String command, String label) {
        var previous = mod.getUserTaskChain().getLastCompletionSnapshot();
        operationInFlight = label;
        phaseStarted = clientTickCount();
        String prefix = mod.getModSettings().getCommandPrefix();
        try {
            AltoClef.getCommandExecutor().execute(prefix + command, () -> {
                var completion = mod.getUserTaskChain().getLastCompletionSnapshot();
                if (completion == null || completion == previous) {
                    fail("Command callback had no new user-task completion snapshot for " + command);
                } else if (completion.failure() != null || completion.cancelled()) {
                    fail("Command task failed/cancelled for " + command + ": "
                            + (completion.failure() == null ? "cancelled" : completion.failure().reason()));
                } else {
                    completedOperation = label;
                }
            }, error -> fail("Command failed for " + command + ": " + error.getMessage()));
        } catch (Throwable error) {
            fail("Could not execute " + command + ": " + error);
        }
        append.accept("CONTAINER_COMMAND\tlabel=" + label + "\t" + command);
    }

    private void requestPoll() {
        Minecraft client = Minecraft.getInstance();
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            fail("Integrated server unavailable while verifying " + phase);
            return;
        }
        if (outstandingPollId != 0) {
            fail("Duplicate container server poll requested while poll " + outstandingPollId + " is outstanding");
            return;
        }
        Phase checking = phase;
        java.util.UUID playerId = client.player == null ? null : client.player.getUUID();
        long pollId = ++nextPollId;
        outstandingPollId = pollId;
        completedPoll = null;
        server.execute(() -> {
            ServerPlayer player = playerId == null ? null : server.getPlayerList().getPlayer(playerId);
            ServerLevel level = player == null ? null : (ServerLevel) player.level();
            boolean valid = player != null && level != null && expectedServerState(checking, player, level);
            String snapshot = player == null || level == null ? "player/level missing"
                    : serverSnapshot(player, level);
            ServerPoll result = new ServerPoll(pollId, checking, valid, snapshot);
            client.execute(() -> {
                if (phase != checking || outstandingPollId != pollId) {
                    fail("Stale container server poll publication: currentPhase=" + phase + ", currentPollId="
                            + outstandingPollId + ", receivedPhase=" + checking + ", receivedPollId=" + pollId);
                    return;
                }
                completedPoll = result;
            });
        });
    }

    private boolean expectedServerState(Phase checking, ServerPlayer player, ServerLevel level) {
        Container loot = chestAt(level, lootChest);
        Container stash = chestAt(level, stashChest);
        Container overflow = chestAt(level, overflowChest);
        if (loot == null || stash == null || !uiClean(player)) return false;
        boolean valid = switch (checking) {
            case VERIFY_LOOT_DIAMONDS -> inventoryCount(player) == DIAMONDS
                    && count(player, Items.DIAMOND) == DIAMONDS
                    && count(player, Items.EMERALD) == 0
                    && containerItemCount(loot) == DIRT
                    && containerItemCount(stash) == EMERALDS + COBBLESTONE
                    && chestCount(loot, Items.DIAMOND) == 0 && chestCount(loot, Items.DIRT) == DIRT
                    && chestCount(stash, Items.EMERALD) == EMERALDS && chestCount(stash, Items.COBBLESTONE) == COBBLESTONE;
            case VERIFY_DEPOSIT_ONE_DIAMOND -> inventoryCount(player) == 2
                    && count(player, Items.DIAMOND) == 2
                    && containerItemCount(loot) + containerItemCount(stash) == 1 + DIRT + EMERALDS + COBBLESTONE
                    && chestCount(loot, Items.DIAMOND) + chestCount(stash, Items.DIAMOND) == 1
                    && chestCount(loot, Items.DIRT) == DIRT
                    && chestCount(stash, Items.EMERALD) == EMERALDS
                    && chestCount(stash, Items.COBBLESTONE) == COBBLESTONE;
            case VERIFY_DEPOSIT_DIAMONDS -> inventoryCount(player) == 0
                    && count(player, Items.DIAMOND) == 0
                    && containerItemCount(loot) + containerItemCount(stash) == DIAMONDS + DIRT + EMERALDS + COBBLESTONE
                    && chestCount(loot, Items.DIAMOND) + chestCount(stash, Items.DIAMOND) == DIAMONDS
                    && chestCount(loot, Items.DIRT) == DIRT
                    && chestCount(stash, Items.EMERALD) == EMERALDS
                    && chestCount(stash, Items.COBBLESTONE) == COBBLESTONE;
            case VERIFY_LOOT_EMERALDS -> inventoryCount(player) == EMERALDS
                    && count(player, Items.EMERALD) == EMERALDS
                    && count(player, Items.DIAMOND) == 0
                    && containerItemCount(loot) == DIRT + diamondsInLootChestAfterDeposit
                    && chestCount(loot, Items.DIAMOND) == diamondsInLootChestAfterDeposit
                    && containerItemCount(stash) == COBBLESTONE + diamondsInStashChestAfterDeposit
                    && chestCount(stash, Items.DIAMOND) == diamondsInStashChestAfterDeposit
                    && chestCount(stash, Items.EMERALD) == 0
                    && chestCount(loot, Items.DIRT) == DIRT && chestCount(stash, Items.COBBLESTONE) == COBBLESTONE
                    && chestCount(loot, Items.DIAMOND) + chestCount(stash, Items.DIAMOND) == DIAMONDS;
            case VERIFY_STASH_EMERALDS -> inventoryCount(player) == 0
                    && count(player, Items.EMERALD) == 0
                    && containerItemCount(loot) == DIRT + diamondsInLootChestAfterDeposit
                    && containerItemCount(stash) == EMERALDS + COBBLESTONE + diamondsInStashChestAfterDeposit
                    && chestCount(loot, Items.DIAMOND) == diamondsInLootChestAfterDeposit
                    && chestCount(stash, Items.DIAMOND) == diamondsInStashChestAfterDeposit
                    && chestCount(loot, Items.DIRT) == DIRT
                    && chestCount(stash, Items.EMERALD) == EMERALDS
                    && chestCount(stash, Items.COBBLESTONE) == COBBLESTONE;
            case VERIFY_STASH_OVERFLOW -> overflow != null
                    && inventoryCount(player) == 0
                    && count(player, Items.OAK_LOG) == 0
                && count(player, Items.OAK_PLANKS) == 0
                && chestCount(overflow, Items.OAK_PLANKS) == OVERFLOW_PLANKS_BEFORE + OVERFLOW_PLANKS_TO_STORE
                && hasExactStackCounts(overflow, Items.OAK_PLANKS, 64, 4)
                && chestCount(overflow, Items.DIRT) == OVERFLOW_DIRT_SENTINEL
                && stackAt(overflow, 5).is(Items.DIRT)
                && stackAt(overflow, 5).getCount() == OVERFLOW_DIRT_SENTINEL
                    && containerItemCount(overflow) == OVERFLOW_PLANKS_BEFORE + OVERFLOW_PLANKS_TO_STORE + OVERFLOW_DIRT_SENTINEL
                    && existingChestsUnchanged(loot, stash);
            default -> false;
        };
        if (valid && (checking == Phase.VERIFY_DEPOSIT_DIAMONDS || checking == Phase.VERIFY_DEPOSIT_ONE_DIAMOND)) {
            diamondsInLootChestAfterDeposit = chestCount(loot, Items.DIAMOND);
            diamondsInStashChestAfterDeposit = chestCount(stash, Items.DIAMOND);
        }
        return valid;
    }

    private boolean existingChestsUnchanged(Container loot, Container stash) {
        return chestCount(loot, Items.DIAMOND) == diamondsInLootChestAfterDeposit
                && chestCount(loot, Items.DIRT) == DIRT
                && containerItemCount(loot) == DIRT + diamondsInLootChestAfterDeposit
                && chestCount(stash, Items.DIAMOND) == diamondsInStashChestAfterDeposit
                && chestCount(stash, Items.EMERALD) == EMERALDS
                && chestCount(stash, Items.COBBLESTONE) == COBBLESTONE
                && containerItemCount(stash) == EMERALDS + COBBLESTONE + diamondsInStashChestAfterDeposit;
    }

    private boolean clientMatchesExpected(Phase checking) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null || !uiClean(client.player)) return false;
        return switch (checking) {
            case VERIFY_LOOT_DIAMONDS -> inventoryCount(client.player) == DIAMONDS
                    && count(client.player, Items.DIAMOND) == DIAMONDS
                    && cachedChestMatches(lootChest, 0, DIRT, 0, 0, 26);
            case VERIFY_DEPOSIT_ONE_DIAMOND -> inventoryCount(client.player) == 2
                    && count(client.player, Items.DIAMOND) == 2
                    && depositedChestCacheMatches();
            case VERIFY_DEPOSIT_DIAMONDS -> inventoryCount(client.player) == 0
                    && depositedChestCacheMatches();
            case VERIFY_LOOT_EMERALDS -> inventoryCount(client.player) == EMERALDS
                    && count(client.player, Items.EMERALD) == EMERALDS
                    && cachedChestMatches(stashChest, diamondsInStashChestAfterDeposit,
                    0, 0, COBBLESTONE, 27 - 1 - (diamondsInStashChestAfterDeposit > 0 ? 1 : 0));
            case VERIFY_STASH_EMERALDS -> inventoryCount(client.player) == 0
                    && cachedChestMatches(lootChest, diamondsInLootChestAfterDeposit, DIRT, 0, 0,
                    27 - 1 - (diamondsInLootChestAfterDeposit > 0 ? 1 : 0))
                    && cachedChestMatches(stashChest, diamondsInStashChestAfterDeposit, 0, EMERALDS, COBBLESTONE,
                    27 - 2 - (diamondsInStashChestAfterDeposit > 0 ? 1 : 0));
            case VERIFY_STASH_OVERFLOW -> inventoryCount(client.player) == 0
                    && count(client.player, Items.OAK_LOG) == 0
                    && count(client.player, Items.OAK_PLANKS) == 0
                    && cachedOverflowChestMatches()
                    && cachedChestMatches(lootChest, diamondsInLootChestAfterDeposit, DIRT, 0, 0,
                    27 - 1 - (diamondsInLootChestAfterDeposit > 0 ? 1 : 0))
                    && cachedChestMatches(stashChest, diamondsInStashChestAfterDeposit, 0, EMERALDS, COBBLESTONE,
                    27 - 2 - (diamondsInStashChestAfterDeposit > 0 ? 1 : 0));
            default -> false;
        };
    }

    /** Reads only menu data captured by ContainerSubTracker while that chest was open. */
    private boolean cachedChestMatches(BlockPos position, int diamonds, int dirt, int emeralds,
                                       int cobblestone, int emptySlots) {
        ContainerCache cache = mod.getItemStorage().getContainerAtPosition(position).orElse(null);
        return cache != null
                && cache.getItemCount(Items.DIAMOND) == diamonds
                && cache.getItemCount(Items.DIRT) == dirt
                && cache.getItemCount(Items.EMERALD) == emeralds
                && cache.getItemCount(Items.COBBLESTONE) == cobblestone
                && cache.getEmptySlotCount() == emptySlots;
    }

    private boolean depositedChestCacheMatches() {
        return (diamondsInLootChestAfterDeposit == 0 || cachedChestMatches(lootChest,
                diamondsInLootChestAfterDeposit, DIRT, 0, 0, 25))
                && (diamondsInStashChestAfterDeposit == 0 || cachedChestMatches(stashChest,
                diamondsInStashChestAfterDeposit, 0, EMERALDS, COBBLESTONE, 24));
    }

    private boolean cachedOverflowChestMatches() {
        ContainerCache cache = mod.getItemStorage().getContainerAtPosition(overflowChest).orElse(null);
        return cache != null
                && cache.getItemCount(Items.OAK_PLANKS) == OVERFLOW_PLANKS_BEFORE + OVERFLOW_PLANKS_TO_STORE
                && cache.getItemCount(Items.DIRT) == OVERFLOW_DIRT_SENTINEL
                && cache.getEmptySlotCount() == 24;
    }

    private static boolean uiClean(Player player) {
        return player.containerMenu == player.inventoryMenu
                && player.containerMenu.getCarried().isEmpty()
                && emptyCraftingGrid(player);
    }

    private static boolean emptyCraftingGrid(Player player) {
        for (int slot = 1; slot <= 4; slot++) {
            if (!player.inventoryMenu.getSlot(slot).getItem().isEmpty()) return false;
        }
        return true;
    }

    private static int inventoryCount(Player player) {
        int count = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            count += player.getInventory().getItem(slot).getCount();
        }
        return count;
    }

    private static int count(Player player, Item item) {
        int count = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    private static int chestCount(Container container, Item item) {
        int count = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    private static ItemStack stackAt(Container container, int slot) {
        return container.getItem(slot);
    }

    private static int containerItemCount(Container container) {
        int count = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            count += container.getItem(slot).getCount();
        }
        return count;
    }

    private static boolean hasExactStackCounts(Container container, Item item, int... expectedCounts) {
        java.util.ArrayList<Integer> actualCounts = new java.util.ArrayList<>();
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.is(item)) actualCounts.add(stack.getCount());
        }
        if (actualCounts.size() != expectedCounts.length) return false;
        java.util.Arrays.sort(expectedCounts);
        actualCounts.sort(Integer::compareTo);
        for (int index = 0; index < expectedCounts.length; index++) {
            if (actualCounts.get(index) != expectedCounts[index]) return false;
        }
        return true;
    }

    private static Container chestAt(net.minecraft.world.level.LevelAccessor level, BlockPos position) {
        if (level == null || position == null) return null;
        return level.getBlockEntity(position) instanceof ChestBlockEntity chest ? chest : null;
    }

    private String serverSnapshot(ServerPlayer player, ServerLevel level) {
        Container loot = chestAt(level, lootChest);
        Container stash = chestAt(level, stashChest);
        Container overflow = chestAt(level, overflowChest);
        return "inventory=" + inventoryCount(player) + ",diamond=" + count(player, Items.DIAMOND)
                + ",emerald=" + count(player, Items.EMERALD) + ",oakLog=" + count(player, Items.OAK_LOG)
                + ",oakPlanks=" + count(player, Items.OAK_PLANKS) + ",uiClean=" + uiClean(player)
                + "," + uiSnapshot(player)
                + ",loot=" + containerSnapshot(loot) + ",stash=" + containerSnapshot(stash)
                + ",overflow=" + containerSnapshot(overflow);
    }

    private String clientSnapshot() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return "player/level missing";
        return "inventory=" + inventoryCount(client.player) + ",diamond=" + count(client.player, Items.DIAMOND)
                + ",emerald=" + count(client.player, Items.EMERALD) + ",oakLog=" + count(client.player, Items.OAK_LOG)
                + ",oakPlanks=" + count(client.player, Items.OAK_PLANKS) + ",uiClean=" + uiClean(client.player)
                + "," + uiSnapshot(client.player)
                + ",lootCached=" + cacheSnapshot(lootChest)
                + ",stashCached=" + cacheSnapshot(stashChest)
                + ",overflowCached=" + cacheSnapshot(overflowChest);
    }

    private static String uiSnapshot(Player player) {
        boolean inventoryMenu = player.containerMenu == player.inventoryMenu;
        boolean cursorEmpty = player.containerMenu.getCarried().isEmpty();
        boolean gridEmpty = emptyCraftingGrid(player);
        return "inventoryMenu=" + inventoryMenu + ",cursorEmpty=" + cursorEmpty
                + ",craftingGridEmpty=" + gridEmpty;
    }

    private String cacheSnapshot(BlockPos position) {
        ContainerCache cache = mod.getItemStorage().getContainerAtPosition(position).orElse(null);
        return cache == null ? "not-yet-observed" : "diamond=" + cache.getItemCount(Items.DIAMOND)
                + ",dirt=" + cache.getItemCount(Items.DIRT) + ",emerald=" + cache.getItemCount(Items.EMERALD)
                + ",cobblestone=" + cache.getItemCount(Items.COBBLESTONE)
                + ",oakPlanks=" + cache.getItemCount(Items.OAK_PLANKS)
                + ",emptySlots=" + cache.getEmptySlotCount();
    }

    private static String containerSnapshot(Container container) {
        if (container == null) return "missing";
        StringBuilder slots = new StringBuilder("[");
        int emptySlots = 0;
        boolean first = true;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty()) {
                emptySlots++;
                continue;
            }
            if (!first) slots.append(';');
            slots.append(slot).append('=').append(BuiltInRegistries.ITEM.getKey(stack.getItem()))
                    .append('x').append(stack.getCount());
            first = false;
        }
        slots.append(']');
        return "diamond=" + chestCount(container, Items.DIAMOND) + ",dirt=" + chestCount(container, Items.DIRT)
                + ",emerald=" + chestCount(container, Items.EMERALD)
                + ",cobblestone=" + chestCount(container, Items.COBBLESTONE)
                + ",oakPlanks=" + chestCount(container, Items.OAK_PLANKS)
                + ",slots=" + slots + ",emptySlots=" + emptySlots;
    }

    private void finishSuccessfully() {
        phase = Phase.DONE;
        append.accept("ASSERT\tLootContainerTask acquired exact diamonds; @deposit diamond stored exactly one and returned the two-item cursor remainder to inventory; @deposit diamond 2 stored the remainder; @stash emerald 5 stored only inside the requested stash bounds; recursive @stash oak_planks 8 used the two seeded logs and stored exactly 8 across the 64-item stack boundary; original chests, overflow chest contents, client caches, inventory, cursor, crafting grid, and menu state were verified");
        append.accept("CONTAINER_ACCEPTANCE\tPASS\tloot + deposits + stash + two-log crafted overflow");
        success.run();
    }

    private void fail(String reason) {
        if (phase == Phase.FAILED || phase == Phase.DONE) return;
        phase = Phase.FAILED;
        failure.accept(reason);
    }

    private boolean timedOut() {
        return clientTickCount() - phaseStarted > PHASE_TIMEOUT_TICKS;
    }

    private long clientTickCount() {
        return Minecraft.getInstance().level == null ? 0 : Minecraft.getInstance().level.getGameTime();
    }

    private String activeTaskDescription() {
        return mod.getUserTaskChain().isActive() ? String.valueOf(mod.getUserTaskChain().getLastCompletionSnapshot()) : "none";
    }
}
