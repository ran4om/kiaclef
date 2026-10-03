package adris.altoclef.runtimetest;

import adris.altoclef.AltoClef;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.Subscription;
import adris.altoclef.eventbus.events.SendChatEvent;
import adris.altoclef.eventbus.events.TaskFinishedEvent;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasks.resources.CollectFoodTask;
import adris.altoclef.tasksystem.Task;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarrotBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Runs normal {@code @food 1} through chat and the default chooser against one prepared mature carrot crop. */
public final class DefaultFoodChooserAcceptanceScenario {
    private enum Phase { NEW, PREPARING, WAIT_CLIENT_SYNC, COLLECT, VERIFY, DONE, FAILED }

    private static final int LOW_FOOD_LEVEL = 6;
    private static final int SETUP_TIMEOUT_TICKS = 1200;
    private static final int TASK_TIMEOUT_TICKS = 6000;
    private static final int VERIFY_TIMEOUT_TICKS = 400;
    private static final int POLL_INTERVAL_TICKS = 5;
    private static final double DEFAULT_FOOD_RANGE = 100.0;
    private static final Block[] TRACKED_FOOD_BLOCKS = {
            Blocks.WHEAT, Blocks.CARROTS, Blocks.POTATOES, Blocks.BEETROOTS,
            Blocks.HAY_BLOCK, Blocks.SWEET_BERRY_BUSH
    };

    private final AltoClef mod;
    private final Consumer<String> append;
    private final Consumer<String> failure;
    private final Runnable success;

    private volatile Phase phase = Phase.NEW;
    private volatile boolean setupReady;
    private volatile String setupState = "not-started";
    private volatile boolean pollOutstanding;
    private volatile boolean pollReady;
    private volatile String pollState = "not-polled";
    private volatile List<ItemStack> serverInventory = List.of();
    private volatile int serverCarrots;
    private volatile int serverFoodLevel;
    private volatile float serverHealth;
    private volatile boolean serverCropAir;
    private volatile boolean serverSurvival;
    private volatile boolean serverAlive;
    private volatile boolean serverUiClean;
    private volatile int serverNearbyDrops;
    private volatile boolean commandSeen;
    private volatile boolean commandCancelled;
    private volatile boolean taskCompleted;
    private volatile String commandFailure;

    private boolean trackedBlocks;
    private boolean autoEatSettingChanged;
    private boolean originalAutoEat;
    private int originalRandomTickSpeed = -1;
    private volatile Difficulty originalDifficulty;
    private volatile boolean difficultyChanged;
    private boolean sawCollectFoodTask;
    private boolean sawDefaultBlockChooser;
    private boolean sawDestroyBlockTask;
    private boolean sawPickupDroppedItemTask;
    private long phaseStarted;
    private long nextPollTick;
    private UUID playerId;
    private Vec3 origin;
    private BlockPos carrotPos;
    private AABB fixtureBounds;
    private Subscription<SendChatEvent> chatObserver;
    private Subscription<TaskFinishedEvent> taskObserver;

    public DefaultFoodChooserAcceptanceScenario(AltoClef mod, Consumer<String> append,
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
                    fail("Default food-chooser fixture setup failed: " + setupState);
                } else {
                    append.accept("DEFAULT_FOOD_SETUP\tok\torigin=" + origin + ",carrot=" + carrotPos
                            + ",inventory=empty,food=" + LOW_FOOD_LEVEL + ",difficulty=NORMAL,originalDifficulty="
                            + originalDifficulty + ",foodDropsSeeded=none,autoEat=false");
                    phase = Phase.WAIT_CLIENT_SYNC;
                    phaseStarted = clientTick();
                }
            } else if (timedOut(SETUP_TIMEOUT_TICKS)) {
                fail("Timed out preparing default food-chooser fixture: " + setupState);
            }
            return;
        }

        if (commandFailure != null) {
            fail("@food 1 command failed: " + commandFailure);
            return;
        }
        if (phase == Phase.WAIT_CLIENT_SYNC) {
            if (clientFixtureSynchronized()) startChatCommand();
            else if (timedOut(VERIFY_TIMEOUT_TICKS)) {
                fail("Carrot fixture did not synchronize to the client: " + clientSnapshot());
            }
            return;
        }

        observeTaskTree();
        if (phase == Phase.COLLECT) {
            if (!pollOutstanding && clientTick() >= nextPollTick) requestServerPoll();
            if (taskCompleted && pollReady) {
                phase = Phase.VERIFY;
                phaseStarted = clientTick();
            } else if (timedOut(TASK_TIMEOUT_TICKS)) {
                fail("Timed out in normal @food 1; tasks=" + mod.getUserTaskChain().getTasks()
                        + ", taskTrace=" + traceSnapshot() + ", client=" + clientSnapshot());
            }
            return;
        }

        if (phase == Phase.VERIFY) {
            if (pollReady) {
                pollReady = false;
                append.accept("DEFAULT_FOOD_FINAL_POLL\t" + pollState);
                if (serverStateIsValid() && clientStateMatchesServer()) {
                    finishSuccessfully();
                } else if (timedOut(VERIFY_TIMEOUT_TICKS)) {
                    fail("Default food-chooser final-state mismatch: server=" + pollState
                            + ", client=" + clientSnapshot());
                }
            } else if (!pollOutstanding && clientTick() >= nextPollTick) {
                requestServerPoll();
            } else if (timedOut(VERIFY_TIMEOUT_TICKS)) {
                fail("Timed out verifying default food-chooser result: " + pollState);
            }
        }
    }

    private void beginSetup() {
        Minecraft client = Minecraft.getInstance();
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null || client.player == null || client.level == null) {
            fail("Default food-chooser acceptance requires an active integrated world");
            return;
        }
        playerId = client.player.getUUID();
        origin = client.player.position();
        mod.getBlockTracker().trackBlock(TRACKED_FOOD_BLOCKS);
        trackedBlocks = true;
        phase = Phase.PREPARING;
        phaseStarted = clientTick();
        try {
            setAutoEat(false);
        } catch (ReflectiveOperationException error) {
            fail("Could not temporarily disable auto-eat: " + error);
            return;
        }
        server.execute(() -> prepareFixture(server));
        append.accept("DEFAULT_FOOD_SETUP\tqueued\tclear inventory and nearby item entities; set NORMAL difficulty for low-hunger survival; restore original difficulty; disable/restore auto-eat; seed one mature carrot crop; command=@food 1");
    }

    private void prepareFixture(MinecraftServer server) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) {
            publishSetupFailure("player=null");
            return;
        }
        ServerLevel level = (ServerLevel) player.level();
        BlockPos base = player.blockPosition();
        int floorY = base.getY() - 1;
        carrotPos = new BlockPos(base.getX() + 3, base.getY(), base.getZ() + 1);
        fixtureBounds = new AABB(base.getX() - (int) DEFAULT_FOOD_RANGE, floorY - 2,
                base.getZ() - (int) DEFAULT_FOOD_RANGE, base.getX() + DEFAULT_FOOD_RANGE + 1,
                base.getY() + 8, base.getZ() + DEFAULT_FOOD_RANGE + 1);

        player.closeContainer();
        player.getInventory().clearContent();
        for (int slot = 1; slot <= 4; slot++) player.inventoryMenu.getSlot(slot).set(ItemStack.EMPTY);
        player.inventoryMenu.setCarried(ItemStack.EMPTY);
        player.setGameMode(GameType.SURVIVAL);
        originalDifficulty = server.getWorldData().getDifficulty();
        server.setDifficulty(Difficulty.NORMAL, true);
        difficultyChanged = true;
        player.setHealth(player.getMaxHealth());
        player.getFoodData().setFoodLevel(LOW_FOOD_LEVEL);
        player.getFoodData().setSaturation(0.0f);

        originalRandomTickSpeed = server.getGameRules().get(GameRules.RANDOM_TICK_SPEED);
        server.getGameRules().set(GameRules.RANDOM_TICK_SPEED, 0, server);
        level.getChunkAt(carrotPos);
        level.setBlock(carrotPos.below(), Blocks.FARMLAND.defaultBlockState()
                .setValue(FarmlandBlock.MOISTURE, 7), 3);
        level.setBlock(carrotPos, matureCarrots(), 3);
        level.setBlock(carrotPos.above(), Blocks.AIR.defaultBlockState(), 3);

        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, fixtureBounds)) item.discard();
        player.inventoryMenu.broadcastChanges();
        boolean valid = player.getInventory().isEmpty() && cleanUi(player)
                && player.getFoodData().getFoodLevel() == LOW_FOOD_LEVEL
                && player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL
                && server.getWorldData().getDifficulty() == Difficulty.NORMAL
                && level.getBlockState(carrotPos).is(Blocks.CARROTS)
                && isMatureCarrots(level.getBlockState(carrotPos))
                && nearbyItemEntityCount(level, fixtureBounds) == 0;
        publishSetup(valid ? "ok" : "invalid:" + serverSnapshot(player, level));
    }

    private static BlockState matureCarrots() {
        CarrotBlock carrots = (CarrotBlock) Blocks.CARROTS;
        return Blocks.CARROTS.defaultBlockState().setValue(CarrotBlock.AGE, carrots.getMaxAge());
    }

    private static boolean isMatureCarrots(BlockState state) {
        return state.is(Blocks.CARROTS) && state.getBlock() instanceof CarrotBlock crop && crop.isMaxAge(state);
    }

    private boolean clientFixtureSynchronized() {
        Minecraft client = Minecraft.getInstance();
        return client.player != null && client.level != null && client.player.getInventory().isEmpty()
                && client.gameMode != null && client.gameMode.getPlayerMode() == GameType.SURVIVAL
                && client.level.getDifficulty() == Difficulty.NORMAL
                && client.player.getFoodData().getFoodLevel() == LOW_FOOD_LEVEL
                && cleanUi(client.player) && isMatureCarrots(client.level.getBlockState(carrotPos))
                && noCompetingFoodBlocks();
    }

    private boolean noCompetingFoodBlocks() {
        for (Block block : TRACKED_FOOD_BLOCKS) {
            var nearest = mod.getBlockTracker().getNearestTracking(origin, block);
            if (nearest.isEmpty() || !nearest.get().closerToCenterThan(origin, DEFAULT_FOOD_RANGE)) continue;
            if (block == Blocks.CARROTS && nearest.get().equals(carrotPos)) continue;
            return false;
        }
        return true;
    }

    private void startChatCommand() {
        Minecraft client = Minecraft.getInstance();
        String command = mod.getModSettings().getCommandPrefix() + "food 1";
        phase = Phase.COLLECT;
        phaseStarted = clientTick();
        nextPollTick = phaseStarted;
        taskObserver = EventBus.subscribe(TaskFinishedEvent.class, event -> {
            if (event.lastTaskRan instanceof CollectFoodTask) {
                if (event.failure != null || event.cancelled) {
                    commandFailure = event.failure == null ? "task cancelled" : event.failure.reason();
                } else {
                    taskCompleted = true;
                    append.accept("TASK_FINISHED\tCollectFoodTask\t" + event.durationSeconds + "s");
                }
            }
        });
        AtomicBoolean sawExpectedChat = new AtomicBoolean();
        AtomicBoolean wasCancelled = new AtomicBoolean();
        chatObserver = EventBus.subscribe(SendChatEvent.class, event -> {
            if (command.equals(event.message)) {
                sawExpectedChat.set(true);
                wasCancelled.set(event.isCancelled());
            }
        });

        ChatScreen screen = new ChatScreen(command, false);
        try {
            client.gui.setScreen(screen);
            screen.handleChatInput(command, true);
        } catch (Throwable error) {
            commandFailure = "ChatScreen.handleChatInput failed: " + error;
        } finally {
            commandSeen = sawExpectedChat.get();
            commandCancelled = wasCancelled.get();
            EventBus.unsubscribe(chatObserver);
            chatObserver = null;
            client.gui.setScreen(null);
        }
        append.accept("CHAT_INPUT\tChatScreen.handleChatInput(" + command + ")\tseen=" + commandSeen
                + "\tcancelled=" + commandCancelled);
        if (!commandSeen || !commandCancelled) {
            commandFailure = "actual client chat path did not publish and cancel the @food command";
        }
    }

    private void observeTaskTree() {
        for (Task task : mod.getUserTaskChain().getTasks()) {
            if (task instanceof CollectFoodTask && !sawCollectFoodTask) {
                sawCollectFoodTask = true;
                append.accept("TASK_TRACE\tCollectFoodTask\tdefault @food command");
            }
            if (task.getClass().getSimpleName().equals("BoundedBlockFoodTask") && !sawDefaultBlockChooser) {
                sawDefaultBlockChooser = true;
                append.accept("TASK_TRACE\tCollectFoodTask default crop chooser\t" + task);
            }
            if (task instanceof DestroyBlockTask && !sawDestroyBlockTask) {
                sawDestroyBlockTask = true;
                append.accept("TASK_TRACE\tDestroyBlockTask\t" + task);
            }
            if (task instanceof PickupDroppedItemTask && !sawPickupDroppedItemTask) {
                sawPickupDroppedItemTask = true;
                append.accept("TASK_TRACE\tPickupDroppedItemTask\t" + task);
            }
        }
    }

    private void requestServerPoll() {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null || playerId == null || pollOutstanding) return;
        pollOutstanding = true;
        pollReady = false;
        nextPollTick = clientTick() + POLL_INTERVAL_TICKS;
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            ServerLevel level = player == null ? null : (ServerLevel) player.level();
            if (player == null || level == null) {
                pollState = "player/level missing";
            } else {
                serverInventory = copyInventory(player);
                serverCarrots = count(player, Items.CARROT);
                serverFoodLevel = player.getFoodData().getFoodLevel();
                serverHealth = player.getHealth();
                serverCropAir = level.getBlockState(carrotPos).isAir();
                serverSurvival = player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL;
                serverAlive = player.isAlive();
                serverUiClean = cleanUi(player);
                serverNearbyDrops = nearbyItemEntityCount(level, fixtureBounds);
                pollState = serverSnapshot(player, level);
            }
            pollReady = true;
            pollOutstanding = false;
        });
    }

    private boolean serverStateIsValid() {
        return commandSeen && commandCancelled && taskCompleted
                && sawCollectFoodTask && sawDefaultBlockChooser && sawDestroyBlockTask
                && serverCropAir && serverCarrots > 0 && inventoryContainsOnlyCarrots(serverInventory)
                && serverNearbyDrops == 0 && serverSurvival && serverAlive && serverHealth > 0
                && serverFoodLevel > 0 && serverFoodLevel <= LOW_FOOD_LEVEL && serverUiClean;
    }

    private boolean clientStateMatchesServer() {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null || client.gameMode == null
                || client.gameMode.getPlayerMode() != GameType.SURVIVAL
                || !client.player.isAlive() || client.player.getHealth() <= 0
                || client.player.getHealth() != serverHealth
                || client.player.getFoodData().getFoodLevel() != serverFoodLevel
                || !client.level.getBlockState(carrotPos).isAir()
                || count(client.player, Items.CARROT) != serverCarrots
                || !inventoryContainsOnlyCarrots(copyInventory(client.player))
                || !cleanUi(client.player)
                || serverInventory.size() != client.player.getInventory().getContainerSize()) return false;
        for (int slot = 0; slot < serverInventory.size(); slot++) {
            if (!ItemStack.matches(serverInventory.get(slot), client.player.getInventory().getItem(slot))) return false;
        }
        return true;
    }

    private static boolean inventoryContainsOnlyCarrots(List<ItemStack> stacks) {
        boolean found = false;
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            if (!stack.is(Items.CARROT)) return false;
            found = true;
        }
        return found;
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
        List<ItemStack> stacks = new ArrayList<>(player.getInventory().getContainerSize());
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            stacks.add(player.getInventory().getItem(slot).copy());
        }
        return List.copyOf(stacks);
    }

    private static int nearbyItemEntityCount(ServerLevel level, AABB bounds) {
        return level.getEntitiesOfClass(ItemEntity.class, bounds).size();
    }

    private static boolean cleanUi(Player player) {
        if (player.containerMenu != player.inventoryMenu || !player.containerMenu.getCarried().isEmpty()) return false;
        for (int slot = 1; slot <= 4; slot++) {
            if (!player.inventoryMenu.getSlot(slot).getItem().isEmpty()) return false;
        }
        return true;
    }

    private String serverSnapshot(ServerPlayer player, ServerLevel level) {
        return "inventory=" + describe(serverInventory) + ",carrots=" + count(player, Items.CARROT)
                + ",crop=" + level.getBlockState(carrotPos) + ",nearbyDrops=" + nearbyItemEntityCount(level, fixtureBounds)
                + ",mode=" + player.gameMode.getGameModeForPlayer() + ",alive=" + player.isAlive()
                + ",health=" + player.getHealth() + ",food=" + player.getFoodData().getFoodLevel()
                + ",uiClean=" + cleanUi(player);
    }

    private String clientSnapshot() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return "player/level missing";
        return "inventory=" + describe(copyInventory(client.player)) + ",crop=" + client.level.getBlockState(carrotPos)
                + ",mode=" + (client.gameMode == null ? "unknown" : client.gameMode.getPlayerMode())
                + ",alive=" + client.player.isAlive() + ",health=" + client.player.getHealth()
                + ",food=" + client.player.getFoodData().getFoodLevel() + ",uiClean=" + cleanUi(client.player)
                + ",taskTrace=" + traceSnapshot();
    }

    private static String describe(List<ItemStack> stacks) {
        return stacks.stream().filter(stack -> !stack.isEmpty()).toList().toString();
    }

    private String traceSnapshot() {
        return "CollectFoodTask=" + sawCollectFoodTask + ",defaultCropChooser=" + sawDefaultBlockChooser
                + ",DestroyBlockTask=" + sawDestroyBlockTask + ",PickupDroppedItemTask=" + sawPickupDroppedItemTask;
    }

    private void finishSuccessfully() {
        phase = Phase.DONE;
        cleanup();
        append.accept("ASSERT\tactual @food 1 used CollectFoodTask's default chooser, broke and picked up a mature carrot crop, and left only synchronized carrot stacks; low-hunger survival and clean UI held");
        append.accept("DEFAULT_FOOD_CHOOSER_ACCEPTANCE\tPASS\tactual ChatScreen input, default crop chooser, direct break/pickup path");
        success.run();
    }

    private void fail(String reason) {
        if (phase == Phase.FAILED || phase == Phase.DONE) return;
        phase = Phase.FAILED;
        mod.cancelUserTask();
        cleanup();
        append.accept("DEFAULT_FOOD_CHOOSER_ACCEPTANCE\tFAIL\t" + reason);
        failure.accept(reason);
    }

    private void cleanup() {
        Minecraft client = Minecraft.getInstance();
        client.gui.setScreen(null);
        if (chatObserver != null) EventBus.unsubscribe(chatObserver);
        if (taskObserver != null) EventBus.unsubscribe(taskObserver);
        chatObserver = null;
        taskObserver = null;
        if (trackedBlocks) {
            mod.getBlockTracker().stopTracking(TRACKED_FOOD_BLOCKS);
            trackedBlocks = false;
        }
        restoreAutoEat();
        restoreRandomTickSpeed();
        restoreDifficulty();
    }

    private void setAutoEat(boolean value) throws ReflectiveOperationException {
        Field field = mod.getModSettings().getClass().getDeclaredField("autoEat");
        field.setAccessible(true);
        originalAutoEat = field.getBoolean(mod.getModSettings());
        field.setBoolean(mod.getModSettings(), value);
        autoEatSettingChanged = true;
        append.accept("SETTING\tautoEat=" + value + "\truntime scenario; original=" + originalAutoEat);
    }

    private void restoreAutoEat() {
        if (!autoEatSettingChanged) return;
        try {
            Field field = mod.getModSettings().getClass().getDeclaredField("autoEat");
            field.setAccessible(true);
            field.setBoolean(mod.getModSettings(), originalAutoEat);
            append.accept("SETTING\tautoEat=" + originalAutoEat + "\trestored");
        } catch (ReflectiveOperationException error) {
            append.accept("WARNING\tcould not restore autoEat setting: " + error);
        } finally {
            autoEatSettingChanged = false;
        }
    }

    private void restoreRandomTickSpeed() {
        if (originalRandomTickSpeed < 0) return;
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server != null) {
            int original = originalRandomTickSpeed;
            server.execute(() -> server.getGameRules().set(GameRules.RANDOM_TICK_SPEED, original, server));
        }
        originalRandomTickSpeed = -1;
    }

    private void restoreDifficulty() {
        if (!difficultyChanged || originalDifficulty == null) return;
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server != null) {
            Difficulty original = originalDifficulty;
            server.execute(() -> server.setDifficulty(original, true));
            append.accept("SETTING\tdifficulty=" + original + "\trestore queued");
        }
        difficultyChanged = false;
        originalDifficulty = null;
    }

    private long clientTick() {
        return Minecraft.getInstance().level == null ? 0 : Minecraft.getInstance().level.getGameTime();
    }

    private boolean timedOut(int limit) {
        return clientTick() - phaseStarted > limit;
    }

    private void publishSetupFailure(String reason) {
        setupState = reason;
        setupReady = true;
    }

    private void publishSetup(String result) {
        setupState = result;
        setupReady = true;
    }
}
