package adris.altoclef.runtimetest;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.container.SmeltInFurnaceTask;
import adris.altoclef.tasks.resources.KillAndLootTask;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FurnaceBlock;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Prepared-world acceptance for catalogued cooked beef gathering. The fixture spawns adult
 * cows and provides a reachable furnace plus basic non-output prerequisites. The player starts
 * with no beef or cooked beef; the normal @get command must acquire raw beef and smelt it.
 * Run only in a disposable singleplayer world.
 */
public final class AnimalFoodAcceptanceScenario {
    private enum Phase { NEW, PREPARING, WAIT_CLIENT_SEED, GET_COOKED_BEEF, VERIFY, DONE, FAILED }

    private static final int TARGET_COOKED_BEEF = 2;
    private static final int FIXTURE_COWS = 4;
    private static final int PHASE_TIMEOUT_TICKS = 6000;
    private static final int VERIFICATION_TIMEOUT_TICKS = 300;
    private static final int CLIENT_SYNC_TIMEOUT_TICKS = 200;
    private static final int SERVER_POLL_INTERVAL_TICKS = 5;

    private final AltoClef mod;
    private final Consumer<String> append;
    private final Consumer<String> failure;
    private final Runnable success;

    private volatile Phase phase = Phase.NEW;
    private long phaseStarted;
    private UUID playerId;
    private BlockPos furnacePos;
    private final List<UUID> cowIds = new ArrayList<>();
    private volatile boolean setupReady;
    private volatile String setupState = "not-started";
    private volatile String taskCompletion;
    private volatile String taskFailure;
    private volatile boolean pollOutstanding;
    private volatile boolean pollReady;
    private volatile boolean pollPassed;
    private volatile String pollState = "not-polled";
    private volatile int serverCookedBeef;
    private volatile int serverRawBeef;
    private volatile int serverFoodLevel;
    private volatile float serverHealth;
    private volatile String serverInventoryState = "not-polled";
    private volatile boolean sawRawAcquisitionTask;
    private volatile boolean sawSmeltingTask;
    private volatile int peakObservedRawBeef;
    private long nextPollAt;

    public AnimalFoodAcceptanceScenario(AltoClef mod, Consumer<String> append,
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
                    fail("Animal food fixture setup failed: " + setupState);
                } else {
                    append.accept("ANIMAL_FOOD_SETUP\tok\t" + setupSnapshot());
                    phase = Phase.WAIT_CLIENT_SEED;
                    phaseStarted = clientTickCount();
                }
            } else if (timedOut()) {
                fail("Timed out preparing animal food fixture: " + setupState);
            }
            return;
        }
        if (phase == Phase.WAIT_CLIENT_SEED) {
            if (clientFixtureIsSynchronized()) {
                append.accept("ANIMAL_FOOD_CLIENT_SEED\tok\t" + clientSnapshot());
                startGetCookedBeef();
            } else if (clientTickCount() - phaseStarted > CLIENT_SYNC_TIMEOUT_TICKS) {
                fail("Timed out waiting for client fixture sync: " + clientSnapshot()
                        + ", fixtureCowsVisible=" + visibleFixtureCowCount());
            }
            return;
        }

        observeTaskTree();
        if (taskFailure != null) {
            fail("@get cooked_beef failed: " + taskFailure);
            return;
        }
        if (taskCompletion != null && phase == Phase.GET_COOKED_BEEF) {
            String completion = taskCompletion;
            taskCompletion = null;
            if (!"get-cooked-beef".equals(completion)) {
                fail("Unexpected user-task completion: " + completion);
                return;
            }
            phase = Phase.VERIFY;
            phaseStarted = clientTickCount();
            nextPollAt = phaseStarted;
            requestPoll();
            return;
        }

        if (phase == Phase.VERIFY) {
            if (pollReady) {
                pollReady = false;
                append.accept("ANIMAL_FOOD_SERVER_POLL\t" + pollState);
                if (pollPassed && clientMatchesExpected()) {
                    finishSuccessfully();
                } else if (timedOut()) {
                    fail("Cooked beef state mismatch: server=" + pollState + ", client=" + clientSnapshot());
                } else {
                    nextPollAt = clientTickCount() + SERVER_POLL_INTERVAL_TICKS;
                }
            } else if (timedOut()) {
                fail("Timed out verifying cooked beef: " + pollState + ", client=" + clientSnapshot());
            } else if (!pollOutstanding && clientTickCount() >= nextPollAt) {
                requestPoll();
            }
            return;
        }

        if (timedOut()) fail("Timed out during " + phase + "; active task="
                + (mod.getUserTaskChain().isActive()
                ? String.valueOf(mod.getUserTaskChain().getLastCompletionSnapshot()) : "none"));
    }

    private void beginSetup() {
        Minecraft client = Minecraft.getInstance();
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null || client.player == null || client.level == null) {
            fail("Animal food acceptance requires an active integrated server and client player");
            return;
        }
        playerId = client.player.getUUID();
        phase = Phase.PREPARING;
        phaseStarted = clientTickCount();
        server.execute(() -> prepareServerFixture(server));
        append.accept("ANIMAL_FOOD_SETUP\tqueued\tprepared cows plus furnace; no raw or cooked beef in inventory");
    }

    private void prepareServerFixture(MinecraftServer server) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) {
            publishSetupFailure("player=null");
            return;
        }
        ServerLevel level = (ServerLevel) player.level();
        BlockPos[] fixture = findFixture(level, player.blockPosition());
        if (fixture == null) {
            publishSetupFailure("no reachable supported furnace position and " + FIXTURE_COWS
                    + " clear cow spawn cells within 10 blocks");
            return;
        }
        furnacePos = fixture[0].immutable();
        List<BlockPos> cowPositions = List.of(fixture).subList(1, fixture.length);

        player.closeContainer();
        player.getInventory().clearContent();
        for (int slot = 1; slot <= 4; slot++) player.inventoryMenu.getSlot(slot).set(ItemStack.EMPTY);
        player.inventoryMenu.setCarried(ItemStack.EMPTY);
        player.getInventory().setItem(0, new ItemStack(Items.STONE_SWORD));
        player.getInventory().setItem(1, new ItemStack(Items.COAL));
        player.getInventory().setSelectedSlot(0);
        player.inventoryMenu.broadcastChanges();
        player.setGameMode(GameType.SURVIVAL);
        player.setHealth(player.getMaxHealth());
        player.getFoodData().setFoodLevel(20);
        player.getFoodData().setSaturation(5.0f);

        level.setBlock(furnacePos, Blocks.FURNACE.defaultBlockState()
                .setValue(FurnaceBlock.FACING, Direction.NORTH), 3);
        FurnaceBlockEntity furnace = level.getBlockEntity(furnacePos) instanceof FurnaceBlockEntity found
                ? found : null;
        if (furnace != null) {
            furnace.setItem(0, ItemStack.EMPTY);
            furnace.setItem(1, ItemStack.EMPTY);
            furnace.setItem(2, ItemStack.EMPTY);
            furnace.setChanged();
        }

        AABB bounds = fixtureBounds(furnacePos, cowPositions);
        for (ItemEntity drop : level.getEntitiesOfClass(ItemEntity.class, bounds)) drop.discard();
        for (Cow existingCow : level.getEntitiesOfClass(Cow.class, bounds)) existingCow.discard();

        int spawned = 0;
        cowIds.clear();
        for (BlockPos pos : cowPositions) {
            Cow cow = EntityTypes.COW.create(level, EntitySpawnReason.COMMAND);
            if (cow == null) continue;
            cow.snapTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0.0f, 0.0f);
            cow.setNoAi(true);
            cow.setPersistenceRequired();
            if (!cow.isBaby() && level.addFreshEntity(cow)) {
                cowIds.add(cow.getUUID());
                spawned++;
            }
        }

        boolean valid = spawned == FIXTURE_COWS
                && furnace != null
                && player.containerMenu == player.inventoryMenu
                && player.containerMenu.getCarried().isEmpty()
                && emptyCraftingGrid(player)
                && count(player, Items.BEEF) == 0
                && count(player, Items.COOKED_BEEF) == 0
                && count(player, Items.COAL) == 1
                && count(player, Items.STONE_SWORD) == 1
                && player.getFoodData().getFoodLevel() == 20
                && player.getHealth() == player.getMaxHealth()
                && inventoryCount(player) == 2
                && countCows(level, bounds) == FIXTURE_COWS;
        setupState = valid ? "ok" : "invalid:" + serverSnapshot(player, level);
        setupReady = true;
    }

    private static BlockPos[] findFixture(ServerLevel level, BlockPos playerPos) {
        for (int radius = 3; radius <= 10; radius++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    BlockPos furnace = playerPos.offset(dx, 0, dz);
                    if (!validFixtureCell(level, furnace)) continue;
                    List<BlockPos> cows = new ArrayList<>(FIXTURE_COWS);
                    for (int innerRadius = 2; innerRadius <= 7 && cows.size() < FIXTURE_COWS; innerRadius++) {
                        for (int cowDz = -innerRadius; cowDz <= innerRadius && cows.size() < FIXTURE_COWS; cowDz++) {
                            for (int cowDx = -innerRadius; cowDx <= innerRadius && cows.size() < FIXTURE_COWS; cowDx++) {
                                if (Math.max(Math.abs(cowDx), Math.abs(cowDz)) != innerRadius) continue;
                                BlockPos candidate = playerPos.offset(cowDx, 0, cowDz);
                                if (candidate.distSqr(playerPos) < 9 || candidate.distSqr(furnace) < 4) continue;
                                if (validFixtureCell(level, candidate) && cows.stream().noneMatch(pos -> pos.distSqr(candidate) < 4)) {
                                    cows.add(candidate);
                                }
                            }
                        }
                    }
                    if (cows.size() == FIXTURE_COWS) {
                        BlockPos[] result = new BlockPos[FIXTURE_COWS + 1];
                        result[0] = furnace;
                        for (int i = 0; i < cows.size(); i++) result[i + 1] = cows.get(i);
                        return result;
                    }
                }
            }
        }
        return null;
    }

    private static boolean validFixtureCell(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos.below()).isRedstoneConductor(level, pos.below())
                && level.getBlockState(pos).isAir()
                && level.getBlockState(pos.above()).isAir();
    }

    private void startGetCookedBeef() {
        var previous = mod.getUserTaskChain().getLastCompletionSnapshot();
        phase = Phase.GET_COOKED_BEEF;
        phaseStarted = clientTickCount();
        String command = mod.getModSettings().getCommandPrefix() + "get cooked_beef " + TARGET_COOKED_BEEF;
        try {
            AltoClef.getCommandExecutor().execute(command, () -> {
                var completion = mod.getUserTaskChain().getLastCompletionSnapshot();
                if (completion == null || completion == previous) {
                    taskFailure = "command callback had no new completion snapshot";
                } else if (completion.failure() != null || completion.cancelled()) {
                    taskFailure = completion.failure() == null ? "cancelled" : completion.failure().reason();
                } else {
                    taskCompletion = "get-cooked-beef";
                }
            }, error -> taskFailure = error.getMessage());
        } catch (Throwable error) {
            taskFailure = "could not execute " + command + ": " + error;
        }
        append.accept("ANIMAL_FOOD_COMMAND\t" + command + "\tcows=" + FIXTURE_COWS
                + "\tfurnace=" + furnacePos + "\tseeded=stone_sword,coal\tseededOutput=none");
    }

    private void observeTaskTree() {
        var task = mod.getUserTaskChain().getCurrentTask();
        if (task == null) return;
        if (task.thisOrChildSatisfies(child -> child instanceof KillAndLootTask)) sawRawAcquisitionTask = true;
        if (task.thisOrChildSatisfies(child -> child instanceof SmeltInFurnaceTask)) sawSmeltingTask = true;
    }

    private void requestPoll() {
        Minecraft client = Minecraft.getInstance();
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null || pollOutstanding) {
            fail("Integrated server unavailable while verifying cooked beef");
            return;
        }
        UUID checkingPlayer = playerId;
        BlockPos checkingFurnace = furnacePos;
        pollOutstanding = true;
        pollReady = false;
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(checkingPlayer);
            ServerLevel level = player == null ? null : (ServerLevel) player.level();
            serverCookedBeef = player == null ? 0 : count(player, Items.COOKED_BEEF);
            serverRawBeef = player == null ? 0 : count(player, Items.BEEF);
            serverFoodLevel = player == null ? 0 : player.getFoodData().getFoodLevel();
            serverHealth = player == null ? 0 : player.getHealth();
            serverInventoryState = player == null ? "player missing" : inventorySnapshot(player);
            peakObservedRawBeef = Math.max(peakObservedRawBeef, serverRawBeef);
            pollPassed = player != null && level != null && expectedServerState(player, level)
                    && sawRawAcquisitionTask && sawSmeltingTask;
            pollState = player == null || level == null ? "player/level missing"
                    : serverSnapshot(player, level, checkingFurnace) + ",valid=" + pollPassed;
            pollOutstanding = false;
            pollReady = true;
        });
    }

    private boolean expectedServerState(ServerPlayer player, ServerLevel level) {
        return uiClean(player)
                && player.isAlive()
                && player.getHealth() > 0
                && player.getFoodData().getFoodLevel() == 20
                && count(player, Items.COOKED_BEEF) == TARGET_COOKED_BEEF
                && count(player, Items.COAL) == 0
                && count(player, Items.STONE_SWORD) == 1
                && furnaceOutputEmpty(level)
                && atLeastOneFixtureCowHarvested(level);
    }

    private boolean clientMatchesExpected() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null || !uiClean(client.player)
                || !client.player.isAlive() || client.player.getHealth() <= 0
                || client.player.getFoodData().getFoodLevel() != 20
                || client.player.getFoodData().getFoodLevel() != serverFoodLevel
                || client.player.getHealth() != serverHealth
                || count(client.player, Items.COOKED_BEEF) != serverCookedBeef
                || count(client.player, Items.BEEF) != serverRawBeef
                || count(client.player, Items.COOKED_BEEF) != TARGET_COOKED_BEEF
                || count(client.player, Items.COAL) != 0
                || count(client.player, Items.STONE_SWORD) != 1
                || !inventorySnapshot(client.player).equals(serverInventoryState)
                || client.player.containerMenu != client.player.inventoryMenu) {
            return false;
        }
        BlockState state = client.level.getBlockState(furnacePos);
        return state.is(Blocks.FURNACE);
    }

    private boolean clientFixtureIsSynchronized() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null || !uiClean(client.player)
                || count(client.player, Items.BEEF) != 0
                || count(client.player, Items.COOKED_BEEF) != 0
                || count(client.player, Items.COAL) != 1
                || count(client.player, Items.STONE_SWORD) != 1
                || inventoryCount(client.player) != 2
                || !client.level.getBlockState(furnacePos).is(Blocks.FURNACE)) {
            return false;
        }
        return visibleFixtureCowCount() == FIXTURE_COWS;
    }

    private int visibleFixtureCowCount() {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return 0;
        int visible = 0;
        for (UUID cowId : cowIds) {
            if (client.level.getEntity(cowId) instanceof Cow cow && !cow.isBaby()) visible++;
        }
        return visible;
    }

    private boolean atLeastOneFixtureCowHarvested(ServerLevel level) {
        int remaining = 0;
        for (UUID cowId : cowIds) {
            if (level.getEntity(cowId) != null) remaining++;
        }
        return remaining < FIXTURE_COWS;
    }

    private boolean furnaceOutputEmpty(ServerLevel level) {
        return level.getBlockEntity(furnacePos) instanceof FurnaceBlockEntity furnace
                && furnace.getItem(2).isEmpty();
    }

    private String setupSnapshot() {
        Minecraft client = Minecraft.getInstance();
        return "playerInventory=" + (client.player == null ? "missing" : inventorySnapshot(client.player))
                + ",cows=" + FIXTURE_COWS + ",furnace=" + furnacePos;
    }

    private String serverSnapshot(ServerPlayer player, ServerLevel level) {
        return serverSnapshot(player, level, furnacePos);
    }

    private String serverSnapshot(ServerPlayer player, ServerLevel level, BlockPos furnace) {
        return "cookedBeef=" + count(player, Items.COOKED_BEEF)
                + ",rawBeef=" + count(player, Items.BEEF)
                + ",coal=" + count(player, Items.COAL)
                + ",inventory=" + inventorySnapshot(player)
                + ",uiClean=" + uiClean(player)
                + ",alive=" + player.isAlive()
                + ",health=" + player.getHealth()
                + ",food=" + player.getFoodData().getFoodLevel()
                + ",furnaceOutputEmpty=" + (level.getBlockEntity(furnace) instanceof FurnaceBlockEntity blockEntity
                    && blockEntity.getItem(2).isEmpty())
                + ",fixtureCowsRemaining=" + remainingFixtureCows(level)
                + ",fixtureCowHarvested=" + atLeastOneFixtureCowHarvested(level)
                + ",taskTrace=killRaw:" + sawRawAcquisitionTask + "/smelt:" + sawSmeltingTask
                + ",peakRawBeef=" + peakObservedRawBeef;
    }

    private String clientSnapshot() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return "player missing";
        return "cookedBeef=" + count(client.player, Items.COOKED_BEEF)
                + ",rawBeef=" + count(client.player, Items.BEEF)
                + ",coal=" + count(client.player, Items.COAL)
                + ",inventory=" + inventorySnapshot(client.player)
                + ",uiClean=" + uiClean(client.player)
                + ",alive=" + client.player.isAlive()
                + ",health=" + client.player.getHealth()
                + ",food=" + client.player.getFoodData().getFoodLevel()
                + ",taskTrace=killRaw:" + sawRawAcquisitionTask + "/smelt:" + sawSmeltingTask;
    }

    private int remainingFixtureCows(ServerLevel level) {
        int remaining = 0;
        for (UUID cowId : cowIds) if (level.getEntity(cowId) != null) remaining++;
        return remaining;
    }

    private static AABB fixtureBounds(BlockPos furnace, List<BlockPos> cows) {
        int minX = furnace.getX(), minY = furnace.getY(), minZ = furnace.getZ();
        int maxX = minX, maxY = minY, maxZ = minZ;
        for (BlockPos pos : cows) {
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }
        return new AABB(minX - 3, minY - 2, minZ - 3, maxX + 4, maxY + 3, maxZ + 4);
    }

    private static int countCows(ServerLevel level, AABB bounds) {
        return level.getEntitiesOfClass(Cow.class, bounds).size();
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

    private static int count(Player player, net.minecraft.world.item.Item item) {
        if (player == null) return 0;
        int count = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    private static String inventorySnapshot(Player player) {
        List<String> stacks = new ArrayList<>();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty()) stacks.add(slot + "=" + stack.getCount() + "x" + stack.getItem());
        }
        return stacks.toString();
    }

    private void publishSetupFailure(String state) {
        setupState = state;
        setupReady = true;
    }

    private void finishSuccessfully() {
        phase = Phase.DONE;
        append.accept("ASSERT\t@get cooked_beef " + TARGET_COOKED_BEEF
                + " acquired raw beef from prepared adult cows, smelted it in the fixture furnace,"
                + " and completed with exact server/client cooked beef inventory, clean UI, and a living player"
                + " (taskTrace=killRaw:" + sawRawAcquisitionTask + "/smelt:" + sawSmeltingTask
                + ",peakRawBeef=" + peakObservedRawBeef + ")");
        append.accept("ANIMAL_FOOD_ACCEPTANCE\tPASS\tprepared cow/furnace fixture; natural discovery not exercised");
        success.run();
    }

    private void fail(String reason) {
        if (phase == Phase.FAILED || phase == Phase.DONE) return;
        phase = Phase.FAILED;
        failure.accept(reason + "; server=" + pollState + "; client=" + clientSnapshot());
    }

    private boolean timedOut() {
        return clientTickCount() - phaseStarted > (phase == Phase.VERIFY
                ? VERIFICATION_TIMEOUT_TICKS : PHASE_TIMEOUT_TICKS);
    }

    private long clientTickCount() {
        return Minecraft.getInstance().level == null ? 0 : Minecraft.getInstance().level.getGameTime();
    }
}
