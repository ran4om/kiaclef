package adris.altoclef.runtimetest;

import adris.altoclef.AltoClef;
import adris.altoclef.chains.MobDefenseChain;
import adris.altoclef.tasks.construction.BuildSchematicTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.baritone.CachedProjectile;
import adris.altoclef.util.helpers.ProjectileHelper;
import adris.altoclef.util.schematic.SchematicLoader;
import adris.altoclef.util.schematic.SchematicSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Dedicated natural-world acceptance for the pinned rotated Litematica placement.
 * The owner must install fixture placement zero and issue the literal @build placement
 * command after {@link #begin}; tick observes that command and owns its verdict.
 */
public final class LitematicaRecoveryAcceptanceScenario {
    public static final String FIXTURE_SHA256 = "d2c54e4ef62310c1394e0fe7c66b6030c3e4549dc86dc6bd8fb012bc6ae942f1";
    private static final String RESOURCE = "/fixtures/altoclef-natural-recovery-rotated-4x2x1.litematic";
    // The packaged runtime runner defaults to 2,400 seconds. Leave six minutes
    // for client launch/save setup, then keep the scenario itself under 34 minutes.
    private static final int TIMEOUT = 34 * 60 * 20;
    private static final int SITE_RADIUS = 48;

    private enum Phase { NEW, PREFLIGHT, BUILD, INTERRUPTED, VERIFY, DONE, FAILED }
    private enum SnapshotPurpose { INITIAL, PRECOMMAND, INTERRUPTION, RESUME, FINAL }

    /** Immutable wrapper around a private copied stack; no mutable stack escapes the snapshot. */
    private static final class StackSnapshot {
        private final ItemStack stack;
        private StackSnapshot(ItemStack value) { stack = value == null ? ItemStack.EMPTY.copy() : value.copy(); }
        private boolean matches(StackSnapshot other) { return other != null && ItemStack.matches(stack, other.stack); }
        private boolean matches(ItemStack other) { return other != null && ItemStack.matches(stack, other); }
        private boolean isEmpty() { return stack.isEmpty(); }
        @Override public String toString() {
            if (stack.isEmpty()) return "empty";
            return BuiltInRegistries.ITEM.getKey(stack.getItem()) + "x" + stack.getCount()
                    + ";components=" + stack.getComponents();
        }
    }

    private record InventorySnapshot(List<StackSnapshot> slots, String menuType, int menuId,
                                     boolean playerInventoryMenu, StackSnapshot cursor,
                                     List<StackSnapshot> menuSlots, List<StackSnapshot> craftingSlots) {
        private InventorySnapshot {
            slots = List.copyOf(slots);
            menuSlots = List.copyOf(menuSlots);
            craftingSlots = List.copyOf(craftingSlots);
        }
        private boolean matches(InventorySnapshot other) {
            if (other == null || slots.size() != other.slots.size() || !menuType.equals(other.menuType)
                    || menuId != other.menuId || playerInventoryMenu != other.playerInventoryMenu
                    || menuSlots.size() != other.menuSlots.size()
                    || craftingSlots.size() != other.craftingSlots.size() || !cursor.matches(other.cursor)) return false;
            for (int i = 0; i < slots.size(); i++) if (!slots.get(i).matches(other.slots.get(i))) return false;
            for (int i = 0; i < menuSlots.size(); i++) if (!menuSlots.get(i).matches(other.menuSlots.get(i))) return false;
            for (int i = 0; i < craftingSlots.size(); i++)
                if (!craftingSlots.get(i).matches(other.craftingSlots.get(i))) return false;
            return true;
        }
        private boolean cleanPlayerUi() {
            if (!playerInventoryMenu || !cursor.isEmpty() || craftingSlots.size() != 5) return false;
            for (StackSnapshot stack : craftingSlots) if (!stack.isEmpty()) return false;
            return true;
        }
        @Override public String toString() {
            return "slots=" + slots + ",menu=" + menuType + "#" + menuId + ",playerInventoryMenu="
                    + playerInventoryMenu + ",cursor=" + cursor + ",menuSlots=" + menuSlots
                    + ",crafting=" + craftingSlots;
        }
    }

    private record CellSnapshot(BlockPos pos, BlockState state) {
        private CellSnapshot { pos = pos.immutable(); }
    }
    private record ChestSnapshot(boolean exists, String blockEntityType, boolean customNamePresent, String customName,
                                 String lootTable, long lootTableSeed, boolean locked,
                                 List<StackSnapshot> slots) {
        private ChestSnapshot { slots = List.copyOf(slots); }
        private boolean emptyCanonical() {
            if (!exists || slots.size() != 27 || customNamePresent || customName == null || !customName.isEmpty()
                    || lootTable == null || !lootTable.isEmpty() || lootTableSeed != 0 || locked) return false;
            for (StackSnapshot stack : slots) if (!stack.isEmpty()) return false;
            return true;
        }
        private boolean matches(ChestSnapshot other) {
            if (other == null || exists != other.exists || !Objects.equals(blockEntityType, other.blockEntityType)
                    || customNamePresent != other.customNamePresent
                    || !Objects.equals(customName, other.customName)
                    || !Objects.equals(lootTable, other.lootTable) || lootTableSeed != other.lootTableSeed
                    || locked != other.locked
                    || slots.size() != other.slots.size()) return false;
            for (int i = 0; i < slots.size(); i++) if (!slots.get(i).matches(other.slots.get(i))) return false;
            return true;
        }
    }
    private record PlayerSnapshot(GameType gameMode, boolean alive, float health, float maxHealth,
                                  int food, float saturation, String dimension, BlockPos position) {
        private PlayerSnapshot { position = position.immutable(); }
    }
    private record WorldSnapshot(long seed, String saveName, String generationOptions,
                                 String difficulty, boolean bonusChest, String dimension,
                                 boolean flatWorld, boolean debugWorld) { }
    private record ServerSnapshot(String token, UUID playerId, long requestId, SnapshotPurpose purpose,
                                  Phase requestedPhase, long serverTick, String error,
                                  WorldSnapshot world, PlayerSnapshot player, InventorySnapshot inventory,
                                  List<CellSnapshot> targetCells, List<CellSnapshot> supportCells, ChestSnapshot chest,
                                  Map<String, Integer> stats, long receiptWatermark, boolean targetVolumeSuitable) {
        private ServerSnapshot {
            targetCells = List.copyOf(targetCells);
            supportCells = List.copyOf(supportCells);
            stats = Map.copyOf(stats);
        }
    }
    private record SnapshotRequest(long id, SnapshotPurpose purpose, Phase requestedPhase, long requestedClientTick) { }
    private record CaptureSession(String token, UUID playerId, String dimension, BlockPos origin,
                                  List<CellSnapshot> oracleCells, Map<String, Integer> statBaseline) {
        private CaptureSession {
            origin = origin.immutable();
            oracleCells = List.copyOf(oracleCells);
            statBaseline = Map.copyOf(statBaseline);
        }
    }
    private final AltoClef mod;
    private final Consumer<String> log;
    private final Consumer<String> fail;
    private final Runnable passed;
    private volatile Phase phase = Phase.NEW;
    private volatile UUID capturedPlayerId;
    private volatile String runToken;
    private final ArrayBlockingQueue<ServerReceipt> serverReceipts = new ArrayBlockingQueue<>(1024);
    private volatile boolean serverReceiptOverflow;
    private final AtomicBoolean firstAuthoredInsertionClaimed = new AtomicBoolean();
    private record ServerReceipt(long sequence, String token, UUID playerId, String kind, String detail) { }
    private long nextServerReceiptSequence, processedServerReceiptSequence;
    private boolean serverReceiptsSealed;
    private long postSealServerReceiptCount;
    private volatile CaptureSession captureSession;
    private volatile ServerSnapshot deliveredSnapshot;
    private SnapshotRequest outstandingSnapshot;
    private long nextSnapshotRequestId;
    private InitialState initialServerState;
    private ServerSnapshot precommandSnapshot, interruptionSnapshot, resumeSnapshot, finalSnapshot;
    private record InitialState(ServerSnapshot snapshot, InventorySnapshot clientInventory,
                                List<CellSnapshot> clientCells, List<CellSnapshot> clientSupportCells, ChestSnapshot clientChest,
                                PlayerSnapshot clientPlayer) { }
    private BlockPos origin;
    private BuildSchematicTask root;
    private long startTick, interruptionTick = -1, resumeTick = -1;
    private long phaseStartedTick;
    private long defenseSelectionSequence = -1, defenseSelectionSchedulerTick = -1, dodgeSequence = -1;
    private long dodgeSchedulerTick = -1, stopSchedulerTick = -1;
    private long stopSequence = -1, resumeSequence = -1;
    private long recountSequence = -1, batchBudgetSequence = -1;
    private boolean authoritativeSnapshotsVerified;
    private long arrowSpawnTick = -1;
    private volatile long currentGameTick;
    private int remainingAtInterruption = -1;
    private volatile UUID arrowId;
    private volatile int arrowEntityId = -1;
    private Vec3 arrowSpawnPosition;
    private volatile boolean arrowSpawnPending, arrowRemoved, arrowRemovalPending;
    private long arrowSpawnRequestedTick = -1, arrowRemovalRequestedTick = -1;
    private boolean directRecount, directBatchBudget;
    private boolean firstAuthoredPlacement, onStop, dodgeTick, mobDefenseSelected, resumed;
    private boolean materialsReadyAtFirstPlacement;
    private boolean directStop, resumeStartObserved, directResume, stoppedIncomplete, stoppedCancelled,
            resumedCancellationCleared, projectileTrackerSeen;
    private BuildSchematicTask stoppedReference, resumedReference;
    private boolean sourceWood, sourceStone, sourceFuel, sourceCoalOre, plankCraftSeen, chestCraftSeen, torchCraftSeen, prohibitedContainer;
    private String statOutput = "not-collected";
    private long breakReceipts, menuOpens;
    private long damagingHitReceipts;
    private final List<String> damageEvents = new ArrayList<>();
    private long droppedEventsAtClose = -1;
    private LitematicaRecoveryEventRecorder.Snapshot archivedCapture;
    private int authoredPlacementReceipts;
    private volatile boolean captureOwned;
    private InventorySnapshot beforeSnapshot, afterResumeSnapshot;
    private List<String> serverStacks = List.of();
    private final List<String> sourceBreakPositions = new ArrayList<>();
    private boolean commandStarted;
    private boolean fixtureInstalled;
    private String fixtureHash;
    private volatile float healthBeforeStimulus;
    private String preflight = "not-run";
    private volatile boolean serverOakLogBreakSeen, serverStoneBreakSeen, serverCoalOreBreakSeen;
    private long interruptionObservationTick = -1, resumeObservationTick = -1;
    private long lastFinalPollTick = -1;
    private int finalPollCount;
    private boolean interruptionSnapshotRequested, resumeSnapshotRequested;
    private adris.altoclef.chains.UserTaskChain.CompletionSnapshot terminalCompletion;
    private long nextArrowOperationId;
    private volatile long arrowSpawnOperationId = -1, arrowRemovalOperationId = -1;
    private record ArrowOperation(long id, String token, UUID playerId, BuildSchematicTask root,
                                  UUID arrowId, int entityId, Phase startedIn) { }

    public LitematicaRecoveryAcceptanceScenario(AltoClef mod, Consumer<String> log,
                                                Consumer<String> failure, Runnable passed) {
        this.mod = Objects.requireNonNull(mod); this.log = Objects.requireNonNull(log);
        this.fail = Objects.requireNonNull(failure); this.passed = Objects.requireNonNull(passed);
        ACTIVE = this;
    }

    /** Integration surface used by the runtime mode after its natural-world reset. */
    public void begin(long gameTick) {
        if (phase != Phase.NEW) throw new IllegalStateException("scenario already started");
        startTick = gameTick;
        currentGameTick = gameTick;
        try {
            Minecraft client = Minecraft.getInstance();
            if (client.player == null || client.level == null || client.getSingleplayerServer() == null)
                throw new IllegalStateException("requires the prepared integrated Survival world");
            capturedPlayerId = client.player.getUUID();
            runToken = UUID.randomUUID().toString();
            if (!cleanStart(client.player) || client.gameMode == null || client.gameMode.getPlayerMode() != GameType.SURVIVAL)
                throw new IllegalStateException("client is not empty-inventory Survival");
            origin = findNaturalSite(client);
            if (origin == null) throw new IllegalStateException("no safe, naturally occupied-AIR target in radius " + SITE_RADIUS);
            phase = Phase.PREFLIGHT;
            phaseStartedTick = gameTick;
            requestServerSnapshot(client, SnapshotPurpose.INITIAL);
        } catch (Exception e) { reject("pinned fixture preflight failed: " + e); }
    }

    /** Compatibility overload for callers that already pinned and independently logged the origin. */
    public void begin(BlockPos pinnedPlacementOrigin, long gameTick) {
        begin(gameTick);
        if (phase != Phase.FAILED && !origin.equals(pinnedPlacementOrigin)) reject("caller origin differs from independently selected natural site");
    }

    private BlockPos findNaturalSite(Minecraft client) {
        var level = client.level;
        BlockPos player = client.player.blockPosition();
        BlockPos best = null;
        long bestDistance = Long.MAX_VALUE;
        for (int dx = -SITE_RADIUS; dx <= SITE_RADIUS; dx++) for (int dz = -SITE_RADIUS; dz <= SITE_RADIUS; dz++) {
            if (dx * dx + dz * dz > SITE_RADIUS * SITE_RADIUS) continue;
            int x = player.getX() + dx, z = player.getZ() + dz;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos candidate = new BlockPos(x, y, z);
            if (!targetVolumeValid(level, candidate)) continue;
            long distance = dx * dx + dz * dz;
            if (distance < bestDistance) { best = candidate; bestDistance = distance; }
        }
        return best;
    }

    private static boolean targetVolumeValid(net.minecraft.world.level.Level level, BlockPos o) {
        for (int z = 0; z < 3; z++) {
            if (!level.getBlockState(o.offset(0, 0, z)).isAir()) return false;
            if (level.getBlockState(o.offset(0, -1, z)).isAir()) return false;
        }
        BlockState occupiedAir = level.getBlockState(o.offset(0, 0, 3));
        if (occupiedAir.isAir() || occupiedAir.getFluidState().isSource()
                || occupiedAir.getDestroySpeed(level, o.offset(0, 0, 3)) < 0) return false;
        for (int z = 0; z < 4; z++) {
            if (!level.getBlockState(o.offset(0, 1, z)).isAir()) return false;
            if (level.getBlockState(o.offset(0, -1, z)).isAir()) return false;
        }
        return true;
    }

    private static boolean bonusChestEnabled(MinecraftServer server) throws Exception {
        Object options = server.getWorldGenSettings().options();
        return (boolean) options.getClass().getMethod("generateBonusChest").invoke(options);
    }

    private void installActivePlacement(Path fixture, Minecraft client) throws Exception {
        Class<?> schematicClass = Class.forName("fi.dy.masa.litematica.schematic.LitematicaSchematic");
        Object schematic = schematicClass.getMethod("createFromFile", Path.class, String.class)
                .invoke(null, fixture.getParent(), fixture.getFileName().toString());
        if (schematic == null) throw new IllegalStateException("Litematica native loader rejected fixture");
        Class<?> placementClass = Class.forName("fi.dy.masa.litematica.schematic.placement.SchematicPlacement");
        Object placement = placementClass.getMethod("createFor", schematicClass, BlockPos.class,
                        String.class, boolean.class, boolean.class)
                .invoke(null, schematic, origin, "AltoClef natural recovery acceptance", true, true);
        Class<?> rotationClass = Class.forName("net.minecraft.world.level.block.Rotation");
        Class<?> mirrorClass = Class.forName("net.minecraft.world.level.block.Mirror");
        Object clockwise90 = Enum.valueOf((Class) rotationClass, "CLOCKWISE_90");
        Object mirrorNone = Enum.valueOf((Class) mirrorClass, "NONE");
        placementClass.getMethod("setRotation", rotationClass,
                Class.forName("fi.dy.masa.malilib.gui.interfaces.IMessageConsumer")).invoke(placement, clockwise90, null);
        placementClass.getMethod("setMirror", mirrorClass,
                Class.forName("fi.dy.masa.malilib.gui.interfaces.IMessageConsumer")).invoke(placement, mirrorNone, null);
        Object manager = Class.forName("fi.dy.masa.litematica.data.DataManager")
                .getMethod("getSchematicPlacementManager").invoke(null);
        List<?> placements = (List<?>) manager.getClass().getMethod("getAllSchematicsPlacements").invoke(manager);
        if (!placements.isEmpty()) throw new IllegalStateException("cannot prove placement index 0; existing placements=" + placements.size());
        manager.getClass().getMethod("addSchematicPlacement", placementClass, boolean.class).invoke(manager, placement, true);
        placements = (List<?>) manager.getClass().getMethod("getAllSchematicsPlacements").invoke(manager);
        if (placements.size() != 1 || placements.get(0) != placement) throw new IllegalStateException("fixture is not active placement index 0");
        if (!placementClass.getMethod("getOrigin").invoke(placement).equals(origin)
                || !placementClass.getMethod("getRotation").invoke(placement).toString().equals("CLOCKWISE_90")
                || !placementClass.getMethod("getMirror").invoke(placement).toString().equals("NONE"))
            throw new IllegalStateException("native placement origin/rotation/mirror mismatch");
    }

    private static void validateUnrotatedFixture(SchematicSnapshot loaded) {
        if (loaded.schematic().widthX() != 4 || loaded.schematic().heightY() != 2 || loaded.schematic().lengthZ() != 1)
            throw new IllegalStateException("pinned file dimensions differ from 4x2x1");
        BlockState[][] cells = {{Blocks.OAK_PLANKS.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState(),
                Blocks.CHEST.defaultBlockState(), Blocks.AIR.defaultBlockState()},
                {Blocks.TORCH.defaultBlockState(), Blocks.TORCH.defaultBlockState(), Blocks.AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()}};
        for (int y = 0; y < 2; y++) for (int x = 0; x < 4; x++)
            if (!Objects.equals(cells[y][x], loaded.schematic().getDirect(x, y, 0)))
                throw new IllegalStateException("handwritten fixture oracle mismatch at " + x + "," + y);
    }

    private void validateActiveOracle(SchematicSnapshot active) {
        if (active.origin().getX() != origin.getX() || active.origin().getY() != origin.getY()
                || active.origin().getZ() != origin.getZ() || active.schematic().widthX() != 1
                || active.schematic().heightY() != 2 || active.schematic().lengthZ() != 4)
            throw new IllegalStateException("native active adapter origin/dimensions mismatch: " + active);
        BlockState[][] expected = {{Blocks.OAK_PLANKS.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState(),
                Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.EAST), Blocks.AIR.defaultBlockState()},
                {Blocks.TORCH.defaultBlockState(), Blocks.TORCH.defaultBlockState(), Blocks.AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()}};
        for (int y = 0; y < 2; y++) for (int z = 0; z < 4; z++) {
            BlockState got = active.schematic().getDirect(0, y, z);
            if (!Objects.equals(expected[y][z], got)) throw new IllegalStateException("rotated adapter oracle mismatch at (0," + y + "," + z + "): " + got);
        }
    }

    private boolean nativeSchematicOracleReady() throws Exception {
        Class<?> handler = Class.forName("fi.dy.masa.litematica.world.SchematicWorldHandler");
        Object world = handler.getMethod("getSchematicWorld").invoke(null);
        if (!(world instanceof net.minecraft.world.level.Level schematicWorld)) return false;
        BlockState[][] expected = {{Blocks.OAK_PLANKS.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState(),
                Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.EAST), Blocks.AIR.defaultBlockState()},
                {Blocks.TORCH.defaultBlockState(), Blocks.TORCH.defaultBlockState(), Blocks.AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()}};
        for (int y=0;y<2;y++) for (int z=0;z<4;z++) {
            BlockState actual = schematicWorld.getBlockState(origin.offset(0,y,z));
            if (actual == null || !actual.equals(expected[y][z])) return false;
        }
        return true;
    }

    private void writeRunManifest(Minecraft client, ServerSnapshot server) throws Exception {
        if (initialServerState == null || server == null) throw new IllegalStateException("authoritative manifest snapshots are missing");
        Path evidence = client.gameDirectory.toPath().resolve("runtime-test-results");
        Files.createDirectories(evidence);
        String row = "scenario=natural-litematica-recovery\nseed=" + server.world().seed()
                + "\nsave=" + server.world().saveName() + "\ndifficulty=" + server.world().difficulty()
                + "\nworldGeneration=" + server.world().generationOptions() + "\nflatWorld=" + server.world().flatWorld()
                + "\ndebugWorld=" + server.world().debugWorld() + "\nbonusChest=" + server.world().bonusChest()
                + "\ndimension=" + server.world().dimension() + "\norigin=" + origin
                + "\nfixtureSha256=" + FIXTURE_SHA256
                + "\nlitematica=0.28.8;jarSha256=6de0774f78aee1698154dc7e7fa3c3afb19201e3d9474ddaf073fa4f4309a064"
                + "\nmalilib=0.29.6;jarSha256=db49040123d8dfe725a95407b4e777383e7162656045bff38ff72fc570080600"
                + "\nexpectedOracle=" + oracleSummary() + ";rotatedOffsets=(0,y,x);chestPayload=empty"
                + "\nrotation=CLOCKWISE_90\nmirror=NONE\ninitialClientCensus="
                + formatCells(initialServerState.clientCells()) + "\ninitialServerCensus="
                + formatCells(initialServerState.snapshot().targetCells()) + "\ninitialClientSupportCensus="
                + formatCells(initialServerState.clientSupportCells()) + "\ninitialServerSupportCensus="
                + formatCells(initialServerState.snapshot().supportCells()) + "\ninitialInventory="
                + initialServerState.clientInventory() + "\ninitialServerInventory=" + initialServerState.snapshot().inventory()
                + "\nserverTick=" + server.serverTick() + "\ninitialClientHealth=" + initialServerState.clientPlayer().health()
                + "\ninitialServerHealth=" + initialServerState.snapshot().player().health()
                + "\n";
        Files.writeString(evidence.resolve("litematica-recovery-" + System.currentTimeMillis() + ".manifest.txt"), row);
    }

    private static String formatCells(List<CellSnapshot> cells) {
        return cells.stream().map(cell -> cell.pos() + "=" + cell.state()).toList().toString();
    }

    /** Called once per client tick. The command itself is dispatched by RuntimeAcceptanceMod. */
    public void tick(long gameTick) {
        if (phase == Phase.NEW || phase == Phase.DONE || phase == Phase.FAILED) return;
        currentGameTick = gameTick;
        drainServerReceipts();
        processDeliveredSnapshot(Minecraft.getInstance(), gameTick);
        if (phase == Phase.DONE || phase == Phase.FAILED) return;
        if (outstandingSnapshot != null && gameTick - outstandingSnapshot.requestedClientTick() > 200) {
            reject("authoritative snapshot timed out: purpose=" + outstandingSnapshot.purpose()
                    + ",request=" + outstandingSnapshot.id() + ",requestedPhase=" + outstandingSnapshot.requestedPhase());
            return;
        }
        if (arrowSpawnPending && arrowSpawnRequestedTick >= 0 && gameTick - arrowSpawnRequestedTick > 100) {
            reject("bounded arrow spawn request timed out"); return;
        }
        if (arrowRemovalPending && arrowRemovalRequestedTick >= 0 && gameTick - arrowRemovalRequestedTick > 100) {
            reject("bounded arrow removal request timed out"); return;
        }
        if (arrowId != null && !arrowRemoved && arrowSpawnTick >= 0 && gameTick - arrowSpawnTick > 40) {
            reject("declared arrow exceeded its 40-tick stimulus window without server removal"); return;
        }
        if (gameTick - startTick > TIMEOUT) { reject("scenario timed out in " + phase); return; }
        long phaseLimit = switch (phase) {
            case PREFLIGHT -> 200;
            case BUILD -> 36_000;
            case INTERRUPTED -> 2_400;
            case VERIFY -> 1_200;
            default -> TIMEOUT;
        };
        if (gameTick - phaseStartedTick > phaseLimit) { reject("phase " + phase + " timed out after " + (gameTick-phaseStartedTick) + " ticks"); return; }
        Minecraft client = Minecraft.getInstance();
        if (phase == Phase.PREFLIGHT) {
            if (client.player == null || client.level == null || client.getSingleplayerServer() == null) return;
            if (!cleanStart(client.player)) { reject("start inventory/menu/game mode is not clean Survival"); return; }
            try {
                if (initialServerState == null) return;
                if (!fixtureInstalled) {
                    Path fixture = client.gameDirectory.toPath().resolve("schematics")
                            .resolve("altoclef-natural-recovery-rotated-4x2x1.litematic");
                    try (InputStream in = LitematicaRecoveryAcceptanceScenario.class.getResourceAsStream(RESOURCE)) {
                        if (in == null) throw new IllegalStateException("Pinned fixture resource missing: " + RESOURCE);
                        Files.createDirectories(fixture.getParent());
                        Files.copy(in, fixture, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                    fixtureHash = sha256(fixture);
                    if (!FIXTURE_SHA256.equals(fixtureHash)) throw new IllegalStateException("fixture hash mismatch: " + fixtureHash);
                    validateUnrotatedFixture(SchematicLoader.load(fixture));
                    installActivePlacement(fixture, client);
                    preflight = "sha256=" + fixtureHash + ";sourceDimensions=4x2x1;rotation=CLOCKWISE_90;mirror=NONE;"
                            + "origin=" + origin + ";expectedDimensions=1x2x4;oracle=" + oracleSummary();
                    log.accept("LITEMATICA_RECOVERY_PREFLIGHT\t" + preflight);
                    fixtureInstalled = true;
                }
                if (!nativeSchematicOracleReady()) {
                    if (gameTick - startTick > 200) reject("native Litematica schematic world did not expose the pinned oracle cells");
                    return;
                }
                validateActiveOracle(SchematicLoader.loadActiveLitematica(0));
                if (!sameCells(initialServerState.clientCells(), captureCells(client.level, origin))
                        || !sameCells(initialServerState.clientSupportCells(), captureSupportCells(client.level, origin))
                        || !initialServerState.clientInventory().matches(captureInventory(client.player))) {
                    reject("client target/inventory changed between initial preflight and command"); return;
                }
            } catch (Exception e) { reject("native/adapter preflight failed: " + e); return; }
            if (precommandSnapshot == null) {
                if (outstandingSnapshot == null) requestServerSnapshot(client, SnapshotPurpose.PRECOMMAND);
                return;
            }
            if (!precommandSnapshot.targetVolumeSuitable()
                    || !initialServerState.snapshot().world().equals(precommandSnapshot.world())
                    || !clientPlayerMatches(initialServerState.snapshot().player(), precommandSnapshot.player())
                    || !initialServerState.snapshot().inventory().matches(precommandSnapshot.inventory())
                    || !initialServerState.snapshot().chest().matches(precommandSnapshot.chest())
                    || !sameCells(initialServerState.snapshot().targetCells(), precommandSnapshot.targetCells())
                    || !sameCells(initialServerState.snapshot().supportCells(), precommandSnapshot.supportCells())
                    || !precommandSnapshot.inventory().matches(captureInventory(client.player))
                    || !clientPlayerMatches(precommandSnapshot.player(), capturePlayer(client.player,
                        client.gameMode == null ? null : client.gameMode.getPlayerMode(),
                        client.level.dimension().identifier().toString()))
                    || !sameCells(precommandSnapshot.targetCells(), captureCells(client.level, origin))
                    || !sameCells(precommandSnapshot.supportCells(), captureSupportCells(client.level, origin))) {
                reject("authoritative world/player snapshot changed before command: " + describeSnapshot(precommandSnapshot));
                return;
            }
            try { writeRunManifest(client, precommandSnapshot); }
            catch (Exception e) { reject("could not write authoritative run manifest: " + e); return; }
            if (!commandStarted) {
                phase = Phase.BUILD;
                phaseStartedTick = gameTick;
                commandStarted = true;
                String prefix = mod.getModSettings().getCommandPrefix();
                log.accept("COMMAND\t" + prefix + "build placement\tnative placement index=0\torigin=" + origin);
                log.accept("LITEMATICA_RECOVERY_START\t" + preflight + ";worldSeed="
                        + precommandSnapshot.world().seed()
                        + ";save=" + precommandSnapshot.world().saveName()
                        + ";serverTick=" + precommandSnapshot.serverTick()
                        + ";spawn=" + client.player.blockPosition());
                try {
                    AltoClef.getCommandExecutor().execute(prefix + "build placement", () -> {
                        if (isTerminal(phase)) return;
                        var outcome = mod.getUserTaskChain().getLastCompletionSnapshot();
                        if (outcome == null) {
                            reject("@build command callback without completion snapshot");
                        } else if (!(outcome.task() instanceof BuildSchematicTask completedRoot)) {
                            reject("@build placement completed a different task: " + outcome);
                        } else {
                            if (root == null) root = completedRoot;
                            if (root == completedRoot) terminalCompletion = outcome;
                        }
                    }, error -> reject("@build placement command dispatch failed: " + error.getMessage()));
                } catch (Throwable error) { reject("could not dispatch @build placement: " + error); }
            }
            return;
        }
        Task current = mod.getUserTaskChain().getCurrentTask();
        if (root == null && current instanceof BuildSchematicTask build) {
            root = build;
            log.accept("LITEMATICA_RECOVERY_ROOT\tclass=BuildSchematicTask\tidentity="
                    + Integer.toUnsignedString(System.identityHashCode(root), 16));
        }
        if (root == null) return;
        LitematicaRecoveryEventRecorder.Snapshot events = LitematicaRecoveryEventRecorder.snapshot();
        List<LitematicaRecoveryEventRecorder.Event> es = events.events();
        refreshLifecycleSequences(es);
        onStop |= directStop && stoppedReference == root;
        if (onStop && beforeSnapshot == null && client.player != null)
            beforeSnapshot = captureInventory(client.player);
        if (onStop && !interruptionSnapshotRequested && outstandingSnapshot == null) {
            interruptionSnapshotRequested = true;
            requestServerSnapshot(client, SnapshotPurpose.INTERRUPTION);
        }
        resumed |= directResume && resumedReference == root && onStop;
        if (resumed && resumeTick < 0) resumeTick = gameTick;
        if (resumed && !resumeSnapshotRequested && outstandingSnapshot == null) {
            resumeSnapshotRequested = true;
            requestServerSnapshot(client, SnapshotPurpose.RESUME);
        }
        for (var selected : es) {
            if (!selected.kind().equals("taskRunner.selectedChain") || selected.relatedType() == null
                    || !selected.relatedType().endsWith("MobDefenseChain")) continue;
            List<LitematicaRecoveryEventRecorder.Event> epoch = es.stream()
                    .filter(e -> e.schedulerTick() == selected.schedulerTick()).toList();
            boolean defense = epoch.stream().anyMatch(e -> priority(e, "Mob Defense", "65.0"));
            boolean user = epoch.stream().anyMatch(e -> priority(e, "User Tasks", "50.0"));
            boolean competitor = epoch.stream().anyMatch(e -> e.kind().equals("taskRunner.priority")
                    && e.detail() != null && !e.detail().contains("name=Mob Defense")
                    && !e.detail().contains("name=User Tasks") && priorityValue(e) >= 65.0f);
            LitematicaRecoveryEventRecorder.Event dodge = epoch.stream()
                    .filter(e -> e.kind().equals("dodge.onTick.return")).findFirst().orElse(null);
            boolean dodged = dodge != null;
            if (defense && user && !competitor && dodged) {
                mobDefenseSelected = true; dodgeTick = true;
                if (defenseSelectionSequence < 0) {
                    defenseSelectionSequence = selected.sequence();
                    defenseSelectionSchedulerTick = selected.schedulerTick();
                    dodgeSequence = dodge.sequence();
                    dodgeSchedulerTick = dodge.schedulerTick();
                }
                if (interruptionTick < 0) interruptionTick = gameTick;
            }
        }
        firstAuthoredPlacement |= authoredPlacementReceipts > 0;
        if (phase == Phase.BUILD && firstAuthoredPlacement && arrowId == null && !arrowSpawnPending)
            spawnStimulus(client.getSingleplayerServer());
        projectileTrackerSeen |= projectileSeenAndApproachesPlayer();
        if (arrowId != null && !arrowRemoved && !arrowRemovalPending && onStop && stoppedIncomplete
                && mobDefenseSelected && dodgeTick && projectileTrackerSeen) {
            if (client.level == null || client.level.getEntity(arrowEntityId) == null) {
                reject("client did not observe the declared arrow before dodge tick"); return;
            }
            removeArrow(client.getSingleplayerServer());
        }
        if (arrowRemoved && phase == Phase.BUILD) { phase = Phase.INTERRUPTED; phaseStartedTick = gameTick; }
        if (phase == Phase.INTERRUPTED && resumed && interruptionSnapshot != null && resumeSnapshot != null
                && hasReplanReceipts(es)) {
            phase = Phase.VERIFY;
            phaseStartedTick = gameTick;
        }
        var completion = terminalCompletion;
        if (completion == null) completion = mod.getUserTaskChain().getLastCompletionSnapshot();
        if (root != null && completion != null && completion.task() == root) {
            if (completion.failure() != null || completion.cancelled()) { reject("root completion failed/cancelled: " + completion); return; }
            boolean recoveryProvenanceObserved = directStop && stoppedReference == root && stoppedIncomplete
                    && stoppedCancelled && directResume && resumeStartObserved && resumedReference == root
                    && resumedCancellationCleared && mobDefenseSelected && dodgeTick && projectileTrackerSeen
                    && hasReplanReceipts(es);
            if (!recoveryProvenanceObserved || (!arrowRemoved && !arrowRemovalPending)) {
                reject("root completed before recovery provenance was recorded; phase=" + phase
                        + ";recoveryProvenance=" + recoveryProvenanceObserved
                        + ";arrowRemoved=" + arrowRemoved + ";removalPending=" + arrowRemovalPending);
                return;
            }
            terminalCompletion = completion;
            if (phase != Phase.VERIFY) return; // Wait for bounded server interruption/resume snapshots.
            if (finalSnapshot == null && outstandingSnapshot == null
                    && (lastFinalPollTick < 0 || gameTick - lastFinalPollTick >= 5)) {
                lastFinalPollTick = gameTick;
                finalPollCount++;
                requestServerSnapshot(client, SnapshotPurpose.FINAL);
            }
            if (finalSnapshot != null) verifyFinal(client, terminalCompletion);
        }
    }

    private boolean hasReplanReceipts(List<LitematicaRecoveryEventRecorder.Event> es) {
        refreshLifecycleSequences(es);
        return resumeStartObserved && directResume && directRecount && directBatchBudget && resumeSequence >= 0
                && recountSequence > resumeSequence && batchBudgetSequence > recountSequence;
    }

    private void refreshLifecycleSequences(List<LitematicaRecoveryEventRecorder.Event> events) {
        if (root == null) return;
        String identity = Integer.toUnsignedString(System.identityHashCode(root), 16);
        for (var event : events) {
            if (!identity.equals(event.subjectIdentity())) continue;
            if (event.kind().equals("build.onStop") && stopSequence < 0) {
                stopSequence = event.sequence(); stopSchedulerTick = event.schedulerTick();
            }
            else if (event.kind().equals("build.onStart") && stopSequence >= 0 && resumeSequence < 0)
                resumeSequence = event.sequence();
            else if (event.kind().equals("build.remainingWorldMaterials.return") && resumeSequence >= 0
                    && event.sequence() > resumeSequence && recountSequence < 0) recountSequence = event.sequence();
            else if (event.kind().equals("build.inventoryMaterialBudget.return") && recountSequence >= 0
                    && event.sequence() > recountSequence && batchBudgetSequence < 0) batchBudgetSequence = event.sequence();
        }
    }

    private void archiveCapture() {
        archivedCapture = LitematicaRecoveryEventRecorder.closeAndSnapshot();
        droppedEventsAtClose = archivedCapture.droppedEvents();
        try {
            Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("runtime-test-results");
            Files.createDirectories(dir);
            StringBuilder out = new StringBuilder("captureId\t").append(archivedCapture.captureId())
                    .append("\ntruncated\t").append(archivedCapture.overflowed())
                    .append("\ndroppedEvents\t").append(archivedCapture.droppedEvents()).append('\n');
            for (var e : archivedCapture.events()) out.append(e.sequence()).append('\t').append(e.schedulerTick())
                    .append('\t').append(e.kind()).append('\t').append(e.subjectType()).append('\t')
                    .append(e.subjectIdentity()).append('\t').append(e.relatedType()).append('\t')
                    .append(e.relatedIdentity()).append('\t').append(String.valueOf(e.detail()).replace('\n',' ')).append('\n');
            Files.writeString(dir.resolve("litematica-recovery-capture-" + runToken + ".tsv"), out);
        } catch (Exception e) {
            log.accept("LITEMATICA_CAPTURE_ARCHIVE_FAIL\t" + e);
            droppedEventsAtClose = -1;
        }
    }

    /** Mixins/caller forward successful server-side natural source breaks and recipe-task trace evidence here. */
    public static void recordConfirmedSourceBreak(ServerLevel level, ServerPlayer player,
                                                               BlockPos pos, BlockState brokenState) {
        LitematicaRecoveryAcceptanceScenario s = ACTIVE;
        CaptureSession session = s == null ? null : s.captureSession;
        if (s == null || session == null || isTerminal(s.phase) || player == null
                || !player.getUUID().equals(session.playerId())
                || !session.dimension().equals(level.dimension().identifier().toString())) return;
        String blockId = BuiltInRegistries.BLOCK.getKey(brokenState.getBlock()).toString();
        String itemId = BuiltInRegistries.ITEM.getKey(brokenState.getBlock().asItem()).toString();
        if (blockId.equals("minecraft:oak_log") || blockId.equals("minecraft:oak_wood")
                || blockId.equals("minecraft:stripped_oak_log") || blockId.equals("minecraft:stripped_oak_wood"))
            s.serverOakLogBreakSeen = true;
        if (blockId.equals("minecraft:stone") || blockId.equals("minecraft:cobblestone"))
            s.serverStoneBreakSeen = true;
        if (blockId.equals("minecraft:coal_ore") || blockId.equals("minecraft:deepslate_coal_ore"))
            s.serverCoalOreBreakSeen = true;
        s.queueServerReceipt(new ServerReceipt(0, session.token(), player.getUUID(), "break",
                pos + "\t" + blockId + "\t" + itemId + "\t" + brokenState));
    }

    /** Runtime-only authoritative health/damage observer; accepted hurt events are never inferred from final health. */
    public static void recordServerHealthDamage(ServerPlayer player, String source, float before,
                                               float after, long serverTick) {
        LitematicaRecoveryAcceptanceScenario s = ACTIVE;
        CaptureSession session = s == null ? null : s.captureSession;
        if (s == null || session == null || isTerminal(s.phase) || player == null
                || !player.getUUID().equals(session.playerId())
                || !session.dimension().equals(player.level().dimension().identifier().toString())) return;
        s.queueServerReceipt(new ServerReceipt(0, session.token(), player.getUUID(), "damage",
                "serverTick=" + serverTick + "\tsource=" + source + "\thealth=" + before + "->" + after));
    }
    /** Call only after an authored BlockItem placement succeeds on the server. */
    public static void onSuccessfulAuthoredBlockPlacement(ServerPlayer player, BlockPos pos, BlockState placedState) {
        LitematicaRecoveryAcceptanceScenario s = ACTIVE;
        CaptureSession session = s == null ? null : s.captureSession;
        if (s == null || session == null || isTerminal(s.phase) || player == null
                || !player.getUUID().equals(session.playerId())
                || !session.dimension().equals(player.level().dimension().identifier().toString())) return;
        BlockPos authoredPos = pos.immutable();
        BlockState expected = null;
        for (CellSnapshot cell : session.oracleCells())
            if (cell.pos().equals(authoredPos) && !cell.state().isAir()) { expected = cell.state(); break; }
        if (expected == null) return;
        BlockState actual = player.level().getBlockState(authoredPos);
        if (!expected.equals(actual)) return;
        boolean first = s.firstAuthoredInsertionClaimed.compareAndSet(false, true);
        Map<String, Integer> stats = readItemStats(player);
        boolean recipesReady = first && s.recipeStatsPassedPure(stats, session.statBaseline())
                && s.serverOakLogBreakSeen && s.serverStoneBreakSeen && s.serverCoalOreBreakSeen;
        s.queueServerReceipt(new ServerReceipt(0, session.token(), player.getUUID(), "placement",
                authoredPos + "\t" + BuiltInRegistries.BLOCK.getKey(actual.getBlock()) + "\tstate=" + actual
                        + "\tserverTick=" + player.level().getServer().getTickCount() + "\tfirst=" + first
                        + "\tfirstRecipeStatsPassed=" + recipesReady + "\tstats=" + statDeltas(stats, session.statBaseline())));
    }
    private static boolean isTerminal(Phase phase) { return phase == Phase.DONE || phase == Phase.FAILED; }
    private synchronized void queueServerReceipt(ServerReceipt receipt) {
        if (phase == Phase.DONE || phase == Phase.FAILED) return;
        if (serverReceiptsSealed) {
            postSealServerReceiptCount++;
            return;
        }
        ServerReceipt ordered = new ServerReceipt(++nextServerReceiptSequence, receipt.token(), receipt.playerId(),
                receipt.kind(), receipt.detail());
        if (!serverReceipts.offer(ordered)) serverReceiptOverflow = true;
    }
    private void drainServerReceipts() {
        ServerReceipt receipt;
        while ((receipt = serverReceipts.poll()) != null) {
            processedServerReceiptSequence = receipt.sequence();
            if (!Objects.equals(runToken, receipt.token()) || !Objects.equals(capturedPlayerId, receipt.playerId())) continue;
            if (receipt.kind().equals("break")) {
                String[] f=receipt.detail().split("\\t",4); if (f.length<4) continue;
                String id=f[1]; boolean wood=id.equals("minecraft:oak_log") || id.equals("minecraft:oak_wood")
                        || id.equals("minecraft:stripped_oak_log") || id.equals("minecraft:stripped_oak_wood");
                boolean stone=id.equals("minecraft:stone") || id.equals("minecraft:cobblestone");
                boolean coal=id.equals("minecraft:coal_ore") || id.equals("minecraft:deepslate_coal_ore");
                if (!wood && !stone && !coal) continue;
                breakReceipts++; sourceWood|=wood; sourceStone|=stone; sourceCoalOre|=coal;
                sourceBreakPositions.add(f[0]+"="+id);
                log.accept("LITEMATICA_NATURAL_BREAK\tpos="+f[0]+"\tblock="+id+"\titem="+f[2]+"\tstate="+f[3]);
            } else if (receipt.kind().equals("placement")) {
                authoredPlacementReceipts++;
                if (receipt.detail().contains("\tfirst=true\tfirstRecipeStatsPassed=true")) materialsReadyAtFirstPlacement=true;
                log.accept("LITEMATICA_AUTHORED_PLACEMENT\t"+receipt.detail());
            } else if (receipt.kind().equals("damage")) {
                damagingHitReceipts++;
                damageEvents.add(receipt.detail());
                log.accept("LITEMATICA_SERVER_DAMAGE\t" + receipt.detail());
            } else if (receipt.kind().equals("menu")) {
                menuOpens++; prohibitedContainer|=receipt.detail().contains("prohibited=true");
                log.accept("LITEMATICA_MENU_OPEN\t"+receipt.detail());
            }
        }
    }
    /** Wire from runtime-test lifecycle mixins; identity is checked with Java reference equality. */
    public static synchronized void onBuildStopped(BuildSchematicTask task) {
        var s = ACTIVE;
        if (s != null && s.captureOwned && !isTerminal(s.phase) && s.root == task
                && s.mod.getUserTaskChain().getCurrentTask() == task) {
            s.stoppedReference = task; s.directStop = true; s.stoppedIncomplete = !task.isFinished(s.mod);
            s.stoppedCancelled = task.wasCancelled();
            Minecraft client = Minecraft.getInstance();
            if (client.player != null) s.beforeSnapshot = captureInventory(client.player);
            if (!s.interruptionSnapshotRequested && s.outstandingSnapshot == null) {
                s.interruptionSnapshotRequested = true;
                s.interruptionObservationTick = s.currentGameTick;
                s.requestServerSnapshot(client, SnapshotPurpose.INTERRUPTION);
            }
        }
    }
    /** Arms provenance before BuildSchematicTask.onStart performs its synchronous prepareBatch calls. */
    public static synchronized void onBuildStarting(BuildSchematicTask task) {
        var s = ACTIVE;
        if (s != null && s.captureOwned && !isTerminal(s.phase) && s.root == task && s.directStop
                && s.mod.getUserTaskChain().getCurrentTask() == task) {
            s.resumedReference = task;
            s.resumeStartObserved = true;
        }
    }
    /** Wire from runtime-test lifecycle mixins. */
    public static synchronized void onBuildStarted(BuildSchematicTask task) {
        var s = ACTIVE;
        if (s != null && s.captureOwned && !isTerminal(s.phase) && s.root == task && s.directStop
                && s.resumeStartObserved
                && s.mod.getUserTaskChain().getCurrentTask() == task) {
            s.resumedReference = task; s.directResume = true;
            s.resumedCancellationCleared = !task.wasCancelled();
            Minecraft client = Minecraft.getInstance();
            if (client.player != null) s.afterResumeSnapshot = captureInventory(client.player);
            if (!s.resumeSnapshotRequested && s.outstandingSnapshot == null) {
                s.resumeSnapshotRequested = true;
                s.resumeObservationTick = s.currentGameTick;
                s.requestServerSnapshot(client, SnapshotPurpose.RESUME);
            }
        }
    }
    /** Runtime observer bridge: pass the actual BuildSchematicTask receiver from getRemainingMaterials RETURN. */
    public static synchronized void onLiveMaterialRecount(BuildSchematicTask task) {
        var s=ACTIVE; if (s != null && s.captureOwned && !isTerminal(s.phase) && s.root == task
                && s.resumeStartObserved) s.directRecount=true;
    }
    /** Runtime observer bridge: pass the actual BuildSchematicTask receiver from fitCurrentInventory RETURN. */
    public static synchronized void onInventoryAwareBatchBudget(BuildSchematicTask task) {
        var s=ACTIVE; if (s != null && s.captureOwned && !isTerminal(s.phase) && s.root == task
                && s.resumeStartObserved) s.directBatchBudget=true;
    }
    private static volatile LitematicaRecoveryAcceptanceScenario ACTIVE;
    /** Call from the server menu-open mixin; any chest/container menu is prohibited in this run. */
    public static void recordServerMenuOpen(ServerPlayer player, AbstractContainerMenu menu) {
        var s = ACTIVE; if (s == null || player == null || menu == null) return;
        CaptureSession session = s.captureSession;
        if (session == null || isTerminal(s.phase) || !player.getUUID().equals(session.playerId())) return;
        boolean allowed = menu instanceof InventoryMenu || menu instanceof CraftingMenu || menu instanceof FurnaceMenu;
        s.queueServerReceipt(new ServerReceipt(0, session.token(), player.getUUID(), "menu",
                "class="+menu.getClass().getName()+"\tallowed="+allowed+(allowed?"":"\tprohibited=true")));
    }
    public void observeTaskTrace(List<String> entries) {
        if (phase != Phase.BUILD && phase != Phase.INTERRUPTED && phase != Phase.VERIFY) return;
        if (root == null || mod.getUserTaskChain().getCurrentTask() != root) return;
        for (String e : entries) {
            if (!e.contains("CraftGenericWithRecipeBooksTask")) continue;
            String lower=e.toLowerCase().replace('_',' ');
            plankCraftSeen |= lower.contains("oak planks");
            chestCraftSeen |= lower.contains("chest");
            torchCraftSeen |= lower.contains("torch");
        }
    }

    private void spawnStimulus(MinecraftServer server) {
        if (!captureOwned) {
            if (root == null || !firstAuthoredPlacement) { reject("cannot open recovery capture before the live build root and authored insertion"); return; }
            try { LitematicaRecoveryEventRecorder.openCapture("natural-litematica-recovery-" + runToken); }
            catch (RuntimeException e) { reject("could not open stimulus-scoped recovery capture: " + e); return; }
            captureOwned = true;
            log.accept("LITEMATICA_RECOVERY_CAPTURE_OPEN\troot="
                    + Integer.toUnsignedString(System.identityHashCode(root), 16) + "\tclientTick=" + currentGameTick);
        }
        boolean dodging = mod.getModSettings().isDodgeProjectiles();
        boolean tryingToEat = mod.getFoodChain().isTryingToEat();
        boolean criticalFood = mod.getFoodChain().needsToEatCritical(mod);
        log.accept("LITEMATICA_ARROW_PRECONDITIONS\tdodging=" + dodging + "\ttryingToEat=" + tryingToEat + "\tcriticalFood=" + criticalFood);
        if (!dodging || tryingToEat || criticalFood) { reject("projectile preconditions not satisfied"); return; }
        if (root == null || phase != Phase.BUILD || captureSession == null) {
            reject("projectile stimulus requires the captured build root in BUILD phase"); return;
        }
        ArrowOperation operation = new ArrowOperation(++nextArrowOperationId, runToken, capturedPlayerId,
                root, null, -1, phase);
        arrowSpawnOperationId = operation.id();
        arrowSpawnPending = true;
        arrowSpawnRequestedTick = currentGameTick;
        server.execute(() -> {
            if (!serverCanSpawnFor(operation, server)) {
                Minecraft.getInstance().execute(() -> failCurrentArrowOperation(operation,
                        "spawn request lost its run token, player, root phase, or capture"));
                return;
            }
            try {
                ServerLevel level = server.overworld();
                ServerPlayer p = server.getPlayerList().getPlayer(operation.playerId());
                if (p == null) throw new IllegalStateException("server player missing");
                // Zero vertical launch speed makes the production predictor and physical
                // ballistic arc agree despite its signed vertical-velocity convention.
                // Try each horizontal approach and use the first whose ballistic corridor is clear,
                // so trees or slopes on one side of a natural build site do not fail the run.
                Vec3 start = null, velocity = null;
                IllegalStateException obstruction = null;
                for (int[] dir : new int[][]{{0, 1}, {0, -1}, {1, 0}, {-1, 0}}) {
                    Vec3 candidateStart = new Vec3(p.getX() + 9 * dir[0], p.getY() + 5.625, p.getZ() + 9 * dir[1]);
                    Vec3 candidateVelocity = new Vec3(-0.6 * dir[0], 0, -0.6 * dir[1]);
                    try {
                        verifyArrowTrajectory(level, p, candidateStart, candidateVelocity);
                        start = candidateStart;
                        velocity = candidateVelocity;
                        break;
                    } catch (IllegalStateException e) {
                        obstruction = obstruction == null ? e
                                : new IllegalStateException(obstruction.getMessage() + "; " + e.getMessage());
                    }
                }
                if (start == null) throw obstruction;
                float health = p.getHealth();
                Arrow arrow = new Arrow(level, start.x, start.y, start.z, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
                arrow.setDeltaMovement(velocity);
                arrow.setBaseDamage(0.0);
                arrow.setCritArrow(false);
                arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
                if (!level.addFreshEntity(arrow)) throw new IllegalStateException("server rejected declared arrow entity");
                UUID uuid=arrow.getUUID(); int entityId=arrow.getId(); Vec3 position=arrow.position();
                Vec3 actualVelocity=arrow.getDeltaMovement(); long spawnTick=server.getTickCount();
                ArrowOperation spawned = new ArrowOperation(operation.id(), operation.token(), operation.playerId(),
                        operation.root(), uuid, entityId, operation.startedIn());
                double closestTime=horizontalTimeToPlayer(p, start, velocity);
                Minecraft.getInstance().execute(() -> {
                    if (!clientCanAcceptSpawn(spawned)) {
                        server.execute(() -> discardArrowIfSame(server, spawned));
                        failCurrentArrowOperation(spawned, "spawn callback arrived outside its captured build run");
                        return;
                    }
                    healthBeforeStimulus=health; arrowId=uuid; arrowEntityId=entityId; arrowSpawnPosition=position;
                    arrowSpawnTick=currentGameTick; arrowSpawnPending=false;
                    log.accept("LITEMATICA_ARROW\tuuid=" + uuid + "\tentityId=" + entityId + "\tpos=" + position
                            + "\tvelocity=" + actualVelocity + "\tserverSpawnTick=" + spawnTick
                            + "\tgravity=" + ProjectileHelper.ARROW_GRAVITY_ACCEL
                            + "\tpickup=DISALLOWED\tbaseDamage=0\tcorridor=clear-ballistic\tclosestTime=" + closestTime);
                });
            } catch (Throwable e) {
                Minecraft.getInstance().execute(() -> failCurrentArrowOperation(operation,
                        "projectile preflight/spawn failed: " + e));
            }
        });
    }

    private void removeArrow(MinecraftServer server) {
        if (arrowRemovalPending || arrowRemoved) return;
        if (root == null || arrowId == null || arrowEntityId < 0
                || (phase != Phase.BUILD && phase != Phase.INTERRUPTED)) {
            reject("cannot remove recovery projectile outside its captured build interruption"); return;
        }
        ArrowOperation operation = new ArrowOperation(++nextArrowOperationId, runToken, capturedPlayerId,
                root, arrowId, arrowEntityId, phase);
        arrowRemovalOperationId = operation.id();
        arrowRemovalPending = true;
        arrowRemovalRequestedTick = currentGameTick;
        server.execute(() -> {
            ServerLevel level = server.overworld();
            var entity = level.getEntity(operation.entityId());
            ServerPlayer p = server.getPlayerList().getPlayer(operation.playerId());
            boolean sameEntity = entity instanceof Arrow && operation.arrowId().equals(entity.getUUID());
            if (!serverCanRemoveFor(operation, server) || !sameEntity || p == null || entity.distanceToSqr(p) < 2.25) {
                if (sameEntity && isTerminal(phase)) entity.discard();
                Minecraft.getInstance().execute(() -> failCurrentArrowOperation(operation,
                        "arrow missing, stale, or at player contact range before removal"));
                return;
            }
            long removalServerTick = server.getTickCount();
            Vec3 removalPosition = entity.position();
            entity.discard();
            boolean serverConfirmedRemoved = entity.isRemoved();
            Minecraft.getInstance().execute(() -> {
                if (!clientCanAcceptRemoval(operation)) {
                    failCurrentArrowOperation(operation, "removal callback arrived outside its captured build run");
                    return;
                }
                arrowRemoved = serverConfirmedRemoved;
                arrowRemovalPending = false;
                if (!arrowRemoved) { reject("server did not confirm arrow removal"); return; }
                Task currentTask = mod.getUserTaskChain().getCurrentTask();
                var completion = terminalCompletion;
                boolean rootCompletedSuccessfully = completion != null && completion.task() == root
                        && completion.failure() == null && !completion.cancelled();
                log.accept("LITEMATICA_INTERRUPTION\tstartTick=" + interruptionTick + "\tresumeTick=" + resumeTick
                        + "\tcurrentTaskIsRecoveryRoot=" + (currentTask == root)
                        + "\tcurrentTaskIsNull=" + (currentTask == null)
                        + "\trootCompletedSuccessfully=" + rootCompletedSuccessfully
                        + "\tremainingAtInterruption=" + remainingAtInterruption
                        + "\tarrowLifetimeTicks=" + Math.max(0, interruptionTick-arrowSpawnTick)
                        + "\tserverRemovalTick=" + removalServerTick + "\tserverRemovalPosition=" + removalPosition
                        + "\tbeforeInventory=" + beforeSnapshot + "\tafterResumeInventory=" + afterResumeSnapshot);
            });
        });
    }

    private boolean serverCanSpawnFor(ArrowOperation operation, MinecraftServer server) {
        CaptureSession session = captureSession;
        return this == ACTIVE && operation.id() == arrowSpawnOperationId && operation.startedIn() == Phase.BUILD
                && operation.arrowId() == null && operation.entityId() == -1
                && session != null && captureOwned && phase == Phase.BUILD
                && Objects.equals(runToken, operation.token()) && Objects.equals(capturedPlayerId, operation.playerId())
                && session.token().equals(operation.token()) && session.playerId().equals(operation.playerId())
                && session.dimension().equals(server.overworld().dimension().identifier().toString());
    }

    private boolean serverCanRemoveFor(ArrowOperation operation, MinecraftServer server) {
        CaptureSession session = captureSession;
        return this == ACTIVE && operation.id() == arrowRemovalOperationId
                && operation.arrowId() != null && operation.entityId() >= 0
                && (operation.startedIn() == Phase.BUILD || operation.startedIn() == Phase.INTERRUPTED)
                && session != null && captureOwned && !isTerminal(phase)
                && (phase == Phase.BUILD || phase == Phase.INTERRUPTED)
                && Objects.equals(runToken, operation.token()) && Objects.equals(capturedPlayerId, operation.playerId())
                && session.token().equals(operation.token()) && session.playerId().equals(operation.playerId())
                && session.dimension().equals(server.overworld().dimension().identifier().toString());
    }

    private boolean clientCanAcceptSpawn(ArrowOperation operation) {
        return this == ACTIVE && operation.id() == arrowSpawnOperationId && operation.root() == root
                && operation.startedIn() == Phase.BUILD && Objects.equals(runToken, operation.token())
                && Objects.equals(capturedPlayerId, operation.playerId()) && phase == Phase.BUILD
                && captureOwned && captureSession != null
                && mod.getUserTaskChain().getCurrentTask() == operation.root();
    }

    private boolean clientCanAcceptRemoval(ArrowOperation operation) {
        var completed = terminalCompletion;
        Task currentTask = mod.getUserTaskChain().getCurrentTask();
        boolean sameRootCompletedSuccessfully = completed != null && completed.task() == operation.root()
                && completed.failure() == null && !completed.cancelled() && currentTask == null;
        return this == ACTIVE && operation.id() == arrowRemovalOperationId && operation.root() == root
                && operation.arrowId().equals(arrowId) && operation.entityId() == arrowEntityId
                && Objects.equals(runToken, operation.token()) && Objects.equals(capturedPlayerId, operation.playerId())
                && !isTerminal(phase) && (phase == Phase.BUILD || phase == Phase.INTERRUPTED)
                && captureOwned && captureSession != null
                && (currentTask == operation.root() || sameRootCompletedSuccessfully);
    }

    private void failCurrentArrowOperation(ArrowOperation operation, String reason) {
        if (this != ACTIVE || !Objects.equals(runToken, operation.token()) || isTerminal(phase)) return;
        if (operation.id() == arrowSpawnOperationId || operation.id() == arrowRemovalOperationId) reject(reason);
    }

    private static void discardArrowIfSame(MinecraftServer server, ArrowOperation operation) {
        ServerLevel level = server.overworld();
        var entity = level.getEntity(operation.entityId());
        if (entity instanceof Arrow && operation.arrowId().equals(entity.getUUID())) entity.discard();
    }

    private void discardKnownArrowAfterFailure() {
        UUID expectedArrow = arrowId;
        int expectedEntityId = arrowEntityId;
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null || expectedArrow == null || expectedEntityId < 0) return;
        ArrowOperation cleanup = new ArrowOperation(++nextArrowOperationId, runToken, capturedPlayerId,
                root, expectedArrow, expectedEntityId, phase);
        try {
            server.execute(() -> discardArrowIfSame(server, cleanup));
        } catch (Throwable error) {
            log.accept("LITEMATICA_ARROW_CLEANUP_QUEUE_FAIL\t" + error);
        }
    }

    private void verifyFinal(Minecraft client, adris.altoclef.chains.UserTaskChain.CompletionSnapshot completion) {
        ServerSnapshot snapshot = finalSnapshot;
        if (snapshot == null) return;
        if (completion == null || completion.task() != root || completion.failure() != null || completion.cancelled()) {
            reject("final snapshot arrived without a successful completion for the same root: " + completion);
            return;
        }
        if (processedServerReceiptSequence < snapshot.receiptWatermark()) return;
        if (serverReceiptOverflow) { reject("server receipt queue overflowed before final snapshot watermark"); return; }
        if (client.player == null || client.level == null || initialServerState == null || interruptionSnapshot == null
                || resumeSnapshot == null || captureSession == null) {
            reject("authoritative snapshot set incomplete at final verification"); return;
        }
        if (!snapshot.world().equals(initialServerState.snapshot().world())
                || !snapshot.player().dimension().equals(captureSession.dimension())
                || !matchesOracle(snapshot.targetCells(), expectedOracleCells(origin))
                || !sameCells(snapshot.supportCells(), initialServerState.snapshot().supportCells())
                || !snapshot.chest().emptyCanonical()
                || !snapshot.player().gameMode().equals(GameType.SURVIVAL)
                || !snapshot.player().alive() || snapshot.player().health() <= 0 || snapshot.player().food() <= 0
                || !snapshot.inventory().cleanPlayerUi()) {
            reject("authoritative final server state violates world/chest/player/inventory oracle: " + describeSnapshot(snapshot));
            return;
        }
        InventorySnapshot clientInventory = captureInventory(client.player);
        List<CellSnapshot> clientCells = captureCells(client.level, origin);
        List<CellSnapshot> clientSupportCells = captureSupportCells(client.level, origin);
        ChestSnapshot clientChest = captureChest(client.level, origin);
        PlayerSnapshot clientPlayer = capturePlayer(client.player,
                client.gameMode == null ? null : client.gameMode.getPlayerMode(),
                client.level.dimension().identifier().toString());
        if (!snapshot.inventory().matches(clientInventory) || !sameCells(snapshot.targetCells(), clientCells)
                || !sameCells(snapshot.supportCells(), clientSupportCells)
                || !snapshot.chest().matches(clientChest) || !clientPlayerMatches(snapshot.player(), clientPlayer)) {
            if (finalPollCount >= 40) {
                reject("server/client final snapshot did not synchronize within 200 client ticks: server="
                        + describeSnapshot(snapshot) + ";clientInventory=" + clientInventory
                        + ";clientCells=" + formatCells(clientCells));
                return;
            }
            finalSnapshot = null;
            if (outstandingSnapshot == null && currentGameTick - lastFinalPollTick >= 5) {
                lastFinalPollTick = currentGameTick;
                finalPollCount++;
                requestServerSnapshot(client, SnapshotPurpose.FINAL);
            }
            return;
        }

        if (captureOwned && archivedCapture == null) archiveCapture();
        List<LitematicaRecoveryEventRecorder.Event> capturedEvents = archivedCapture == null
                ? List.of() : archivedCapture.events();
        refreshLifecycleSequences(capturedEvents);
        String rootIdentity = root == null ? "" : Integer.toUnsignedString(System.identityHashCode(root), 16);
        long capturedStops = capturedEvents.stream().filter(e -> e.kind().equals("build.onStop")
                && rootIdentity.equals(e.subjectIdentity())).count();
        long capturedInterruptedStops = capturedEvents.stream().filter(e -> e.kind().equals("build.onStop")
                && rootIdentity.equals(e.subjectIdentity()) && "currentUserTask=true".equals(e.detail())).count();
        long capturedCleanStops = capturedEvents.stream().filter(e -> e.kind().equals("build.onStop")
                && rootIdentity.equals(e.subjectIdentity()) && "currentUserTask=false".equals(e.detail())).count();
        long capturedStarts = capturedEvents.stream().filter(e -> e.kind().equals("build.onStart")
                && rootIdentity.equals(e.subjectIdentity())).count();

        Map<String, Integer> baseline = captureSession.statBaseline();
        statOutput = statDeltas(snapshot.stats(), baseline);
        boolean statsPassed = recipeStatsPassed(snapshot.stats(), baseline);
        sourceFuel = serverCoalOreBreakSeen && statDelta(snapshot.stats(), baseline, Items.COAL, Stats.ITEM_PICKED_UP) > 0;
        serverStacks = snapshot.inventory().slots().stream().map(StackSnapshot::toString).toList();
        if (!sourceWood || !sourceStone || !sourceFuel || !statsPassed
                || !plankCraftSeen || !chestCraftSeen || !torchCraftSeen || !materialsReadyAtFirstPlacement
                || prohibitedContainer || breakReceipts == 0 || sourceBreakPositions.isEmpty()
                || !directStop || !stoppedIncomplete || !stoppedCancelled || !directResume || !resumed || !resumedCancellationCleared
                || interruptionTick < 0 || resumeTick <= interruptionTick || remainingAtInterruption <= 0
                || beforeSnapshot == null || afterResumeSnapshot == null || !beforeSnapshot.matches(afterResumeSnapshot)
                || !interruptionSnapshot.inventory().matches(beforeSnapshot)
                || !resumeSnapshot.inventory().matches(afterResumeSnapshot)
                || damagingHitReceipts != 0
                || capturedStops != 2 || capturedInterruptedStops != 1 || capturedCleanStops != 1 || capturedStarts != 1
                || authoredPlacementReceipts < 5 || !mobDefenseSelected || !dodgeTick || !projectileTrackerSeen || !arrowRemoved
                || defenseSelectionSequence < 0 || defenseSelectionSchedulerTick < 0
                || stopSchedulerTick != defenseSelectionSchedulerTick || dodgeSchedulerTick != defenseSelectionSchedulerTick
                || stopSequence < 0 || dodgeSequence <= stopSequence || defenseSelectionSequence <= dodgeSequence
                || resumeSequence <= stopSequence || recountSequence <= resumeSequence || batchBudgetSequence <= recountSequence) {
            reject("natural source/recipe/recovery provenance gate failed wood=" + sourceWood + " stone=" + sourceStone
                    + " fuel=" + sourceFuel + " crafts=" + statOutput + " breaks=" + breakReceipts
                    + " remainingAtStop=" + remainingAtInterruption + " container=" + prohibitedContainer); return;
        }
        if (snapshot.player().health() != healthBeforeStimulus || !damageEvents.isEmpty()
                || snapshot.player().maxHealth() != initialServerState.snapshot().player().maxHealth()
                || eventsDropped() != 0 || droppedEventsAtClose != 0 || archivedCapture == null || archivedCapture.overflowed()
                || serverReceiptOverflow || processedServerReceiptSequence < snapshot.receiptWatermark()
                || postSealServerReceiptCount() != 0) {
            reject("final health/capture/receipt verification failed"); return;
        }
        if (!commitSuccessfulVerdict()) {
            reject("server receipt arrived after the final snapshot boundary"); return;
        }
        authoritativeSnapshotsVerified = true;
        log.accept("SUMMARY\tPASS\tnatural rotated pinned Litematica @build placement interrupted once by MobDefense/DodgeProjectilesTask and same root resumed/replanned; sourceBreaks="
                + sourceBreakPositions + ";pickup/craft deltas=" + statOutput + ";allAuthoredRecipesReadyBeforeFirstPlacement="
                + materialsReadyAtFirstPlacement + ";menuOpens=" + menuOpens
                + ";durationTicks=" + (resumeTick - interruptionTick) + ";interruptionServerTick=" + interruptionSnapshot.serverTick()
                + ";resumeServerTick=" + resumeSnapshot.serverTick() + ";finalServerTick=" + snapshot.serverTick()
                + ";serverReceiptWatermark=" + snapshot.receiptWatermark() + ";serverReceiptBoundary=sealed"
                + ";serverInventory=" + serverStacks);
        passed.run();
    }

    private static boolean matchesOracle(List<CellSnapshot> actual, List<CellSnapshot> expected) {
        return sameCells(actual, expected);
    }
    private boolean cleanStart(net.minecraft.client.player.LocalPlayer p) {
        if (Minecraft.getInstance().gameMode == null || Minecraft.getInstance().gameMode.getPlayerMode() != GameType.SURVIVAL || !(p.containerMenu instanceof InventoryMenu)
                || !p.containerMenu.getCarried().isEmpty() || !cleanCraftingSlots(p.containerMenu)) return false;
        for (int i=0;i<p.getInventory().getContainerSize();i++) if (!p.getInventory().getItem(i).isEmpty()) return false;
        return true;
    }

    private void requestServerSnapshot(Minecraft client, SnapshotPurpose purpose) {
        if (outstandingSnapshot != null) {
            reject("snapshot request " + purpose + " overlaps " + outstandingSnapshot.purpose()
                    + " request " + outstandingSnapshot.id());
            return;
        }
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null || client.player == null || origin == null || runToken == null) {
            reject("cannot request " + purpose + " authoritative snapshot: server/player/origin/token missing");
            return;
        }
        UUID playerId = capturedPlayerId;
        String token = runToken;
        BlockPos snapshotOrigin = origin.immutable();
        SnapshotRequest request = new SnapshotRequest(++nextSnapshotRequestId, purpose, phase, currentGameTick);
        outstandingSnapshot = request;
        server.execute(() -> {
            ServerSnapshot snapshot;
            try {
                snapshot = createServerSnapshot(server, token, playerId, request, snapshotOrigin);
            } catch (Throwable error) {
                snapshot = failedServerSnapshot(token, playerId, request, error);
            }
            ServerSnapshot publishedSnapshot = snapshot;
            client.execute(() -> acceptServerSnapshot(publishedSnapshot, request));
        });
        log.accept("LITEMATICA_SERVER_SNAPSHOT_REQUEST\tpurpose=" + purpose + "\trequest=" + request.id()
                + "\tphase=" + request.requestedPhase() + "\tclientTick=" + request.requestedClientTick());
    }

    private ServerSnapshot createServerSnapshot(MinecraftServer server, String token, UUID playerId,
                                                SnapshotRequest request, BlockPos snapshotOrigin) {
        long receiptWatermark = request.purpose() == SnapshotPurpose.FINAL
                ? sealServerReceiptsAndGetWatermark() : receiptWatermark();
        ServerLevel level = server.overworld();
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null || player.level() != level) {
            return new ServerSnapshot(token, playerId, request.id(), request.purpose(), request.requestedPhase(),
                    server.getTickCount(), "captured player is absent from the Overworld", null, null, null,
                    List.of(), List.of(), new ChestSnapshot(false, null, false, "", "", 0, false, List.of()),
                    Map.of(), receiptWatermark, false);
        }
        List<CellSnapshot> cells = captureCells(level, snapshotOrigin);
        List<CellSnapshot> supports = captureSupportCells(level, snapshotOrigin);
        InventorySnapshot inventory = captureInventory(player);
        ChestSnapshot chest = captureChest(level, snapshotOrigin);
        Map<String, Integer> stats = readItemStats(player);
        WorldSnapshot world = new WorldSnapshot(server.getWorldGenSettings().options().seed(),
                server.getWorldData().getLevelName(), String.valueOf(server.getWorldGenSettings().options()),
                String.valueOf(server.getWorldData().getDifficulty()), bonusChestEnabledValue(server),
                level.dimension().identifier().toString(), server.getWorldData().isFlatWorld(),
                server.getWorldData().isDebugWorld());
        PlayerSnapshot playerState = new PlayerSnapshot(player.gameMode.getGameModeForPlayer(), player.isAlive(),
                player.getHealth(), player.getMaxHealth(), player.getFoodData().getFoodLevel(),
                player.getFoodData().getSaturationLevel(), player.level().dimension().identifier().toString(),
                player.blockPosition());
        return new ServerSnapshot(token, playerId, request.id(), request.purpose(), request.requestedPhase(),
                server.getTickCount(), null, world, playerState, inventory, cells, supports, chest, stats,
                receiptWatermark, targetVolumeValid(level, snapshotOrigin));
    }

    private ServerSnapshot failedServerSnapshot(String token, UUID playerId, SnapshotRequest request,
                                                Throwable error) {
        long receiptWatermark = request.purpose() == SnapshotPurpose.FINAL
                ? sealServerReceiptsAndGetWatermark() : receiptWatermark();
        return new ServerSnapshot(token, playerId, request.id(), request.purpose(), request.requestedPhase(),
                -1, "server snapshot failed: " + error, null, null, null, List.of(), List.of(),
                new ChestSnapshot(false, null, false, "", "", 0, false, List.of()), Map.of(), receiptWatermark, false);
    }

    private static boolean bonusChestEnabledValue(MinecraftServer server) {
        try { return bonusChestEnabled(server); }
        catch (Exception e) { throw new IllegalStateException("could not read bonus chest option", e); }
    }

    private void acceptServerSnapshot(ServerSnapshot snapshot, SnapshotRequest request) {
        if (phase == Phase.DONE || phase == Phase.FAILED) return;
        SnapshotRequest outstanding = outstandingSnapshot;
        if (outstanding == null || outstanding.id() != request.id() || snapshot.requestId() != request.id()
                || snapshot.purpose() != request.purpose() || snapshot.requestedPhase() != request.requestedPhase()
                || !Objects.equals(snapshot.token(), runToken)
                || !Objects.equals(snapshot.playerId(), capturedPlayerId)) {
            reject("stale or mismatched server snapshot receipt: purpose=" + snapshot.purpose()
                    + ",request=" + snapshot.requestId() + ",current=" + (outstanding == null ? "none" : outstanding.id()));
            return;
        }
        if (!snapshotPhaseStillValid(snapshot.purpose())) {
            reject("server snapshot arrived in an unexpected phase: purpose=" + snapshot.purpose() + ",phase=" + phase);
            return;
        }
        outstandingSnapshot = null;
        deliveredSnapshot = snapshot;
    }

    private boolean snapshotPhaseStillValid(SnapshotPurpose purpose) {
        return switch (purpose) {
            case INITIAL, PRECOMMAND -> phase == Phase.PREFLIGHT;
            case INTERRUPTION -> phase == Phase.BUILD || phase == Phase.INTERRUPTED;
            case RESUME -> phase == Phase.BUILD || phase == Phase.INTERRUPTED || phase == Phase.VERIFY;
            case FINAL -> phase == Phase.VERIFY;
        };
    }

    private void processDeliveredSnapshot(Minecraft client, long gameTick) {
        ServerSnapshot snapshot = deliveredSnapshot;
        if (snapshot == null) return;
        deliveredSnapshot = null;
        if (snapshot.error() != null || snapshot.world() == null || snapshot.player() == null || snapshot.inventory() == null) {
            reject("authoritative " + snapshot.purpose() + " snapshot invalid: " + snapshot.error());
            return;
        }
        switch (snapshot.purpose()) {
            case INITIAL -> acceptInitialSnapshot(client, snapshot);
            case PRECOMMAND -> precommandSnapshot = snapshot;
            case INTERRUPTION -> {
                if (beforeSnapshot == null || client.player == null || client.level == null
                        || !beforeSnapshot.matches(snapshot.inventory())
                        || !sameCells(snapshot.targetCells(), captureCells(client.level, origin))) {
                    reject("interruption server/client snapshot mismatch: " + describeSnapshot(snapshot));
                    return;
                }
                interruptionSnapshot = snapshot;
                remainingAtInterruption = remainingAuthoredCells(snapshot.targetCells());
                log.accept("LITEMATICA_INTERRUPTION_SNAPSHOT\trequest=" + snapshot.requestId()
                        + "\tserverTick=" + snapshot.serverTick() + "\tclientObservationTick=" + interruptionObservationTick
                        + "\tremainingAuthoredCells=" + remainingAtInterruption
                        + "\tinventory=" + snapshot.inventory());
            }
            case RESUME -> {
                if (afterResumeSnapshot == null || !afterResumeSnapshot.matches(snapshot.inventory())) {
                    reject("resume server/client inventory snapshot mismatch: " + describeSnapshot(snapshot));
                    return;
                }
                resumeSnapshot = snapshot;
                log.accept("LITEMATICA_RESUME_SNAPSHOT\trequest=" + snapshot.requestId()
                        + "\tserverTick=" + snapshot.serverTick() + "\tclientObservationTick=" + resumeObservationTick
                        + "\tinventory=" + snapshot.inventory());
            }
            case FINAL -> finalSnapshot = snapshot;
        }
    }

    private void acceptInitialSnapshot(Minecraft client, ServerSnapshot snapshot) {
        if (client.player == null || client.level == null) { reject("client disconnected before initial snapshot publication"); return; }
        InventorySnapshot clientInventory = captureInventory(client.player);
        List<CellSnapshot> clientCells = captureCells(client.level, origin);
        List<CellSnapshot> clientSupportCells = captureSupportCells(client.level, origin);
        ChestSnapshot clientChest = captureChest(client.level, origin);
        PlayerSnapshot clientPlayer = capturePlayer(client.player, client.gameMode == null ? null : client.gameMode.getPlayerMode(),
                client.level.dimension().identifier().toString());
        if (!snapshot.targetVolumeSuitable() || !snapshot.world().difficulty().equals("NORMAL")
                || snapshot.world().bonusChest() || snapshot.world().flatWorld() || snapshot.world().debugWorld()
                || snapshot.player().gameMode() != GameType.SURVIVAL || !snapshot.player().alive()
                || snapshot.player().health() != snapshot.player().maxHealth() || snapshot.player().food() != 20
                || !snapshot.inventory().cleanPlayerUi() || !inventoryEmpty(snapshot.inventory())
                || !snapshot.inventory().matches(clientInventory) || !sameCells(snapshot.targetCells(), clientCells)
                || !sameCells(snapshot.supportCells(), clientSupportCells)
                || !snapshot.chest().matches(clientChest) || !clientPlayerMatches(snapshot.player(), clientPlayer)
                || !targetVolumeValid(client.level, origin)) {
            reject("initial server/client snapshot mismatch or unsuitable world: server=" + describeSnapshot(snapshot)
                    + "; clientInventory=" + clientInventory + "; clientCells=" + formatCells(clientCells));
            return;
        }
        initialServerState = new InitialState(snapshot, clientInventory, clientCells, clientSupportCells,
                clientChest, clientPlayer);
        captureSession = new CaptureSession(runToken, capturedPlayerId, snapshot.player().dimension(), origin,
                expectedOracleCells(origin), snapshot.stats());
        log.accept("LITEMATICA_INITIAL_SNAPSHOT\trequest=" + snapshot.requestId() + "\tserverTick="
                + snapshot.serverTick() + "\tseed=" + snapshot.world().seed() + "\tsave=" + snapshot.world().saveName()
                + "\tdifficulty=" + snapshot.world().difficulty() + "\tbonusChest=" + snapshot.world().bonusChest()
                + "\tserverInventory=" + snapshot.inventory() + "\tserverCells=" + formatCells(snapshot.targetCells())
                + "\tserverSupports=" + formatCells(snapshot.supportCells()));
    }

    private static boolean inventoryEmpty(InventorySnapshot inventory) {
        for (StackSnapshot stack : inventory.slots()) if (!stack.isEmpty()) return false;
        return true;
    }

    private static boolean clientPlayerMatches(PlayerSnapshot server, PlayerSnapshot client) {
        return server != null && client != null && server.gameMode() == client.gameMode()
                && server.alive() == client.alive() && server.health() == client.health()
                && server.maxHealth() == client.maxHealth() && server.food() == client.food()
                && server.saturation() == client.saturation() && server.dimension().equals(client.dimension());
    }

    private static String describeSnapshot(ServerSnapshot snapshot) {
        return "purpose=" + snapshot.purpose() + ",request=" + snapshot.requestId() + ",serverTick="
                + snapshot.serverTick() + ",world=" + snapshot.world() + ",player=" + snapshot.player()
                + ",inventory=" + snapshot.inventory() + ",cells=" + formatCells(snapshot.targetCells())
                + ",supports=" + formatCells(snapshot.supportCells());
    }

    private static InventorySnapshot captureInventory(net.minecraft.world.entity.player.Player player) {
        List<StackSnapshot> inventory = new ArrayList<>();
        for (int i = 0; i < player.getInventory().getContainerSize(); i++)
            inventory.add(new StackSnapshot(player.getInventory().getItem(i)));
        AbstractContainerMenu menu = player.containerMenu;
        List<StackSnapshot> menuContents = new ArrayList<>();
        for (int i = 0; i < menu.slots.size(); i++) menuContents.add(new StackSnapshot(menu.getSlot(i).getItem()));
        List<StackSnapshot> crafting = new ArrayList<>();
        if (menu instanceof InventoryMenu) {
            for (int slot = 0; slot <= 4; slot++) crafting.add(new StackSnapshot(menu.getSlot(slot).getItem()));
        } else if (menu instanceof CraftingMenu) {
            for (int slot = 0; slot <= 9; slot++) crafting.add(new StackSnapshot(menu.getSlot(slot).getItem()));
        }
        return new InventorySnapshot(inventory, menu.getClass().getName(), menu.containerId,
                menu instanceof InventoryMenu && menu == player.inventoryMenu,
                new StackSnapshot(menu.getCarried()), menuContents, crafting);
    }

    private static PlayerSnapshot capturePlayer(net.minecraft.world.entity.player.Player player, GameType mode,
                                                String dimension) {
        return new PlayerSnapshot(mode, player.isAlive(), player.getHealth(), player.getMaxHealth(),
                player.getFoodData().getFoodLevel(), player.getFoodData().getSaturationLevel(), dimension,
                player.blockPosition());
    }

    private static List<CellSnapshot> captureCells(net.minecraft.world.level.Level level, BlockPos pos) {
        List<CellSnapshot> result = new ArrayList<>(8);
        for (int y = 0; y < 2; y++) for (int z = 0; z < 4; z++) {
            BlockPos cell = pos.offset(0, y, z).immutable();
            result.add(new CellSnapshot(cell, level.getBlockState(cell)));
        }
        return List.copyOf(result);
    }

    private static List<CellSnapshot> captureSupportCells(net.minecraft.world.level.Level level, BlockPos pos) {
        List<CellSnapshot> result = new ArrayList<>(4);
        for (int z = 0; z < 4; z++) {
            BlockPos support = pos.offset(0, -1, z).immutable();
            result.add(new CellSnapshot(support, level.getBlockState(support)));
        }
        return List.copyOf(result);
    }

    private static ChestSnapshot captureChest(net.minecraft.world.level.Level level, BlockPos pos) {
        BlockEntity entity = level.getBlockEntity(pos.offset(0, 0, 2));
        if (!(entity instanceof ChestBlockEntity chest))
            return new ChestSnapshot(false, null, false, "", "", 0, false, List.of());
        List<StackSnapshot> contents = new ArrayList<>(chest.getContainerSize());
        for (int i = 0; i < chest.getContainerSize(); i++) contents.add(new StackSnapshot(chest.getItem(i)));
        boolean customNamePresent = chest.hasCustomName();
        String customName = customNamePresent ? chest.getCustomName().getString() : "";
        var lootTable = chest.getLootTable();
        String lootTableId = lootTable == null ? "" : lootTable.identifier().toString();
        return new ChestSnapshot(true, entity.getClass().getName(), customNamePresent, customName, lootTableId,
                chest.getLootTableSeed(), chest.isLocked(), contents);
    }

    private static Map<String, Integer> readItemStats(ServerPlayer player) {
        Map<String, Integer> result = new java.util.LinkedHashMap<>();
        for (var item : List.of(Items.OAK_LOG, Items.COBBLESTONE, Items.COAL, Items.CHARCOAL))
            result.put("picked:" + BuiltInRegistries.ITEM.getKey(item), player.getStats().getValue(Stats.ITEM_PICKED_UP, item));
        for (var item : List.of(Items.OAK_PLANKS, Items.CHEST, Items.TORCH, Items.CHARCOAL))
            result.put("crafted:" + BuiltInRegistries.ITEM.getKey(item), player.getStats().getValue(Stats.ITEM_CRAFTED, item));
        return Map.copyOf(result);
    }

    private static boolean sameCells(List<CellSnapshot> left, List<CellSnapshot> right) {
        if (left == null || right == null || left.size() != right.size()) return false;
        for (int i = 0; i < left.size(); i++)
            if (!left.get(i).pos().equals(right.get(i).pos()) || !left.get(i).state().equals(right.get(i).state())) return false;
        return true;
    }

    private static List<CellSnapshot> expectedOracleCells(BlockPos origin) {
        BlockState[][] expected = {{Blocks.OAK_PLANKS.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState(),
                Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.EAST), Blocks.AIR.defaultBlockState()},
                {Blocks.TORCH.defaultBlockState(), Blocks.TORCH.defaultBlockState(), Blocks.AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()}};
        List<CellSnapshot> result = new ArrayList<>(8);
        for (int y = 0; y < 2; y++) for (int z = 0; z < 4; z++)
            result.add(new CellSnapshot(origin.offset(0, y, z), expected[y][z]));
        return List.copyOf(result);
    }

    private static int remainingAuthoredCells(List<CellSnapshot> actual) {
        if (actual.size() != 8) return -1;
        List<CellSnapshot> expected = expectedOracleCells(actual.get(0).pos());
        int remaining = 0;
        for (int i = 0; i < expected.size(); i++) {
            if (!expected.get(i).pos().equals(actual.get(i).pos())) return -1;
            BlockState wanted = expected.get(i).state();
            BlockState found = actual.get(i).state();
            if (wanted.isAir() ? !found.isAir() : !wanted.equals(found)) remaining++;
        }
        return remaining;
    }

    private long receiptWatermark() {
        synchronized (this) { return nextServerReceiptSequence; }
    }
    private long sealServerReceiptsAndGetWatermark() {
        synchronized (this) {
            serverReceiptsSealed = true;
            return nextServerReceiptSequence;
        }
    }
    private long postSealServerReceiptCount() {
        synchronized (this) { return postSealServerReceiptCount; }
    }
    private boolean commitSuccessfulVerdict() {
        synchronized (this) {
            if (phase == Phase.FAILED || postSealServerReceiptCount != 0) return false;
            phase = Phase.DONE;
            return true;
        }
    }
    private boolean recipeStatsPassed(Map<String, Integer> stats, Map<String, Integer> baseline) {
        return statDelta(stats, baseline, Items.OAK_LOG, Stats.ITEM_PICKED_UP) > 0
                && statDelta(stats, baseline, Items.COBBLESTONE, Stats.ITEM_PICKED_UP) > 0
                && serverCoalOreBreakSeen && statDelta(stats, baseline, Items.COAL, Stats.ITEM_PICKED_UP) > 0
                && statDelta(stats, baseline, Items.OAK_PLANKS, Stats.ITEM_CRAFTED) > 0
                && statDelta(stats, baseline, Items.CHEST, Stats.ITEM_CRAFTED) > 0
                && statDelta(stats, baseline, Items.TORCH, Stats.ITEM_CRAFTED) >= 2;
    }
    private boolean recipeStatsPassedPure(Map<String, Integer> stats, Map<String, Integer> baseline) {
        return statDelta(stats, baseline, Items.OAK_LOG, Stats.ITEM_PICKED_UP) > 0
                && statDelta(stats, baseline, Items.COBBLESTONE, Stats.ITEM_PICKED_UP) > 0
                && serverCoalOreBreakSeen && statDelta(stats, baseline, Items.COAL, Stats.ITEM_PICKED_UP) > 0
                && statDelta(stats, baseline, Items.OAK_PLANKS, Stats.ITEM_CRAFTED) > 0
                && statDelta(stats, baseline, Items.CHEST, Stats.ITEM_CRAFTED) > 0
                && statDelta(stats, baseline, Items.TORCH, Stats.ITEM_CRAFTED) >= 2;
    }
    private static int statDelta(Map<String, Integer> stats, Map<String, Integer> baseline,
                          net.minecraft.world.item.Item item,
                          net.minecraft.stats.StatType<net.minecraft.world.item.Item> type) {
        String key = (type == Stats.ITEM_PICKED_UP ? "picked:" : "crafted:") + BuiltInRegistries.ITEM.getKey(item);
        return stats.getOrDefault(key, 0) - baseline.getOrDefault(key, 0);
    }
    private static boolean cleanCraftingSlots(AbstractContainerMenu menu) {
        if (!(menu instanceof InventoryMenu)) return false;
        for (int i=0;i<=4;i++) if (!menu.getSlot(i).getItem().isEmpty()) return false;
        return true;
    }
    private static String statDeltas(Map<String, Integer> stats, Map<String, Integer> baseline) {
        return "pickedOakLog=" + statDelta(stats, baseline, Items.OAK_LOG, Stats.ITEM_PICKED_UP)
                + ",pickedCobblestone=" + statDelta(stats, baseline, Items.COBBLESTONE, Stats.ITEM_PICKED_UP)
                + ",pickedCoal=" + statDelta(stats, baseline, Items.COAL, Stats.ITEM_PICKED_UP)
                + ",pickedCharcoal=" + statDelta(stats, baseline, Items.CHARCOAL, Stats.ITEM_PICKED_UP)
                + ",craftedPlanks=" + statDelta(stats, baseline, Items.OAK_PLANKS, Stats.ITEM_CRAFTED)
                + ",craftedChest=" + statDelta(stats, baseline, Items.CHEST, Stats.ITEM_CRAFTED)
                + ",craftedTorches=" + statDelta(stats, baseline, Items.TORCH, Stats.ITEM_CRAFTED)
                + ",craftedCharcoal=" + statDelta(stats, baseline, Items.CHARCOAL, Stats.ITEM_CRAFTED);
    }
    private static boolean priority(LitematicaRecoveryEventRecorder.Event event, String name, String value) {
        return event.kind().equals("taskRunner.priority") && event.detail() != null
                && event.detail().contains("name=" + name) && event.detail().contains("returnedPriority=" + value);
    }
    private static float priorityValue(LitematicaRecoveryEventRecorder.Event event) {
        try { return Float.parseFloat(event.detail().substring(event.detail().lastIndexOf("returnedPriority=") + 17)); }
        catch (RuntimeException ignored) { return Float.NEGATIVE_INFINITY; }
    }
    private boolean projectileSeenAndApproachesPlayer() {
        if (arrowId == null || arrowSpawnPosition == null) return false;
        Minecraft client=Minecraft.getInstance();
        if (client.level == null || !(client.level.getEntity(arrowEntityId) instanceof Arrow actual)
                || !actual.getUUID().equals(arrowId)) return false;
        for (CachedProjectile projectile : mod.getEntityTracker().getProjectiles()) {
            if (projectile.projectileType != Arrow.class || projectile.position.distanceToSqr(actual.position()) > 1.0
                    || projectile.velocity.distanceToSqr(actual.getDeltaMovement()) > 0.25) continue;
            if (MobDefenseChain.isProjectileCloseToPlayer(projectile, mod.getPlayer().position())) return true;
        }
        return false;
    }
    /** Time until the arrow is level with the player along its horizontal direction of travel. */
    private static double horizontalTimeToPlayer(ServerPlayer player, Vec3 start, Vec3 velocity) {
        double speedSq = velocity.x * velocity.x + velocity.z * velocity.z;
        return ((player.getX() - start.x) * velocity.x + (player.getZ() - start.z) * velocity.z) / speedSq;
    }

    private static void verifyArrowTrajectory(ServerLevel level, ServerPlayer player, Vec3 start, Vec3 velocity) {
        double horizontalTime = horizontalTimeToPlayer(player, start, velocity);
        double gravity = ProjectileHelper.ARROW_GRAVITY_ACCEL;
        double closestY = start.y + velocity.y * horizontalTime - 0.5 * gravity * horizontalTime * horizontalTime;
        double verticalOffset = Math.abs(closestY - player.getY());
        Vec3 productionPrediction = ProjectileHelper.calculateArrowClosestApproach(start, velocity,
                gravity, player.position());
        if (horizontalTime <= 0 || Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z) <= 0
                || verticalOffset >= 1.7 || Math.abs(productionPrediction.y - closestY) > 1.0e-6
                || Math.abs(productionPrediction.y - player.getY()) >= 1.7)
            throw new IllegalStateException("arrow trajectory misses the production-predicted player hitbox: t="
                + horizontalTime + ",physicalY=" + closestY + ",predictedY=" + productionPrediction.y);
        Vec3 previous=start;
        for (int tick=1;tick<=Math.ceil(horizontalTime);tick++) {
            double t=Math.min(tick,horizontalTime);
            Vec3 next = new Vec3(start.x + velocity.x*t, start.y + velocity.y*t - 0.5*gravity*t*t,
                    start.z + velocity.z*t);
            BlockHitResult hit = level.clip(new ClipContext(previous, next, ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, player));
            if (hit.getType() != HitResult.Type.MISS)
                throw new IllegalStateException("ballistic arrow corridor obstructed at tick " + tick
                        + " by " + level.getBlockState(hit.getBlockPos()) + " at " + hit.getBlockPos().toShortString()
                        + " (player " + player.blockPosition().toShortString() + ", start " + start + ")");
            previous=next;
        }
    }
    private long eventsDropped() { return LitematicaRecoveryEventRecorder.snapshot().droppedEvents(); }
    private static String sha256(Path p) throws Exception {
        MessageDigest d=MessageDigest.getInstance("SHA-256");
        try (InputStream in=Files.newInputStream(p)) { byte[] b=new byte[8192]; for(int n;(n=in.read(b))>0;) d.update(b,0,n); }
        return HexFormat.of().formatHex(d.digest());
    }
    private static String oracleSummary() { return "planks@O+0,0,0;cobble@O+0,0,1;east-empty-chest@O+0,0,2;AIR@O+0,0,3;torch@O+0,1,0/1;AIR@O+0,1,2/3"; }
    private void reject(String why) {
        if (isTerminal(phase)) return;
        phase=Phase.FAILED;
        if (captureOwned && archivedCapture == null && LitematicaRecoveryEventRecorder.isCapturing()) archiveCapture();
        discardKnownArrowAfterFailure();
        fail.accept("LITEMATICA_RECOVERY_FAIL\t"+why);
    }
    public boolean isDone() { return phase == Phase.DONE || phase == Phase.FAILED; }
}
