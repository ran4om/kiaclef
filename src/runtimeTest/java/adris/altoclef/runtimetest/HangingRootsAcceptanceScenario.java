package adris.altoclef.runtimetest;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.resources.ShearAndCollectBlockTask;
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
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/** Exercises the vanilla shears-only hanging-roots drop through the normal @get command path. */
public final class HangingRootsAcceptanceScenario {
    private enum Phase { NEW, PREPARING, WAIT_SYNC, COLLECT, VERIFY, DONE, FAILED }

    private static final int SETUP_TIMEOUT_TICKS = 1200;
    private static final int TASK_TIMEOUT_TICKS = 6000;
    private static final int VERIFY_TIMEOUT_TICKS = 300;
    private static final int POLL_INTERVAL_TICKS = 5;

    private final AltoClef mod;
    private final Consumer<String> append;
    private final Consumer<String> failure;
    private final Runnable success;
    private volatile Phase phase = Phase.NEW;
    private volatile boolean setupReady;
    private volatile String setupState = "not-started";
    private volatile String commandCompletion;
    private volatile String commandFailure;
    private volatile boolean pollOutstanding;
    private volatile boolean pollReady;
    private volatile String pollState = "not-polled";
    private volatile int serverRoots;
    private volatile int serverShearsDamage;
    private volatile boolean serverRootAir;
    private volatile boolean serverAnchorIntact;
    private volatile int serverArenaDrops;
    private volatile boolean serverCleanUi;
    private volatile boolean serverPlayerAlive;
    private volatile float serverHealth;
    private volatile int serverFood;
    private volatile List<ItemStack> serverInventory = List.of();
    private boolean sawShearCollector;
    private long phaseStarted;
    private long nextPollAt;
    private UUID playerId;
    private BlockPos rootsPos;
    private BlockPos anchorPos;
    private AABB fixtureBounds;

    public HangingRootsAcceptanceScenario(AltoClef mod, Consumer<String> append,
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
                if (!"ok".equals(setupState)) fail("Hanging-roots fixture setup failed: " + setupState);
                else {
                    append.accept("HANGING_ROOTS_SETUP\tok\t" + setupSnapshot());
                    phase = Phase.WAIT_SYNC;
                    phaseStarted = clientTick();
                }
            } else if (timedOut(SETUP_TIMEOUT_TICKS)) fail("Timed out preparing hanging-roots fixture: " + setupState);
            return;
        }
        if (commandFailure != null) {
            fail("Hanging-roots command failed: " + commandFailure);
            return;
        }
        if (phase == Phase.WAIT_SYNC) {
            if (fixtureSynchronized()) startGet();
            else if (timedOut(VERIFY_TIMEOUT_TICKS)) fail("Hanging-roots fixture did not synchronize: " + clientSnapshot());
            return;
        }
        if (phase == Phase.COLLECT) {
            traceCollector();
            if ("hanging-roots".equals(commandCompletion)) {
                commandCompletion = null;
                phase = Phase.VERIFY;
                phaseStarted = clientTick();
                nextPollAt = phaseStarted;
                requestPoll();
            } else if (timedOut(TASK_TIMEOUT_TICKS)) {
                fail("Timed out collecting hanging_roots 1; tasks=" + mod.getUserTaskChain().getTasks());
            }
            return;
        }
        if (phase == Phase.VERIFY) {
            if (pollReady) {
                pollReady = false;
                append.accept("HANGING_ROOTS_FINAL_POLL\t" + pollState);
                if (finalStateIsValid() && clientMatchesServer()) finish();
                else if (timedOut(VERIFY_TIMEOUT_TICKS)) {
                    fail("Hanging-roots final state mismatch: server=" + pollState + ", client=" + clientSnapshot());
                } else nextPollAt = clientTick() + POLL_INTERVAL_TICKS;
            } else if (timedOut(VERIFY_TIMEOUT_TICKS)) {
                fail("Timed out verifying hanging-roots collection: " + pollState);
            } else if (!pollOutstanding && clientTick() >= nextPollAt) requestPoll();
        }
    }

    private void beginSetup() {
        Minecraft client = Minecraft.getInstance();
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null || client.player == null || client.level == null) {
            fail("Hanging-roots acceptance requires an active integrated world");
            return;
        }
        if (!TaskCatalogue.taskExists("hanging_roots")) {
            fail("Hanging-roots catalogue entry is missing");
            return;
        }
        playerId = client.player.getUUID();
        mod.cancelUserTask();
        phase = Phase.PREPARING;
        phaseStarted = clientTick();
        server.execute(() -> prepareFixture(server));
        append.accept("HANGING_ROOTS_SETUP\tqueued\tclear inventory; seed one hanging_roots block and shears; command=@get hanging_roots 1");
    }

    private void prepareFixture(MinecraftServer server) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) { publishSetup("player=null"); return; }
        ServerLevel level = (ServerLevel) player.level();
        BlockPos base = player.blockPosition();
        int floorY = base.getY() - 1;
        fixtureBounds = new AABB(base.getX() - 8, floorY, base.getZ() - 8,
                base.getX() + 9, floorY + 8, base.getZ() + 9);
        rootsPos = new BlockPos(base.getX() + 2, floorY + 3, base.getZ() + 1);
        anchorPos = rootsPos.above();
        player.closeContainer();
        player.getInventory().clearContent();
        for (int slot = 1; slot <= 4; slot++) player.inventoryMenu.getSlot(slot).set(ItemStack.EMPTY);
        player.inventoryMenu.setCarried(ItemStack.EMPTY);
        player.getInventory().setItem(0, new ItemStack(Items.SHEARS));
        player.getInventory().setSelectedSlot(0);
        player.setGameMode(GameType.SURVIVAL);
        player.setHealth(player.getMaxHealth());
        player.getFoodData().setFoodLevel(20);
        player.getFoodData().setSaturation(5.0f);
        for (int x = base.getX() - 8; x <= base.getX() + 8; x++) {
            for (int z = base.getZ() - 8; z <= base.getZ() + 8; z++) {
                BlockPos floor = new BlockPos(x, floorY, z);
                level.getChunkAt(floor);
                level.setBlock(floor, Blocks.STONE.defaultBlockState(), 3);
                for (int y = floorY + 1; y <= floorY + 6; y++) {
                    level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, fixtureBounds)) item.discard();
        level.setBlock(anchorPos, Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(rootsPos, Blocks.HANGING_ROOTS.defaultBlockState(), 3);
        boolean valid = player.getInventory().getItem(0).is(Items.SHEARS)
                && count(player, Items.HANGING_ROOTS) == 0
                && level.getBlockState(rootsPos).is(Blocks.HANGING_ROOTS)
                && level.getBlockState(anchorPos).is(Blocks.STONE)
                && inventoryCount(player) == 1 && droppedItemCount(level, fixtureBounds) == 0
                && cleanUi(player) && player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL;
        player.inventoryMenu.broadcastChanges();
        publishSetup(valid ? "ok" : "invalid:" + snapshot(player, level));
    }

    private void startGet() {
        phase = Phase.COLLECT;
        phaseStarted = clientTick();
        var previous = mod.getUserTaskChain().getLastCompletionSnapshot();
        String command = mod.getModSettings().getCommandPrefix() + "get hanging_roots 1";
        append.accept("COMMAND\t" + command + "\tshearsSeeded=1\thangingRootsSeeded=0");
        try {
            AltoClef.getCommandExecutor().execute(command, () -> {
                var completion = mod.getUserTaskChain().getLastCompletionSnapshot();
                if (completion == null || completion == previous) commandFailure = "callback had no new completion snapshot";
                else if (completion.failure() != null || completion.cancelled()) {
                    commandFailure = completion.failure() == null ? "cancelled" : completion.failure().reason();
                } else commandCompletion = "hanging-roots";
            }, error -> commandFailure = command + ": " + error.getMessage());
        } catch (Throwable error) { commandFailure = "could not execute " + command + ": " + error; }
    }

    private boolean fixtureSynchronized() {
        Minecraft client = Minecraft.getInstance();
        return client.level != null && client.player != null
                && client.level.getBlockState(rootsPos).is(Blocks.HANGING_ROOTS)
                && client.level.getBlockState(anchorPos).is(Blocks.STONE)
                && client.player.getInventory().getItem(0).is(Items.SHEARS)
                && cleanUi(client.player);
    }

    private boolean finalStateIsValid() {
        Minecraft client = Minecraft.getInstance();
        return serverRoots == 1 && serverShearsDamage == 1 && serverPlayerAlive && serverHealth > 0
                && serverFood > 0 && serverRootAir && serverAnchorIntact && serverArenaDrops == 0
                && inventoryCount(serverInventory) == 2 && validCollectedInventory(serverInventory)
                && serverCleanUi && sawShearCollector && client.player != null && client.player.isAlive()
                && client.player.getHealth() > 0 && client.gameMode != null
                && client.gameMode.getPlayerMode() == GameType.SURVIVAL
                && client.player.getHealth() == serverHealth
                && client.player.getFoodData().getFoodLevel() == serverFood
                && count(client.player, Items.HANGING_ROOTS) == 1
                && inventoryCount(client.player) == 2 && expectedDamagedShearsMatches(client.player)
                && cleanUi(client.player) && client.level.getBlockState(rootsPos).isAir()
                && client.level.getBlockState(anchorPos).is(Blocks.STONE);
    }

    private boolean clientMatchesServer() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null || !client.player.isAlive()
                || client.player.getHealth() != serverHealth || client.player.getFoodData().getFoodLevel() != serverFood
                || client.gameMode == null || client.gameMode.getPlayerMode() != GameType.SURVIVAL
                || !expectedDamagedShearsMatches(client.player)) return false;
        for (int slot = 0; slot < serverInventory.size(); slot++) {
            if (!ItemStack.matches(serverInventory.get(slot), client.player.getInventory().getItem(slot))) return false;
        }
        return serverRoots == count(client.player, Items.HANGING_ROOTS)
                && client.level.getBlockState(rootsPos).isAir()
                && client.level.getBlockState(anchorPos).is(Blocks.STONE);
    }

    private void requestPoll() {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null || playerId == null) { fail("Integrated server disappeared during verification"); return; }
        pollOutstanding = true;
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null || !(player.level() instanceof ServerLevel level)) {
                pollState = "player/level missing";
            } else {
                serverRoots = count(player, Items.HANGING_ROOTS);
                serverShearsDamage = shearsDamage(player);
                serverHealth = player.getHealth();
                serverFood = player.getFoodData().getFoodLevel();
                serverPlayerAlive = player.isAlive() && player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL;
                serverRootAir = level.getBlockState(rootsPos).isAir();
                serverAnchorIntact = level.getBlockState(anchorPos).is(Blocks.STONE);
                serverArenaDrops = droppedItemCount(level, fixtureBounds);
                serverInventory = inventorySnapshot(player);
                serverCleanUi = cleanUi(player);
                pollState = snapshot(player, level);
            }
            pollReady = true;
            pollOutstanding = false;
        });
    }

    private void traceCollector() {
        if (sawShearCollector) return;
        if (mod.getUserTaskChain().getTasks().stream().anyMatch(task -> task instanceof ShearAndCollectBlockTask)) {
            sawShearCollector = true;
            append.accept("TASK_TRACE\tShearAndCollectBlockTask\tobserved during @get");
        }
    }

    private String setupSnapshot() {
        Minecraft client = Minecraft.getInstance();
        return client.player == null ? "player=null" : "inventory=" + inventorySnapshot(client.player)
                + ",rootsPos=" + rootsPos + ",anchorPos=" + anchorPos + ",cleanUI=" + cleanUi(client.player);
    }

    private String clientSnapshot() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return "player/level=null";
        return "inventory=" + inventorySnapshot(client.player) + ",roots=" + client.level.getBlockState(rootsPos)
                + ",anchor=" + client.level.getBlockState(anchorPos) + ",cleanUI=" + cleanUi(client.player);
    }

    private String snapshot(ServerPlayer player, ServerLevel level) {
        return "inventory=" + inventorySnapshot(player) + ",hangingRoots=" + count(player, Items.HANGING_ROOTS)
                + ",rootsState=" + level.getBlockState(rootsPos) + ",anchorState=" + level.getBlockState(anchorPos)
                + ",arenaDrops=" + droppedItemCount(level, fixtureBounds) + ",shearsDamage=" + shearsDamage(player)
                + ",alive=" + player.isAlive() + ",health=" + player.getHealth()
                + ",food=" + player.getFoodData().getFoodLevel() + ",cleanUI=" + cleanUi(player);
    }

    private static boolean cleanUi(Player player) {
        if (player.containerMenu != player.inventoryMenu || !player.containerMenu.getCarried().isEmpty()) return false;
        for (int slot = 1; slot <= 4; slot++) if (!player.inventoryMenu.getSlot(slot).getItem().isEmpty()) return false;
        return true;
    }

    private static boolean validCollectedInventory(List<ItemStack> stacks) {
        int roots = 0, shears = 0;
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            if (stack.is(Items.HANGING_ROOTS) && stack.getCount() == 1) roots++;
            else if (stack.is(Items.SHEARS) && stack.getCount() == 1 && stack.getDamageValue() == 1) shears++;
            else return false;
        }
        return roots == 1 && shears == 1;
    }

    private static int shearsDamage(Player player) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(Items.SHEARS)) return stack.getDamageValue();
        }
        return -1;
    }

    private static boolean expectedDamagedShearsMatches(Player player) {
        ItemStack expected = new ItemStack(Items.SHEARS);
        expected.setDamageValue(1);
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(Items.SHEARS)) return stack.getCount() == 1 && ItemStack.matches(expected, stack);
        }
        return false;
    }

    private static int droppedItemCount(ServerLevel level, AABB bounds) {
        return level.getEntitiesOfClass(ItemEntity.class, bounds).size();
    }

    private static List<ItemStack> inventorySnapshot(Player player) {
        List<ItemStack> result = new ArrayList<>(36);
        for (int slot = 0; slot < 36; slot++) result.add(player.getInventory().getItem(slot).copy());
        return List.copyOf(result);
    }

    private static int inventoryCount(Player player) {
        int total = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++)
            if (!player.getInventory().getItem(slot).isEmpty()) total++;
        return total;
    }

    private static int inventoryCount(List<ItemStack> stacks) {
        int total = 0;
        for (ItemStack stack : stacks) if (!stack.isEmpty()) total++;
        return total;
    }

    private static int count(Player player, Item item) {
        int total = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    private void publishSetup(String value) { setupState = value; setupReady = true; }
    private boolean timedOut(int ticks) { return clientTick() - phaseStarted > ticks; }
    private static long clientTick() { return Minecraft.getInstance().level == null ? 0 : Minecraft.getInstance().level.getGameTime(); }
    private void finish() {
        append.accept("ASSERT\texact hanging_roots drop; supplied shears at damage 1; collector traced; source AIR and anchor STONE; no arena drops; server/client inventory, survival health/food synchronized; UI clean");
        append.accept("HANGING_ROOTS_ACCEPTANCE\tPASS\tvanilla shears-only drop");
        phase = Phase.DONE;
        success.run();
    }
    private void fail(String reason) {
        phase = Phase.FAILED;
        append.accept("HANGING_ROOTS_ACCEPTANCE\tFAIL\t" + reason);
        failure.accept(reason);
    }
}
