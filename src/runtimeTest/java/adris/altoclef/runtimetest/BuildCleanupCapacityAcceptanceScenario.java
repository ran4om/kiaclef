package adris.altoclef.runtimetest;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.construction.BuildSchematicTask;
import baritone.api.schematic.IStaticSchematic;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/** Verifies successful build cleanup waits for inventory capacity instead of dropping a carried stack. */
public final class BuildCleanupCapacityAcceptanceScenario {
    private enum Phase { NEW, PREPARING, WAIT_SYNC, OPEN_CHEST, WAIT_CHEST, SEED_CARRIED,
        WAIT_CARRIED_SYNC, BUILD_BLOCKED, MOVE_CAPACITY_STACK, WAIT_CAPACITY_STACK,
        BUILD_RESUMED, VERIFY, DONE, FAILED }

    private static final int PLAYER_SLOTS = 36;
    private static final int BLOCKED_WAIT_TICKS = 60;
    private static final int SETUP_TIMEOUT_TICKS = 1200;
    private static final int VERIFY_TIMEOUT_TICKS = 300;
    private static final int TASK_TIMEOUT_TICKS = 1000;
    private static final int POLL_INTERVAL_TICKS = 3;

    private final AltoClef mod;
    private final Consumer<String> append;
    private final Consumer<String> failure;
    private final Runnable success;

    private volatile Phase phase = Phase.NEW;
    private long phaseStarted;
    private long nextPollAt;
    private long blockedPhaseStarted;
    private UUID playerId;
    private BlockPos buildOrigin;
    private BlockPos chestPos;
    private AABB fixtureBounds;
    private IStaticSchematic matchingSchematic;
    private BuildSchematicTask buildTask;
    private List<ItemStack> originalInventory = List.of();
    private volatile boolean setupReady;
    private volatile String setupState = "not-started";
    private volatile boolean completionObserved;
    private volatile boolean taskFailed;
    private volatile String taskFailure = "";
    private volatile boolean pollOutstanding;
    private volatile boolean pollReady;
    private volatile String pollState = "not-polled";
    private volatile List<ItemStack> serverInventory = List.of();
    private volatile List<ItemStack> serverChest = List.of();
    private volatile List<ItemStack> clientChestMenuContents = List.of();
    private volatile boolean clientChestMenuContentsCaptured;
    private volatile ItemStack serverCursor = ItemStack.EMPTY;
    private volatile int serverDrops;
    private volatile boolean serverMenuInventory;
    private volatile boolean serverMenuChest;
    private volatile boolean capacityTransferIssued;
    private volatile boolean cursorSeedReady;
    private volatile String cursorSeedState = "not-seeded";
    private volatile boolean serverWorldMatches;
    private volatile boolean serverSurvival;
    private volatile boolean serverAlive;
    private volatile float serverHealth;
    private volatile int serverFood;
    private volatile ItemStack cursorAtCallback = ItemStack.EMPTY;

    public BuildCleanupCapacityAcceptanceScenario(AltoClef mod, Consumer<String> append,
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
                if (!"ok".equals(setupState)) fail("Capacity fixture setup failed: " + setupState);
                else {
                    append.accept("BUILD_CLEANUP_CAPACITY_SETUP\tok\t" + clientSnapshot());
                    phase = Phase.WAIT_SYNC;
                    phaseStarted = clientTickCount();
                }
            } else if (timedOut(SETUP_TIMEOUT_TICKS)) {
                fail("Timed out preparing full-inventory fixture: " + setupState);
            }
            return;
        }
        if (taskFailed) {
            fail("BuildSchematicTask failed during capacity cleanup: " + taskFailure);
            return;
        }
        if (phase == Phase.WAIT_SYNC) {
            if (clientFixtureSynchronized()) {
                phase = Phase.OPEN_CHEST;
                phaseStarted = clientTickCount();
            }
            else if (timedOut(VERIFY_TIMEOUT_TICKS)) {
                fail("Full-inventory fixture did not synchronize: " + clientSnapshot());
            }
            return;
        }
        if (phase == Phase.OPEN_CHEST) {
            releaseSneak();
            if (Minecraft.getInstance().player != null
                    && Minecraft.getInstance().player.isSecondaryUseActive()) {
                if (timedOut(VERIFY_TIMEOUT_TICKS)) fail("Secondary use did not release before chest interaction");
                return;
            }
            if (!issueChestInteraction()) {
                fail("Could not issue real useItemOn interaction with the capacity-test chest");
                return;
            }
            phase = Phase.WAIT_CHEST;
            phaseStarted = clientTickCount();
            append.accept("INTERACT\tmethod=MultiPlayerGameMode.useItemOn\tblock=" + chestPos);
            return;
        }
        if (phase == Phase.WAIT_CHEST) {
            if (Minecraft.getInstance().player != null
                    && Minecraft.getInstance().player.containerMenu instanceof ChestMenu) {
                phase = Phase.SEED_CARRIED;
                phaseStarted = clientTickCount();
            } else if (timedOut(VERIFY_TIMEOUT_TICKS)) {
                fail("Real chest interaction did not open a ChestMenu: " + clientSnapshot());
            } else if ((clientTickCount() - phaseStarted) % 20 == 0) {
                // Allow the server to acknowledge released secondary use and fixture updates.
                releaseSneak();
                if (!Minecraft.getInstance().player.isSecondaryUseActive()) issueChestInteraction();
            }
            return;
        }
        if (phase == Phase.SEED_CARRIED) {
            seedCarriedStack();
            phase = Phase.WAIT_CARRIED_SYNC;
            phaseStarted = clientTickCount();
            return;
        }
        if (phase == Phase.WAIT_CARRIED_SYNC) {
            if (cursorSeedReady && clientHasOriginalStoneOnCursor()) {
                append.accept("BUILD_CLEANUP_CAPACITY_CURSOR\tok\t" + cursorSeedState);
                startBuild();
            } else if (timedOut(VERIFY_TIMEOUT_TICKS)) {
                fail("Server-seeded cursor stone did not synchronize in the open ChestMenu: " + clientSnapshot());
            }
            return;
        }
        if (phase == Phase.BUILD_BLOCKED) {
            if (completionObserved) {
                fail("BuildSchematicTask completed while its unique cursor stack had no inventory capacity");
                return;
            }
            if (timedOut(TASK_TIMEOUT_TICKS)) {
                fail("Build task did not remain safely pending with a full inventory: " + clientSnapshot());
                return;
            }
            if (!pollOutstanding && clientTickCount() >= nextPollAt) requestPoll();
            if (clientTickCount() - blockedPhaseStarted >= BLOCKED_WAIT_TICKS && pollReady) {
                pollReady = false;
                if (!blockedStateIsSafe()) {
                    fail("Full-inventory cleanup dropped or altered the carried stack: " + pollState);
                    return;
                }
                append.accept("ASSERT\tBuildSchematicTask stayed pending with all 36 player slots full, preserved the unique cursor stack, and spawned no item drops");
                phase = Phase.MOVE_CAPACITY_STACK;
                phaseStarted = clientTickCount();
            }
            return;
        }
        if (phase == Phase.MOVE_CAPACITY_STACK) {
            if (!capacityTransferIssued) {
                int coalWindowSlot = chestMenuSlotForInventorySlot(0);
                if (!clientHasOriginalStoneOnCursor() || coalWindowSlot < 0
                        || !clickSlot(coalWindowSlot, ContainerInput.QUICK_MOVE)) {
                    fail("Could not issue actual ChestMenu QUICK_MOVE for a full coal stack while preserving carried stone");
                    return;
                }
                Minecraft client = Minecraft.getInstance();
                if (client.player != null && client.player.containerMenu instanceof ChestMenu menu) {
                    clientChestMenuContents = copyChestMenu(menu);
                    clientChestMenuContentsCaptured = true;
                    append.accept("CLIENT_CHEST_MENU_PREDICTION\t" + describe(clientChestMenuContents));
                }
                capacityTransferIssued = true;
                append.accept("CLICK\tmenu=ChestMenu\tslot=" + coalWindowSlot
                        + "\tinput=QUICK_MOVE\tstack=64_coal\tcursor=custom_named_stone");
            }
            phase = Phase.WAIT_CAPACITY_STACK;
            phaseStarted = clientTickCount();
            requestPoll();
            return;
        }
        if (phase == Phase.WAIT_CAPACITY_STACK) {
            if (clientCapacityTransferConserved() && serverCapacityTransferConserved()
                    && inventoriesMatch()) {
                append.accept("ASSERT\tactual ChestMenu QUICK_MOVE transferred coal64; the named stone remained conserved while build cleanup returned it when capacity became available");
                pollReady = false;
                phase = completionObserved ? Phase.VERIFY : Phase.BUILD_RESUMED;
                phaseStarted = clientTickCount();
                nextPollAt = phaseStarted;
                requestPoll();
            } else if (timedOut(VERIFY_TIMEOUT_TICKS)) {
                fail("QUICK_MOVE did not transfer the coal stack and free capacity: server="
                        + pollState + ", client=" + clientSnapshot());
            } else if (!pollOutstanding && clientTickCount() >= nextPollAt) requestPoll();
            return;
        }
        if (phase == Phase.BUILD_RESUMED) {
            if (completionObserved) {
                phase = Phase.VERIFY;
                phaseStarted = clientTickCount();
                nextPollAt = phaseStarted;
                requestPoll();
            } else if (timedOut(TASK_TIMEOUT_TICKS)) {
                fail("BuildSchematicTask did not resume cleanup after QUICK_MOVE created one free inventory slot");
            } else if (!pollOutstanding && clientTickCount() >= nextPollAt) requestPoll();
            return;
        }
        if (phase == Phase.VERIFY) {
            if (pollReady) {
                pollReady = false;
                append.accept("BUILD_CLEANUP_CAPACITY_FINAL\t" + pollState);
                if (finalStateIsValid() && inventoriesMatch()) {
                    finishSuccessfully();
                } else if (timedOut(VERIFY_TIMEOUT_TICKS)) {
                    fail("Capacity cleanup final state mismatch: server=" + pollState
                            + ", client=" + clientSnapshot());
                } else {
                    nextPollAt = clientTickCount() + POLL_INTERVAL_TICKS;
                }
            } else if (timedOut(VERIFY_TIMEOUT_TICKS)) {
                fail("Timed out verifying completed capacity cleanup: server=" + pollState
                        + ", client=" + clientSnapshot());
            } else if (!pollOutstanding && clientTickCount() >= nextPollAt) requestPoll();
        }
    }

    private void beginSetup() {
        Minecraft client = Minecraft.getInstance();
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null || client.player == null || client.level == null) {
            fail("Capacity cleanup acceptance requires an active integrated server and player");
            return;
        }
        playerId = client.player.getUUID();
        mod.cancelUserTask();
        phase = Phase.PREPARING;
        phaseStarted = clientTickCount();
        server.execute(() -> prepareServerFixture(server));
        append.accept("BUILD_CLEANUP_CAPACITY_SETUP\tqueued\t36 full coal stacks in player inventory; real chest opened before named cursor stone is server-seeded; already-matching schematic");
    }

    private void prepareServerFixture(MinecraftServer server) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) {
            setupState = "player=null";
            setupReady = true;
            return;
        }
        ServerLevel level = (ServerLevel) player.level();
        BlockPos playerPos = player.blockPosition();
        buildOrigin = playerPos.offset(4, 0, 0).immutable();
        chestPos = playerPos.offset(2, 0, 0).immutable();
        fixtureBounds = new AABB(playerPos.getX() - 5, playerPos.getY() - 2, playerPos.getZ() - 5,
                playerPos.getX() + 8, playerPos.getY() + 5, playerPos.getZ() + 8);
        for (int x = playerPos.getX() - 5; x <= playerPos.getX() + 8; x++) {
            for (int z = playerPos.getZ() - 5; z <= playerPos.getZ() + 8; z++) {
                BlockPos floor = new BlockPos(x, playerPos.getY() - 1, z);
                level.getChunkAt(floor);
                level.setBlock(floor, Blocks.STONE.defaultBlockState(), 3);
                for (int y = playerPos.getY(); y <= playerPos.getY() + 4; y++) {
                    level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        level.setBlock(buildOrigin, Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), 3);
        matchingSchematic = createMatchingStoneSchematic();
        for (ItemEntity drop : level.getEntitiesOfClass(ItemEntity.class, fixtureBounds)) drop.discard();

        player.closeContainer();
        player.getInventory().clearContent();
        for (int slot = 0; slot < PLAYER_SLOTS; slot++) {
            player.getInventory().setItem(slot, new ItemStack(Items.COAL, 64));
        }
        for (int slot = 1; slot <= 4; slot++) player.inventoryMenu.getSlot(slot).set(ItemStack.EMPTY);
        player.inventoryMenu.setCarried(ItemStack.EMPTY);
        player.setGameMode(GameType.SURVIVAL);
        player.setHealth(player.getMaxHealth());
        player.getFoodData().setFoodLevel(20);
        player.getFoodData().setSaturation(5.0f);
        player.inventoryMenu.broadcastChanges();
        originalInventory = copyInventory(player);
        setupState = player.getInventory().getContainerSize() >= PLAYER_SLOTS
                && originalInventory.size() == player.getInventory().getContainerSize()
                && firstPlayerInventorySlotsFull(originalInventory)
                && player.containerMenu == player.inventoryMenu
                && player.containerMenu.getCarried().isEmpty()
                && cleanGrid(player.inventoryMenu)
                && level.getBlockState(buildOrigin).is(Blocks.STONE)
                && level.getBlockEntity(chestPos) instanceof ChestBlockEntity
                ? "ok" : "invalid:" + serverSnapshot(player, level);
        setupReady = true;
    }

    private void startBuild() {
        phase = Phase.BUILD_BLOCKED;
        phaseStarted = blockedPhaseStarted = clientTickCount();
        nextPollAt = phaseStarted;
        completionObserved = false;
        taskFailed = false;
        cursorAtCallback = ItemStack.EMPTY;
        buildTask = new BuildSchematicTask("runtime build cleanup capacity wait", matchingSchematic, buildOrigin);
        mod.runUserTask(buildTask, () -> {
            Minecraft client = Minecraft.getInstance();
            if (client.player != null) cursorAtCallback = client.player.containerMenu.getCarried().copy();
            if (buildTask.hasFailed()) {
                taskFailed = true;
                taskFailure = buildTask.getFailureReason();
            } else {
                completionObserved = true;
            }
        });
        append.accept("TASK\tBuildSchematicTask\tcase=full-inventory-cursor\tmatchingOneCell=true\tinventorySlots=36\tnoAdditionalInventoryCapacity=true");
    }

    private void seedCarriedStack() {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            cursorSeedState = "server missing";
            cursorSeedReady = true;
            return;
        }
        UUID seededPlayerId = playerId;
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(seededPlayerId);
            if (player == null || !(player.containerMenu instanceof ChestMenu)
                    || !firstPlayerInventorySlotsFull(copyInventory(player))) {
                cursorSeedState = "player/menu/inventory changed";
            } else {
                player.containerMenu.setCarried(originalStone());
                player.containerMenu.broadcastChanges();
                cursorSeedState = "server ChestMenu carried=" + describe(player.containerMenu.getCarried())
                        + ",inventory=" + describe(copyInventory(player));
            }
            cursorSeedReady = true;
        });
    }

    private void releaseSneak() {
        mod.getInputControls().release(baritone.api.utils.input.Input.SNEAK);
        mod.getClientBaritone().getInputOverrideHandler()
                .setInputForceState(baritone.api.utils.input.Input.SNEAK, false);
    }

    private boolean issueChestInteraction() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null || client.gameMode == null
                || client.player.containerMenu != client.player.inventoryMenu
                || !client.level.getBlockState(chestPos).is(Blocks.CHEST)) return false;
        BlockHitResult hit = new BlockHitResult(new Vec3(chestPos.getX() + 0.5,
                chestPos.getY() + 0.9, chestPos.getZ() + 0.5), Direction.UP, chestPos, false);
        var result = client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit);
        append.accept("CHEST_INTERACTION_RESULT\tresult=" + result
                + "\tsecondaryUse=" + client.player.isSecondaryUseActive()
                + "\tplayer=" + client.player.position() + "\tchest=" + chestPos);
        return true;
    }

    private boolean clickSlot(int menuSlot, ContainerInput input) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.player.containerMenu == null || client.gameMode == null) return false;
        mod.getController().handleContainerInput(client.player.containerMenu.containerId,
                menuSlot, 0, input, client.player);
        return true;
    }

    private static int chestMenuSlotForInventorySlot(int inventorySlot) {
        if (inventorySlot < 0 || inventorySlot >= PLAYER_SLOTS) return -1;
        return inventorySlot < 9 ? inventorySlot + 54 : inventorySlot + 18;
    }

    private void requestPoll() {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null || pollOutstanding) return;
        pollOutstanding = true;
        pollReady = false;
        nextPollAt = clientTickCount() + POLL_INTERVAL_TICKS;
        UUID checkingPlayer = playerId;
        AABB bounds = fixtureBounds;
        BlockPos target = buildOrigin;
        BlockPos chest = chestPos;
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(checkingPlayer);
            ServerLevel level = player == null ? null : (ServerLevel) player.level();
            serverInventory = player == null ? List.of() : copyInventory(player);
            serverCursor = player == null ? ItemStack.EMPTY : player.containerMenu.getCarried().copy();
            serverChest = readChest(level, chest);
            serverDrops = level == null ? -1 : level.getEntitiesOfClass(ItemEntity.class, bounds).size();
            serverMenuInventory = player != null && player.containerMenu == player.inventoryMenu;
            serverMenuChest = player != null && player.containerMenu instanceof ChestMenu;
            serverWorldMatches = level != null && level.getBlockState(target).is(Blocks.STONE);
            serverSurvival = player != null && player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL;
            serverAlive = player != null && player.isAlive() && player.getHealth() > 0;
            serverHealth = player == null ? 0 : player.getHealth();
            serverFood = player == null ? 0 : player.getFoodData().getFoodLevel();
            pollState = player == null || level == null ? "player/level missing" : serverSnapshot(player, level);
            pollOutstanding = false;
            pollReady = true;
        });
    }

    private boolean blockedStateIsSafe() {
        Minecraft client = Minecraft.getInstance();
        return !completionObserved && !taskFailed && client.player != null
                && client.player.containerMenu instanceof ChestMenu
                && client.player.getInventory().getContainerSize() == originalInventory.size()
                && inventoryMatches(originalInventory, copyInventory(client.player))
                && ItemStack.matches(originalStone(), client.player.containerMenu.getCarried())
                && serverInventory.size() == originalInventory.size()
                && inventoryMatches(originalInventory, serverInventory)
                && ItemStack.matches(originalStone(), serverCursor)
                && serverMenuChest
                && serverDrops == 0 && countDrops(client.level) == 0
                && serverWorldMatches;
    }

    private boolean clientFixtureSynchronized() {
        Minecraft client = Minecraft.getInstance();
        return client.player != null && client.level != null
                && client.player.containerMenu == client.player.inventoryMenu
                && client.player.getInventory().getContainerSize() == originalInventory.size()
                && inventoryMatches(originalInventory, copyInventory(client.player))
                && client.player.containerMenu.getCarried().isEmpty()
                && cleanGrid(client.player.containerMenu)
                && client.level.getBlockState(buildOrigin).is(Blocks.STONE)
                && client.level.getBlockEntity(chestPos) instanceof ChestBlockEntity;
    }

    private boolean clientHasOriginalStoneOnCursor() {
        Minecraft client = Minecraft.getInstance();
        return client.player != null && client.player.containerMenu instanceof ChestMenu
                && ItemStack.matches(originalStone(), client.player.containerMenu.getCarried())
                && inventoryMatches(originalInventory, copyInventory(client.player));
    }

    private boolean clientCapacityTransferConserved() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return false;
        if (client.player.containerMenu instanceof ChestMenu menu) {
            clientChestMenuContents = copyChestMenu(menu);
            clientChestMenuContentsCaptured = true;
        }
        if (!clientChestMenuContentsCaptured || !chestContainsCoal(clientChestMenuContents)) return false;
        List<ItemStack> inventory = copyInventory(client.player);
        boolean cursorWaiting = client.player.containerMenu instanceof ChestMenu
                && ItemStack.matches(originalStone(), client.player.containerMenu.getCarried())
                && inventoryHasOneFreeSlot(inventory);
        boolean cursorReturned = client.player.containerMenu == client.player.inventoryMenu
                && client.player.containerMenu.getCarried().isEmpty()
                && finalInventoryContainsReturnedStone(inventory);
        return inventoryCoalCount(inventory) == (PLAYER_SLOTS - 1) * 64
                && (cursorWaiting || cursorReturned) && countDrops(client.level) == 0;
    }

    private boolean serverCapacityTransferConserved() {
        if (!pollReady || !chestContainsCoal(serverChest)) return false;
        boolean cursorWaiting = serverMenuChest && ItemStack.matches(originalStone(), serverCursor)
                && inventoryHasOneFreeSlot(serverInventory);
        boolean cursorReturned = serverMenuInventory && serverCursor.isEmpty()
                && finalInventoryContainsReturnedStone(serverInventory);
        return inventoryCoalCount(serverInventory) == (PLAYER_SLOTS - 1) * 64
                && (cursorWaiting || cursorReturned) && serverDrops == 0;
    }

    private static boolean chestContainsCoal(List<ItemStack> chest) {
        return chest.size() == 27 && chest.get(0).is(Items.COAL)
                && chest.get(0).getCount() == 64 && onlyFirstChestSlotOccupied(chest);
    }

    private boolean finalStateIsValid() {
        Minecraft client = Minecraft.getInstance();
        return completionObserved && cursorAtCallback.isEmpty() && client.level != null
                && client.level.getBlockState(chestPos).is(Blocks.CHEST)
                && client.player != null && client.player.containerMenu == client.player.inventoryMenu
                && serverMenuInventory && !serverMenuChest
                && clientChestMenuContentsCaptured && chestContainsCoal(clientChestMenuContents)
                && serverChest.size() == 27 && serverChest.get(0).is(Items.COAL)
                && serverChest.get(0).getCount() == 64 && onlyFirstChestSlotOccupied(serverChest)
                && serverCursor.isEmpty() && finalInventoryContainsReturnedStone(serverInventory)
                && finalInventoryContainsReturnedStone(copyInventory(client.player))
                && serverDrops == 0 && countDrops(client.level) == 0
                && serverWorldMatches && serverSurvival && serverAlive && serverHealth > 0 && serverFood == 20
                && client.gameMode != null && client.gameMode.getPlayerMode() == GameType.SURVIVAL
                && client.player.isAlive() && client.player.getHealth() > 0
                && client.player.getFoodData().getFoodLevel() == 20
                && cleanGrid(client.player.containerMenu);
    }

    private boolean inventoriesMatch() {
        Minecraft client = Minecraft.getInstance();
        return client.player != null && inventoryMatches(serverInventory, copyInventory(client.player))
                && ItemStack.matches(serverCursor, client.player.containerMenu.getCarried());
    }

    private boolean inventoryHasOneFreeSlot(List<ItemStack> inventory) {
        if (inventory.size() < PLAYER_SLOTS) return false;
        int empty = 0;
        for (int slot = 0; slot < PLAYER_SLOTS; slot++) if (inventory.get(slot).isEmpty()) empty++;
        return empty == 1;
    }

    private int inventoryCoalCount(List<ItemStack> inventory) {
        int count = 0;
        for (ItemStack stack : inventory) if (stack.is(Items.COAL)) count += stack.getCount();
        return count;
    }

    private boolean finalInventoryContainsReturnedStone(List<ItemStack> inventory) {
        int coalStacks = 0;
        int coalItems = 0;
        int markedStoneCount = 0;
        for (int slot = 0; slot < Math.min(PLAYER_SLOTS, inventory.size()); slot++) {
            ItemStack stack = inventory.get(slot);
            if (stack.isEmpty()) continue;
            if (stack.is(Items.COAL) && stack.getCount() == 64) {
                coalStacks++;
                coalItems += stack.getCount();
            } else if (ItemStack.matches(originalStone(), stack) && stack.getCount() == 1) {
                markedStoneCount++;
            } else {
                return false;
            }
        }
        return coalStacks == PLAYER_SLOTS - 1 && coalItems == (PLAYER_SLOTS - 1) * 64
                && markedStoneCount == 1;
    }

    private static boolean onlyFirstChestSlotOccupied(List<ItemStack> chest) {
        if (chest.size() != 27) return false;
        for (int slot = 1; slot < chest.size(); slot++) if (!chest.get(slot).isEmpty()) return false;
        return true;
    }

    private static List<ItemStack> readChest(ServerLevel level, BlockPos pos) {
        if (level == null || !(level.getBlockEntity(pos) instanceof ChestBlockEntity chest)) return List.of();
        List<ItemStack> contents = new ArrayList<>(chest.getContainerSize());
        for (int slot = 0; slot < chest.getContainerSize(); slot++) contents.add(chest.getItem(slot).copy());
        return List.copyOf(contents);
    }

    /** Copies the client menu's synced slot view; the closed-world ChestBlockEntity is not authoritative client inventory state. */
    private static List<ItemStack> copyChestMenu(ChestMenu menu) {
        List<ItemStack> contents = new ArrayList<>(27);
        for (int slot = 0; slot < 27; slot++) contents.add(menu.getSlot(slot).getItem().copy());
        return List.copyOf(contents);
    }

    private static List<ItemStack> copyInventory(Player player) {
        List<ItemStack> result = new ArrayList<>();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            result.add(player.getInventory().getItem(slot).copy());
        }
        return List.copyOf(result);
    }

    private static boolean inventoryMatches(List<ItemStack> expected, List<ItemStack> actual) {
        if (expected.size() != actual.size()) return false;
        for (int i = 0; i < expected.size(); i++) {
            if (!ItemStack.matches(expected.get(i), actual.get(i))) return false;
        }
        return true;
    }

    private static boolean firstPlayerInventorySlotsFull(List<ItemStack> stacks) {
        if (stacks.size() < PLAYER_SLOTS) return false;
        for (int slot = 0; slot < PLAYER_SLOTS; slot++) {
            ItemStack stack = stacks.get(slot);
            if (!stack.is(Items.COAL) || stack.getCount() != stack.getMaxStackSize()) return false;
        }
        return true;
    }

    private static ItemStack originalStone() {
        ItemStack markedStone = new ItemStack(Items.STONE);
        markedStone.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                net.minecraft.network.chat.Component.literal("AltoClef full-capacity conservation probe"));
        return markedStone;
    }

    private static boolean cleanGrid(net.minecraft.world.inventory.AbstractContainerMenu menu) {
        int inputSlots = menu instanceof net.minecraft.world.inventory.CraftingMenu ? 9
                : menu instanceof net.minecraft.world.inventory.InventoryMenu ? 4 : 0;
        for (int slot = 1; slot <= inputSlots; slot++) {
            if (!menu.getSlot(slot).getItem().isEmpty()) return false;
        }
        return true;
    }

    private int countDrops(Level level) {
        return level == null || fixtureBounds == null ? -1
                : level.getEntitiesOfClass(ItemEntity.class, fixtureBounds).size();
    }

    private String clientSnapshot() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return "player missing";
        List<ItemStack> openChestMenu = client.player.containerMenu instanceof ChestMenu menu
                ? copyChestMenu(menu) : List.of();
        return "inventory=" + describe(copyInventory(client.player))
                + ",cursor=" + describe(client.player.containerMenu.getCarried())
                + ",menu=" + client.player.containerMenu.getClass().getSimpleName()
                + ",openChestMenu=" + describe(openChestMenu)
                + ",capturedChestMenu=" + (clientChestMenuContentsCaptured
                    ? describe(clientChestMenuContents) : "not-captured")
                + ",drops=" + countDrops(client.level);
    }

    private String serverSnapshot(ServerPlayer player, ServerLevel level) {
        return "inventory=" + describe(serverInventory) + ",cursor=" + describe(player.containerMenu.getCarried())
                + ",menu=" + player.containerMenu.getClass().getSimpleName()
                + ",chest=" + describe(serverChest) + ",drops=" + serverDrops
                + ",worldMatch=" + level.getBlockState(buildOrigin).is(Blocks.STONE)
                + ",mode=" + player.gameMode.getGameModeForPlayer() + ",alive=" + player.isAlive()
                + ",health=" + player.getHealth() + ",food=" + player.getFoodData().getFoodLevel();
    }

    private static String describe(ItemStack stack) {
        return stack.isEmpty() ? "empty" : stack.getCount() + "x" + stack.getItem()
                + ",components=" + stack.getComponents();
    }

    private static String describe(List<ItemStack> stacks) {
        return stacks.stream().map(BuildCleanupCapacityAcceptanceScenario::describe).toList().toString();
    }

    private static IStaticSchematic createMatchingStoneSchematic() {
        BlockState stone = Blocks.STONE.defaultBlockState();
        return new IStaticSchematic() {
            @Override public BlockState getDirect(int x, int y, int z) {
                return x == 0 && y == 0 && z == 0 ? stone : null;
            }
            @Override public BlockState desiredState(int x, int y, int z, BlockState current,
                                                     List<BlockState> available) {
                return getDirect(x, y, z);
            }
            @Override public boolean inSchematic(int x, int y, int z, BlockState current) {
                return x == 0 && y == 0 && z == 0;
            }
            @Override public int widthX() { return 1; }
            @Override public int heightY() { return 1; }
            @Override public int lengthZ() { return 1; }
        };
    }

    private void finishSuccessfully() {
        phase = Phase.DONE;
        append.accept("ASSERT\tafter QUICK_MOVE, final server/client inventories contain exactly 35 coal stacks plus the named stone; server chest slot 0 contains exactly coal64, client ChestMenu prediction captured the matching 27 slots, and the returned cursor is empty");
        append.accept("ASSERT\tBuildSchematicTask stayed pending without drops until a real chest transfer freed the cursor; it then closed the chest and completed");
        append.accept("ASSERT\tclient/server inventories, chest contents, cursor, no-drop count, world, survival, health, food, and UI state match");
        append.accept("BUILD_CLEANUP_CAPACITY_ACCEPTANCE\tPASS\tfull inventory safely blocks build cleanup until the carried stack has an external destination");
        success.run();
    }

    private void fail(String reason) {
        if (phase == Phase.FAILED || phase == Phase.DONE) return;
        phase = Phase.FAILED;
        mod.cancelUserTask();
        failure.accept(reason + "; server=" + pollState + "; client=" + clientSnapshot());
    }

    private long clientTickCount() {
        return Minecraft.getInstance().level == null ? 0 : Minecraft.getInstance().level.getGameTime();
    }

    private boolean timedOut(int limit) {
        return clientTickCount() - phaseStarted > limit;
    }
}
