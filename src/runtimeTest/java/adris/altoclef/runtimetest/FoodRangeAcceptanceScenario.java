package adris.altoclef.runtimetest;

import adris.altoclef.AltoClef;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/** Exercises CollectFoodTask's actual bounded drop helper with a compact, observable radius. */
public final class FoodRangeAcceptanceScenario {
    private enum Phase { NEW, PREPARING, WAIT_CLIENT_SYNC, COLLECTING_FIRST, WAIT_OUTSIDE_HOLD, SPAWN_SECOND,
        COLLECTING_SECOND, VERIFY_FINAL, DONE, FAILED }

    private static final int PHASE_TIMEOUT_TICKS = 6000;
    private static final int VERIFY_TIMEOUT_TICKS = 300;
    private static final int OUTSIDE_HOLD_TICKS = 100;
    private static final double PICKUP_RADIUS = 8.0;
    private static final double OUTSIDE_DISTANCE = 12.0;

    private final AltoClef mod;
    private final Consumer<String> append;
    private final Consumer<String> failure;
    private final Runnable success;

    private Phase phase = Phase.NEW;
    private long phaseStarted;
    private long lastPollTick;
    private UUID playerId;
    private Vec3 insidePosition;
    private Vec3 secondInsidePosition;
    private Vec3 outsidePosition;
    private Vec3 holdPosition;
    private UUID firstDropId;
    private UUID secondDropId;
    private UUID outsideDropId;
    private volatile boolean setupReady;
    private volatile String setupState = "not-started";
    private volatile boolean pollOutstanding;
    private volatile boolean pollReady;
    private volatile boolean pollPassed;
    private volatile String pollState = "not-polled";
    private volatile int serverBerries;
    private volatile boolean secondSeedReady;
    private volatile String secondSeedState = "not-seeded";
    private volatile boolean completionObserved;

    public FoodRangeAcceptanceScenario(AltoClef mod, Consumer<String> append,
                                       Consumer<String> failure, Runnable success) {
        this.mod = Objects.requireNonNull(mod, "mod");
        this.append = Objects.requireNonNull(append, "append");
        this.failure = Objects.requireNonNull(failure, "failure");
        this.success = Objects.requireNonNull(success, "success");
    }

    public void tick() {
        if (phase == Phase.DONE || phase == Phase.FAILED) return;
        if (phase == Phase.NEW) {
            beginSetup();
            return;
        }
        if (phase == Phase.PREPARING) {
            if (setupReady) {
                if (!"ok".equals(setupState)) fail("Food-range fixture setup failed: " + setupState);
                else {
                    append.accept("FOOD_RANGE_SETUP\tok\t" + setupSnapshot());
                    phase = Phase.WAIT_CLIENT_SYNC;
                    phaseStarted = clientTickCount();
                }
            } else if (timedOut(PHASE_TIMEOUT_TICKS)) fail("Timed out preparing food-range fixture: " + setupState);
            return;
        }
        if (phase == Phase.WAIT_CLIENT_SYNC) {
            if (clientFixtureIsSynchronized()) startBoundedPickup();
            else if (timedOut(200)) fail("Food-range fixture did not synchronize: " + clientSnapshot());
            return;
        }

        if (phase == Phase.COLLECTING_FIRST || phase == Phase.COLLECTING_SECOND) {
            if (completionObserved) {
                fail("Bounded pickup task unexpectedly completed before the acceptance scenario finished");
                return;
            }
            if (pollReady) {
                pollReady = false;
                append.accept("FOOD_RANGE_SERVER_POLL\t" + pollState);
                if (pollPassed) {
                    if (phase == Phase.COLLECTING_FIRST) {
                        holdPosition = Minecraft.getInstance().player.position();
                        phase = Phase.WAIT_OUTSIDE_HOLD;
                        phaseStarted = clientTickCount();
                    } else {
                        mod.cancelUserTask();
                        phase = Phase.VERIFY_FINAL;
                        phaseStarted = clientTickCount();
                        lastPollTick = 0;
                    }
                } else if (timedOut(VERIFY_TIMEOUT_TICKS)) {
                    fail("Bounded pickup did not reach the expected state: " + pollState
                            + ", client=" + clientSnapshot());
                }
            } else if (clientTickCount() - lastPollTick >= 10) {
                requestPoll(phase == Phase.COLLECTING_FIRST ? 1 : 2);
            }
            if ((phase == Phase.COLLECTING_FIRST || phase == Phase.COLLECTING_SECOND)
                    && timedOut(PHASE_TIMEOUT_TICKS)) {
                fail("Bounded pickup timed out: " + pollState + ", client=" + clientSnapshot()
                        + ", tasks=" + mod.getUserTaskChain().getTasks());
            }
            return;
        }
        if (phase == Phase.WAIT_OUTSIDE_HOLD) {
            if (timedOut(OUTSIDE_HOLD_TICKS) && !pollOutstanding && !pollReady) {
                phase = Phase.SPAWN_SECOND;
                phaseStarted = clientTickCount();
                seedSecondDrop();
                return;
            }
            if (pollReady) {
                pollReady = false;
                append.accept("FOOD_RANGE_HOLD_POLL\t" + pollState);
                if (!pollPassed) {
                    fail("Out-of-range berry was not preserved during bounded-pickup hold: " + pollState);
                    return;
                }
            } else if (clientTickCount() - lastPollTick >= 10) requestPoll(1);
            return;
        }
        if (phase == Phase.SPAWN_SECOND) {
            if (!secondSeedReady) {
                if (timedOut(VERIFY_TIMEOUT_TICKS)) fail("Timed out spawning second in-range drop: " + secondSeedState);
                return;
            }
            if (!"ok".equals(secondSeedState)) {
                fail("Could not spawn second in-range drop: " + secondSeedState);
                return;
            }
            phase = Phase.COLLECTING_SECOND;
            phaseStarted = clientTickCount();
            lastPollTick = 0;
            return;
        }
        if (phase == Phase.VERIFY_FINAL) {
            if (pollReady) {
                pollReady = false;
                append.accept("FOOD_RANGE_FINAL_POLL\t" + pollState);
                if (pollPassed && clientMatchesExpected()) finishSuccessfully();
                else if (timedOut(VERIFY_TIMEOUT_TICKS)) {
                    fail("Food-range final state mismatch: server=" + pollState + ", client=" + clientSnapshot());
                }
            } else if (clientTickCount() - lastPollTick >= 10) requestFinalPoll();
            return;
        }
    }

    private void beginSetup() {
        Minecraft client = Minecraft.getInstance();
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null || client.player == null || client.level == null) {
            fail("Food-range acceptance requires an active integrated server and client player");
            return;
        }
        playerId = client.player.getUUID();
        Vec3 playerPosition = client.player.position();
        insidePosition = new Vec3(playerPosition.x + 3.5, playerPosition.y + 0.1, playerPosition.z);
        secondInsidePosition = new Vec3(playerPosition.x + 5.5, playerPosition.y + 0.1, playerPosition.z);
        outsidePosition = new Vec3(playerPosition.x + OUTSIDE_DISTANCE, playerPosition.y + 0.1,
                playerPosition.z);
        phase = Phase.PREPARING;
        phaseStarted = clientTickCount();
        server.execute(() -> prepareServerFixture(server));
        append.accept("FOOD_RANGE_SETUP\tqueued\tCollectFoodTask helper radius=" + PICKUP_RADIUS
                + "\tinside=" + insidePosition + "\toutside=" + outsidePosition);
    }

    private void prepareServerFixture(MinecraftServer server) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) {
            publishSetupFailure("player=null");
            return;
        }
        ServerLevel level = (ServerLevel) player.level();
        BlockPos insideBlock = BlockPos.containing(insidePosition);
        BlockPos outsideBlock = BlockPos.containing(outsidePosition);
        level.getChunkAt(insideBlock);
        level.getChunkAt(outsideBlock);
        player.closeContainer();
        player.getInventory().clearContent();
        for (int slot = 1; slot <= 4; slot++) player.inventoryMenu.getSlot(slot).set(ItemStack.EMPTY);
        player.inventoryMenu.setCarried(ItemStack.EMPTY);
        player.inventoryMenu.broadcastChanges();
        player.setGameMode(GameType.SURVIVAL);

        for (BlockPos dropBlock : new BlockPos[]{insideBlock, outsideBlock}) {
            level.setBlock(dropBlock.below(), Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(dropBlock, Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(dropBlock.above(), Blocks.AIR.defaultBlockState(), 3);
        }

        AABB cleanupBounds = new AABB(insidePosition.x - 2, insidePosition.y - 2, insidePosition.z - 2,
                outsidePosition.x + 2, outsidePosition.y + 2, outsidePosition.z + 2);
        for (ItemEntity existing : level.getEntitiesOfClass(ItemEntity.class, cleanupBounds)) {
            if (existing.getItem().is(Items.SWEET_BERRIES)) existing.discard();
        }

        ItemEntity inside = spawnBerry(level, insidePosition);
        ItemEntity outside = spawnBerry(level, outsidePosition);
        firstDropId = inside.getUUID();
        outsideDropId = outside.getUUID();
        boolean spawned = level.addFreshEntity(inside) && level.addFreshEntity(outside);
        boolean valid = spawned && player.getInventory().isEmpty()
                && player.containerMenu == player.inventoryMenu
                && player.containerMenu.getCarried().isEmpty() && emptyCraftingGrid(player)
                && level.getEntity(firstDropId) instanceof ItemEntity insideEntity
                && level.getEntity(outsideDropId) instanceof ItemEntity outsideEntity
                && insideEntity.position().distanceToSqr(player.position()) < PICKUP_RADIUS * PICKUP_RADIUS
                && outsideEntity.position().distanceToSqr(player.position()) > PICKUP_RADIUS * PICKUP_RADIUS;
        setupState = valid ? "ok" : "seed-invalid:" + serverSnapshot(player, level, firstDropId);
        setupReady = true;
    }

    private void startBoundedPickup() {
        Minecraft client = Minecraft.getInstance();
        mod.getBlockTracker().trackBlock(Blocks.SWEET_BERRY_BUSH);
        try {
            Method helper = CollectFoodTask.class.getDeclaredMethod("pickupBlockTaskOrNull",
                    AltoClef.class, net.minecraft.world.level.block.Block.class, Item.class,
                    java.util.function.Predicate.class, double.class);
            helper.setAccessible(true);
            Task task = (Task) helper.invoke(new CollectFoodTask(1), mod, Blocks.SWEET_BERRY_BUSH,
                    Items.SWEET_BERRIES, (java.util.function.Predicate<BlockPos>) position -> false, PICKUP_RADIUS);
            if (task == null || !task.getClass().getSimpleName().equals("PickupFoodDropTask")) {
                fail("CollectFoodTask helper did not return its bounded pickup wrapper: "
                        + (task == null ? "null" : task.getClass().getName()));
                return;
            }
            Method accepts = task.getClass().getDeclaredMethod("acceptsDrop", AltoClef.class, ItemEntity.class);
            accepts.setAccessible(true);
            ItemEntity inside = itemEntity(client.level, firstDropId);
            ItemEntity outside = itemEntity(client.level, outsideDropId);
            if (inside == null || outside == null
                    || !(Boolean) accepts.invoke(task, mod, inside)
                    || (Boolean) accepts.invoke(task, mod, outside)) {
                fail("CollectFoodTask bounded wrapper did not accept the in-range berry and reject the tracked out-of-range berry");
                return;
            }
            append.accept("ASSERT\tCollectFoodTask helper returned PickupFoodDropTask(radius=" + PICKUP_RADIUS
                    + ") and accepted inside/rejected outside entity");
            phase = Phase.COLLECTING_FIRST;
            phaseStarted = clientTickCount();
            lastPollTick = 0;
            mod.runUserTask(task, () -> completionObserved = true);
        } catch (ReflectiveOperationException e) {
            fail("Could not invoke CollectFoodTask bounded pickup helper: " + e);
        } finally {
            mod.getBlockTracker().stopTracking(Blocks.SWEET_BERRY_BUSH);
        }
    }

    private void seedSecondDrop() {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            secondSeedState = "server missing";
            secondSeedReady = true;
            return;
        }
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) {
                secondSeedState = "player missing";
                secondSeedReady = true;
                return;
            }
            ServerLevel level = (ServerLevel) player.level();
            level.getChunkAt(BlockPos.containing(secondInsidePosition));
            ItemEntity second = spawnBerry(level, secondInsidePosition);
            secondDropId = second.getUUID();
            secondSeedState = level.addFreshEntity(second) ? "ok" : "entity rejected";
            secondSeedReady = true;
        });
    }

    private void requestPoll(int expected) {
        requestPollInternal(expected);
    }

    private void requestFinalPoll() {
        requestPollInternal(2);
    }

    private void requestPollInternal(int expected) {
        Minecraft client = Minecraft.getInstance();
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null || pollOutstanding) return;
        pollOutstanding = true;
        pollReady = false;
        lastPollTick = clientTickCount();
        boolean holding = phase == Phase.WAIT_OUTSIDE_HOLD;
        Vec3 holdAnchor = holdPosition;
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            ServerLevel level = player == null ? null : (ServerLevel) player.level();
            UUID inRangeId = expected == 2 ? secondDropId : firstDropId;
            ItemEntity inRange = level == null ? null : itemEntity(level, inRangeId);
            ItemEntity outside = level == null ? null : itemEntity(level, outsideDropId);
            serverBerries = player == null ? 0 : count(player, Items.SWEET_BERRIES);
            boolean stayedNearby = !holding || player != null && holdAnchor != null
                    && player.position().subtract(holdAnchor).horizontalDistanceSqr() <= 4.0;
            pollPassed = player != null && level != null && serverBerries == expected && cleanUi(player)
                    && stayedNearby
                    && (inRange == null || !inRange.isAlive())
                    && outside != null && outside.isAlive() && outside.getItem().is(Items.SWEET_BERRIES)
                    && outside.getItem().getCount() == 1;
            pollState = player == null || level == null ? "player/level missing"
                    : serverSnapshot(player, level, inRangeId) + ",expected=" + expected
                    + ",outside=" + entitySnapshot(outside) + ",holdStayedNearby=" + stayedNearby
                    + ",playerPos=" + player.position() + ",valid=" + pollPassed;
            pollOutstanding = false;
            pollReady = true;
        });
    }

    private boolean clientMatchesExpected() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null || !cleanUi(client.player)
                || serverBerries != 2 || count(client.player, Items.SWEET_BERRIES) != serverBerries) return false;
        for (int slot = 0; slot < client.player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = client.player.getInventory().getItem(slot);
            if (!stack.isEmpty() && !stack.is(Items.SWEET_BERRIES)) return false;
        }
        ItemEntity outside = itemEntity(client.level, outsideDropId);
        return outside != null && outside.isAlive() && outside.getItem().is(Items.SWEET_BERRIES)
                && outside.getItem().getCount() == 1
                && itemEntity(client.level, secondDropId) == null;
    }

    private boolean clientFixtureIsSynchronized() {
        Minecraft client = Minecraft.getInstance();
        return client.player != null && client.level != null && client.player.getInventory().isEmpty()
                && cleanUi(client.player) && itemEntity(client.level, firstDropId) != null
                && itemEntity(client.level, outsideDropId) != null
                && mod.getEntityTracker().getDroppedItems().stream()
                        .anyMatch(drop -> drop.getUUID().equals(firstDropId))
                && mod.getEntityTracker().getDroppedItems().stream()
                        .anyMatch(drop -> drop.getUUID().equals(outsideDropId));
    }

    private static ItemEntity spawnBerry(ServerLevel level, Vec3 position) {
        ItemEntity entity = new ItemEntity(level, position.x, position.y, position.z,
                new ItemStack(Items.SWEET_BERRIES));
        entity.setDefaultPickUpDelay();
        return entity;
    }

    private static ItemEntity itemEntity(net.minecraft.world.level.Level level, UUID id) {
        return id == null || !(level.getEntity(id) instanceof ItemEntity entity) ? null : entity;
    }

    private static int count(Player player, Item item) {
        int total = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    private static boolean emptyCraftingGrid(Player player) {
        for (int slot = 1; slot <= 4; slot++) {
            if (!player.inventoryMenu.getSlot(slot).getItem().isEmpty()) return false;
        }
        return true;
    }

    private static boolean cleanUi(Player player) {
        return player.containerMenu == player.inventoryMenu
                && player.containerMenu.getCarried().isEmpty() && emptyCraftingGrid(player);
    }

    private boolean timedOut(int limit) {
        return clientTickCount() - phaseStarted > limit;
    }

    private long clientTickCount() {
        return Minecraft.getInstance().level == null ? 0 : Minecraft.getInstance().level.getGameTime();
    }

    private String setupSnapshot() {
        return "inventory=empty,inside=" + insidePosition + ",outside=" + outsidePosition
                + ",radius=" + PICKUP_RADIUS;
    }

    private String serverSnapshot(ServerPlayer player, ServerLevel level, UUID insideId) {
        return "berries=" + count(player, Items.SWEET_BERRIES)
                + ",inside=" + entitySnapshot(itemEntity(level, insideId))
                + ",uiClean=" + cleanUi(player);
    }

    private static String entitySnapshot(ItemEntity entity) {
        return entity == null ? "missing" : "{alive=" + entity.isAlive()
                + ",count=" + entity.getItem().getCount() + ",pos=" + entity.position() + "}";
    }

    private void publishSetupFailure(String state) {
        setupState = state;
        setupReady = true;
    }

    private String clientSnapshot() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return "player/level missing";
        return "berries=" + count(client.player, Items.SWEET_BERRIES)
                + ",first=" + entitySnapshot(itemEntity(client.level, firstDropId))
                + ",second=" + entitySnapshot(itemEntity(client.level, secondDropId))
                + ",outside=" + entitySnapshot(itemEntity(client.level, outsideDropId))
                + ",uiClean=" + cleanUi(client.player);
    }

    private void finishSuccessfully() {
        phase = Phase.DONE;
        append.accept("ASSERT\tbounded pickup collected both in-range berries while preserving the tracked out-of-range berry");
        append.accept("FOOD_RANGE_ACCEPTANCE\tPASS\tCollectFoodTask private helper and bounded pickup wrapper kept drops inside its radius");
        success.run();
    }

    private void fail(String reason) {
        if (phase == Phase.FAILED || phase == Phase.DONE) return;
        phase = Phase.FAILED;
        mod.cancelUserTask();
        failure.accept(reason);
    }
}
