package adris.altoclef.runtimetest;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.construction.BuildSchematicTask;
import adris.altoclef.tasksystem.Task;
import baritone.api.schematic.IStaticSchematic;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Exercises BuildSchematicTask's successful-completion inventory cleanup with an already
 * satisfied one-cell schematic. No builder placement or material gathering is required.
 */
public final class BuildInventoryCleanupAcceptanceScenario {
    private enum Phase { NEW, PREPARING, WAIT_INITIAL_SYNC, SEED_CURSOR, WAIT_CURSOR_SYNC,
        BUILD_CURSOR, VERIFY_CURSOR, SEED_GRID_PICKUP, WAIT_GRID_CURSOR, SEED_GRID_PLACE,
        WAIT_GRID_SYNC, BUILD_GRID, VERIFY_GRID, OPEN_TABLE, WAIT_TABLE_MENU, TABLE_PICKUP,
        WAIT_TABLE_CURSOR, TABLE_PLACE, WAIT_TABLE_GRID, BUILD_TABLE, VERIFY_TABLE, DONE, FAILED }

    private enum BuildCase { CURSOR, INVENTORY_GRID, TABLE_GRID }

    private static final int SETUP_TIMEOUT_TICKS = 1200;
    private static final int TASK_TIMEOUT_TICKS = 600;
    private static final int VERIFY_TIMEOUT_TICKS = 200;
    private static final int POLL_INTERVAL_TICKS = 3;
    private static final int FLOOR_RADIUS = 8;

    private final AltoClef mod;
    private final Consumer<String> append;
    private final Consumer<String> failure;
    private final Runnable success;

    private volatile Phase phase = Phase.NEW;
    private long phaseStarted;
    private long nextPollAt;
    private UUID playerId;
    private BlockPos buildOrigin;
    private BlockPos craftingTablePos;
    private IStaticSchematic matchingSchematic;
    private BuildSchematicTask buildTask;
    private BuildCase buildCase;
    private volatile boolean setupReady;
    private volatile String setupState = "not-started";
    private volatile boolean pollOutstanding;
    private volatile boolean pollReady;
    private volatile String pollState = "not-polled";
    private volatile List<ItemStack> serverInventory = List.of();
    private volatile List<ItemStack> serverCraftingGrid = List.of();
    private volatile ItemStack serverCursor = ItemStack.EMPTY;
    private volatile ItemStack originalStone = ItemStack.EMPTY;
    private volatile boolean serverSurvival;
    private volatile boolean serverAlive;
    private volatile float serverHealth;
    private volatile int serverFood;
    private volatile boolean serverWorldMatches;
    private volatile boolean serverMenuIsInventory;
    private volatile boolean serverMenuIsCrafting;
    private volatile boolean clientTableGridObservedEmpty;
    private volatile boolean serverTableGridObservedEmpty;
    private volatile boolean tableClickIssued;
    private volatile boolean tableBuildStarted;
    private volatile boolean completionObserved;
    private volatile boolean taskFailed;
    private volatile boolean prematureCursorCompletion;
    private volatile boolean prematureGridCompletion;
    private volatile String taskFailure = "";
    private volatile ItemStack cursorAtCallback = ItemStack.EMPTY;
    private volatile List<ItemStack> gridAtCallback = List.of();
    private int gridSeedStep;

    public BuildInventoryCleanupAcceptanceScenario(AltoClef mod, Consumer<String> append,
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
                if (!"ok".equals(setupState)) fail("Build cleanup fixture setup failed: " + setupState);
                else {
                    append.accept("BUILD_CLEANUP_SETUP\tok\t" + clientSnapshot());
                    phase = Phase.WAIT_INITIAL_SYNC;
                    phaseStarted = clientTickCount();
                }
            } else if (timedOut(SETUP_TIMEOUT_TICKS)) {
                fail("Timed out preparing build cleanup fixture: " + setupState);
            }
            return;
        }
        if (phase == Phase.WAIT_INITIAL_SYNC) {
            if (initialFixtureMatches()) {
                phase = Phase.SEED_CURSOR;
                phaseStarted = clientTickCount();
            } else if (timedOut(VERIFY_TIMEOUT_TICKS)) {
                fail("Build cleanup fixture did not synchronize: " + clientSnapshot());
            }
            return;
        }
        if (phase == Phase.SEED_CURSOR) {
            if (!clickSlot(36)) {
                fail("Could not issue actual client PICKUP click for the seeded stone stack");
                return;
            }
            phase = Phase.WAIT_CURSOR_SYNC;
            phaseStarted = clientTickCount();
            requestPoll();
            append.accept("CLICK\tcase=cursor\tmenu=InventoryMenu\tslot=36\tbutton=0\tinput=PICKUP");
            return;
        }
        if (phase == Phase.WAIT_CURSOR_SYNC) {
            if (clientCursorSeeded() && serverCursorSeeded()) {
                append.accept("BUILD_CLEANUP_CURSOR_SEED\tok\t" + pollState);
                runBuildCase(BuildCase.CURSOR);
            } else if (timedOut(VERIFY_TIMEOUT_TICKS)) {
                fail("Stone did not synchronize onto cursor before build: server=" + pollState
                        + ", client=" + clientSnapshot());
            } else if (!pollOutstanding && clientTickCount() >= nextPollAt) requestPoll();
            return;
        }
        if (phase == Phase.BUILD_TABLE) {
            observeTableGridCleanup();
            if (!pollOutstanding && clientTickCount() >= nextPollAt) requestPoll();
        }
        if (phase == Phase.BUILD_CURSOR || phase == Phase.BUILD_GRID || phase == Phase.BUILD_TABLE) {
            if (prematureCursorCompletion || prematureGridCompletion) {
                fail("BuildSchematicTask completion callback ran while cursor/crafting input was still occupied: "
                        + clientSnapshot());
                return;
            }
            if (taskFailed) {
                fail("BuildSchematicTask failed during " + phase + ": " + taskFailure);
                return;
            }
            if (completionObserved) {
                phase = switch (buildCase) {
                    case CURSOR -> Phase.VERIFY_CURSOR;
                    case INVENTORY_GRID -> Phase.VERIFY_GRID;
                    case TABLE_GRID -> Phase.VERIFY_TABLE;
                };
                phaseStarted = clientTickCount();
                nextPollAt = phaseStarted;
                requestPoll();
                return;
            }
            if (timedOut(TASK_TIMEOUT_TICKS)) {
                fail("BuildSchematicTask did not complete its cleanup case: " + phase
                        + ", task=" + (buildTask == null ? "null" : buildTask.getFailureReason())
                        + ", server=" + pollState + ", client=" + clientSnapshot());
            }
            return;
        }
        if (phase == Phase.VERIFY_CURSOR) {
            if (pollReady) {
                pollReady = false;
                append.accept("BUILD_CLEANUP_CURSOR_FINAL\t" + pollState);
                if (cleanCursorCaseIsValid() && inventoriesMatch()) {
                    append.accept("ASSERT\tBuildSchematicTask did not complete with occupied cursor; stone was conserved in inventory and cursor/grid are clean");
                    beginGridCase();
                } else if (timedOut(VERIFY_TIMEOUT_TICKS)) {
                    fail("Cursor cleanup result mismatch: server=" + pollState
                            + ", client=" + clientSnapshot());
                } else {
                    nextPollAt = clientTickCount() + POLL_INTERVAL_TICKS;
                }
            } else if (timedOut(VERIFY_TIMEOUT_TICKS)) {
                fail("Timed out verifying cursor cleanup: server=" + pollState
                        + ", client=" + clientSnapshot());
            } else if (!pollOutstanding && clientTickCount() >= nextPollAt) requestPoll();
            return;
        }
        if (phase == Phase.SEED_GRID_PICKUP) {
            if (gridSeedStep == 0) {
                int inventorySlot = findStoneInventorySlot(Minecraft.getInstance().player);
                if (inventorySlot < 0 || !clickSlot(windowSlotForInventorySlot(inventorySlot))) {
                    fail("Could not pick up conserved stone for the crafting-grid test");
                    return;
                }
                gridSeedStep = 1;
                phase = Phase.WAIT_GRID_CURSOR;
                phaseStarted = clientTickCount();
                requestPoll();
                append.accept("CLICK\tcase=grid\tmenu=InventoryMenu\tslot="
                        + windowSlotForInventorySlot(inventorySlot) + "\tbutton=0\tinput=PICKUP");
            }
            return;
        }
        if (phase == Phase.WAIT_GRID_CURSOR) {
            if (clientCursorSeeded() && serverCursorSeeded()) {
                phase = Phase.SEED_GRID_PLACE;
                phaseStarted = clientTickCount();
            } else if (timedOut(VERIFY_TIMEOUT_TICKS)) {
                fail("Stone did not synchronize onto cursor for grid test: server=" + pollState
                        + ", client=" + clientSnapshot());
            } else if (!pollOutstanding && clientTickCount() >= nextPollAt) requestPoll();
            return;
        }
        if (phase == Phase.SEED_GRID_PLACE) {
            if (!clickSlot(1)) {
                fail("Could not issue actual client PICKUP click into 2x2 crafting slot 1");
                return;
            }
            phase = Phase.WAIT_GRID_SYNC;
            phaseStarted = clientTickCount();
            requestPoll();
            append.accept("CLICK\tcase=grid\tmenu=InventoryMenu\tslot=1\tbutton=0\tinput=PICKUP");
            return;
        }
        if (phase == Phase.WAIT_GRID_SYNC) {
            if (clientGridSeeded() && serverGridSeeded()) {
                append.accept("BUILD_CLEANUP_GRID_SEED\tok\t" + pollState);
                runBuildCase(BuildCase.INVENTORY_GRID);
            } else if (timedOut(VERIFY_TIMEOUT_TICKS)) {
                fail("Stone did not synchronize into the crafting grid before build: server=" + pollState
                        + ", client=" + clientSnapshot());
            } else if (!pollOutstanding && clientTickCount() >= nextPollAt) requestPoll();
            return;
        }
        if (phase == Phase.VERIFY_GRID) {
            if (pollReady) {
                pollReady = false;
                append.accept("BUILD_CLEANUP_GRID_FINAL\t" + pollState);
                if (cleanGridCaseIsValid() && inventoriesMatch()) {
                    append.accept("ASSERT\t2x2 crafting-grid case preserved the named stone and returned it to inventory");
                    beginTableCase();
                } else if (timedOut(VERIFY_TIMEOUT_TICKS)) {
                    fail("Crafting-grid cleanup result mismatch: server=" + pollState
                            + ", client=" + clientSnapshot());
                } else {
                    nextPollAt = clientTickCount() + POLL_INTERVAL_TICKS;
                }
            } else if (timedOut(VERIFY_TIMEOUT_TICKS)) {
                fail("Timed out verifying crafting-grid cleanup: server=" + pollState
                        + ", client=" + clientSnapshot());
            } else if (!pollOutstanding && clientTickCount() >= nextPollAt) requestPoll();
            return;
        }
        if (phase == Phase.OPEN_TABLE) {
            if (Minecraft.getInstance().player != null
                    && Minecraft.getInstance().player.containerMenu instanceof CraftingMenu) {
                phase = Phase.WAIT_TABLE_MENU;
                phaseStarted = clientTickCount();
            } else if (!tableClickIssued) {
                if (!useCraftingTable()) {
                    fail("Could not issue actual controller useItemOn interaction with the seeded crafting table");
                    return;
                }
                tableClickIssued = true;
                phase = Phase.WAIT_TABLE_MENU;
                phaseStarted = clientTickCount();
                append.accept("INTERACT\tcase=table-grid\tmethod=MultiPlayerGameMode.useItemOn\tblock=" + craftingTablePos);
            }
            return;
        }
        if (phase == Phase.WAIT_TABLE_MENU) {
            if (Minecraft.getInstance().player != null
                    && Minecraft.getInstance().player.containerMenu instanceof CraftingMenu) {
                phase = Phase.TABLE_PICKUP;
                phaseStarted = clientTickCount();
            } else if (timedOut(VERIFY_TIMEOUT_TICKS)) {
                fail("Actual crafting-table interaction did not open a CraftingMenu: " + clientSnapshot());
            }
            return;
        }
        if (phase == Phase.TABLE_PICKUP) {
            int inventorySlot = findStoneInventorySlot(Minecraft.getInstance().player);
            int windowSlot = craftingWindowSlotForInventorySlot(inventorySlot);
            if (inventorySlot < 0 || windowSlot < 0 || !clickSlot(windowSlot)) {
                fail("Could not issue actual CraftingMenu PICKUP click for the conserved named stone");
                return;
            }
            phase = Phase.WAIT_TABLE_CURSOR;
            phaseStarted = clientTickCount();
            requestPoll();
            append.accept("CLICK\tcase=table-grid\tmenu=CraftingMenu\tslot=" + windowSlot
                    + "\tbutton=0\tinput=PICKUP");
            return;
        }
        if (phase == Phase.WAIT_TABLE_CURSOR) {
            if (clientOriginalStoneOnCursor() && serverOriginalStoneOnCursor()) {
                phase = Phase.TABLE_PLACE;
                phaseStarted = clientTickCount();
            } else if (timedOut(VERIFY_TIMEOUT_TICKS)) {
                fail("Named stone did not synchronize onto cursor in CraftingMenu: server=" + pollState
                        + ", client=" + clientSnapshot());
            } else if (!pollOutstanding && clientTickCount() >= nextPollAt) requestPoll();
            return;
        }
        if (phase == Phase.TABLE_PLACE) {
            if (!clickSlot(1)) {
                fail("Could not issue actual CraftingMenu PICKUP click into 3x3 input slot 1");
                return;
            }
            phase = Phase.WAIT_TABLE_GRID;
            phaseStarted = clientTickCount();
            requestPoll();
            append.accept("CLICK\tcase=table-grid\tmenu=CraftingMenu\tslot=1\tbutton=0\tinput=PICKUP");
            return;
        }
        if (phase == Phase.WAIT_TABLE_GRID) {
            if (clientTableGridSeeded() && serverTableGridSeeded()) {
                append.accept("BUILD_CLEANUP_TABLE_GRID_SEED\tok\t" + pollState);
                runBuildCase(BuildCase.TABLE_GRID);
            } else if (timedOut(VERIFY_TIMEOUT_TICKS)) {
                fail("Named stone did not synchronize into CraftingMenu 3x3 input before build: server="
                        + pollState + ", client=" + clientSnapshot());
            } else if (!pollOutstanding && clientTickCount() >= nextPollAt) requestPoll();
            return;
        }
        if (phase == Phase.VERIFY_TABLE) {
            if (pollReady) {
                pollReady = false;
                append.accept("BUILD_CLEANUP_TABLE_FINAL\t" + pollState);
                if (cleanTableCaseIsValid() && inventoriesMatch()) {
                    finishSuccessfully();
                } else if (timedOut(VERIFY_TIMEOUT_TICKS)) {
                    fail("Crafting-table cleanup result mismatch: server=" + pollState
                            + ", client=" + clientSnapshot());
                } else {
                    nextPollAt = clientTickCount() + POLL_INTERVAL_TICKS;
                }
            } else if (timedOut(VERIFY_TIMEOUT_TICKS)) {
                fail("Timed out verifying crafting-table cleanup: server=" + pollState
                        + ", client=" + clientSnapshot());
            } else if (!pollOutstanding && clientTickCount() >= nextPollAt) requestPoll();
        }
    }

    private void beginSetup() {
        Minecraft client = Minecraft.getInstance();
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null || client.player == null || client.level == null) {
            fail("Build cleanup acceptance requires an active integrated server and client player");
            return;
        }
        playerId = client.player.getUUID();
        mod.cancelUserTask();
        phase = Phase.PREPARING;
        phaseStarted = clientTickCount();
        server.execute(() -> prepareServerFixture(server));
        append.accept("BUILD_CLEANUP_SETUP\tqueued\tmatching one-cell stone schematic; one ordinary stone seeded; no resource gathering or placement expected");
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
        craftingTablePos = playerPos.offset(2, 0, 0).immutable();
        for (int x = playerPos.getX() - FLOOR_RADIUS; x <= playerPos.getX() + FLOOR_RADIUS; x++) {
            for (int z = playerPos.getZ() - FLOOR_RADIUS; z <= playerPos.getZ() + FLOOR_RADIUS; z++) {
                BlockPos floor = new BlockPos(x, playerPos.getY() - 1, z);
                level.getChunkAt(floor);
                level.setBlock(floor, Blocks.STONE.defaultBlockState(), 3);
                for (int y = playerPos.getY(); y <= playerPos.getY() + 3; y++) {
                    level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        level.setBlock(buildOrigin, Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(craftingTablePos, Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
        matchingSchematic = createMatchingStoneSchematic();

        player.closeContainer();
        player.getInventory().clearContent();
        for (int slot = 1; slot <= 4; slot++) player.inventoryMenu.getSlot(slot).set(ItemStack.EMPTY);
        player.inventoryMenu.setCarried(ItemStack.EMPTY);
        ItemStack markedStone = new ItemStack(Items.STONE);
        markedStone.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                net.minecraft.network.chat.Component.literal("AltoClef cleanup conservation probe"));
        originalStone = markedStone.copy();
        player.getInventory().setItem(0, markedStone);
        player.getInventory().setSelectedSlot(0);
        player.setGameMode(GameType.SURVIVAL);
        player.setHealth(player.getMaxHealth());
        player.getFoodData().setFoodLevel(20);
        player.getFoodData().setSaturation(5.0f);
        player.inventoryMenu.broadcastChanges();

        boolean valid = player.getInventory().getItem(0).is(Items.STONE)
                && player.getInventory().getItem(0).getCount() == 1
                && inventoryCount(player) == 1 && uiClean(player)
                && playerMode(player) == GameType.SURVIVAL
                && level.getBlockState(buildOrigin).is(Blocks.STONE);
        setupState = valid ? "ok" : "invalid:" + playerSnapshot(player, level);
        setupReady = true;
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

    private void runBuildCase(BuildCase testCase) {
        buildCase = testCase;
        phase = switch (testCase) {
            case CURSOR -> Phase.BUILD_CURSOR;
            case INVENTORY_GRID -> Phase.BUILD_GRID;
            case TABLE_GRID -> Phase.BUILD_TABLE;
        };
        phaseStarted = clientTickCount();
        nextPollAt = phaseStarted;
        completionObserved = false;
        taskFailed = false;
        prematureCursorCompletion = false;
        prematureGridCompletion = false;
        cursorAtCallback = ItemStack.EMPTY;
        gridAtCallback = List.of();
        if (testCase == BuildCase.TABLE_GRID) {
            clientTableGridObservedEmpty = false;
            serverTableGridObservedEmpty = false;
            tableBuildStarted = true;
        }
        String label = switch (testCase) {
            case CURSOR -> "occupied-cursor";
            case INVENTORY_GRID -> "2x2-crafting-grid";
            case TABLE_GRID -> "3x3-crafting-grid";
        };
        buildTask = new BuildSchematicTask("runtime inventory cleanup " + label,
                matchingSchematic, buildOrigin);
        mod.runUserTask(buildTask, () -> {
            Minecraft client = Minecraft.getInstance();
            if (client.player != null) {
                cursorAtCallback = client.player.containerMenu.getCarried().copy();
                gridAtCallback = copyGrid(client.player);
                prematureCursorCompletion = !cursorAtCallback.isEmpty();
                prematureGridCompletion = !gridEmpty(gridAtCallback);
            }
            if (buildTask.hasFailed()) {
                taskFailed = true;
                taskFailure = buildTask.getFailureReason();
            } else {
                completionObserved = true;
            }
        });
        append.accept("TASK\tBuildSchematicTask\tcase=" + label
                + "\tmatchingOneCell=true\tcallbackRequiresCleanCursorAndGrid=true");
    }

    private void beginGridCase() {
        phase = Phase.SEED_GRID_PICKUP;
        phaseStarted = clientTickCount();
        gridSeedStep = 0;
        nextPollAt = phaseStarted;
        if (!initialStoneInInventory()) {
            fail("Cursor case did not return the stone to player inventory before grid case: "
                    + clientSnapshot());
        }
    }

    private void beginTableCase() {
        Minecraft client = Minecraft.getInstance();
        if (!initialStoneInInventory() || client.level == null
                || !client.level.getBlockState(craftingTablePos).is(Blocks.CRAFTING_TABLE)) {
            fail("2x2 grid case did not leave the named stone in inventory or crafting table fixture is missing: "
                    + clientSnapshot());
            return;
        }
        clientTableGridObservedEmpty = false;
        serverTableGridObservedEmpty = false;
        tableClickIssued = false;
        phase = Phase.OPEN_TABLE;
        phaseStarted = clientTickCount();
    }

    private boolean useCraftingTable() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null || client.gameMode == null
                || client.player.containerMenu != client.player.inventoryMenu
                || !uiClean(client.player)
                || !client.level.getBlockState(craftingTablePos).is(Blocks.CRAFTING_TABLE)) return false;
        Vec3 hitLocation = new Vec3(craftingTablePos.getX() + 0.5,
                craftingTablePos.getY() + 1.0, craftingTablePos.getZ() + 0.5);
        BlockHitResult hit = new BlockHitResult(hitLocation, Direction.UP, craftingTablePos, false);
        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit);
        return true;
    }

    private void observeTableGridCleanup() {
        Minecraft client = Minecraft.getInstance();
        if (tableBuildStarted && client.player != null && client.player.containerMenu instanceof CraftingMenu
                && gridEmpty(copyGrid(client.player))) {
            clientTableGridObservedEmpty = true;
        }
    }

    private void requestPoll() {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null || pollOutstanding) return;
        pollOutstanding = true;
        pollReady = false;
        nextPollAt = clientTickCount() + (phase == Phase.BUILD_TABLE ? 1 : POLL_INTERVAL_TICKS);
        UUID checkingPlayer = playerId;
        BlockPos checkingOrigin = buildOrigin;
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(checkingPlayer);
            ServerLevel level = player == null ? null : (ServerLevel) player.level();
            if (player == null || level == null) {
                pollState = "player/level missing";
            } else {
                serverInventory = copyInventory(player);
                serverCraftingGrid = copyGrid(player);
                serverCursor = player.containerMenu.getCarried().copy();
                serverMenuIsInventory = player.containerMenu == player.inventoryMenu;
                serverMenuIsCrafting = player.containerMenu instanceof CraftingMenu;
                if (tableBuildStarted && serverMenuIsCrafting && gridEmpty(serverCraftingGrid)) {
                    serverTableGridObservedEmpty = true;
                }
                serverSurvival = playerMode(player) == GameType.SURVIVAL;
                serverAlive = player.isAlive() && player.getHealth() > 0;
                serverHealth = player.getHealth();
                serverFood = player.getFoodData().getFoodLevel();
                serverWorldMatches = level.getBlockState(checkingOrigin).is(Blocks.STONE);
                pollState = playerSnapshot(player, level);
            }
            pollOutstanding = false;
            pollReady = true;
        });
    }

    private boolean initialFixtureMatches() {
        Minecraft client = Minecraft.getInstance();
        return client.player != null && client.level != null
                && client.gameMode != null && client.gameMode.getPlayerMode() == GameType.SURVIVAL
                && client.player.getInventory().getItem(0).is(Items.STONE)
                && inventoryCount(client.player) == 1
                && cursorEmpty(client.player) && gridEmpty(client.player)
                && client.level.getBlockState(buildOrigin).is(Blocks.STONE)
                && client.level.getBlockState(craftingTablePos).is(Blocks.CRAFTING_TABLE);
    }

    private boolean clientCursorSeeded() {
        Minecraft client = Minecraft.getInstance();
        return client.player != null && inventoryCount(client.player) == 0
                && client.player.containerMenu.getCarried().is(Items.STONE)
                && gridEmpty(client.player);
    }

    private boolean serverCursorSeeded() {
        return pollReady && inventoryCount(serverInventory) == 0
                && serverCursor.is(Items.STONE) && gridEmpty(serverCraftingGrid);
    }

    private boolean clientGridSeeded() {
        Minecraft client = Minecraft.getInstance();
        return client.player != null && inventoryCount(client.player) == 0
                && cursorEmpty(client.player) && client.player.inventoryMenu.getSlot(1).getItem().is(Items.STONE)
                && oneStoneGrid(client.player);
    }

    private boolean serverGridSeeded() {
        return pollReady && inventoryCount(serverInventory) == 0
                && serverCursor.isEmpty() && serverCraftingGrid.get(0).is(Items.STONE)
                && oneStoneGrid(serverCraftingGrid);
    }

    private boolean clientOriginalStoneOnCursor() {
        Minecraft client = Minecraft.getInstance();
        return client.player != null && client.player.containerMenu instanceof CraftingMenu
                && inventoryCount(client.player) == 0
                && ItemStack.matches(originalStone, client.player.containerMenu.getCarried())
                && gridEmpty(copyGrid(client.player));
    }

    private boolean serverOriginalStoneOnCursor() {
        return pollReady && serverMenuIsCrafting && inventoryCount(serverInventory) == 0
                && ItemStack.matches(originalStone, serverCursor) && gridEmpty(serverCraftingGrid);
    }

    private boolean clientTableGridSeeded() {
        Minecraft client = Minecraft.getInstance();
        return client.player != null && client.player.containerMenu instanceof CraftingMenu
                && inventoryCount(client.player) == 0 && cursorEmpty(client.player)
                && oneStackGrid(copyGrid(client.player), originalStone, 9);
    }

    private boolean serverTableGridSeeded() {
        return pollReady && serverMenuIsCrafting && inventoryCount(serverInventory) == 0
                && serverCursor.isEmpty() && oneStackGrid(serverCraftingGrid, originalStone, 9);
    }

    private boolean cleanCursorCaseIsValid() {
        Minecraft client = Minecraft.getInstance();
        return client.player != null && cleanSurvivalPlayer(client.player)
                && inventoryCount(client.player) == 1 && countStone(client.player) == 1
                && cursorAtCallback.isEmpty() && gridEmpty(gridAtCallback)
                && countStone(serverInventory) == 1 && inventoryCount(serverInventory) == 1
                && originalStackPreserved(serverInventory)
                && serverCursor.isEmpty() && gridEmpty(serverCraftingGrid)
                && serverSurvival && serverAlive && serverHealth > 0 && serverFood == 20
                && serverUiClean() && serverWorldMatches;
    }

    private boolean cleanGridCaseIsValid() {
        Minecraft client = Minecraft.getInstance();
        return client.player != null && cleanSurvivalPlayer(client.player)
                && inventoryCount(client.player) == 1 && countStone(client.player) == 1
                && cursorAtCallback.isEmpty() && gridEmpty(gridAtCallback)
                && countStone(serverInventory) == 1 && inventoryCount(serverInventory) == 1
                && originalStackPreserved(serverInventory)
                && serverCursor.isEmpty() && gridEmpty(serverCraftingGrid)
                && serverSurvival && serverAlive && serverHealth > 0 && serverFood == 20
                && serverUiClean() && serverWorldMatches;
    }

    private boolean cleanTableCaseIsValid() {
        Minecraft client = Minecraft.getInstance();
        return client.player != null && cleanSurvivalPlayer(client.player)
                && client.player.containerMenu == client.player.inventoryMenu
                && inventoryCount(client.player) == 1 && countStone(client.player) == 1
                && originalStackPreserved(copyInventory(client.player))
                && cursorAtCallback.isEmpty() && gridEmpty(gridAtCallback)
                && clientTableGridObservedEmpty && serverTableGridObservedEmpty
                && countStone(serverInventory) == 1 && inventoryCount(serverInventory) == 1
                && originalStackPreserved(serverInventory)
                && serverCursor.isEmpty() && gridEmpty(serverCraftingGrid)
                && serverMenuIsInventory && serverSurvival && serverAlive
                && serverHealth > 0 && serverFood == 20 && serverUiClean() && serverWorldMatches;
    }

    private boolean originalStackPreserved(List<ItemStack> inventory) {
        return inventory.stream().filter(stack -> !stack.isEmpty())
                .anyMatch(stack -> ItemStack.matches(originalStone, stack));
    }

    private boolean inventoriesMatch() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || serverInventory.size() != client.player.getInventory().getContainerSize()
                || (serverMenuIsInventory != (client.player.containerMenu == client.player.inventoryMenu))
                || (serverMenuIsCrafting != (client.player.containerMenu instanceof CraftingMenu))) return false;
        for (int slot = 0; slot < serverInventory.size(); slot++) {
            if (!ItemStack.matches(serverInventory.get(slot), client.player.getInventory().getItem(slot))) return false;
        }
        List<ItemStack> clientGrid = copyGrid(client.player);
        if (serverCraftingGrid.size() != clientGrid.size()) return false;
        for (int slot = 0; slot < clientGrid.size(); slot++) {
            if (!ItemStack.matches(serverCraftingGrid.get(slot), clientGrid.get(slot))) return false;
        }
        return ItemStack.matches(serverCursor, client.player.containerMenu.getCarried());
    }

    private boolean clickSlot(int windowSlot) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.gameMode == null || windowSlot < 0) return false;
        mod.getController().handleContainerInput(client.player.containerMenu.containerId,
                windowSlot, 0, ContainerInput.PICKUP, client.player);
        return true;
    }

    private static GameType playerMode(Player player) {
        return player instanceof ServerPlayer serverPlayer
                ? serverPlayer.gameMode.getGameModeForPlayer()
                : Minecraft.getInstance().gameMode.getPlayerMode();
    }

    private boolean initialStoneInInventory() {
        Player player = Minecraft.getInstance().player;
        return player != null && inventoryCount(player) == 1 && countStone(player) == 1
                && cursorEmpty(player) && gridEmpty(player) && originalStackPreserved(copyInventory(player));
    }

    private static int windowSlotForInventorySlot(int inventorySlot) {
        return inventorySlot < 9 ? inventorySlot + 36 : inventorySlot;
    }

    private static int craftingWindowSlotForInventorySlot(int inventorySlot) {
        if (inventorySlot < 0 || inventorySlot >= 36) return -1;
        return inventorySlot < 9 ? inventorySlot + 37 : inventorySlot + 1;
    }

    private static int findStoneInventorySlot(Player player) {
        if (player == null) return -1;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (player.getInventory().getItem(slot).is(Items.STONE)) return slot;
        }
        return -1;
    }

    private static List<ItemStack> copyInventory(Player player) {
        List<ItemStack> result = new ArrayList<>();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            result.add(player.getInventory().getItem(slot).copy());
        }
        return List.copyOf(result);
    }

    private static List<ItemStack> copyGrid(Player player) {
        int inputSlots = player.containerMenu instanceof CraftingMenu ? 9 : 4;
        List<ItemStack> result = new ArrayList<>(inputSlots);
        for (int slot = 1; slot <= inputSlots; slot++) {
            result.add(player.containerMenu.getSlot(slot).getItem().copy());
        }
        return List.copyOf(result);
    }

    private static boolean gridEmpty(Player player) {
        return gridEmpty(copyGrid(player));
    }

    private static boolean gridEmpty(List<ItemStack> stacks) {
        return (stacks.size() == 4 || stacks.size() == 9) && stacks.stream().allMatch(ItemStack::isEmpty);
    }

    private static boolean oneStoneGrid(Player player) {
        return oneStoneGrid(copyGrid(player));
    }

    private static boolean oneStoneGrid(List<ItemStack> stacks) {
        if (stacks.size() != 4 || !stacks.get(0).is(Items.STONE)
                || stacks.get(0).getCount() != 1) return false;
        for (int i = 1; i < 4; i++) if (!stacks.get(i).isEmpty()) return false;
        return true;
    }

    private static boolean oneStackGrid(List<ItemStack> stacks, ItemStack expected, int size) {
        if (stacks.size() != size || !ItemStack.matches(expected, stacks.get(0))
                || stacks.get(0).getCount() != 1) return false;
        for (int i = 1; i < size; i++) if (!stacks.get(i).isEmpty()) return false;
        return true;
    }

    private static boolean cursorEmpty(Player player) {
        return player != null && player.containerMenu.getCarried().isEmpty();
    }

    private static boolean cleanSurvivalPlayer(Player player) {
        return player.containerMenu == player.inventoryMenu && uiClean(player)
                && playerMode(player) == GameType.SURVIVAL
                && player.isAlive() && player.getHealth() > 0 && player.getFoodData().getFoodLevel() == 20;
    }

    private boolean serverUiClean() {
        return serverCursor.isEmpty() && gridEmpty(serverCraftingGrid);
    }

    private static boolean uiClean(Player player) {
        return player.containerMenu == player.inventoryMenu
                && player.containerMenu.getCarried().isEmpty() && gridEmpty(player);
    }

    private static int inventoryCount(Player player) {
        return player == null ? 0 : inventoryCount(copyInventory(player));
    }

    private static int inventoryCount(List<ItemStack> stacks) {
        int count = 0;
        for (ItemStack stack : stacks) count += stack.getCount();
        return count;
    }

    private static int countStone(Player player) {
        return player == null ? 0 : countStone(copyInventory(player));
    }

    private static int countStone(List<ItemStack> stacks) {
        int count = 0;
        for (ItemStack stack : stacks) if (stack.is(Items.STONE)) count += stack.getCount();
        return count;
    }

    private String clientSnapshot() {
        Minecraft client = Minecraft.getInstance();
        return client.player == null ? "player missing" : playerSnapshot(client.player, client.level);
    }

    private String playerSnapshot(Player player, ServerLevel level) {
        return playerSnapshot(player, (net.minecraft.world.level.Level) level);
    }

    private String playerSnapshot(Player player, net.minecraft.world.level.Level level) {
        return "inventory=" + describeStacks(copyInventory(player))
                + ",cursor=" + describeStack(player.containerMenu.getCarried())
                + ",grid=" + describeStacks(copyGrid(player))
                + ",uiClean=" + uiClean(player)
                + ",mode=" + playerMode(player)
                + ",alive=" + player.isAlive() + ",health=" + player.getHealth()
                + ",food=" + player.getFoodData().getFoodLevel()
                + ",worldMatch=" + (level != null && buildOrigin != null
                    && level.getBlockState(buildOrigin).is(Blocks.STONE));
    }

    private static String describeStacks(List<ItemStack> stacks) {
        List<String> result = new ArrayList<>(stacks.size());
        for (ItemStack stack : stacks) result.add(describeStack(stack));
        return result.toString();
    }

    private static String describeStack(ItemStack stack) {
        return stack.isEmpty() ? "empty" : stack.getCount() + "x" + stack.getItem()
                + ",damage=" + stack.getDamageValue() + ",components=" + stack.getComponents();
    }

    private String playerSnapshot(ServerPlayer player, ServerLevel level) {
        return "inventory=" + describeStacks(serverInventory.isEmpty() ? copyInventory(player) : serverInventory)
                + ",cursor=" + describeStack(player.containerMenu.getCarried())
                + ",grid=" + describeStacks(copyGrid(player))
                + ",uiClean=" + uiClean(player)
                + ",mode=" + playerMode(player)
                + ",alive=" + player.isAlive() + ",health=" + player.getHealth()
                + ",food=" + player.getFoodData().getFoodLevel()
                + ",worldMatch=" + level.getBlockState(buildOrigin).is(Blocks.STONE);
    }

    private void finishSuccessfully() {
        phase = Phase.DONE;
        append.accept("ASSERT\toccupied-cursor success case preserved the stone and completed only after returning it to inventory");
        append.accept("ASSERT\t2x2 crafting-grid success case returned its stone to inventory before completion");
        append.accept("ASSERT\t3x3 CraftingMenu input was observed empty on client/server before the table screen returned to InventoryMenu; the original named stone was preserved");
        append.accept("ASSERT\tfull player inventory snapshots, original custom-named stone components, cursor, crafting grids, survival, alive/health/food, and matching schematic world verified on client and server");
        append.accept("BUILD_INVENTORY_CLEANUP_ACCEPTANCE\tPASS\tBuildSchematicTask already-satisfied one-cell cursor, 2x2, and 3x3 completion cases");
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
