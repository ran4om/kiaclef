package adris.altoclef.runtimetest;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.AbstractDoToClosestObjectTask;
import adris.altoclef.tasks.DoToClosestBlockTask;
import adris.altoclef.tasks.resources.CollectFoodTask;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Exercises the actual bounded mature-crop selection helper with an opt-in prepared fixture. */
public final class FoodBlockRangeAcceptanceScenario {
    private enum Phase { NEW, PREPARING, WAIT_CLIENT_SYNC, HARVEST_FIRST, WAIT_IDLE, SEED_RESUME,
        WAIT_RESUME, VERIFY_FINAL, DONE, FAILED }

    private static final double SEARCH_RADIUS = 8.0;
    private static final int PHASE_TIMEOUT_TICKS = 6000;
    private static final int VERIFY_TIMEOUT_TICKS = 300;
    private static final int IDLE_STABLE_TICKS = 40;

    private final AltoClef mod;
    private final Consumer<String> append;
    private final Consumer<String> failure;
    private final Runnable success;

    private Phase phase = Phase.NEW;
    private long phaseStarted;
    private long nextPollTick;
    private long idleSince = -1;
    private UUID playerId;
    private Vec3 origin;
    private BlockPos firstInside;
    private BlockPos outside;
    private BlockPos resumedInside;
    private int originalRandomTickSpeed = -1;
    private volatile boolean setupReady;
    private volatile String setupState = "not-started";
    private volatile boolean pollOutstanding;
    private volatile boolean pollReady;
    private volatile String pollState = "not-polled";
    private volatile boolean firstIsAir;
    private volatile boolean outsideIsMature;
    private volatile boolean resumedIsMature;
    private volatile boolean resumedIsAir;
    private volatile int wheatCount;
    private volatile int droppedWheatCount;
    private volatile boolean serverFinalReady;
    private volatile boolean resumeSeedReady;
    private volatile String resumeSeedState = "not-seeded";
    private volatile boolean unexpectedCompletion;
    private boolean expectedCancellation;

    public FoodBlockRangeAcceptanceScenario(AltoClef mod, Consumer<String> append,
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
                    fail("Food block-range fixture setup failed: " + setupState);
                } else {
                    append.accept("FOOD_BLOCK_RANGE_SETUP\tok\torigin=" + origin + ",radius=" + SEARCH_RADIUS
                            + ",inside=" + firstInside + ",outside=" + outside + ",resume=" + resumedInside
                            + ",inventory=empty,seededCropOutputs=none");
                    phase = Phase.WAIT_CLIENT_SYNC;
                    phaseStarted = clientTickCount();
                }
            } else if (timedOut(PHASE_TIMEOUT_TICKS)) {
                fail("Timed out preparing food block-range fixture: " + setupState);
            }
            return;
        }

        if (unexpectedCompletion) {
            fail("Bounded mature-crop follow-up unexpectedly completed before scenario verification");
            return;
        }
        if (phase == Phase.WAIT_CLIENT_SYNC) {
            if (clientFixtureSynchronized()) startBoundedHarvest();
            else if (timedOut(VERIFY_TIMEOUT_TICKS)) {
                fail("Mature-crop fixture did not synchronize into the client tracker: " + clientSnapshot());
            }
            return;
        }
        if (phase == Phase.HARVEST_FIRST) {
            if (pollReady) {
                pollReady = false;
                append.accept("FOOD_BLOCK_RANGE_HARVEST_POLL\t" + pollState);
                if (firstIsAir && outsideIsMature && (wheatCount > 0 || droppedWheatCount > 0)) {
                    append.accept("ASSERT\tactual helper harvested the in-range mature wheat; outside mature wheat remains");
                    phase = Phase.WAIT_IDLE;
                    phaseStarted = clientTickCount();
                    idleSince = -1;
                } else if (timedOut(VERIFY_TIMEOUT_TICKS)) {
                    fail("In-range crop was not harvested with outside crop preserved: " + pollState
                            + ", client=" + clientSnapshot());
                }
            } else if (clientTickCount() >= nextPollTick) requestServerPoll();
            if (phase == Phase.HARVEST_FIRST && timedOut(PHASE_TIMEOUT_TICKS)) {
                fail("Timed out harvesting the in-range mature crop: " + pollState
                        + ", tasks=" + mod.getUserTaskChain().getTasks());
            }
            return;
        }
        if (phase == Phase.WAIT_IDLE) {
            boolean idle = taskHasNoPursuit();
            if (idle && outsideIsMature && firstIsAir) {
                if (idleSince < 0) idleSince = clientTickCount();
                if (clientTickCount() - idleSince >= IDLE_STABLE_TICKS) {
                    append.accept("ASSERT\tbounded crop task idled after eligible blocks were exhausted; outside crop untouched");
                    seedResumeCrop();
                }
            } else {
                idleSince = -1;
            }
            if (phase == Phase.WAIT_IDLE && clientTickCount() >= nextPollTick) requestServerPoll();
            if (phase == Phase.WAIT_IDLE && timedOut(VERIFY_TIMEOUT_TICKS)) {
                fail("Bounded crop task did not remain idle with only an outside crop available: "
                        + pollState + ", client=" + clientSnapshot() + ", pursued=" + currentPursuit());
            }
            return;
        }
        if (phase == Phase.SEED_RESUME) {
            if (resumeSeedReady) {
                if (!"ok".equals(resumeSeedState)) {
                    fail("Could not seed a new in-range mature crop: " + resumeSeedState);
                } else {
                    append.accept("FOOD_BLOCK_RANGE_RESUME_SETUP\tok\tposition=" + resumedInside);
                    phase = Phase.WAIT_RESUME;
                    phaseStarted = clientTickCount();
                }
            } else if (timedOut(VERIFY_TIMEOUT_TICKS)) {
                fail("Timed out spawning a new in-range mature crop: " + resumeSeedState);
            }
            return;
        }
        if (phase == Phase.WAIT_RESUME) {
            if (clientMature(resumedInside) && Objects.equals(currentPursuit(), resumedInside)) {
                append.accept("ASSERT\tactual bounded crop helper resumed for a newly available in-range mature wheat block");
                expectedCancellation = true;
                mod.cancelUserTask();
                phase = Phase.VERIFY_FINAL;
                phaseStarted = clientTickCount();
                requestServerPoll();
            } else if (timedOut(VERIFY_TIMEOUT_TICKS)) {
                fail("New in-range mature crop did not resume bounded selection: pursued=" + currentPursuit()
                        + ", client=" + clientSnapshot() + ", server=" + pollState);
            }
            return;
        }
        if (phase == Phase.VERIFY_FINAL) {
            if (pollReady) {
                pollReady = false;
                append.accept("FOOD_BLOCK_RANGE_FINAL_POLL\t" + pollState);
                if (firstIsAir && outsideIsMature && (resumedIsMature || resumedIsAir)
                        && (wheatCount > 0 || droppedWheatCount > 0) && serverFinalReady
                        && Minecraft.getInstance().player != null && cleanUi(Minecraft.getInstance().player)
                        && Minecraft.getInstance().level != null
                        && Minecraft.getInstance().level.getBlockState(firstInside).isAir()
                        && clientMature(outside)) {
                    finishSuccessfully();
                } else if (timedOut(VERIFY_TIMEOUT_TICKS)) {
                    fail("Food block-range final state mismatch: " + pollState + ", client=" + clientSnapshot());
                }
            } else if (!pollOutstanding && clientTickCount() >= nextPollTick) requestServerPoll();
        }
    }

    private void beginSetup() {
        Minecraft client = Minecraft.getInstance();
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null || client.player == null || client.level == null) {
            fail("Food block-range acceptance requires an active integrated server and client player");
            return;
        }
        playerId = client.player.getUUID();
        origin = client.player.position();
        mod.getBlockTracker().trackBlock(Blocks.WHEAT);
        phase = Phase.PREPARING;
        phaseStarted = clientTickCount();
        server.execute(() -> prepareServerFixture(server));
        append.accept("SKIP\truntimeStart=foodblockrange\tdiamond/list/build, natural resource discovery, and default CollectFoodTask chooser skipped; actual private block helper uses radius 8 and mature wheat fixture");
        append.accept("FOOD_BLOCK_RANGE_SETUP\tqueued\tactual CollectFoodTask block helper, radius=" + SEARCH_RADIUS);
    }

    private void prepareServerFixture(MinecraftServer server) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) {
            publishSetupFailure("player=null");
            return;
        }
        ServerLevel level = (ServerLevel) player.level();
        BlockPos[] fixture = findFixture(level, player.blockPosition(), player.position());
        if (fixture == null) {
            publishSetupFailure("no two inside and one outside crop cells over solid support");
            return;
        }
        firstInside = fixture[0].immutable();
        resumedInside = fixture[1].immutable();
        outside = fixture[2].immutable();
        level.getChunkAt(firstInside);
        level.getChunkAt(resumedInside);
        level.getChunkAt(outside);

        player.closeContainer();
        player.getInventory().clearContent();
        for (int slot = 1; slot <= 4; slot++) player.inventoryMenu.getSlot(slot).set(ItemStack.EMPTY);
        player.inventoryMenu.setCarried(ItemStack.EMPTY);
        player.inventoryMenu.broadcastChanges();
        player.setGameMode(GameType.SURVIVAL);

        originalRandomTickSpeed = server.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.RANDOM_TICK_SPEED);
        server.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.RANDOM_TICK_SPEED, 0, server);
        for (BlockPos pos : new BlockPos[]{firstInside, resumedInside, outside}) {
            level.setBlock(pos.below(), Blocks.FARMLAND.defaultBlockState()
                    .setValue(FarmlandBlock.MOISTURE, 7), 3);
            level.setBlock(pos.above(), Blocks.AIR.defaultBlockState(), 3);
        }
        level.setBlock(firstInside, wheatAtAge(((CropBlock) Blocks.WHEAT).getMaxAge()), 3);
        level.setBlock(resumedInside, Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(outside, wheatAtAge(((CropBlock) Blocks.WHEAT).getMaxAge()), 3);

        AABB cleanup = new AABB(origin.x - SEARCH_RADIUS, origin.y - SEARCH_RADIUS, origin.z - SEARCH_RADIUS,
                origin.x + SEARCH_RADIUS, origin.y + SEARCH_RADIUS, origin.z + SEARCH_RADIUS);
        for (ItemEntity drop : level.getEntitiesOfClass(ItemEntity.class, cleanup)) {
            if (drop.getItem().is(Items.WHEAT) || drop.getItem().is(Items.WHEAT_SEEDS)) drop.discard();
        }
        boolean valid = player.getInventory().isEmpty() && cleanUi(player)
                && isMature(level.getBlockState(firstInside))
                && level.getBlockState(resumedInside).isAir()
                && isMature(level.getBlockState(outside))
                && firstInside.closerToCenterThan(origin, SEARCH_RADIUS)
                && resumedInside.closerToCenterThan(origin, SEARCH_RADIUS)
                && !outside.closerToCenterThan(origin, SEARCH_RADIUS);
        setupState = valid ? "ok" : "seed-invalid:" + serverSnapshot(player, level);
        setupReady = true;
    }

    private static BlockPos[] findFixture(ServerLevel level, BlockPos playerPos, Vec3 playerPosition) {
        ArrayList<BlockPos> inside = new ArrayList<>();
        ArrayList<BlockPos> outside = new ArrayList<>();
        for (int distance = 2; distance <= 14; distance++) {
            for (int dx = -distance; dx <= distance; dx++) {
                int dzAbs = distance - Math.abs(dx);
                for (int dz : dzAbs == 0 ? new int[]{0} : new int[]{dzAbs, -dzAbs}) {
                    BlockPos pos = playerPos.offset(dx, 0, dz).immutable();
                    if (!validCropCell(level, pos)) continue;
                    if (pos.closerToCenterThan(playerPosition, SEARCH_RADIUS)) inside.add(pos);
                    else if (pos.closerToCenterThan(playerPosition, SEARCH_RADIUS + 5)) outside.add(pos);
                }
            }
            if (inside.size() >= 2 && !outside.isEmpty()) break;
        }
        if (inside.size() < 2 || outside.isEmpty()) return null;
        inside.sort(Comparator.comparingDouble(pos -> pos.distToCenterSqr(playerPosition)));
        outside.sort(Comparator.comparingDouble(pos -> pos.distToCenterSqr(playerPosition)));
        return new BlockPos[]{inside.get(0), inside.get(1), outside.get(0)};
    }

    private static boolean validCropCell(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos.below()).isRedstoneConductor(level, pos.below())
                && level.getBlockState(pos).isAir()
                && level.getBlockState(pos.above()).isAir();
    }

    private static BlockState wheatAtAge(int age) {
        return Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, age);
    }

    private void startBoundedHarvest() {
        try {
            Predicate<BlockPos> matureFixture = pos -> (pos.equals(firstInside) || pos.equals(resumedInside)
                    || pos.equals(outside)) && clientMature(pos);
            Method helper = CollectFoodTask.class.getDeclaredMethod("pickupBlockTaskOrNull",
                    AltoClef.class, net.minecraft.world.level.block.Block.class, Item.class,
                    Predicate.class, double.class);
            helper.setAccessible(true);
            Task task = (Task) helper.invoke(new CollectFoodTask(1), mod, Blocks.WHEAT, Items.WHEAT,
                    matureFixture, SEARCH_RADIUS);
            if (!(task instanceof DoToClosestBlockTask)
                    || !task.getClass().getSimpleName().equals("BoundedBlockFoodTask")) {
                fail("CollectFoodTask helper did not return its bounded block follow-up: "
                        + (task == null ? "null" : task.getClass().getName()));
                return;
            }
            if (!Objects.equals(nearestFixtureBlock(), firstInside)) {
                fail("Bounded helper did not select the nearest in-range mature fixture crop: " + nearestFixtureBlock());
                return;
            }
            phase = Phase.HARVEST_FIRST;
            phaseStarted = clientTickCount();
            nextPollTick = 0;
            mod.runUserTask(task, () -> {
                if (!expectedCancellation) unexpectedCompletion = true;
            });
            append.accept("FOOD_BLOCK_RANGE_TASK\tstarted\thelper=CollectFoodTask.pickupBlockTaskOrNull\tradius="
                    + SEARCH_RADIUS + "\tselected=" + firstInside + "\trejectedOutside=" + outside);
        } catch (ReflectiveOperationException error) {
            fail("Could not invoke CollectFoodTask mature-crop helper: " + error);
        }
    }

    private void seedResumeCrop() {
        phase = Phase.SEED_RESUME;
        phaseStarted = clientTickCount();
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            resumeSeedState = "server missing";
            resumeSeedReady = true;
            return;
        }
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) {
                resumeSeedState = "player missing";
                resumeSeedReady = true;
                return;
            }
            ServerLevel level = (ServerLevel) player.level();
            level.setBlock(resumedInside.below(), Blocks.FARMLAND.defaultBlockState()
                    .setValue(FarmlandBlock.MOISTURE, 7), 3);
            level.setBlock(resumedInside, wheatAtAge(((CropBlock) Blocks.WHEAT).getMaxAge()), 3);
            level.setBlock(resumedInside.above(), Blocks.AIR.defaultBlockState(), 3);
            resumeSeedState = isMature(level.getBlockState(resumedInside)) ? "ok" : "wheat state rejected";
            resumeSeedReady = true;
        });
    }

    private void requestServerPoll() {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null || pollOutstanding) return;
        pollOutstanding = true;
        pollReady = false;
        nextPollTick = clientTickCount() + 10;
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            ServerLevel level = player == null ? null : (ServerLevel) player.level();
            firstIsAir = level != null && level.getBlockState(firstInside).isAir();
            outsideIsMature = level != null && isMature(level.getBlockState(outside));
            resumedIsMature = level != null && isMature(level.getBlockState(resumedInside));
            resumedIsAir = level != null && level.getBlockState(resumedInside).isAir();
            serverFinalReady = player != null && cleanUi(player) && player.isAlive()
                    && player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL;
            wheatCount = player == null ? 0 : count(player, Items.WHEAT);
            droppedWheatCount = level == null ? 0 : countDroppedWheat(level, fixtureBounds());
            pollState = player == null || level == null ? "player/level missing"
                    : serverSnapshot(player, level) + ",droppedWheat=" + droppedWheatCount;
            pollOutstanding = false;
            pollReady = true;
        });
    }

    private boolean clientFixtureSynchronized() {
        Minecraft client = Minecraft.getInstance();
        return client.player != null && client.level != null && client.player.getInventory().isEmpty()
                && cleanUi(client.player) && clientMature(firstInside) && clientMature(outside)
                && client.level.getBlockState(resumedInside).isAir()
                && Objects.equals(nearestFixtureBlock(), firstInside);
    }

    private BlockPos nearestFixtureBlock() {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return null;
        return mod.getBlockTracker().getNearestTracking(origin, this::isEligibleFixtureCrop, Blocks.WHEAT).orElse(null);
    }

    private boolean isEligibleFixtureCrop(BlockPos pos) {
        return (pos.equals(firstInside) || pos.equals(resumedInside) || pos.equals(outside))
                && clientMature(pos) && pos.closerToCenterThan(origin, SEARCH_RADIUS);
    }

    private boolean clientMature(BlockPos pos) {
        Minecraft client = Minecraft.getInstance();
        return client.level != null && isMature(client.level.getBlockState(pos));
    }

    private static boolean isMature(BlockState state) {
        return state.getBlock() == Blocks.WHEAT && state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state);
    }

    private BlockPos currentPursuit() {
        try {
            Object task = activeScenarioTask();
            if (task == null) return null;
            Field field = AbstractDoToClosestObjectTask.class.getDeclaredField("_currentlyPursuing");
            field.setAccessible(true);
            Object value = field.get(task);
            return value instanceof BlockPos pos ? pos : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private boolean taskHasNoPursuit() {
        try {
            Object task = activeScenarioTask();
            if (task == null) return false;
            Field pursuit = AbstractDoToClosestObjectTask.class.getDeclaredField("_currentlyPursuing");
            Field goal = AbstractDoToClosestObjectTask.class.getDeclaredField("_goalTask");
            pursuit.setAccessible(true);
            goal.setAccessible(true);
            return pursuit.get(task) == null && goal.get(task) == null;
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private Object activeScenarioTask() throws ReflectiveOperationException {
        return mod.getUserTaskChain().getTasks().stream()
                .filter(task -> task.getClass().getSimpleName().equals("BoundedBlockFoodTask"))
                .findFirst().orElse(null);
    }

    private AABB fixtureBounds() {
        int minX = Math.min(firstInside.getX(), Math.min(outside.getX(), resumedInside.getX())) - 2;
        int maxX = Math.max(firstInside.getX(), Math.max(outside.getX(), resumedInside.getX())) + 3;
        int minY = Math.min(firstInside.getY(), Math.min(outside.getY(), resumedInside.getY())) - 1;
        int maxY = Math.max(firstInside.getY(), Math.max(outside.getY(), resumedInside.getY())) + 2;
        int minZ = Math.min(firstInside.getZ(), Math.min(outside.getZ(), resumedInside.getZ())) - 2;
        int maxZ = Math.max(firstInside.getZ(), Math.max(outside.getZ(), resumedInside.getZ())) + 3;
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static int countDroppedWheat(ServerLevel level, AABB bounds) {
        return level.getEntitiesOfClass(ItemEntity.class, bounds).stream()
                .filter(drop -> drop.getItem().is(Items.WHEAT))
                .mapToInt(drop -> drop.getItem().getCount()).sum();
    }

    private static int count(Player player, Item item) {
        int result = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) result += stack.getCount();
        }
        return result;
    }

    private static boolean cleanUi(Player player) {
        return player.containerMenu == player.inventoryMenu && player.containerMenu.getCarried().isEmpty();
    }

    private long clientTickCount() {
        return Minecraft.getInstance().level == null ? 0 : Minecraft.getInstance().level.getGameTime();
    }

    private boolean timedOut(int limit) {
        return clientTickCount() - phaseStarted > limit;
    }

    private String serverSnapshot(ServerPlayer player, ServerLevel level) {
        return "inside=" + level.getBlockState(firstInside) + ",outside=" + level.getBlockState(outside)
                + ",resume=" + level.getBlockState(resumedInside) + ",wheat=" + count(player, Items.WHEAT)
                + ",uiClean=" + cleanUi(player);
    }

    private String clientSnapshot() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return "player/level missing";
        return "inside=" + client.level.getBlockState(firstInside) + ",outside=" + client.level.getBlockState(outside)
                + ",resume=" + client.level.getBlockState(resumedInside) + ",wheat=" + count(client.player, Items.WHEAT)
                + ",pursued=" + currentPursuit();
    }

    private void finishSuccessfully() {
        phase = Phase.DONE;
        restoreRandomTickSpeed();
        mod.getBlockTracker().stopTracking(Blocks.WHEAT);
        append.accept("FOOD_BLOCK_RANGE_ACCEPTANCE\tPASS\tactual CollectFoodTask helper harvested nearest in-range mature wheat, preserved out-of-range mature wheat, idled with no eligible crops, and resumed for a new in-range crop");
        success.run();
    }

    private void fail(String reason) {
        if (phase == Phase.FAILED || phase == Phase.DONE) return;
        phase = Phase.FAILED;
        mod.cancelUserTask();
        restoreRandomTickSpeed();
        mod.getBlockTracker().stopTracking(Blocks.WHEAT);
        failure.accept(reason);
    }

    private void restoreRandomTickSpeed() {
        if (originalRandomTickSpeed < 0) return;
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server != null) {
            int original = originalRandomTickSpeed;
            server.execute(() -> server.getGameRules().set(
                    net.minecraft.world.level.gamerules.GameRules.RANDOM_TICK_SPEED, original, server));
        }
        originalRandomTickSpeed = -1;
    }

    private void publishSetupFailure(String reason) {
        setupState = reason;
        setupReady = true;
    }
}
