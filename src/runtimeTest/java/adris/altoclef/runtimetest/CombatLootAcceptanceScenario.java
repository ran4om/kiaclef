package adris.altoclef.runtimetest;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.resources.KillAndLootTask;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Runtime acceptance for ordinary zombie combat and loot acquisition through normal @get.
 * The fixture supplies only an iron sword and one living zombie; all rotten flesh must come
 * from vanilla zombie loot. Run only in a disposable singleplayer world.
 */
public final class CombatLootAcceptanceScenario {
    private enum Phase { NEW, PREPARING, WAIT_CLIENT_SEED, GET_ROTTEN_FLESH, VERIFY, DONE, FAILED }

    private static final int TARGET_ROTTEN_FLESH = 1;
    private static final int MAX_ZOMBIES = 8;
    private static final int PHASE_TIMEOUT_TICKS = 12000;
    private static final int VERIFY_TIMEOUT_TICKS = 400;
    private static final int CLIENT_SYNC_TIMEOUT_TICKS = 300;
    private static final int POLL_INTERVAL_TICKS = 5;
    private static final int ARENA_RADIUS = 8;
    private static final int ROOF_HEIGHT = 4;

    private final AltoClef mod;
    private final Consumer<String> append;
    private final Consumer<String> failure;
    private final Runnable success;

    private volatile Phase phase = Phase.NEW;
    private long phaseStarted;
    private long nextPollAt;
    private UUID playerId;
    private BlockPos arenaOrigin;
    private AABB fixtureBounds;
    private volatile UUID targetZombieId;
    private final List<UUID> zombieIds = new ArrayList<>();
    private volatile boolean setupReady;
    private volatile String setupState = "not-started";
    private volatile String taskCompletion;
    private volatile String taskFailure;
    private volatile boolean pollOutstanding;
    private volatile boolean pollReady;
    private volatile String pollState = "not-polled";
    private volatile int serverRottenFlesh;
    private volatile int serverDroppedRottenFlesh;
    private volatile int serverZombieCount;
    private volatile int serverZombieKillCount;
    private volatile double serverTargetHealth;
    private volatile double initialTargetHealth;
    private volatile double lowestObservedTargetHealth = Double.POSITIVE_INFINITY;
    private volatile float serverPlayerHealth;
    private volatile int serverFoodLevel;
    private volatile int serverSwordDamage;
    private volatile int initialSwordDamage;
    private volatile List<ItemStack> serverInventory = List.of();
    private volatile String serverInventoryState = "not-polled";
    private volatile boolean serverUiClean;
    private volatile boolean serverPlayerAlive;
    private volatile boolean sawTarget;
    private volatile boolean sawTargetDamage;
    private volatile boolean sawTargetDeath;
    private volatile boolean sawKillAndLootTask;
    private volatile int deathCountedForCurrentTarget;

    public CombatLootAcceptanceScenario(AltoClef mod, Consumer<String> append,
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
                    fail("Combat/loot fixture setup failed: " + setupState);
                } else {
                    append.accept("COMBAT_LOOT_SETUP\tok\t" + setupSnapshot());
                    phase = Phase.WAIT_CLIENT_SEED;
                    phaseStarted = clientTickCount();
                }
            } else if (timedOut(PHASE_TIMEOUT_TICKS)) {
                fail("Timed out preparing combat/loot fixture: " + setupState);
            }
            return;
        }
        if (phase == Phase.WAIT_CLIENT_SEED) {
            if (clientFixtureIsSynchronized()) {
                append.accept("COMBAT_LOOT_CLIENT_SEED\tok\t" + clientSnapshot());
                startGetRottenFlesh();
            } else if (timedOut(CLIENT_SYNC_TIMEOUT_TICKS)) {
                fail("Timed out waiting for combat fixture sync: " + clientSnapshot());
            }
            return;
        }

        observeTaskTree();
        if (taskFailure != null) {
            fail("@get rotten_flesh failed: " + taskFailure);
            return;
        }
        if (phase == Phase.GET_ROTTEN_FLESH) {
            if (taskCompletion != null) {
                taskCompletion = null;
                phase = Phase.VERIFY;
                phaseStarted = clientTickCount();
                nextPollAt = phaseStarted;
                requestServerPoll();
                return;
            }
            if (!pollOutstanding && clientTickCount() >= nextPollAt) requestServerPoll();
            if (timedOut(PHASE_TIMEOUT_TICKS)) {
                fail("Timed out during @get rotten_flesh " + TARGET_ROTTEN_FLESH
                        + " after " + zombieAttemptCount() + " zombie(s); tasks="
                        + mod.getUserTaskChain().getTasks());
            }
            return;
        }
        if (phase == Phase.VERIFY) {
            if (pollReady) {
                pollReady = false;
                append.accept("COMBAT_LOOT_SERVER_POLL\t" + pollState);
                if (serverStateIsValid() && clientMatchesServer()) {
                    finishSuccessfully();
                } else if (timedOut(VERIFY_TIMEOUT_TICKS)) {
                    fail("Combat/loot final state mismatch: server=" + pollState
                            + ", client=" + clientSnapshot());
                } else {
                    nextPollAt = clientTickCount() + POLL_INTERVAL_TICKS;
                }
            } else if (timedOut(VERIFY_TIMEOUT_TICKS)) {
                fail("Timed out verifying combat/loot result: " + pollState
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
            fail("Combat/loot acceptance requires an active integrated server and client player");
            return;
        }
        playerId = client.player.getUUID();
        phase = Phase.PREPARING;
        phaseStarted = clientTickCount();
        server.execute(() -> prepareServerFixture(server));
        append.accept("COMBAT_LOOT_SETUP\tqueued\tSurvival/Easy; supplied iron sword; no mob drops or rotten flesh seeded; max zombie attempts=" + MAX_ZOMBIES);
    }

    private void prepareServerFixture(MinecraftServer server) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) {
            publishSetupFailure("player=null");
            return;
        }
        ServerLevel level = (ServerLevel) player.level();
        BlockPos center = player.blockPosition();
        arenaOrigin = center.immutable();
        fixtureBounds = new AABB(center.getX() - ARENA_RADIUS - 2, center.getY() - 2,
                center.getZ() - ARENA_RADIUS - 2, center.getX() + ARENA_RADIUS + 3,
                center.getY() + ROOF_HEIGHT + 3, center.getZ() + ARENA_RADIUS + 3);

        player.closeContainer();
        player.getInventory().clearContent();
        for (int slot = 1; slot <= 4; slot++) player.inventoryMenu.getSlot(slot).set(ItemStack.EMPTY);
        player.inventoryMenu.setCarried(ItemStack.EMPTY);
        player.getInventory().setItem(0, new ItemStack(Items.IRON_SWORD));
        player.getInventory().setSelectedSlot(0);
        player.inventoryMenu.broadcastChanges();
        player.setGameMode(GameType.SURVIVAL);
        player.setHealth(player.getMaxHealth());
        player.getFoodData().setFoodLevel(20);
        player.getFoodData().setSaturation(5.0f);
        server.setDifficulty(Difficulty.EASY, true);

        int floorY = center.getY() - 1;
        for (int x = center.getX() - ARENA_RADIUS; x <= center.getX() + ARENA_RADIUS; x++) {
            for (int z = center.getZ() - ARENA_RADIUS; z <= center.getZ() + ARENA_RADIUS; z++) {
                BlockPos floor = new BlockPos(x, floorY, z);
                level.getChunkAt(floor);
                level.setBlock(floor, Blocks.STONE.defaultBlockState(), 3);
                for (int y = center.getY(); y < center.getY() + ROOF_HEIGHT; y++) {
                    level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
                }
                level.setBlock(new BlockPos(x, center.getY() + ROOF_HEIGHT, z),
                        Blocks.STONE.defaultBlockState(), 3);
                if (Math.floorMod(x - center.getX(), 4) == 0
                        && Math.floorMod(z - center.getZ(), 4) == 0) {
                    level.setBlock(new BlockPos(x, center.getY() + ROOF_HEIGHT - 1, z),
                            Blocks.GLOWSTONE.defaultBlockState(), 3);
                }
            }
        }

        for (Monster monster : level.getEntitiesOfClass(Monster.class, fixtureBounds)) monster.discard();
        for (ItemEntity drop : level.getEntitiesOfClass(ItemEntity.class, fixtureBounds)) drop.discard();

        zombieIds.clear();
        targetZombieId = spawnZombie(level, center.offset(4, 0, 1));
        initialTargetHealth = targetZombieId != null && level.getEntity(targetZombieId) instanceof Zombie zombie
                ? zombie.getHealth() : 0.0;
        initialSwordDamage = player.getInventory().getItem(0).getDamageValue();
        serverZombieCount = targetZombieId == null ? 0 : 1;
        boolean valid = targetZombieId != null
                && player.getInventory().getItem(0).is(Items.IRON_SWORD)
                && player.getInventory().getItem(0).getDamageValue() == 0
                && count(player, Items.ROTTEN_FLESH) == 0
                && droppedCount(level, Items.ROTTEN_FLESH, fixtureBounds) == 0
                && inventoryCount(player) == 1
                && uiClean(player)
                && player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL
                && server.getWorldData().getDifficulty() == Difficulty.EASY
                && player.getHealth() == player.getMaxHealth()
                && player.getFoodData().getFoodLevel() == 20
                && targetIsFullHealth(level);
        setupState = valid ? "ok" : "invalid:" + setupSnapshotServer(player, level);
        setupReady = true;
    }

    private UUID spawnZombie(ServerLevel level, BlockPos spawnPos) {
        Zombie zombie = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
        if (zombie == null) return null;
        zombie.snapTo(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5, 180.0f, 0.0f);
        zombie.setPersistenceRequired();
        if (!level.addFreshEntity(zombie)) return null;
        UUID id = zombie.getUUID();
        synchronized (zombieIds) {
            zombieIds.add(id);
        }
        return id;
    }

    private boolean targetIsFullHealth(ServerLevel level) {
        Zombie target = targetZombieId == null ? null : level.getEntity(targetZombieId) instanceof Zombie found ? found : null;
        return target != null && target.isAlive() && target.getHealth() == target.getMaxHealth();
    }

    private void startGetRottenFlesh() {
        var previous = mod.getUserTaskChain().getLastCompletionSnapshot();
        phase = Phase.GET_ROTTEN_FLESH;
        phaseStarted = clientTickCount();
        nextPollAt = phaseStarted;
        String command = mod.getModSettings().getCommandPrefix() + "get rotten_flesh " + TARGET_ROTTEN_FLESH;
        append.accept("COMMAND\t" + command + "\tseeded=iron_sword\tseededOutput=none");
        try {
            AltoClef.getCommandExecutor().execute(command, () -> {
                var completion = mod.getUserTaskChain().getLastCompletionSnapshot();
                if (completion == null || completion == previous) {
                    taskFailure = "command callback had no new completion snapshot";
                } else if (completion.failure() != null || completion.cancelled()) {
                    taskFailure = completion.failure() == null ? "cancelled" : completion.failure().reason();
                } else {
                    taskCompletion = "get-rotten-flesh";
                }
            }, error -> taskFailure = error.getMessage());
        } catch (Throwable error) {
            taskFailure = "could not execute " + command + ": " + error;
        }
    }

    private void observeTaskTree() {
        if (phase != Phase.GET_ROTTEN_FLESH) return;
        var task = mod.getUserTaskChain().getCurrentTask();
        if (task != null) sawKillAndLootTask |= task.thisOrChildSatisfies(child -> child instanceof KillAndLootTask);
    }

    private void requestServerPoll() {
        Minecraft client = Minecraft.getInstance();
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null || pollOutstanding) return;
        pollOutstanding = true;
        pollReady = false;
        nextPollAt = clientTickCount() + POLL_INTERVAL_TICKS;
        UUID checkingPlayer = playerId;
        AABB bounds = fixtureBounds;
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(checkingPlayer);
            ServerLevel level = player == null ? null : (ServerLevel) player.level();
            if (player != null && level != null) {
                observeTarget(level);
                serverRottenFlesh = count(player, Items.ROTTEN_FLESH);
                serverDroppedRottenFlesh = droppedCount(level, Items.ROTTEN_FLESH, bounds);
                serverZombieCount = zombieAttemptCount();
                serverZombieKillCount = killedZombieCount(level);
                serverSwordDamage = swordDamage(player);
                serverPlayerHealth = player.getHealth();
                serverFoodLevel = player.getFoodData().getFoodLevel();
                serverInventoryState = inventorySnapshot(player);
                List<ItemStack> stacks = new ArrayList<>();
                for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
                    stacks.add(player.getInventory().getItem(slot).copy());
                }
                serverInventory = List.copyOf(stacks);
                serverUiClean = uiClean(player);
                serverPlayerAlive = player.isAlive() && player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL;

                // Vanilla zombie drops are random. If this zombie died without rotten flesh,
                // spawn another one only after the death was observed, up to a bounded maximum.
                if (phase == Phase.GET_ROTTEN_FLESH && sawTargetDeath
                        && serverRottenFlesh == 0 && serverDroppedRottenFlesh == 0
                        && zombieAttemptCount() < MAX_ZOMBIES) {
                    UUID next = spawnZombie(level, nextZombiePosition(arenaOrigin, zombieAttemptCount()));
                    if (next != null) {
                        targetZombieId = next;
                        serverZombieCount = zombieAttemptCount();
                        sawTarget = false;
                        sawTargetDamage = false;
                        sawTargetDeath = false;
                        deathCountedForCurrentTarget = 0;
                    }
                }
                serverTargetHealth = currentTargetHealth(level);
                pollState = serverSnapshot(player, level, bounds);
            } else {
                pollState = "player/level missing";
            }
            pollOutstanding = false;
            pollReady = true;
        });
    }

    private void observeTarget(ServerLevel level) {
        UUID currentId = targetZombieId;
        if (currentId == null) return;
        if (level.getEntity(currentId) instanceof Zombie target) {
            sawTarget = true;
            lowestObservedTargetHealth = Math.min(lowestObservedTargetHealth, target.getHealth());
            if (target.getHealth() < target.getMaxHealth()) sawTargetDamage = true;
            if (target.isDeadOrDying()) markTargetDead();
        } else if (sawTarget && sawTargetDamage) {
            // Persistent fixture zombies should not despawn. If the death animation entity has
            // already been removed, its observed damage and disappearance establish the kill.
            markTargetDead();
        }
    }

    private void markTargetDead() {
        if (deathCountedForCurrentTarget != 0) return;
        deathCountedForCurrentTarget = 1;
        sawTargetDeath = true;
    }

    private int killedZombieCount(ServerLevel level) {
        int killed = 0;
        synchronized (zombieIds) {
            for (UUID id : zombieIds) {
                if (level.getEntity(id) == null) killed++;
                else if (level.getEntity(id) instanceof Zombie zombie && zombie.isDeadOrDying()) killed++;
            }
        }
        return killed;
    }

    private int zombieAttemptCount() {
        synchronized (zombieIds) {
            return zombieIds.size();
        }
    }

    private String zombieIdsSnapshot() {
        synchronized (zombieIds) {
            return zombieIds.toString();
        }
    }

    private static BlockPos nextZombiePosition(BlockPos center, int spawnIndex) {
        int[][] offsets = {{-4, 1}, {1, -4}, {4, -1}, {-1, 4}, {-5, -2}, {2, 5}, {5, 2}};
        int[] offset = offsets[Math.floorMod(spawnIndex - 1, offsets.length)];
        return center.offset(offset[0], 0, offset[1]);
    }

    private boolean serverStateIsValid() {
        return serverRottenFlesh >= TARGET_ROTTEN_FLESH
                && serverZombieKillCount >= 1
                && sawTarget && sawTargetDamage && sawTargetDeath
                && serverSwordDamage > initialSwordDamage
                && serverPlayerAlive && serverPlayerHealth > 0
                && serverUiClean && sawKillAndLootTask;
    }

    private boolean clientMatchesServer() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null || !uiClean(client.player)
                || !client.player.isAlive() || client.player.getHealth() <= 0
                || client.player.getHealth() != serverPlayerHealth
                || client.player.getFoodData().getFoodLevel() != serverFoodLevel
                || count(client.player, Items.ROTTEN_FLESH) != serverRottenFlesh
                || swordDamage(client.player) != serverSwordDamage
                || !targetIsDeadClientSide()) return false;
        if (client.player.getInventory().getContainerSize() != serverInventory.size()) return false;
        for (int slot = 0; slot < serverInventory.size(); slot++) {
            if (!ItemStack.matches(client.player.getInventory().getItem(slot), serverInventory.get(slot))) return false;
        }
        return true;
    }

    private boolean targetIsDeadClientSide() {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return false;
        synchronized (zombieIds) {
            for (UUID id : zombieIds) {
                if (client.level.getEntity(id) instanceof Zombie zombie && zombie.isAlive()) return false;
            }
            return !zombieIds.isEmpty();
        }
    }

    private boolean clientFixtureIsSynchronized() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null || !uiClean(client.player)
                || count(client.player, Items.ROTTEN_FLESH) != 0
                || inventoryCount(client.player) != 1
                || !client.player.getInventory().getItem(0).is(Items.IRON_SWORD)
                || swordDamage(client.player) != 0 || !client.player.isAlive()
                || client.player.getFoodData().getFoodLevel() != 20 || !client.level.getDifficulty().equals(Difficulty.EASY)) {
            return false;
        }
        return targetZombieId != null && client.level.getEntity(targetZombieId) instanceof Zombie zombie
                && zombie.isAlive() && zombie.getHealth() == zombie.getMaxHealth();
    }

    private double currentTargetHealth(ServerLevel level) {
        return targetZombieId != null && level.getEntity(targetZombieId) instanceof Zombie zombie
                ? zombie.getHealth() : 0.0;
    }

    private static int swordDamage(Player player) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(Items.IRON_SWORD)) return stack.getDamageValue();
        }
        return -1;
    }

    private static int count(Player player, Item item) {
        if (player == null) return 0;
        int total = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    private static int droppedCount(ServerLevel level, Item item, AABB bounds) {
        int total = 0;
        for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, bounds)) {
            if (entity.getItem().is(item)) total += entity.getItem().getCount();
        }
        return total;
    }

    private static int inventoryCount(Player player) {
        int total = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            total += player.getInventory().getItem(slot).getCount();
        }
        return total;
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
        return "difficulty=EASY,gameMode=SURVIVAL,player="
                + (client.player == null ? "missing" : inventorySnapshot(client.player))
                + ",target=" + targetZombieId + ",zombieIds=" + zombieIdsSnapshot()
                + ",zombieAttempts=" + zombieAttemptCount()
                + ",initialTargetHealth=" + initialTargetHealth
                + ",seededDrop=none,seededRottenFlesh=none,roofHeight=" + ROOF_HEIGHT;
    }

    private String setupSnapshotServer(ServerPlayer player, ServerLevel level) {
        return "inventory=" + inventorySnapshot(player) + ",rottenFlesh=" + count(player, Items.ROTTEN_FLESH)
                + ",dropRottenFlesh=" + droppedCount(level, Items.ROTTEN_FLESH, fixtureBounds)
                + ",zombie=" + targetZombieId + ",difficulty=" + level.getDifficulty()
                + ",mode=" + player.gameMode.getGameModeForPlayer() + ",health=" + player.getHealth()
                + ",food=" + player.getFoodData().getFoodLevel();
    }

    private String serverSnapshot(ServerPlayer player, ServerLevel level, AABB bounds) {
        return "rottenFlesh=" + serverRottenFlesh + ",droppedRottenFlesh=" + serverDroppedRottenFlesh
                + ",inventory=" + serverInventoryState + ",uiClean=" + serverUiClean
                + ",zombiesSpawned=" + serverZombieCount + ",zombiesKilled=" + serverZombieKillCount
                + ",zombieIds=" + zombieIdsSnapshot() + ",currentTarget=" + targetZombieId
                + ",targetHealth=" + serverTargetHealth
                + ",initialTargetHealth=" + initialTargetHealth
                + ",lowestObservedTargetHealth=" + lowestObservedTargetHealth
                + ",targetDamageSeen=" + sawTargetDamage + ",targetDeathSeen=" + sawTargetDeath
                + ",ironSwordDamage=" + serverSwordDamage + ",playerAlive=" + serverPlayerAlive
                + ",playerHealth=" + serverPlayerHealth + ",food=" + serverFoodLevel
                + ",killAndLootTaskSeen=" + sawKillAndLootTask
                + ",otherHostiles=" + level.getEntitiesOfClass(Monster.class, bounds).size();
    }

    private String clientSnapshot() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return "player missing";
        return "rottenFlesh=" + count(client.player, Items.ROTTEN_FLESH)
                + ",inventory=" + inventorySnapshot(client.player)
                + ",uiClean=" + uiClean(client.player)
                + ",currentTarget=" + targetZombieId
                + ",targetAlive=" + (client.level != null && targetZombieId != null
                    && client.level.getEntity(targetZombieId) instanceof Zombie zombie && zombie.isAlive())
                + ",ironSwordDamage=" + swordDamage(client.player)
                + ",playerAlive=" + client.player.isAlive() + ",health=" + client.player.getHealth()
                + ",food=" + client.player.getFoodData().getFoodLevel();
    }

    private void finishSuccessfully() {
        phase = Phase.DONE;
        append.accept("ASSERT\t@get rotten_flesh " + TARGET_ROTTEN_FLESH
                + " killed at least one live zombie and acquired real vanilla loot; zombie attempts=" + zombieAttemptCount()
                + ", killed=" + serverZombieKillCount + ", targetDamageSeen=" + sawTargetDamage
                + ", targetDeathSeen=" + sawTargetDeath + ", ironSwordDamage=" + serverSwordDamage
                + ", rottenFlesh=" + serverRottenFlesh);
        append.accept("ASSERT\tclient/server final inventory snapshots including item components match; Survival/Easy, player health/food recorded, alive, and UI clean");
        append.accept("COMBAT_LOOT_ACCEPTANCE\tPASS\tprepared roofed zombie arena; iron sword supplied; no mob drops or rotten flesh seeded");
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
