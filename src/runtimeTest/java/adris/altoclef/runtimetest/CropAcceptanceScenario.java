package adris.altoclef.runtimetest;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.container.LootContainerTask;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Prepared-world acceptance for catalogued wheat gathering. The fixture has two mature
 * crops, one immature crop, and real seed sources; no crop output is seeded in the
 * player's inventory. List-container mode obtains seeds from a warmed real chest cache.
 * Run only in a disposable singleplayer world.
 */
public final class CropAcceptanceScenario {
    private enum Phase { NEW, PREPARING, LOOT_SEEDS, WAIT_CACHE, VERIFY_SEEDS, GET_WHEAT, VERIFY, DONE, FAILED }

    private static final int TARGET_WHEAT = 2;
    private static final int FIXTURE_SEEDS = 2;
    private static final int FIXTURE_CHEST_SEEDS = 5;
    private static final int CHEST_SEEDS_REMAINING = FIXTURE_CHEST_SEEDS - FIXTURE_SEEDS;
    private static final int IMMATURE_AGE = 3;
    private static final int PHASE_TIMEOUT_TICKS = 6000;
    private static final int VERIFICATION_TIMEOUT_TICKS = 300;

    private final AltoClef mod;
    private final Consumer<String> append;
    private final Consumer<String> failure;
    private final Runnable success;

    private volatile Phase phase = Phase.NEW;
    private long phaseStarted;
    private UUID playerId;
    private BlockPos matureOne;
    private BlockPos matureTwo;
    private BlockPos immature;
    private BlockPos seedChest;
    private volatile int originalRandomTickSpeed = -1;
    private boolean replantExpected;
    private volatile boolean setupReady;
    private volatile String setupState = "not-started";
    private volatile String taskCompletion;
    private volatile String taskFailure;
    private volatile boolean pollOutstanding;
    private volatile boolean pollReady;
    private volatile boolean pollPassed;
    private volatile String pollState = "not-polled";
    private volatile int serverWheatCount;
    private volatile int serverSeedCount;
    private volatile boolean chestPollOutstanding;
    private volatile boolean chestPollReady;
    private volatile int observedServerChestSeeds = -1;
    private long nextPollAt;
    private long nextChestPollAt;
    private long nextProgressAt;

    public CropAcceptanceScenario(AltoClef mod, Consumer<String> append,
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
                    fail("Crop fixture setup failed: " + setupState);
                } else {
                    append.accept("CROP_SETUP\tok\tplayer inventory empty; mature=" + matureOne + "," + matureTwo
                            + ", immature=" + immature + " age=" + IMMATURE_AGE
                            + ", replant=" + replantExpected
                            + (isListContainerMode() ? ", seedChest=" + seedChest
                            + " chestSeeds=" + FIXTURE_CHEST_SEEDS : ""));
                    if (isListContainerMode()) startSeedLoot();
                    else startGetWheat();
                }
            } else if (timedOut()) {
                fail("Timed out preparing crop fixture: " + setupState);
            }
            return;
        }

        if (taskFailure != null) {
            fail("Crop acceptance task failed: " + taskFailure);
            return;
        }
        if (taskCompletion != null) {
            String completion = taskCompletion;
            taskCompletion = null;
            if (phase == Phase.LOOT_SEEDS && "loot-seeds".equals(completion)) {
                phase = Phase.WAIT_CACHE;
                phaseStarted = clientTickCount();
                append.accept("CROP_CACHE\tseed-loot-complete\tseed chest=" + seedChest);
                return;
            }
            if (phase == Phase.GET_WHEAT && "get-wheat".equals(completion)) {
                phase = Phase.VERIFY;
                phaseStarted = clientTickCount();
                nextPollAt = phaseStarted;
                requestPoll();
                return;
            }
            fail("Unexpected user-task completion: " + completion + " during " + phase);
            return;
        }

        if (phase == Phase.WAIT_CACHE) {
            int cachedSeeds = cachedChestSeedCount();
            if (cachedSeeds == CHEST_SEEDS_REMAINING) {
                append.accept("CROP_CACHE\tseed chest warmed\tclientCacheSeeds=" + cachedSeeds);
                phase = Phase.VERIFY_SEEDS;
                phaseStarted = clientTickCount();
                nextPollAt = phaseStarted;
                requestPoll();
            } else if (timedOut()) {
                fail("Timed out warming seed chest cache: clientCacheSeeds=" + cachedSeeds);
            }
            return;
        }

        if (phase == Phase.VERIFY_SEEDS || phase == Phase.VERIFY) {
            if (pollReady) {
                pollReady = false;
                append.accept("CROP_SERVER_POLL\t" + pollState
                        + (isListContainerMode() ? "\tclientCacheSeeds=" + cachedChestSeedCount() : ""));
                if (pollPassed && (phase == Phase.VERIFY_SEEDS
                        ? clientMatchesSeedCacheExpected() : clientMatchesExpected())) {
                    if (phase == Phase.VERIFY_SEEDS) startGetWheat();
                    else finishSuccessfully();
                } else if (timedOut()) {
                    fail("Crop/list state mismatch in " + phase + ": server=" + pollState
                            + ", client=" + clientSnapshot());
                } else {
                    nextPollAt = clientTickCount() + 5;
                }
            } else if (timedOut()) {
                fail("Timed out verifying " + phase + ": " + pollState + ", client=" + clientSnapshot());
            } else if (!pollOutstanding && clientTickCount() >= nextPollAt) {
                requestPoll();
            }
            return;
        }

        if (phase == Phase.GET_WHEAT) {
            if (isListContainerMode()) {
                int cachedSeeds = cachedChestSeedCount();
                if (cachedSeeds != CHEST_SEEDS_REMAINING) {
                    fail("Client container cache changed during list gathering: expected="
                            + CHEST_SEEDS_REMAINING + ", actual=" + cachedSeeds);
                    return;
                }
                if (chestPollReady) {
                    chestPollReady = false;
                    if (observedServerChestSeeds != CHEST_SEEDS_REMAINING) {
                        fail("Server chest changed during list gathering: expected="
                                + CHEST_SEEDS_REMAINING + ", actual=" + observedServerChestSeeds);
                        return;
                    }
                    append.accept("CROP_CACHE\tlist-invariant\tserverChestSeeds="
                            + observedServerChestSeeds + "\tclientCacheSeeds=" + cachedSeeds);
                }
                if (!chestPollOutstanding && clientTickCount() >= nextChestPollAt) {
                    requestChestCountPoll();
                    nextChestPollAt = clientTickCount() + 20;
                }
            }
            if (clientTickCount() >= nextProgressAt) {
                append.accept("CROP_PROGRESS\t" + clientSnapshot() + "\ttasks="
                        + mod.getUserTaskChain().getTasks());
                nextProgressAt = clientTickCount() + 100;
            }
        }
        if (timedOut()) fail("Timed out during " + phase + "; client=" + clientSnapshot()
                + "; active tasks=" + mod.getUserTaskChain().getTasks());
    }

    private void beginSetup() {
        Minecraft client = Minecraft.getInstance();
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null || client.player == null || client.level == null) {
            fail("Crop acceptance requires an active integrated server and client player");
            return;
        }
        playerId = client.player.getUUID();
        replantExpected = mod.getModSettings().shouldReplantCrops();
        if (isListContainerMode()) seedChest = client.player.blockPosition().offset(0, 0, 4).immutable();
        phase = Phase.PREPARING;
        phaseStarted = clientTickCount();
        server.execute(() -> prepareServerFixture(server));
        append.accept("CROP_SETUP\tqueued\tprepared mature and immature wheat fixture");
    }

    private void prepareServerFixture(MinecraftServer server) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) {
            publishSetupFailure("player=null");
            return;
        }
        ServerLevel level = (ServerLevel) player.level();
        BlockPos[] positions = findFixture(level, player.blockPosition());
        if (positions == null) {
            publishSetupFailure("no three adjacent air crop cells over solid support within 12 blocks");
            return;
        }
        matureOne = positions[0].immutable();
        matureTwo = positions[1].immutable();
        immature = positions[2].immutable();

        if (isListContainerMode() && (!level.getBlockState(seedChest).isAir()
                || !level.getBlockState(seedChest.above()).isAir()
                || !level.getBlockState(seedChest.below()).isRedstoneConductor(level, seedChest.below()))) {
            publishSetupFailure("seed chest position must be air over solid support: "
                    + level.getBlockState(seedChest) + " over " + level.getBlockState(seedChest.below()));
            return;
        }

        player.closeContainer();
        player.getInventory().clearContent();
        for (int slot = 1; slot <= 4; slot++) player.inventoryMenu.getSlot(slot).set(ItemStack.EMPTY);
        player.inventoryMenu.setCarried(ItemStack.EMPTY);
        player.inventoryMenu.broadcastChanges();
        player.setGameMode(GameType.SURVIVAL);

        originalRandomTickSpeed = server.getGameRules().get(GameRules.RANDOM_TICK_SPEED);
        server.getGameRules().set(GameRules.RANDOM_TICK_SPEED, 0, server);

        BlockState farmland = Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE, 7);
        BlockState matureWheat = wheatAtAge(((CropBlock) Blocks.WHEAT).getMaxAge());
        BlockState immatureWheat = wheatAtAge(IMMATURE_AGE);
        for (BlockPos cropPos : new BlockPos[]{matureOne, matureTwo, immature}) {
            level.setBlock(cropPos.below(), farmland, 3);
        }
        level.setBlock(matureOne, matureWheat, 3);
        level.setBlock(matureTwo, matureWheat, 3);
        level.setBlock(immature, immatureWheat, 3);

        if (isListContainerMode()) {
            level.setBlock(seedChest, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH), 3);
            if (level.getBlockEntity(seedChest) instanceof ChestBlockEntity chest) {
                chest.clearContent();
                chest.setItem(0, new ItemStack(Items.WHEAT_SEEDS, FIXTURE_SEEDS));
                chest.setItem(1, new ItemStack(Items.WHEAT_SEEDS, CHEST_SEEDS_REMAINING));
                chest.setChanged();
            }
        }

        AABB fixtureBounds = fixtureBounds();
        for (ItemEntity drop : level.getEntitiesOfClass(ItemEntity.class, fixtureBounds)) drop.discard();
        ItemEntity seedDrop = new ItemEntity(level, matureOne.getX() + 0.5, matureOne.getY() + 0.1,
                matureOne.getZ() + 1.5, new ItemStack(Items.WHEAT_SEEDS, FIXTURE_SEEDS));
        seedDrop.setDefaultPickUpDelay();
        boolean seedSpawned = isListContainerMode() || level.addFreshEntity(seedDrop);

        boolean valid = seedSpawned
                && player.containerMenu == player.inventoryMenu
                && player.containerMenu.getCarried().isEmpty()
                && emptyCraftingGrid(player)
                && inventoryCount(player) == 0
                && level.getBlockState(matureOne).getBlock() == Blocks.WHEAT
                && cropAge(level.getBlockState(matureOne)) == ((CropBlock) Blocks.WHEAT).getMaxAge()
                && level.getBlockState(matureTwo).getBlock() == Blocks.WHEAT
                && cropAge(level.getBlockState(matureTwo)) == ((CropBlock) Blocks.WHEAT).getMaxAge()
                && level.getBlockState(immature).getBlock() == Blocks.WHEAT
                && cropAge(level.getBlockState(immature)) == IMMATURE_AGE
                && (!isListContainerMode() || chestSeedCount(level, seedChest) == FIXTURE_CHEST_SEEDS)
                && itemEntityCount(level, fixtureBounds, Items.WHEAT_SEEDS)
                == (isListContainerMode() ? 0 : FIXTURE_SEEDS);
        setupState = valid ? "ok" : "seed-invalid:" + serverSnapshot(player, level);
        setupReady = true;
    }

    private static BlockPos[] findFixture(ServerLevel level, BlockPos playerPos) {
        for (int distance = 3; distance <= 12; distance++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int sign : new int[]{1, -1}) {
                    BlockPos first = playerPos.below().offset(sign * distance, 0, dz);
                    BlockPos second = first.east();
                    BlockPos third = second.east();
                    if (validCropCell(level, first) && validCropCell(level, second) && validCropCell(level, third)) {
                        return new BlockPos[]{first.above(), second.above(), third.above()};
                    }
                }
            }
        }
        return null;
    }

    private static boolean validCropCell(ServerLevel level, BlockPos farmlandPos) {
        BlockPos cropPos = farmlandPos.above();
        return level.getBlockState(farmlandPos).isRedstoneConductor(level, farmlandPos)
                && level.getBlockState(cropPos).isAir()
                && level.getBlockState(cropPos.above()).isAir();
    }

    private static BlockState wheatAtAge(int age) {
        return Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, age);
    }

    private void startSeedLoot() {
        var previous = mod.getUserTaskChain().getLastCompletionSnapshot();
        phase = Phase.LOOT_SEEDS;
        phaseStarted = clientTickCount();
        try {
            mod.runUserTask(new LootContainerTask(seedChest, java.util.List.of(Items.WHEAT_SEEDS),
                    stack -> stack.getCount() == FIXTURE_SEEDS), () -> {
                var completion = mod.getUserTaskChain().getLastCompletionSnapshot();
                if (completion == null || completion == previous) {
                    taskFailure = "seed-loot task had no new completion snapshot";
                } else if (completion.failure() != null || completion.cancelled()) {
                    taskFailure = completion.failure() == null ? "seed-loot task cancelled"
                            : completion.failure().reason();
                } else {
                    taskCompletion = "loot-seeds";
                }
            });
        } catch (Throwable error) {
            taskFailure = "could not loot the seed stack from chest: " + error;
        }
        append.accept("CROP_COMMAND\t@loot wheat_seeds " + FIXTURE_SEEDS + "\tchest=" + seedChest
                + "\tinitialChestSeeds=" + FIXTURE_CHEST_SEEDS
                + "\tfilteredStackCount=" + FIXTURE_SEEDS);
    }

    private void publishSetupFailure(String state) {
        setupState = state;
        setupReady = true;
    }

    private void startGetWheat() {
        var previous = mod.getUserTaskChain().getLastCompletionSnapshot();
        phase = Phase.GET_WHEAT;
        phaseStarted = clientTickCount();
        nextChestPollAt = phaseStarted;
        String command = mod.getModSettings().getCommandPrefix()
                + (isListMode()
                ? "get [wheat " + TARGET_WHEAT + ", wheat_seeds 1]" : "get wheat " + TARGET_WHEAT);
        try {
            AltoClef.getCommandExecutor().execute(command, () -> {
                var completion = mod.getUserTaskChain().getLastCompletionSnapshot();
                if (completion == null || completion == previous) {
                    taskFailure = "command callback had no new completion snapshot";
                } else if (completion.failure() != null || completion.cancelled()) {
                    taskFailure = completion.failure() == null ? "cancelled" : completion.failure().reason();
                } else {
                    taskCompletion = "get-wheat";
                }
            }, error -> taskFailure = error.getMessage());
        } catch (Throwable error) {
            taskFailure = "could not execute " + command + ": " + error;
        }
        append.accept("CROP_COMMAND\t" + command + "\tseedDrops="
                + (isListContainerMode() ? 0 : FIXTURE_SEEDS)
                + "\ttargetWheat=" + TARGET_WHEAT
                + (isListContainerMode() ? "\tseedChest=" + seedChest
                + "\tchestSeedsMustRemain=" + CHEST_SEEDS_REMAINING : ""));
    }

    private void requestPoll() {
        Minecraft client = Minecraft.getInstance();
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null || pollOutstanding) {
            fail("Integrated server unavailable while verifying wheat harvest");
            return;
        }
        UUID checkingPlayer = playerId;
        pollOutstanding = true;
        pollReady = false;
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(checkingPlayer);
            ServerLevel level = player == null ? null : (ServerLevel) player.level();
            serverWheatCount = player == null ? 0 : count(player, Items.WHEAT);
            serverSeedCount = player == null ? 0 : count(player, Items.WHEAT_SEEDS);
            boolean checkingSeeds = phase == Phase.VERIFY_SEEDS;
            pollPassed = player != null && level != null
                    && (checkingSeeds ? expectedServerSeedState(player, level) : expectedServerState(player, level));
            pollState = player == null || level == null ? "player/level missing"
                    : serverSnapshot(player, level) + ",valid=" + pollPassed;
            pollOutstanding = false;
            pollReady = true;
        });
    }

    private boolean expectedServerSeedState(ServerPlayer player, ServerLevel level) {
        return uiClean(player) && validSurvivalInventory(player)
                && count(player, Items.WHEAT) == 0 && count(player, Items.WHEAT_SEEDS) == FIXTURE_SEEDS
                && inventoryCount(player) == FIXTURE_SEEDS
                && chestSeedCount(level, seedChest) == CHEST_SEEDS_REMAINING
                && matureCropPreserved(level, matureOne) && matureCropPreserved(level, matureTwo)
                && immatureCropPreserved(level);
    }

    private void requestChestCountPoll() {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null || chestPollOutstanding) return;
        chestPollOutstanding = true;
        chestPollReady = false;
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            observedServerChestSeeds = player == null ? -1
                    : chestSeedCount((ServerLevel) player.level(), seedChest);
            chestPollOutstanding = false;
            chestPollReady = true;
        });
    }

    private boolean expectedServerState(ServerPlayer player, ServerLevel level) {
        if (!uiClean(player) || !validSurvivalInventory(player) || count(player, Items.WHEAT) != TARGET_WHEAT
                || inventoryCount(player) != TARGET_WHEAT + count(player, Items.WHEAT_SEEDS)
                || (isListContainerMode() && chestSeedCount(level, seedChest) != CHEST_SEEDS_REMAINING)
                || !immatureCropPreserved(level)) {
            return false;
        }
        if (replantExpected) {
            return replantedAtAgeZero(level, matureOne) && replantedAtAgeZero(level, matureTwo);
        }
        return level.getBlockState(matureOne).isAir() && level.getBlockState(matureTwo).isAir();
    }

    private boolean clientMatchesExpected() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null || !uiClean(client.player)
                || count(client.player, Items.WHEAT) != serverWheatCount
                || count(client.player, Items.WHEAT_SEEDS) != serverSeedCount
                || count(client.player, Items.WHEAT) != TARGET_WHEAT
                || !validSurvivalInventory(client.player)
                || inventoryCount(client.player) != TARGET_WHEAT + count(client.player, Items.WHEAT_SEEDS)
                || (isListContainerMode() && cachedChestSeedCount() != CHEST_SEEDS_REMAINING)
                || !immatureCropPreserved(client.level)) {
            return false;
        }
        if (replantExpected) {
            return replantedAtAgeZero(client.level, matureOne) && replantedAtAgeZero(client.level, matureTwo);
        }
        return client.level.getBlockState(matureOne).isAir() && client.level.getBlockState(matureTwo).isAir();
    }

    private boolean clientMatchesSeedCacheExpected() {
        Minecraft client = Minecraft.getInstance();
        return client.player != null && client.level != null && uiClean(client.player)
                && validSurvivalInventory(client.player)
                && count(client.player, Items.WHEAT) == 0
                && count(client.player, Items.WHEAT_SEEDS) == FIXTURE_SEEDS
                && inventoryCount(client.player) == FIXTURE_SEEDS
                && cachedChestSeedCount() == CHEST_SEEDS_REMAINING
                && matureCropPreserved(client.level, matureOne)
                && matureCropPreserved(client.level, matureTwo)
                && immatureCropPreserved(client.level);
    }

    private boolean matureCropPreserved(net.minecraft.world.level.LevelReader level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getBlock() == Blocks.WHEAT && cropAge(state) == ((CropBlock) Blocks.WHEAT).getMaxAge();
    }

    private boolean immatureCropPreserved(net.minecraft.world.level.LevelReader level) {
        BlockState state = level.getBlockState(immature);
        return state.getBlock() == Blocks.WHEAT && cropAge(state) == IMMATURE_AGE;
    }

    private static boolean replantedAtAgeZero(net.minecraft.world.level.LevelReader level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getBlock() == Blocks.WHEAT && cropAge(state) == 0;
    }

    private AABB fixtureBounds() {
        int minX = Math.min(matureOne.getX(), Math.min(matureTwo.getX(), immature.getX())) - 3;
        int minY = Math.min(matureOne.getY(), immature.getY()) - 2;
        int minZ = Math.min(matureOne.getZ(), Math.min(matureTwo.getZ(), immature.getZ())) - 3;
        int maxX = Math.max(matureOne.getX(), Math.max(matureTwo.getX(), immature.getX())) + 4;
        int maxY = Math.max(matureOne.getY(), immature.getY()) + 3;
        int maxZ = Math.max(matureOne.getZ(), Math.max(matureTwo.getZ(), immature.getZ())) + 4;
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private String serverSnapshot(ServerPlayer player, ServerLevel level) {
        return "wheat=" + count(player, Items.WHEAT) + ",seeds=" + count(player, Items.WHEAT_SEEDS)
                + ",inventory=" + inventoryCount(player)
                + (isListContainerMode() ? ",seedChest=" + seedChest
                + ",serverChestSeeds=" + chestSeedCount(level, seedChest) : "")
                + ",matureOne=" + level.getBlockState(matureOne)
                + ",matureTwo=" + level.getBlockState(matureTwo)
                + ",immature=" + level.getBlockState(immature)
                + ",seedDrops=" + itemEntityCount(level, fixtureBounds(), Items.WHEAT_SEEDS)
                + ",uiClean=" + uiClean(player);
    }

    private String clientSnapshot() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return "player/level missing";
        return "wheat=" + count(client.player, Items.WHEAT) + ",seeds=" + count(client.player, Items.WHEAT_SEEDS)
                + ",inventory=" + inventoryCount(client.player)
                + (isListContainerMode() ? ",seedChest=" + seedChest
                + ",clientCacheSeeds=" + cachedChestSeedCount() : "")
                + ",matureOne=" + client.level.getBlockState(matureOne)
                + ",matureTwo=" + client.level.getBlockState(matureTwo)
                + ",immature=" + client.level.getBlockState(immature)
                + ",uiClean=" + uiClean(client.player);
    }

    private static int cropAge(BlockState state) {
        return state.getBlock() instanceof CropBlock crop ? crop.getAge(state) : -1;
    }

    private static int itemEntityCount(ServerLevel level, AABB bounds, Item item) {
        int total = 0;
        for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, bounds)) {
            if (entity.getItem().is(item)) total += entity.getItem().getCount();
        }
        return total;
    }

    private static int chestSeedCount(ServerLevel level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof ChestBlockEntity chest
                ? count(chest, Items.WHEAT_SEEDS) : -1;
    }

    private int cachedChestSeedCount() {
        if (!isListContainerMode() || seedChest == null) return -1;
        return mod.getItemStorage().getContainerAtPosition(seedChest)
                .map(cache -> cache.getItemCount(Items.WHEAT_SEEDS)).orElse(-1);
    }

    private static int count(net.minecraft.world.Container container, Item item) {
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    private static boolean isListMode() {
        String startMode = System.getProperty("altoclef.runtimeStart");
        return "cropslist".equalsIgnoreCase(startMode) || "cropslistcontainer".equalsIgnoreCase(startMode);
    }

    private static boolean isListContainerMode() {
        return "cropslistcontainer".equalsIgnoreCase(System.getProperty("altoclef.runtimeStart"));
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
        if (player == null) return 0;
        int count = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    private static boolean validSurvivalInventory(Player player) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty() && !stack.is(Items.WHEAT) && !stack.is(Items.WHEAT_SEEDS)) return false;
        }
        return true;
    }

    private void finishSuccessfully() {
        phase = Phase.DONE;
        restoreRandomTickSpeed();
        append.accept("ASSERT\t" + (isListMode()
                ? "@get [wheat " + TARGET_WHEAT + ", wheat_seeds 1]" : "@get wheat " + TARGET_WHEAT)
                + " harvested mature vanilla crops for exactly "
                + TARGET_WHEAT + " wheat; server/client wheat and seed inventory matched; immature wheat was preserved"
                + (replantExpected ? " and both harvested plots were replanted" : "")
                + (isListContainerMode() ? "; server chest and client cache both retained exactly "
                + CHEST_SEEDS_REMAINING + " wheat seeds through list gathering and replanting" : ""));
        append.accept("CROP_ACCEPTANCE\tPASS\twheat harvest + immature preservation"
                + (replantExpected ? " + replant" : "")
                + (isListContainerMode() ? " + satisfied-target chest cache ignored" : ""));
        success.run();
    }

    private void fail(String reason) {
        if (phase == Phase.FAILED || phase == Phase.DONE) return;
        phase = Phase.FAILED;
        restoreRandomTickSpeed();
        failure.accept(reason);
    }

    private void restoreRandomTickSpeed() {
        int original = originalRandomTickSpeed;
        if (original < 0) return;
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server != null) server.execute(() -> {
            server.getGameRules().set(GameRules.RANDOM_TICK_SPEED, original, server);
            originalRandomTickSpeed = -1;
        });
    }

    private boolean timedOut() {
        return clientTickCount() - phaseStarted > (phase == Phase.VERIFY || phase == Phase.VERIFY_SEEDS
                ? VERIFICATION_TIMEOUT_TICKS : PHASE_TIMEOUT_TICKS);
    }

    private long clientTickCount() {
        return Minecraft.getInstance().level == null ? 0 : Minecraft.getInstance().level.getGameTime();
    }
}
