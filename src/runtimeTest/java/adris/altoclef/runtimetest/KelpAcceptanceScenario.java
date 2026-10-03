package adris.altoclef.runtimetest;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.CraftInInventoryTask;
import adris.altoclef.tasks.container.CraftInTableTask;
import adris.altoclef.tasks.container.SmeltInFurnaceTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasks.resources.MineAndCollectTask;
import adris.altoclef.tasks.resources.CollectKelpTask;
import adris.altoclef.tasks.resources.SatisfyMiningRequirementTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.baritone.GoalFollowEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Exercises the normal resource plan from kelp blocks to one dried kelp block. */
public final class KelpAcceptanceScenario {
    private static volatile KelpAcceptanceScenario activeHarvestScenario;

    private enum Phase { NEW, PREPARING, WAIT_CLIENT_SYNC, GET_BLOCK, VERIFY, DONE, FAILED }

    private static final int LOG_SOURCES = 3;
    private static final int COAL_ORE_SOURCES = 16;
    private static final int KELP_TOP_SOURCES = 9;
    private static final int COAL_SOURCE_EXCLUSION_RADIUS = 48;
    private static final String COAL_DIAGNOSTIC_POSITIONS_PROPERTY =
            "altoclef.runtimeTest.kelpCoalSourcePositions";
    private static final int SETUP_TIMEOUT_TICKS = 1200;
    private static final int SETUP_SETTLE_TICKS = 40;
    private static final int PROGRESS_SNAPSHOT_INTERVAL_TICKS = 200;
    private static final int TASK_TIMEOUT_TICKS = 36000;
    private static final int VERIFY_TIMEOUT_TICKS = 400;
    private static final int TRACKER_READY_TIMEOUT_TICKS = 600;
    private static final int POLL_INTERVAL_TICKS = 10;
    private static final int FLOOR_RADIUS = 20;

    private final AltoClef mod;
    private final Consumer<String> append;
    private final Consumer<String> failure;
    private final Runnable success;

    private volatile Phase phase = Phase.NEW;
    private long phaseStarted;
    private long nextPollAt;
    private long nextProgressSnapshotAt;
    private volatile UUID playerId;
    private BlockPos fixtureOrigin;
    private int fixtureFloorY;
    private AABB fixtureBounds;
    private final List<BlockPos> seededLogPositions = new ArrayList<>();
    private final List<BlockPos> seededCoalOrePositions = new ArrayList<>();
    private final List<BlockPos> seededKelpTipPositions = new ArrayList<>();
    private final List<BlockPos> seededKelpBodyPositions = new ArrayList<>();
    private final Set<BlockPos> brokenSeededKelpTips = ConcurrentHashMap.newKeySet();
    private volatile Set<BlockPos> expectedSeededKelpTips = Set.of();
    private int oakLogsBefore;
    private int coalOreBefore;
    private int kelpTopsBefore;
    private boolean preparedCoalTrackingHeld;
    private boolean preparedCoalCacheReadyLogged;
    private boolean coalCacheDiagnosticPositionsPublished;
    private List<BlockPos> preparedCoalCacheSnapshot = List.of();
    private volatile boolean setupReady;
    private volatile String setupState = "not-started";
    private volatile boolean setupSnapshotOutstanding;
    private volatile boolean setupSnapshotReady;
    private volatile boolean setupSnapshotValid;
    private volatile String setupSnapshotState = "not-polled";
    private boolean setupSnapshotLogged;
    private volatile boolean progressSnapshotOutstanding;
    private volatile String commandCompletion;
    private volatile String commandFailure;
    private volatile boolean pollOutstanding;
    private volatile boolean pollReady;
    private volatile String pollState = "not-polled";
    private volatile List<ItemStack> serverInventory = List.of();
    private volatile int serverBlockCount;
    private volatile int serverDriedKelpCount;
    private volatile int serverKelpCount;
    private volatile int serverOakLogs;
    private volatile int serverCoalOre;
    private volatile int serverKelpTops;
    private volatile int serverSeededLogs;
    private volatile int serverSeededCoalOre;
    private volatile int serverSeededKelpTips;
    private volatile int serverSeededKelpBodiesAsHeads;
    private volatile int serverItemDropsInFixture;
    private volatile int serverRelevantItemDropsInFixture;
    private volatile String serverSeededCellStates = "not-polled";
    private volatile boolean serverUiClean;
    private volatile boolean serverSurvival;
    private volatile boolean serverAlive;
    private volatile float serverHealth;
    private volatile int serverFood;
    private volatile int nearbyNaturalCoalOresRemoved;
    private volatile int nearbyNaturalCoalOresRemaining;
    private volatile int nearbyNaturalCoalOresAtSettle;
    private volatile int loadedCoalChunksScanned;
    private volatile int unloadedCoalChunksSkipped;
    private volatile int loadedCoalChunksAtSettle;
    private volatile int unloadedCoalChunksSkippedAtSettle;
    private boolean sawMiningTask;
    private boolean sawKelpTask;
    private boolean sawMiningRequirementTask;
    private boolean sawInventoryCraftTask;
    private boolean sawTableCraftTask;
    private boolean sawSmeltTask;
    private String lastPathDiagnosticSignature;

    public KelpAcceptanceScenario(AltoClef mod, Consumer<String> append,
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
                    fail("Kelp fixture setup failed: " + setupState);
                } else {
                    append.accept("KELP_SETUP\tok\t" + setupSnapshot());
                    phase = Phase.WAIT_CLIENT_SYNC;
                    phaseStarted = clientTickCount();
                }
            } else if (timedOut(SETUP_TIMEOUT_TICKS)) {
                fail("Timed out preparing kelp fixture: " + setupState);
            }
            return;
        }

        observeTaskTree();
        if (commandFailure != null) {
            fail("Kelp acquisition command failed during " + phase + ": " + commandFailure);
            return;
        }
        if (phase == Phase.WAIT_CLIENT_SYNC) {
            if (!setupSnapshotReady) {
                if (timedOut(VERIFY_TIMEOUT_TICKS)) {
                    fail("Timed out waiting for settled kelp server fixture snapshot: snapshotOutstanding="
                            + setupSnapshotOutstanding + ", snapshot=" + setupSnapshotState);
                    return;
                }
                if (!setupSnapshotOutstanding && timedOut(SETUP_SETTLE_TICKS)) {
                    requestSetupSnapshot();
                }
                return;
            }
            if (!setupSnapshotValid) {
                fail("Kelp server fixture changed during settle: " + setupSnapshotState);
                return;
            }
            if (!setupSnapshotLogged) {
                append.accept("KELP_SERVER_FIXTURE_SETTLED\t" + setupSnapshotState);
                append.accept("KELP_COAL_SOURCE_CLEANUP\tloaded natural coal ores within radius "
                        + COAL_SOURCE_EXCLUSION_RADIUS + " removed=" + nearbyNaturalCoalOresRemoved
                        + ",remainingImmediately=" + nearbyNaturalCoalOresRemaining
                        + ",settleCensusRemaining=" + nearbyNaturalCoalOresAtSettle
                        + ",loadedChunksScanned=" + loadedCoalChunksScanned
                        + ",unloadedChunksSkipped=" + unloadedCoalChunksSkipped
                        + ",settleLoadedChunksScanned=" + loadedCoalChunksAtSettle
                        + ",settleUnloadedChunksSkipped=" + unloadedCoalChunksSkippedAtSettle
                        + "; the sphere is centered on the fixture origin; only loaded chunks are covered");
                setupSnapshotLogged = true;
            }
            if (clientFixtureIsSynchronized()) {
                if (!coalCacheDiagnosticPositionsPublished) {
                    System.setProperty(COAL_DIAGNOSTIC_POSITIONS_PROPERTY,
                            encodePositions(seededCoalOrePositions));
                    append.accept("KELP_COAL_CACHE_TRACE_CONFIG\tpublishedBeforePreparedCacheReadiness=true"
                            + ",positions=" + seededCoalOrePositions);
                    coalCacheDiagnosticPositionsPublished = true;
                }
                if (preparedCoalCacheReady()) {
                    if (!preparedCoalCacheReadyLogged) {
                        append.accept("KELP_PREPARED_COAL_TRACKED\tpositions=" + seededCoalOrePositions
                                + ",known=" + preparedCoalCacheSnapshot);
                        preparedCoalCacheReadyLogged = true;
                    }
                    startGetCommand();
                } else if (timedOut(TRACKER_READY_TIMEOUT_TICKS)) {
                    fail("Timed out waiting for BlockTracker to see every prepared coal source: known="
                            + preparedCoalCacheSnapshot
                            + ",expected=" + seededCoalOrePositions);
                }
            } else if (timedOut(VERIFY_TIMEOUT_TICKS)) {
                fail("Kelp source fixture did not synchronize after server settle: "
                        + clientSnapshot() + "; serverFixture=" + setupSnapshotState);
            }
            return;
        }
        if (phase == Phase.GET_BLOCK) {
            if (!progressSnapshotOutstanding && clientTickCount() >= nextProgressSnapshotAt) {
                requestProgressSnapshot();
            }
            if ("kelp-block".equals(commandCompletion)) {
                commandCompletion = null;
                phase = Phase.VERIFY;
                phaseStarted = clientTickCount();
                nextPollAt = phaseStarted;
                requestServerPoll();
            } else if (timedOut(TASK_TIMEOUT_TICKS)) {
                fail("Timed out during @get dried_kelp_block 1; tasks="
                        + mod.getUserTaskChain().getTasks());
            }
            return;
        }
        if (phase == Phase.VERIFY) {
            if (pollReady) {
                pollReady = false;
                append.accept("KELP_FINAL_POLL\t" + pollState);
                if (finalStateIsValid() && clientMatchesExpected()) {
                    finishSuccessfully();
                } else if (timedOut(VERIFY_TIMEOUT_TICKS)) {
                    fail("Kelp acquisition final state mismatch: server=" + pollState
                            + ", client=" + clientSnapshot());
                } else {
                    nextPollAt = clientTickCount() + POLL_INTERVAL_TICKS;
                }
            } else if (timedOut(VERIFY_TIMEOUT_TICKS)) {
                fail("Timed out waiting for final kelp acceptance verification: " + pollState
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
            fail("Kelp acceptance requires an active integrated server and client player");
            return;
        }
        if (!TaskCatalogue.taskExists("kelp") || !TaskCatalogue.taskExists(Items.DRIED_KELP)
                || !TaskCatalogue.taskExists(Items.DRIED_KELP_BLOCK)) {
            fail("Kelp acceptance requires kelp, dried kelp smelting, and dried kelp block recipe tasks");
            return;
        }
        playerId = client.player.getUUID();
        activeHarvestScenario = this;
        mod.cancelUserTask();
        mod.getBlockTracker().trackBlock(Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE);
        preparedCoalTrackingHeld = true;
        phase = Phase.PREPARING;
        phaseStarted = clientTickCount();
        server.execute(() -> prepareServerFixture(server));
        append.accept("KELP_SETUP\tqueued\tplayer inventory empty; seeded oak logs=" + LOG_SOURCES
                + ", coal ore=" + COAL_ORE_SOURCES + ", shallow-water kelp tops=" + KELP_TOP_SOURCES
                + "; cleared other loaded coal ores within " + COAL_SOURCE_EXCLUSION_RADIUS
                + " blocks; output/tool/fuel items not seeded; command=@get dried_kelp_block 1");
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
        fixtureOrigin = playerPos;
        fixtureFloorY = floorY;
        fixtureBounds = new AABB(playerPos.getX() - FLOOR_RADIUS, floorY - 2, playerPos.getZ() - FLOOR_RADIUS,
                playerPos.getX() + FLOOR_RADIUS + 1, floorY + 13,
                playerPos.getZ() + FLOOR_RADIUS + 1);
        seededLogPositions.clear();
        seededCoalOrePositions.clear();
        seededKelpTipPositions.clear();
        seededKelpBodyPositions.clear();

        player.closeContainer();
        player.getInventory().clearContent();
        for (int slot = 1; slot <= 4; slot++) player.inventoryMenu.getSlot(slot).set(ItemStack.EMPTY);
        player.inventoryMenu.setCarried(ItemStack.EMPTY);
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
                BlockPos floor = new BlockPos(x, floorY, z);
                level.getChunkAt(floor);
                level.setBlock(floor.below(2), Blocks.STONE.defaultBlockState(), 3);
                level.setBlock(floor.below(), Blocks.STONE.defaultBlockState(), 3);
                level.setBlock(floor, Blocks.STONE.defaultBlockState(), 3);
                for (int y = floorY + 1; y <= floorY + 12; y++) {
                    level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }

        // Seed ordinary logs on dry ground so the normal task tree must make its own tools.
        int logX = playerPos.getX() - 5;
        int logZ = playerPos.getZ() + 2;
        for (int i = 0; i < LOG_SOURCES; i++) {
            int x = logX + (i % 2);
            int y = floorY + 1 + (i / 2);
            BlockPos source = new BlockPos(x, y, logZ);
            level.setBlock(source, Blocks.OAK_LOG.defaultBlockState(), 3);
            seededLogPositions.add(source);
        }

        // Keep the prepared fuel bank beside the work area and remove competing loaded
        // coal sources from the bounded source-selection fixture below.
        int oreX = playerPos.getX() - 2;
        int oreZ = playerPos.getZ() - 7;
        for (int i = 0; i < COAL_ORE_SOURCES; i++) {
            int x = oreX + (i % 4);
            int z = oreZ + (i / 4);
            BlockPos source = new BlockPos(x, floorY + 1, z);
            level.setBlock(source, Blocks.COAL_ORE.defaultBlockState(), 3);
            seededCoalOrePositions.add(source);
        }

        CoalSourceScan coalCleanup = scanLoadedCoalSources(level, playerPos,
                seededCoalOrePositions, COAL_SOURCE_EXCLUSION_RADIUS, true);
        nearbyNaturalCoalOresRemoved = coalCleanup.changed();
        nearbyNaturalCoalOresRemaining = coalCleanup.remaining();
        loadedCoalChunksScanned = coalCleanup.loadedChunks();
        unloadedCoalChunksSkipped = coalCleanup.unloadedChunks();

        seedKelpPool(level, playerPos, floorY);
        for (ItemEntity drop : level.getEntitiesOfClass(ItemEntity.class, fixtureBounds)) drop.discard();

        oakLogsBefore = countBlocks(level, fixtureBounds, Blocks.OAK_LOG);
        coalOreBefore = countBlocks(level, fixtureBounds, Blocks.COAL_ORE);
        kelpTopsBefore = countBlocks(level, fixtureBounds, Blocks.KELP);
        int seededLogsBefore = countSeededBlocks(level, seededLogPositions, Blocks.OAK_LOG);
        int seededCoalBefore = countSeededBlocks(level, seededCoalOrePositions, Blocks.COAL_ORE);
        int seededTipsBefore = countSeededBlocks(level, seededKelpTipPositions, Blocks.KELP);
        int seededBodiesBefore = countSeededBlocks(level, seededKelpBodyPositions, Blocks.KELP_PLANT);
        boolean valid = player.getInventory().isEmpty() && cleanUi(player)
                && count(player, Items.DRIED_KELP_BLOCK) == 0 && count(player, Items.DRIED_KELP) == 0
                && count(player, Items.KELP) == 0
                && oakLogsBefore == LOG_SOURCES && coalOreBefore == COAL_ORE_SOURCES
                && kelpTopsBefore == KELP_TOP_SOURCES
                && seededLogsBefore == LOG_SOURCES && seededCoalBefore == COAL_ORE_SOURCES
                && seededTipsBefore == KELP_TOP_SOURCES && seededBodiesBefore == KELP_TOP_SOURCES
                && nearbyNaturalCoalOresRemaining == 0
                && player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL;
        player.inventoryMenu.broadcastChanges();
        setupState = valid ? "ok" : "seed-invalid:" + serverSnapshot(player, level)
                + ",nearbyCoalRemoved=" + nearbyNaturalCoalOresRemoved
                + ",nearbyCoalRemaining=" + nearbyNaturalCoalOresRemaining
                + ",nearbyCoalAtSettle=" + nearbyNaturalCoalOresAtSettle;
        setupReady = true;
    }

    private void seedKelpPool(ServerLevel level, BlockPos playerPos, int floorY) {
        int poolX = playerPos.getX() + 10;
        int poolZ = playerPos.getZ() + 3;
        for (int x = poolX; x <= poolX + 6; x++) {
            for (int z = poolZ; z <= poolZ + 6; z++) {
                // The generated terrain below this arena can be ocean water. Anchor the sand
                // so it cannot fall through the prepared floor before the kelp can settle.
                level.setBlock(new BlockPos(x, floorY - 3, z), Blocks.STONE.defaultBlockState(), 3);
                level.setBlock(new BlockPos(x, floorY - 2, z), Blocks.SAND.defaultBlockState(), 3);
                level.setBlock(new BlockPos(x, floorY - 1, z), Blocks.WATER.defaultBlockState(), 3);
                level.setBlock(new BlockPos(x, floorY, z), Blocks.WATER.defaultBlockState(), 3);
            }
        }
        // The pool surface is level with the dry floor, with both water layers contained below it. Short stems leave the player's eyes in air
        // while the target blocks stay submerged and can be broken from the pool edge.
        for (int dx = 0; dx < 3; dx++) {
            for (int dz = 0; dz < 3; dz++) {
                int x = poolX + 1 + dx * 2;
                int z = poolZ + 1 + dz * 2;
                BlockPos body = new BlockPos(x, floorY - 1, z);
                BlockPos tip = new BlockPos(x, floorY, z);
                level.setBlock(body, Blocks.KELP_PLANT.defaultBlockState(), 3);
                level.setBlock(tip, Blocks.KELP.defaultBlockState(), 3);
                seededKelpBodyPositions.add(body);
                seededKelpTipPositions.add(tip);
            }
        }
    }

    private void requestSetupSnapshot() {
        Minecraft client = Minecraft.getInstance();
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null || playerId == null) {
            setupSnapshotState = "server/player unavailable after fixture settle";
            setupSnapshotValid = false;
            setupSnapshotReady = true;
            return;
        }
        setupSnapshotOutstanding = true;
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null || !(player.level() instanceof ServerLevel level)) {
                setupSnapshotState = "player/level unavailable after fixture settle";
                setupSnapshotValid = false;
                setupSnapshotOutstanding = false;
                setupSnapshotReady = true;
                return;
            }
            int logs = countBlocks(level, fixtureBounds, Blocks.OAK_LOG);
            int coal = countBlocks(level, fixtureBounds, Blocks.COAL_ORE);
            int kelp = countBlocks(level, fixtureBounds, Blocks.KELP);
            int seededLogs = countSeededBlocks(level, seededLogPositions, Blocks.OAK_LOG);
            int seededCoal = countSeededBlocks(level, seededCoalOrePositions, Blocks.COAL_ORE);
            int seededTips = countSeededBlocks(level, seededKelpTipPositions, Blocks.KELP);
            int seededBodies = countSeededBlocks(level, seededKelpBodyPositions, Blocks.KELP_PLANT);
            CoalSourceScan settleCensus = scanLoadedCoalSources(level, fixtureOrigin,
                    seededCoalOrePositions, COAL_SOURCE_EXCLUSION_RADIUS, false);
            nearbyNaturalCoalOresAtSettle = settleCensus.remaining();
            loadedCoalChunksAtSettle = settleCensus.loadedChunks();
            unloadedCoalChunksSkippedAtSettle = settleCensus.unloadedChunks();
            setupSnapshotState = "logs=" + logs + ",coalOre=" + coal + ",kelpTops=" + kelp
                    + ",seeded{logs=" + seededLogs + ",coalOre=" + seededCoal
                    + ",kelpOriginalTips=" + seededTips + ",kelpBodies=" + seededBodies + "}"
                    + ",serverPlayer=" + player.blockPosition() + ",fixtureOrigin=" + fixtureOrigin
                    + ",fixtureFloorY=" + fixtureFloorY + ",dimension=" + level.dimension().identifier()
                    + ",nearbyCoalSourcesRemoved=" + nearbyNaturalCoalOresRemoved
                    + ",nearbyCoalSourcesRemaining=" + nearbyNaturalCoalOresRemaining
                    + ",nearbyCoalSourcesAtSettle=" + nearbyNaturalCoalOresAtSettle
                    + ",coalSourceExclusionRadius=" + COAL_SOURCE_EXCLUSION_RADIUS
                    + ",loadedCoalChunksScanned=" + loadedCoalChunksScanned
                    + ",unloadedCoalChunksSkipped=" + unloadedCoalChunksSkipped
                    + ",settleLoadedCoalChunks=" + loadedCoalChunksAtSettle
                    + ",settleUnloadedCoalChunksSkipped=" + unloadedCoalChunksSkippedAtSettle
                    + ",kelpCells=" + kelpCellSnapshot(level, fixtureOrigin, fixtureFloorY)
                    + ",inventoryEmpty=" + player.getInventory().isEmpty()
                    + ",uiClean=" + cleanUi(player);
            setupSnapshotValid = logs == LOG_SOURCES && coal == COAL_ORE_SOURCES
                    && kelp == KELP_TOP_SOURCES && player.getInventory().isEmpty()
                    && cleanUi(player) && seededLogs == LOG_SOURCES
                    && seededCoal == COAL_ORE_SOURCES && seededTips == KELP_TOP_SOURCES
                    && seededBodies == KELP_TOP_SOURCES && nearbyNaturalCoalOresRemaining == 0
                    && nearbyNaturalCoalOresAtSettle == 0;
            setupSnapshotOutstanding = false;
            setupSnapshotReady = true;
        });
    }

    private String kelpCellSnapshot(ServerLevel level, BlockPos playerPos, int floorY) {
        int poolX = playerPos.getX() + 10;
        int poolZ = playerPos.getZ() + 3;
        List<String> cells = new ArrayList<>(KELP_TOP_SOURCES);
        for (int dx = 0; dx < 3; dx++) {
            for (int dz = 0; dz < 3; dz++) {
                BlockPos top = new BlockPos(poolX + 1 + dx * 2, floorY, poolZ + 1 + dz * 2);
                cells.add(top + "=" + describeBlockAndFluid(level, top)
                        + ";below=" + describeBlockAndFluid(level, top.below())
                        + ";sand=" + describeBlockAndFluid(level, top.below(2))
                        + ";footing=" + describeBlockAndFluid(level, top.below(3)));
            }
        }
        return cells.toString();
    }

    private static String describeBlockAndFluid(ServerLevel level, BlockPos pos) {
        var state = level.getBlockState(pos);
        return state + "/" + state.getFluidState();
    }

    private void startGetCommand() {
        resetTaskEvidence();
        expectedSeededKelpTips = Set.copyOf(seededKelpTipPositions);
        brokenSeededKelpTips.clear();
        phase = Phase.GET_BLOCK;
        phaseStarted = clientTickCount();
        nextProgressSnapshotAt = phaseStarted + PROGRESS_SNAPSHOT_INTERVAL_TICKS;
        var previous = mod.getUserTaskChain().getLastCompletionSnapshot();
        String command = mod.getModSettings().getCommandPrefix() + "get dried_kelp_block 1";
        append.accept("COMMAND\t" + command + "\tseededLogs=" + LOG_SOURCES + "\tseededCoalOre="
                + COAL_ORE_SOURCES + "\tseededKelpTops=" + KELP_TOP_SOURCES + "\tseededToolsOrOutputs=none");
        try {
            AltoClef.getCommandExecutor().execute(command, () -> {
                var completion = mod.getUserTaskChain().getLastCompletionSnapshot();
                if (completion == null || completion == previous) {
                    commandFailure = command + " callback had no new completion snapshot";
                } else if (completion.failure() != null || completion.cancelled()) {
                    commandFailure = completion.failure() == null ? "cancelled" : completion.failure().reason();
                } else {
                    commandCompletion = "kelp-block";
                }
            }, error -> commandFailure = command + ": " + error.getMessage());
        } catch (Throwable error) {
            commandFailure = "could not execute " + command + ": " + error;
        }
    }

    private void observeTaskTree() {
        if (phase != Phase.GET_BLOCK) return;
        Task task = mod.getUserTaskChain().getCurrentTask();
        if (task != null) {
            sawKelpTask |= task.thisOrChildSatisfies(child -> child instanceof CollectKelpTask);
            sawMiningTask |= task.thisOrChildSatisfies(child -> child instanceof MineAndCollectTask);
            sawMiningRequirementTask |= task.thisOrChildSatisfies(child -> child instanceof SatisfyMiningRequirementTask);
            sawInventoryCraftTask |= task.thisOrChildSatisfies(child -> child instanceof CraftInInventoryTask);
            sawTableCraftTask |= task.thisOrChildSatisfies(child -> child instanceof CraftInTableTask);
            sawSmeltTask |= task.thisOrChildSatisfies(child -> child instanceof SmeltInFurnaceTask);
        }
        PathDiagnostic diagnostic = pathDiagnosticSnapshot();
        if (!Objects.equals(lastPathDiagnosticSignature, diagnostic.signature())) {
            lastPathDiagnosticSignature = diagnostic.signature();
            Minecraft client = Minecraft.getInstance();
            append.accept("KELP_PATH_STATE\tclientTick=" + clientTickCount()
                    + ",player=" + (client.player == null ? "missing" : client.player.position())
                    + "," + diagnostic.details());
        }
    }

    private void resetTaskEvidence() {
        sawKelpTask = false;
        sawMiningTask = false;
        sawMiningRequirementTask = false;
        sawInventoryCraftTask = false;
        sawTableCraftTask = false;
        sawSmeltTask = false;
        lastPathDiagnosticSignature = null;
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
            serverInventory = player == null ? List.of() : copyInventory(player);
            serverBlockCount = player == null ? 0 : count(player, Items.DRIED_KELP_BLOCK);
            serverDriedKelpCount = player == null ? 0 : count(player, Items.DRIED_KELP);
            serverKelpCount = player == null ? 0 : count(player, Items.KELP);
            serverUiClean = player != null && cleanUi(player);
            serverSurvival = player != null && player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL;
            serverAlive = player != null && player.isAlive() && player.getHealth() > 0;
            serverHealth = player == null ? 0 : player.getHealth();
            serverFood = player == null ? 0 : player.getFoodData().getFoodLevel();
            serverOakLogs = level == null ? 0 : countBlocks(level, bounds, Blocks.OAK_LOG);
            serverCoalOre = level == null ? 0 : countBlocks(level, bounds, Blocks.COAL_ORE);
            serverKelpTops = level == null ? 0 : countBlocks(level, bounds, Blocks.KELP);
            serverSeededLogs = level == null ? 0
                    : countSeededBlocks(level, seededLogPositions, Blocks.OAK_LOG);
            serverSeededCoalOre = level == null ? 0
                    : countSeededBlocks(level, seededCoalOrePositions, Blocks.COAL_ORE);
            serverSeededKelpTips = level == null ? 0
                    : countSeededBlocks(level, seededKelpTipPositions, Blocks.KELP);
            serverSeededKelpBodiesAsHeads = level == null ? 0
                    : countSeededBlocks(level, seededKelpBodyPositions, Blocks.KELP);
            serverSeededCellStates = level == null ? "player/level missing" : seededCellSnapshot(level);
            serverItemDropsInFixture = level == null ? 0 : countItemDrops(level, bounds);
            serverRelevantItemDropsInFixture = level == null ? 0 : countRelevantItemDrops(level, bounds);
            pollState = player == null || level == null ? "player/level missing" : serverSnapshot(player, level);
            pollOutstanding = false;
            pollReady = true;
        });
    }

    private void requestProgressSnapshot() {
        Minecraft client = Minecraft.getInstance();
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null || client.player == null || client.level == null) return;
        progressSnapshotOutstanding = true;
        long clientTick = clientTickCount();
        List<PickupDropProbe> pickupDropProbes = pickupDropProbes(client, clientTick, true);
        String clientState = "clientTick=" + clientTick + ",clientPlayer=" + client.player.blockPosition()
                + ",fixtureOrigin=" + fixtureOrigin + ",fixtureFloorY=" + fixtureFloorY
                + ",sourceCounts{logs=" + countBlocks(client.level, fixtureBounds, Blocks.OAK_LOG)
                + ",coalOre=" + countBlocks(client.level, fixtureBounds, Blocks.COAL_ORE)
                + ",kelpTops=" + countBlocks(client.level, fixtureBounds, Blocks.KELP)
                + "},seededKelp=" + seededKelpProgressSnapshot(client.level)
                + ",dropsInFixture=" + describeItemDrops(client.level, fixtureBounds)
                + ",dropsNearPlayer=" + describeItemDrops(client.level,
                        new AABB(client.player.blockPosition()).inflate(24))
                + ",pickupTargets=" + describePickupDropProbes(pickupDropProbes)
                + ",inventory=" + describeStacks(copyInventory(client.player))
                + ",ui=" + uiDiagnostic(client.player) + ",taskTrace=" + taskTraceSnapshot()
                + ",pathDiagnostic=" + pathDiagnosticSnapshot().details();
        UUID checkingPlayer = playerId;
        AABB bounds = fixtureBounds;
        BlockPos origin = fixtureOrigin;
        int floorY = fixtureFloorY;
        nextProgressSnapshotAt = clientTick + PROGRESS_SNAPSHOT_INTERVAL_TICKS;
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(checkingPlayer);
            if (player == null || !(player.level() instanceof ServerLevel level)) {
                String serverState = "serverPlayer/level missing";
                client.execute(() -> {
                    append.accept("KELP_PROGRESS\t" + clientState + ";server=" + serverState);
                    progressSnapshotOutstanding = false;
                });
                return;
            }
            String serverState = "serverPlayer=" + player.blockPosition() + ",fixtureOrigin=" + origin
                    + ",fixtureFloorY=" + floorY + ",sourceCounts{logs="
                    + countBlocks(level, bounds, Blocks.OAK_LOG) + ",coalOre="
                    + countBlocks(level, bounds, Blocks.COAL_ORE) + ",kelpTops="
                    + countBlocks(level, bounds, Blocks.KELP) + "},serverTick=" + level.getGameTime()
                    + ",seededKelp=" + seededKelpProgressSnapshot(level)
                    + ",dropsInFixture=" + describeItemDrops(level, bounds)
                    + ",dropsNearPlayer=" + describeItemDrops(level,
                            new AABB(player.blockPosition()).inflate(24))
                    + ",pickupTargetServerState=" + describeServerPickupDropProbes(level, pickupDropProbes)
                    + ",inventory=" + describeStacks(copyInventory(player))
                    + ",ui=" + uiDiagnostic(player);
            client.execute(() -> {
                append.accept("KELP_PROGRESS\tclientSample{" + clientState + "};serverSample{"
                        + serverState + "};sampling=client-first-nonatomic");
                progressSnapshotOutstanding = false;
            });
        });
    }

    private String taskTraceSnapshot() {
        Task current = mod.getUserTaskChain().getCurrentTask();
        return ("current=" + (current == null ? "none" : current)
                + ",tasks=" + mod.getUserTaskChain().getTasks())
                .replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    private PathDiagnostic pathDiagnosticSnapshot() {
        Minecraft client = Minecraft.getInstance();
        List<String> taskStates = mod.getUserTaskChain().getTasks().stream()
                .filter(Task::isActive)
                .map(task -> task.getClass().getSimpleName() + ":" + sanitizeDiagnostic(task.toString()))
                .toList();
        List<String> pickupTargets = pickupDropProbes(client, clientTickCount(), false).stream()
                .map(probe -> describePickupDropProbe(probe, client, false)).toList();

        var baritone = mod.getClientBaritone();
        if (baritone == null) {
            String missing = "baritone=missing,tasks=" + taskStates + ",pickupTargets=" + pickupTargets;
            return new PathDiagnostic(missing, missing);
        }
        var customGoals = baritone.getCustomGoalProcess();
        var pathing = baritone.getPathingBehavior();
        var activePath = pathing.getCurrent();
        Object customGoal = customGoals.getGoal();
        Object recentGoal = customGoals.mostRecentGoal();
        Object pathGoal = pathing.getGoal();
        String activePathSummary = describePathExecutor(activePath, client);
        String pathEndpoint = activePath == null ? "none" : String.valueOf(activePath.getPath().getDest());
        String controlProcess = baritone.getPathingControlManager().mostRecentInControl()
                .map(process -> process.getClass().getSimpleName() + ":" + process.displayName())
                .orElse("none");
        String lastCommand = baritone.getPathingControlManager().mostRecentCommand()
                .map(command -> command.commandType + ":" + describeGoal(command.goal, client, false))
                .orElse("none");
        String signature = "tasks=" + taskStates + ",pickupTargets=" + pickupTargets
                + ",customGoalActive=" + customGoals.isActive()
                + ",customGoal=" + describeGoal(customGoal, client, false)
                + ",pathing=" + pathing.isPathing()
                + ",pathGoal=" + describeGoal(pathGoal, client, false)
                + ",pathEndpoint=" + pathEndpoint
                + ",control=" + controlProcess + ",lastCommand=" + lastCommand;
        String detail = signature + ",recentGoal=" + describeGoal(recentGoal, client, true)
                + ",currentPath=" + activePathSummary
                + ",nextPath=" + describePathExecutor(pathing.getNext(), client)
                + ",recentPath=" + pathing.getPath().map(path -> "src=" + path.getSrc()
                        + ",dest=" + path.getDest() + ",goal=" + describeGoal(path.getGoal(), client, true))
                        .orElse("none");
        return new PathDiagnostic(signature, sanitizeDiagnostic(detail));
    }

    private List<PickupDropProbe> pickupDropProbes(Minecraft client, long sampleTick, boolean includeClosest) {
        List<PickupDropProbe> result = new ArrayList<>();
        for (Task task : mod.getUserTaskChain().getTasks()) {
            if (!(task instanceof PickupDroppedItemTask pickup) || !task.isActive()) continue;
            addPickupDropProbe(result, "currentPursuit", pickup.getCurrentDropForDiagnostics(), client, sampleTick);
            addPickupDropProbe(result, "lastSelected", pickup.getLastSelectedDropForDiagnostics(), client, sampleTick);
            if (includeClosest) {
                addPickupDropProbe(result, "closestEligible", pickup.getClosestEligibleDrop(mod), client, sampleTick);
            }
        }
        return List.copyOf(result);
    }

    private static void addPickupDropProbe(List<PickupDropProbe> result, String role,
                                           Optional<ItemEntity> drop, Minecraft client, long sampleTick) {
        drop.ifPresent(entity -> result.add(new PickupDropProbe(role, entity.getUUID(), entity.getId(),
                BuiltInRegistries.ITEM.getKey(entity.getItem().getItem()).toString(),
                entity.blockPosition(), entity.position(), entity.isAlive(), entity.isRemoved(),
                client.level != null && client.level.getEntity(entity.getId()) == entity, sampleTick)));
    }

    private String describePickupDropProbes(List<PickupDropProbe> probes) {
        if (probes.isEmpty()) return "none";
        Minecraft client = Minecraft.getInstance();
        return probes.stream().map(probe -> describePickupDropProbe(probe, client, true)).toList().toString();
    }

    private static String describePickupDropProbe(PickupDropProbe probe, Minecraft client, boolean includePosition) {
        return probe.role() + "{uuid=" + probe.uuid() + ",entityId=" + probe.entityId()
                + ",item=" + probe.item() + ",blockPos=" + probe.blockPosition()
                + (includePosition ? ",pos=" + probe.position() : "")
                + ",alive=" + probe.alive() + ",removed=" + probe.removed()
                + ",clientRegistered=" + probe.clientRegistered()
                + (includePosition && client.player != null
                    ? ",distance=" + client.player.position().distanceTo(probe.position()) : "")
                + (includePosition ? ",sampleClientTick=" + probe.sampleClientTick() : "") + "}";
    }

    private static String describeServerPickupDropProbes(ServerLevel level, List<PickupDropProbe> probes) {
        if (probes.isEmpty()) return "none";
        return probes.stream().map(probe -> {
            boolean chunkLoaded = level.hasChunkAt(probe.blockPosition());
            Entity entity = level.getEntity(probe.uuid());
            if (entity == null) {
                return probe.role() + "{uuid=" + probe.uuid() + ",sampleClientTick=" + probe.sampleClientTick()
                        + ",targetChunkLoaded=" + chunkLoaded + ",serverLookup="
                        + (chunkLoaded ? "absent-from-loaded-world" : "inconclusive-chunk-unloaded") + "}";
            }
            String stack = entity instanceof ItemEntity item
                    ? ",item=" + BuiltInRegistries.ITEM.getKey(item.getItem().getItem()) + "x" + item.getItem().getCount()
                    : "";
            return probe.role() + "{uuid=" + probe.uuid() + ",sampleClientTick=" + probe.sampleClientTick()
                    + ",serverEntityId=" + entity.getId() + ",type=" + BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())
                    + stack + ",pos=" + entity.position() + ",alive=" + entity.isAlive()
                    + ",removed=" + entity.isRemoved() + ",targetChunkLoaded=" + chunkLoaded + "}";
        }).toList().toString();
    }

    private String describeGoal(Object goal, Minecraft client, boolean includePosition) {
        if (goal == null) return "none";
        if (goal instanceof GoalFollowEntity follow) {
            Entity entity = follow.getFollowedEntityForDiagnostics();
            return "GoalFollowEntity{target=" + describeEntity(entity, client, includePosition)
                    + ",closeEnough=" + follow.getCloseEnoughDistanceForDiagnostics() + "}";
        }
        return goal.getClass().getSimpleName() + ":" + sanitizeDiagnostic(goal.toString());
    }

    private static String describeEntity(Entity entity, Minecraft client, boolean includePosition) {
        if (entity == null) return "missing";
        String stack = entity instanceof ItemEntity item
                ? ",item=" + BuiltInRegistries.ITEM.getKey(item.getItem().getItem()) + "x" + item.getItem().getCount()
                : "";
        return "uuid=" + entity.getUUID() + ",entityId=" + entity.getId()
                + ",type=" + BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()) + stack
                + ",blockPos=" + entity.blockPosition()
                + (includePosition ? ",pos=" + entity.position() : "")
                + ",alive=" + entity.isAlive() + ",removed=" + entity.isRemoved()
                + ",clientRegistered=" + (client.level != null && client.level.getEntity(entity.getId()) == entity)
                + (includePosition && client.player != null
                    ? ",distance=" + client.player.position().distanceTo(entity.position()) : "");
    }

    private String describePathExecutor(baritone.api.pathing.path.IPathExecutor executor, Minecraft client) {
        if (executor == null) return "none";
        try {
            var path = executor.getPath();
            return "index=" + executor.getPosition() + ",length=" + path.length()
                    + ",src=" + path.getSrc() + ",dest=" + path.getDest()
                    + ",goal=" + describeGoal(path.getGoal(), client, true);
        } catch (RuntimeException exception) {
            return "diagnosticError=" + exception.getClass().getSimpleName();
        }
    }

    private record PickupDropProbe(String role, UUID uuid, int entityId, String item,
                                   BlockPos blockPosition, Vec3 position, boolean alive,
                                   boolean removed, boolean clientRegistered, long sampleClientTick) {
    }

    private String seededKelpProgressSnapshot(Level level) {
        List<String> tipStates = seededKelpTipPositions.stream()
                .map(pos -> pos + "=" + level.getBlockState(pos).getBlock()).toList();
        List<String> bodyStates = seededKelpBodyPositions.stream()
                .map(pos -> pos + "=" + level.getBlockState(pos).getBlock()).toList();
        List<String> broken = brokenSeededKelpTips.stream().map(BlockPos::toString).sorted().toList();
        return "confirmedOriginalTipBreaks=" + broken.size() + "/" + expectedSeededKelpTips.size()
                + ",breakPositions=" + broken + ",originalTipCells=" + tipStates + ",originalBodyCells=" + bodyStates;
    }

    private static String describeItemDrops(Level level, AABB bounds) {
        List<String> drops = level.getEntitiesOfClass(ItemEntity.class, bounds).stream()
                .filter(ItemEntity::isAlive)
                .map(entity -> entity.getItem().isEmpty() ? null
                        : "uuid=" + entity.getUUID() + ",entityId=" + entity.getId()
                        + ",item=" + BuiltInRegistries.ITEM.getKey(entity.getItem().getItem())
                        + "x" + entity.getItem().getCount() + ",blockPos=" + entity.blockPosition()
                        + ",pos=" + entity.position() + ",loaded=" + level.hasChunkAt(entity.blockPosition()))
                .filter(Objects::nonNull)
                .sorted()
                .limit(40)
                .toList();
        return drops.isEmpty() ? "none" : drops.toString();
    }

    private static String sanitizeDiagnostic(String value) {
        return value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    private record PathDiagnostic(String signature, String details) {
    }

    private static String uiDiagnostic(Player player) {
        if (player == null || player.containerMenu == null || player.inventoryMenu == null) {
            return "menu=missing";
        }
        List<ItemStack> inputs = new ArrayList<>(4);
        for (int slot = 1; slot <= 4; slot++) {
            inputs.add(player.inventoryMenu.getSlot(slot).getItem().copy());
        }
        return "menu=" + player.containerMenu.getClass().getSimpleName()
                + "#" + player.containerMenu.containerId
                + ",carried=" + describeStacks(List.of(player.containerMenu.getCarried().copy()))
                + ",inventoryMenuInputs1to4=" + describeStacks(inputs);
    }

    private boolean finalStateIsValid() {
        Minecraft client = Minecraft.getInstance();
        return client.player != null && client.level != null
                && serverBlockCount == 1 && serverDriedKelpCount == 0 && serverKelpCount == 0
                // Natural trees are not cleared from the fixture, so seeded-log use is diagnostic only.
                && serverSeededCoalOre < COAL_ORE_SOURCES
                && allSeededKelpTipsWereBroken() && serverRelevantItemDropsInFixture == 0
                && serverUiClean && serverSurvival && serverAlive
                && serverHealth > 0 && serverFood == 20 && cleanUi(client.player)
                && sawKelpTask && sawMiningTask && sawMiningRequirementTask && sawInventoryCraftTask
                && sawTableCraftTask && sawSmeltTask;
    }

    private boolean clientMatchesExpected() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null || !cleanUi(client.player)
                || client.gameMode == null || client.gameMode.getPlayerMode() != GameType.SURVIVAL
                || !client.player.isAlive() || client.player.getHealth() <= 0
                || client.player.getFoodData().getFoodLevel() != 20
                || count(client.player, Items.DRIED_KELP_BLOCK) != 1
                || count(client.player, Items.DRIED_KELP) != 0
                || count(client.player, Items.KELP) != 0
                || serverInventory.size() != client.player.getInventory().getContainerSize()) return false;
        for (int slot = 0; slot < serverInventory.size(); slot++) {
            if (!ItemStack.matches(client.player.getInventory().getItem(slot), serverInventory.get(slot))) return false;
        }
        return countBlocks(client.level, fixtureBounds, Blocks.OAK_LOG) == serverOakLogs
                && countBlocks(client.level, fixtureBounds, Blocks.COAL_ORE) == serverCoalOre
                && countBlocks(client.level, fixtureBounds, Blocks.KELP) == serverKelpTops
                && countItemDrops(client.level, fixtureBounds) == serverItemDropsInFixture
                && countRelevantItemDrops(client.level, fixtureBounds) == serverRelevantItemDropsInFixture
                && countSeededBlocks(client.level, seededLogPositions, Blocks.OAK_LOG) == serverSeededLogs
                && countSeededBlocks(client.level, seededCoalOrePositions, Blocks.COAL_ORE) == serverSeededCoalOre
                && countSeededBlocks(client.level, seededKelpTipPositions, Blocks.KELP) == serverSeededKelpTips
                && countSeededBlocks(client.level, seededKelpBodyPositions, Blocks.KELP)
                == serverSeededKelpBodiesAsHeads
                && seededCellSnapshot(client.level).equals(serverSeededCellStates);
    }

    private boolean clientFixtureIsSynchronized() {
        Minecraft client = Minecraft.getInstance();
        return client.player != null && client.level != null && client.player.getInventory().isEmpty()
                && cleanUi(client.player) && count(client.player, Items.DRIED_KELP_BLOCK) == 0
                && count(client.player, Items.DRIED_KELP) == 0 && count(client.player, Items.KELP) == 0
                && countBlocks(client.level, fixtureBounds, Blocks.OAK_LOG) == oakLogsBefore
                && countBlocks(client.level, fixtureBounds, Blocks.COAL_ORE) == coalOreBefore
                && countBlocks(client.level, fixtureBounds, Blocks.KELP) == kelpTopsBefore
                && countSeededBlocks(client.level, seededLogPositions, Blocks.OAK_LOG) == LOG_SOURCES
                && countSeededBlocks(client.level, seededCoalOrePositions, Blocks.COAL_ORE) == COAL_ORE_SOURCES
                && countSeededBlocks(client.level, seededKelpTipPositions, Blocks.KELP) == KELP_TOP_SOURCES
                && countSeededBlocks(client.level, seededKelpBodyPositions, Blocks.KELP_PLANT)
                == KELP_TOP_SOURCES;
    }

    private static int countBlocks(Level level, AABB bounds, Block block) {
        int count = 0;
        BlockPos min = BlockPos.containing(bounds.minX, bounds.minY, bounds.minZ);
        BlockPos max = BlockPos.containing(Math.nextDown(bounds.maxX), Math.nextDown(bounds.maxY), Math.nextDown(bounds.maxZ));
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (level.getBlockState(pos).is(block)) count++;
        }
        return count;
    }

    private boolean preparedCoalCacheReady() {
        preparedCoalCacheSnapshot = mod.getBlockTracker().getKnownLocations(
                Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE);
        if (seededCoalOrePositions.isEmpty()
                || !preparedCoalCacheSnapshot.containsAll(seededCoalOrePositions)) return false;
        Set<BlockPos> seeded = Set.copyOf(seededCoalOrePositions);
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null) return false;
        return preparedCoalCacheSnapshot.stream()
                .filter(position -> !seeded.contains(position))
                .filter(position -> withinRadius(position, fixtureOrigin,
                        COAL_SOURCE_EXCLUSION_RADIUS))
                .noneMatch(position -> isCoalOre(client.level.getBlockState(position)));
    }

    private static CoalSourceScan scanLoadedCoalSources(ServerLevel level, BlockPos center,
                                                        List<BlockPos> seededSources, int radius,
                                                        boolean removeSources) {
        Set<BlockPos> seeded = Set.copyOf(seededSources);
        int changed = 0;
        int remaining = 0;
        int radiusSquared = radius * radius;
        Set<Long> loadedChunks = new HashSet<>();
        Set<Long> unloadedChunks = new HashSet<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int horizontalSquared = dx * dx + dz * dz;
                if (horizontalSquared > radiusSquared) continue;
                int verticalRadius = (int) Math.floor(Math.sqrt(radiusSquared - horizontalSquared));
                int minY = Math.max(level.getMinY(), center.getY() - verticalRadius);
                int maxY = Math.min(level.getMaxY() - 1, center.getY() + verticalRadius);
                int x = center.getX() + dx;
                int z = center.getZ() + dz;
                long chunkKey = ChunkPos.pack(x >> 4, z >> 4);
                if (!loadedChunks.contains(chunkKey) && !unloadedChunks.contains(chunkKey)) {
                    if (level.hasChunkAt(new BlockPos(x, center.getY(), z))) loadedChunks.add(chunkKey);
                    else unloadedChunks.add(chunkKey);
                }
                if (unloadedChunks.contains(chunkKey)) continue;
                for (int y = minY; y <= maxY; y++) {
                    BlockPos source = new BlockPos(x, y, z);
                    if (seeded.contains(source)) continue;
                    if (!isCoalOre(level.getBlockState(source))) continue;
                    if (!removeSources) {
                        remaining++;
                    } else {
                        level.setBlock(source, Blocks.STONE.defaultBlockState(), 3);
                        if (isCoalOre(level.getBlockState(source))) remaining++;
                        else changed++;
                    }
                }
            }
        }
        return new CoalSourceScan(changed, remaining, loadedChunks.size(), unloadedChunks.size());
    }

    private static boolean isCoalOre(BlockState state) {
        return state.is(Blocks.COAL_ORE) || state.is(Blocks.DEEPSLATE_COAL_ORE);
    }

    private static boolean withinRadius(BlockPos position, BlockPos center, int radius) {
        long dx = (long) position.getX() - center.getX();
        long dy = (long) position.getY() - center.getY();
        long dz = (long) position.getZ() - center.getZ();
        return dx * dx + dy * dy + dz * dz <= (long) radius * radius;
    }

    private static String encodePositions(List<BlockPos> positions) {
        return positions.stream().map(pos -> pos.getX() + "," + pos.getY() + "," + pos.getZ())
                .collect(java.util.stream.Collectors.joining(";"));
    }

    private static int countSeededBlocks(Level level, List<BlockPos> seededPositions, Block block) {
        int count = 0;
        for (BlockPos pos : seededPositions) {
            if (level.getBlockState(pos).is(block)) count++;
        }
        return count;
    }

    private static int countItemDrops(Level level, AABB bounds) {
        return level.getEntitiesOfClass(ItemEntity.class, bounds).size();
    }

    private static int countRelevantItemDrops(Level level, AABB bounds) {
        int count = 0;
        for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, bounds)) {
            ItemStack stack = entity.getItem();
            if (stack.is(Items.KELP) || stack.is(Items.DRIED_KELP) || stack.is(Items.DRIED_KELP_BLOCK)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private boolean allSeededKelpTipsWereBroken() {
        return !expectedSeededKelpTips.isEmpty()
                && brokenSeededKelpTips.size() == expectedSeededKelpTips.size()
                && brokenSeededKelpTips.containsAll(expectedSeededKelpTips);
    }

    /** Called by the runtime-test mixin after ServerPlayerGameMode reaches Block.playerDestroy. */
    public static void recordConfirmedKelpBreak(ServerPlayer player, BlockPos position,
                                                net.minecraft.world.level.block.state.BlockState brokenState) {
        KelpAcceptanceScenario scenario = activeHarvestScenario;
        if (scenario == null || scenario.phase != Phase.GET_BLOCK || player == null
                || !scenario.playerId.equals(player.getUUID()) || !brokenState.is(Blocks.KELP)) return;
        BlockPos immutablePosition = position.immutable();
        if (scenario.expectedSeededKelpTips.contains(immutablePosition)) {
            scenario.brokenSeededKelpTips.add(immutablePosition);
        }
    }

    private String seededCellSnapshot(Level level) {
        List<String> cells = new ArrayList<>(seededLogPositions.size() + seededCoalOrePositions.size()
                + seededKelpTipPositions.size() + seededKelpBodyPositions.size());
        appendCellStates(cells, level, "log", seededLogPositions);
        appendCellStates(cells, level, "coal", seededCoalOrePositions);
        appendCellStates(cells, level, "kelpTip", seededKelpTipPositions);
        appendCellStates(cells, level, "kelpBody", seededKelpBodyPositions);
        return cells.toString();
    }

    private static void appendCellStates(List<String> cells, Level level, String kind, List<BlockPos> positions) {
        for (BlockPos pos : positions) {
            cells.add(kind + "@" + pos + "=" + level.getBlockState(pos).getBlock());
        }
    }

    private static int count(Player player, Item item) {
        int count = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    private static List<ItemStack> copyInventory(Player player) {
        List<ItemStack> result = new ArrayList<>();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            result.add(player.getInventory().getItem(slot).copy());
        }
        return List.copyOf(result);
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
        return "inventory=empty,oakLogSources=" + oakLogsBefore + ",coalOreSources=" + coalOreBefore
                + ",kelpTopSources=" + kelpTopsBefore + ",shallowWater=two submerged blocks below dry-floor height,seededToolsOrOutputs=none";
    }

    private String clientSnapshot() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return "player/level missing";
        String snapshot = "inventory=" + describeStacks(copyInventory(client.player)) + ",uiClean=" + cleanUi(client.player)
                + ",blockOutput=" + count(client.player, Items.DRIED_KELP_BLOCK)
                + ",driedKelp=" + count(client.player, Items.DRIED_KELP)
                + ",kelp=" + count(client.player, Items.KELP);
        if (fixtureBounds == null) return snapshot;
        return snapshot + ",world{logs=" + countBlocks(client.level, fixtureBounds, Blocks.OAK_LOG)
                + ",coalOre=" + countBlocks(client.level, fixtureBounds, Blocks.COAL_ORE)
                + ",kelpTops=" + countBlocks(client.level, fixtureBounds, Blocks.KELP) + "}"
                + ",seeded{logs=" + countSeededBlocks(client.level, seededLogPositions, Blocks.OAK_LOG)
                + "/" + LOG_SOURCES + ",coalOre="
                + countSeededBlocks(client.level, seededCoalOrePositions, Blocks.COAL_ORE) + "/" + COAL_ORE_SOURCES
                + ",kelpOriginalTips=" + countSeededBlocks(client.level, seededKelpTipPositions, Blocks.KELP)
                + "/" + KELP_TOP_SOURCES + ",kelpBodiesNowHeads="
                + countSeededBlocks(client.level, seededKelpBodyPositions, Blocks.KELP) + "}"
                + ",mode=" + (client.gameMode == null ? "unknown" : client.gameMode.getPlayerMode())
                + ",alive=" + client.player.isAlive() + ",health=" + client.player.getHealth()
                + ",food=" + client.player.getFoodData().getFoodLevel();
    }

    private String serverSnapshot(ServerPlayer player, ServerLevel level) {
        return "inventory=" + describeStacks(copyInventory(player)) + ",blockOutput="
                + count(player, Items.DRIED_KELP_BLOCK) + ",driedKelp=" + count(player, Items.DRIED_KELP)
                + ",kelp=" + count(player, Items.KELP) + ",world{logs="
                + countBlocks(level, fixtureBounds, Blocks.OAK_LOG) + ",coalOre="
                + countBlocks(level, fixtureBounds, Blocks.COAL_ORE) + ",kelpTops="
                + countBlocks(level, fixtureBounds, Blocks.KELP) + "},uiClean=" + cleanUi(player)
                + ",seeded{logs=" + countSeededBlocks(level, seededLogPositions, Blocks.OAK_LOG)
                + "/" + LOG_SOURCES + ",coalOre="
                + countSeededBlocks(level, seededCoalOrePositions, Blocks.COAL_ORE) + "/" + COAL_ORE_SOURCES
                + ",kelpOriginalTips=" + countSeededBlocks(level, seededKelpTipPositions, Blocks.KELP)
                + "/" + KELP_TOP_SOURCES + ",kelpBodiesNowHeads="
                + countSeededBlocks(level, seededKelpBodyPositions, Blocks.KELP) + "}"
                + ",mode=" + player.gameMode.getGameModeForPlayer() + ",alive=" + player.isAlive()
                + ",health=" + player.getHealth() + ",food=" + player.getFoodData().getFoodLevel()
                + ",itemDrops=" + countItemDrops(level, fixtureBounds)
                + ",kelpRelatedItemDrops=" + countRelevantItemDrops(level, fixtureBounds)
                + ",seededCells=" + seededCellSnapshot(level);
    }

    private static String describeStacks(List<ItemStack> stacks) {
        List<String> result = new ArrayList<>(stacks.size());
        for (ItemStack stack : stacks) {
            result.add(stack.isEmpty() ? "empty" : stack.getCount() + "x" + stack.getItem()
                    + ",damage=" + stack.getDamageValue() + ",components=" + stack.getComponents());
        }
        return result.toString();
    }

    private void finishSuccessfully() {
        phase = Phase.DONE;
        releaseHarvestObservation();
        releasePreparedCoalTracking();
        append.accept("ASSERT\t@get dried_kelp_block 1 observed recursive mining, tool requirements, inventory/table crafting and smelting; prepared source counts decreased; sourcesBefore{logs="
                + oakLogsBefore + ",coalOre=" + coalOreBefore + ",kelpTops=" + kelpTopsBefore
                + "},sourcesAfter{logs=" + serverOakLogs + ",coalOre=" + serverCoalOre
                + ",kelpTops=" + serverKelpTops + "},seededRemaining{logs=" + serverSeededLogs
                + ",coalOre=" + serverSeededCoalOre + ",originalKelpTips=" + serverSeededKelpTips
                + ",kelpBodiesNowHeads=" + serverSeededKelpBodiesAsHeads + "},harvestedSeededTips="
                + brokenSeededKelpTips.size() + "/" + expectedSeededKelpTips.size()
                + ",itemDrops=" + serverItemDropsInFixture + ",kelpRelatedItemDrops="
                + serverRelevantItemDropsInFixture + "; all nine seeded tip break events observed");
        append.accept("ASSERT\tserver/client full inventories match; exactly one dried kelp block, no intermediate dried kelp or kelp, no kelp-related item drops, clean UI, survival/alive/health/food verified");
        append.accept("KELP_ACCEPTANCE\tPASS\tnormal recursive kelp-to-dried-kelp-block acquisition");
        success.run();
    }

    private void fail(String reason) {
        if (phase == Phase.FAILED || phase == Phase.DONE) return;
        phase = Phase.FAILED;
        String predicates = finalStatePredicates();
        String server = pollState;
        String client = clientSnapshot();
        mod.cancelUserTask();
        releaseHarvestObservation();
        releasePreparedCoalTracking();
        failure.accept(reason + "; predicates=" + predicates + "; server=" + server + "; client=" + client);
    }

    private String finalStatePredicates() {
        Minecraft client = Minecraft.getInstance();
        return "outputOne=" + (serverBlockCount == 1)
                + ",noDriedKelp=" + (serverDriedKelpCount == 0)
                + ",noKelp=" + (serverKelpCount == 0)
                + ",noKelpRelatedDrops=" + (serverRelevantItemDropsInFixture == 0)
                + ",seededLogsConsumedDiagnostic=" + (serverSeededLogs < LOG_SOURCES)
                + ",seededCoalConsumed=" + (serverSeededCoalOre < COAL_ORE_SOURCES)
                + ",allOriginalKelpTipBreaksObserved=" + allSeededKelpTipsWereBroken()
                + "(" + brokenSeededKelpTips.size() + "/" + expectedSeededKelpTips.size() + ")"
                + ",fixtureDropsDiagnostic=" + serverItemDropsInFixture
                + ",serverUiClean=" + serverUiClean + ",survival=" + serverSurvival
                + ",alive=" + serverAlive + ",serverClientInventoryMatch=" + clientMatchesExpected()
                + ",cleanClientUi=" + (client.player != null && cleanUi(client.player))
                + ",seededCellsMatch=" + (client.level != null
                && seededCellSnapshot(client.level).equals(serverSeededCellStates))
                + ",taskEvidence{kelp=" + sawKelpTask + ",mining=" + sawMiningTask
                + ",miningRequirement=" + sawMiningRequirementTask + ",inventoryCraft=" + sawInventoryCraftTask
                + ",tableCraft=" + sawTableCraftTask + ",smelt=" + sawSmeltTask + "}";
    }

    private void releasePreparedCoalTracking() {
        if (preparedCoalTrackingHeld) {
            preparedCoalTrackingHeld = false;
            mod.getBlockTracker().stopTracking(Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE);
        }
        System.clearProperty(COAL_DIAGNOSTIC_POSITIONS_PROPERTY);
    }

    private void releaseHarvestObservation() {
        if (activeHarvestScenario == this) activeHarvestScenario = null;
    }

    private void publishSetupFailure(String reason) {
        setupState = reason;
        setupReady = true;
    }

    private record CoalSourceScan(int changed, int remaining, int loadedChunks, int unloadedChunks) { }
}
