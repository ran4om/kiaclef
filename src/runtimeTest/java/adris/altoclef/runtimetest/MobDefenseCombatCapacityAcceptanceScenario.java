package adris.altoclef.runtimetest;

import adris.altoclef.AltoClef;
import adris.altoclef.Settings;
import adris.altoclef.chains.MobDefenseChain;
import adris.altoclef.chains.UserTaskChain;
import adris.altoclef.control.KillAura;
import adris.altoclef.tasks.movement.IdleTask;
import adris.altoclef.tasks.movement.RunAwayFromHostilesTask;
import adris.altoclef.tasksystem.Task;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Strict packaged Run 144 scheduler acceptance for the wooden-sword retreat tier. */
public final class MobDefenseCombatCapacityAcceptanceScenario {
    private static final int SETUP_TIMEOUT_TICKS = 300;
    private static final int SYNC_TIMEOUT_TICKS = 300;
    private static final int CAPTURE_TIMEOUT_TICKS = 80;
    private static final int ARENA_RADIUS = 16;
    private static final int ARENA_HEIGHT = 5;
    private static final String CAPTURE_ID = "run144-mob-defense-combat-capacity";

    private enum Phase { NEW, PREPARING, SYNCING, WAIT_IDLE, SYNCING_HOSTILES, CAPTURING, TERMINAL, DONE, FAILED }

    private final AltoClef mod;
    private final Consumer<String> append;
    private final Consumer<String> failure;
    private final Runnable success;
    private volatile Phase phase = Phase.NEW;
    private long phaseStarted;
    private UUID playerId;
    private BlockPos center;
    private AABB fixtureBounds;
    private List<UUID> zombieIds = List.of();
    private volatile String setupState = "not-started";
    private volatile boolean setupReady;
    private volatile boolean hostilesSpawned;
    private volatile boolean serverPollOutstanding;
    private volatile boolean serverPollReady;
    private volatile ServerSnapshot serverSnapshot;
    private volatile String serverPollError;
    private ClientSnapshot preCaptureClientSnapshot;
    private ClientSnapshot hostileClientSeedSnapshot;
    private MobDefenseCombatCapacityEventRecorder.Snapshot capturedEvents;
    private boolean spawnRequested;
    private int firstTrackedHostileEpoch = -1;

    public MobDefenseCombatCapacityAcceptanceScenario(AltoClef mod, Consumer<String> append,
                                                      Consumer<String> failure, Runnable success) {
        this.mod = Objects.requireNonNull(mod, "mod");
        this.append = Objects.requireNonNull(append, "append");
        this.failure = Objects.requireNonNull(failure, "failure");
        this.success = Objects.requireNonNull(success, "success");
    }

    /** Called once per client tick until success or failure is reported. */
    public void tick() {
        if (phase == Phase.DONE || phase == Phase.FAILED) return;
        if (phase == Phase.NEW) {
            begin();
            return;
        }
        if (phase == Phase.PREPARING) {
            if (setupReady) {
                if (!"ok".equals(setupState)) {
                    fail("FAIL\tfixture invalid: " + setupState);
                    return;
                }
                append.accept("MOB_DEFENSE_CAPACITY_SETUP\tok\t" + setupState);
                phase = Phase.SYNCING;
                phaseStarted = clientTicks();
            } else if (timedOut(SETUP_TIMEOUT_TICKS)) {
                incomplete("server fixture setup did not publish a complete snapshot: " + setupState);
            }
            return;
        }
        if (phase == Phase.SYNCING) {
            ClientSnapshot client = clientSnapshot();
            if (client != null && client.isSynchronizedWith(lastSetupSnapshot, List.of())) {
                preCaptureClientSnapshot = client;
                append.accept("MOB_DEFENSE_CAPACITY_CLIENT_SEED\t" + preCaptureClientSnapshot);
                startCapture();
            } else if (timedOut(SYNC_TIMEOUT_TICKS)) {
                incomplete("client fixture/tracker did not synchronize: " + (client == null ? "unavailable" : client));
            }
            return;
        }
        if (phase == Phase.WAIT_IDLE) {
            if (setupReady && setupState.startsWith("invalid:")) {
                fail("FAIL\tfixture hostile setup failed: " + setupState);
                return;
            }
            Task root = mod.getUserTaskChain().getCurrentTask();
            if (mod.getUserTaskChain().isActive() && root instanceof IdleTask) {
                if (!spawnRequested) {
                    spawnRequested = true;
                    queueHostiles();
                    phase = Phase.SYNCING_HOSTILES;
                    phaseStarted = clientTicks();
                }
            } else if (timedOut(SYNC_TIMEOUT_TICKS)) {
                incomplete("commandless User Tasks IdleTask did not become active; root=" + root);
            }
            return;
        }
        if (phase == Phase.SYNCING_HOSTILES) {
            if (setupReady && setupState.startsWith("invalid:")) {
                fail("FAIL\tfixture hostile setup failed: " + setupState);
                return;
            }
            ClientSnapshot client = clientSnapshot();
            if (hostilesSpawned && client != null && client.isSynchronizedWith(lastSetupSnapshot, zombieIds)) {
                hostileClientSeedSnapshot = client;
                try {
                    SettingsState.enableDiagnosticMobDefense(mod.getModSettings());
                    MobDefenseCombatCapacityEventRecorder.openCapture(CAPTURE_ID);
                } catch (RuntimeException error) {
                    incomplete("could not arm event recorder after fixture sync: "
                            + error.getClass().getSimpleName() + ": " + error.getMessage());
                    return;
                }
                phase = Phase.CAPTURING;
                phaseStarted = clientTicks();
                append.accept("MOB_DEFENSE_CAPACITY_CAPTURE\tarmed-after-hostile-sync\t" + hostileClientSeedSnapshot);
            } else if (timedOut(SYNC_TIMEOUT_TICKS)) {
                incomplete("spawned hostile fixture did not synchronize before capture: "
                        + (client == null ? "client unavailable" : client));
            }
            return;
        }
        if (phase == Phase.CAPTURING) {
            MobDefenseCombatCapacityEventRecorder.Snapshot events = MobDefenseCombatCapacityEventRecorder.snapshot();
            if (setupReady && setupState.startsWith("invalid:")) {
                capturedEvents = MobDefenseCombatCapacityEventRecorder.closeAndSnapshot();
                fail("FAIL\tfixture hostile setup failed: " + setupState);
                return;
            }
            if (events.overflowed()) {
                capturedEvents = MobDefenseCombatCapacityEventRecorder.closeAndSnapshot();
                incomplete("event recorder overflowed: " + renderEvents(capturedEvents));
                return;
            }
            ClientSnapshot synchronizedClient = clientSnapshot();
            if (firstTrackedHostileEpoch < 0 && synchronizedClient != null
                    && synchronizedClient.isSynchronizedWith(lastSetupSnapshot, zombieIds)
                    && !events.epochs().isEmpty()) {
                firstTrackedHostileEpoch = firstMobDefenseEpochWithHostiles(events, zombieIds);
                if (firstTrackedHostileEpoch >= 0) {
                    append.accept("MOB_DEFENSE_CAPACITY_FIRST_HOSTILE_EPOCH\tindex=" + firstTrackedHostileEpoch
                            + "\tepoch=" + events.epochs().get(firstTrackedHostileEpoch));
                }
            }
            if (firstTrackedHostileEpoch >= 0 && hostileClientSeedSnapshot != null
                    && events.epochs().size() > firstTrackedHostileEpoch + 1
                    && synchronizedClient != null && synchronizedClient.isSynchronizedWith(lastSetupSnapshot, zombieIds)) {
                Candidate candidate = new Candidate(firstTrackedHostileEpoch, firstTrackedHostileEpoch + 1);
                append.accept("MOB_DEFENSE_CAPACITY_BOUNDARIES\tserverGameTime=" + lastSetupSnapshot.gameTime
                        + ";hostileIds=" + zombieIds + ";serverNearby=" + lastSetupSnapshot.nearbyHostileIds
                        + ";clientTracked=" + synchronizedClient.trackedHostileIds
                        + ";timerArmed=" + events.epochs().get(candidate.firstIndex)
                        + ";eligible=" + events.epochs().get(candidate.secondIndex));
                capturedEvents = MobDefenseCombatCapacityEventRecorder.closeAndSnapshot();
                append.accept("MOB_DEFENSE_CAPACITY_EPOCHS\t" + renderEvents(capturedEvents));
                Verdict verdict = inspectEpochs(capturedEvents, candidate);
                if (verdict.incomplete != null) {
                    incomplete(verdict.incomplete);
                    return;
                }
                if (verdict.failure != null) {
                    fail("FAIL\t" + verdict.failure);
                    return;
                }
                phase = Phase.TERMINAL;
                phaseStarted = clientTicks();
                requestTerminalServerSnapshot();
            } else if (timedOut(CAPTURE_TIMEOUT_TICKS)) {
                capturedEvents = MobDefenseCombatCapacityEventRecorder.closeAndSnapshot();
                incomplete("first hostile scheduler epoch and immediate successor unavailable: " + renderEvents(capturedEvents));
            }
            return;
        }
        if (phase == Phase.TERMINAL) {
            if (serverPollReady) {
                serverPollReady = false;
                ClientSnapshot terminalClient = clientSnapshot();
                String terminal = "serverGameTime=" + (serverSnapshot == null ? "missing" : serverSnapshot.gameTime)
                        + ";server=" + serverSnapshot + ";client=" + terminalClient;
                append.accept("MOB_DEFENSE_CAPACITY_TERMINAL\t" + terminal);
                if (serverPollError != null || serverSnapshot == null || terminalClient == null) {
                    incomplete("terminal snapshot incomplete: serverError=" + serverPollError + ";" + terminal);
                } else if (!terminalClient.isSynchronizedWith(serverSnapshot, zombieIds)
                        || !serverSnapshot.validTerminal(zombieIds, playerId)
                        || !terminalClient.validTerminal(zombieIds, playerId)
                        || hostileClientSeedSnapshot == null
                        || !terminalClient.zombies.equals(hostileClientSeedSnapshot.zombies)
                        || !terminalClient.inventory.equals(hostileClientSeedSnapshot.inventory)
                        || !terminalClient.armor.equals(hostileClientSeedSnapshot.armor)
                        || !terminalClient.playerPosition.equals(hostileClientSeedSnapshot.playerPosition)
                        || terminalClient.health != hostileClientSeedSnapshot.health
                        || terminalClient.food != hostileClientSeedSnapshot.food) {
                    fail("FAIL\tterminal server/client state changed or mismatched: " + terminal);
                } else {
                    finishSuccessfully();
                }
            } else if (timedOut(SYNC_TIMEOUT_TICKS)) {
                incomplete("terminal server snapshot timed out");
            }
        }
    }

    private void begin() {
        Minecraft client = Minecraft.getInstance();
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null || client.player == null || client.level == null) {
            fail("FAIL\tRun 144 requires an active integrated server and client player");
            return;
        }
        playerId = client.player.getUUID();
        phase = Phase.PREPARING;
        phaseStarted = clientTicks();
        try {
            SettingsState.applyDiagnostic(mod.getModSettings());
            if (mod.getModSettings().isMobDefense() || !mod.getModSettings().isDodgeProjectiles()
                    || !mod.getModSettings().shouldDealWithAnnoyingHostiles()
                    || mod.getModSettings().getKillHostileWhenCloseForSeconds() != 0.0
                    || mod.getModSettings().getForceFieldStrategy() != KillAura.Strategy.OFF) {
                throw new IllegalStateException("diagnostic staging settings did not take effect");
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            fail("FAIL\tcould not configure and preserve diagnostic settings: "
                    + error.getClass().getSimpleName() + ": " + error.getMessage());
            return;
        }
        server.execute(() -> prepareServerFixture(server));
        append.accept("MOB_DEFENSE_CAPACITY_SETUP\tqueued\tprepared flat arena; only wooden sword supplied; forcefield off; hostile timer zero");
    }

    /** All world, entity, inventory, and menu reads/writes in this method run on the server thread. */
    private void prepareServerFixture(MinecraftServer server) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) {
            publishSetupFailure("player=null");
            return;
        }
        ServerLevel level = (ServerLevel) player.level();
        center = player.blockPosition().immutable();
        player.teleportTo(center.getX() + 0.5, center.getY(), center.getZ() + 0.5);
        player.setDeltaMovement(0.0, 0.0, 0.0);
        fixtureBounds = new AABB(center.getX() - ARENA_RADIUS, center.getY() - 2, center.getZ() - ARENA_RADIUS,
                center.getX() + ARENA_RADIUS + 1, center.getY() + ARENA_HEIGHT + 2, center.getZ() + ARENA_RADIUS + 1);
        player.closeContainer();
        player.getInventory().clearContent();
        for (int slot = 1; slot <= 4; slot++) player.inventoryMenu.getSlot(slot).set(ItemStack.EMPTY);
        player.inventoryMenu.setCarried(ItemStack.EMPTY);
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR) player.setItemSlot(slot, ItemStack.EMPTY);
        }
        player.getInventory().setItem(0, new ItemStack(Items.WOODEN_SWORD));
        player.getInventory().setSelectedSlot(0);
        player.inventoryMenu.broadcastChanges();
        player.setGameMode(GameType.SURVIVAL);
        player.setHealth(player.getMaxHealth());
        player.getFoodData().setFoodLevel(20);
        player.getFoodData().setSaturation(5.0f);

        int floorY = center.getY() - 1;
        for (int x = center.getX() - ARENA_RADIUS; x <= center.getX() + ARENA_RADIUS; x++) {
            for (int z = center.getZ() - ARENA_RADIUS; z <= center.getZ() + ARENA_RADIUS; z++) {
                BlockPos floor = new BlockPos(x, floorY, z);
                level.getChunkAt(floor);
                level.setBlock(floor, Blocks.STONE.defaultBlockState(), 3);
                for (int y = center.getY(); y <= center.getY() + ARENA_HEIGHT; y++) {
                    level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        for (Monster monster : level.getEntitiesOfClass(Monster.class, fixtureBounds)) monster.discard();
        for (Projectile projectile : level.getEntitiesOfClass(Projectile.class, fixtureBounds)) projectile.discard();
        for (ItemEntity drop : level.getEntitiesOfClass(ItemEntity.class, fixtureBounds)) drop.discard();

        ServerSnapshot snapshot = snapshotServer(server, player, level);
        lastSetupSnapshot = snapshot;
        String problems = snapshot.setupBaseProblems(playerId);
        setupState = problems.isEmpty() ? "ok" : "invalid:" + problems + ";" + snapshot;
        setupReady = true;
    }

    private Zombie spawnZombie(ServerLevel level, ServerPlayer player, double x, double z) {
        Zombie zombie = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
        if (zombie == null) return null;
        zombie.snapTo(x, center.getY(), z, x < center.getX() ? -90.0f : 90.0f, 0.0f);
        zombie.setNoAi(true);
        zombie.setPersistenceRequired();
        if (!level.addFreshEntity(zombie)) return null;
        zombie.setTarget(player);
        return zombie;
    }

    private void publishSetupFailure(String reason) {
        setupState = "invalid:" + reason;
        setupReady = true;
    }

    private volatile ServerSnapshot lastSetupSnapshot;

    private void startCapture() {
        mod.runUserTask(new IdleTask());
        phase = Phase.WAIT_IDLE;
        phaseStarted = clientTicks();
    }

    /** Spawn only after the idle task is active; Mob Defense stays disabled until both mobs synchronize. */
    private void queueHostiles() {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            incomplete("integrated server disappeared before hostile spawn");
            return;
        }
        server.execute(() -> {
            try {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player == null) throw new IllegalStateException("player=null");
                ServerLevel level = (ServerLevel) player.level();
                ArrayList<UUID> spawned = new ArrayList<>();
                Zombie left = spawnZombie(level, player, center.getX() + 0.5 - 4.5, center.getZ() + 0.5);
                Zombie right = spawnZombie(level, player, center.getX() + 0.5 + 4.5, center.getZ() + 0.5);
                if (left != null) spawned.add(left.getUUID());
                if (right != null) spawned.add(right.getUUID());
                zombieIds = List.copyOf(spawned);
                lastSetupSnapshot = snapshotServer(server, player, level);
                String problems = lastSetupSnapshot.setupProblems(zombieIds, playerId);
                if (!problems.isEmpty()) {
                    setupState = "invalid:" + problems + ";" + lastSetupSnapshot;
                    setupReady = true;
                    return;
                }
                hostilesSpawned = true;
            } catch (RuntimeException error) {
                setupState = "invalid:hostile spawn/capture error=" + error.getClass().getSimpleName() + ":" + error.getMessage();
                setupReady = true;
            }
        });
    }

    private void requestTerminalServerSnapshot() {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            serverPollError = "integrated-server=null";
            serverPollReady = true;
            return;
        }
        serverPollOutstanding = true;
        server.execute(() -> {
            try {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player == null) throw new IllegalStateException("player=null");
                serverSnapshot = snapshotServer(server, player, (ServerLevel) player.level());
            } catch (RuntimeException error) {
                serverPollError = error.getClass().getSimpleName() + ":" + error.getMessage();
            } finally {
                serverPollOutstanding = false;
                serverPollReady = true;
            }
        });
    }

    private ServerSnapshot snapshotServer(MinecraftServer server, ServerPlayer player, ServerLevel level) {
        List<String> inventory = inventory(player);
        List<String> armor = armor(player);
        List<String> nearby = level.getEntitiesOfClass(Monster.class, fixtureBounds,
                        entity -> entity.isAlive() && entity.distanceToSqr(player) <= 100.0)
                .stream().map(entity -> entity.getUUID().toString()).sorted().toList();
        List<String> zombieStates = new ArrayList<>();
        List<String> zombieTargets = new ArrayList<>();
        for (UUID id : zombieIds) {
            Entity entity = level.getEntity(id);
            if (entity instanceof Zombie zombie) {
                zombieStates.add(zombie.getUUID() + ",alive=" + zombie.isAlive() + ",noAi=" + zombie.isNoAi()
                        + ",pos=" + xyz(zombie) + ",health=" + zombie.getHealth());
                zombieTargets.add(zombie.getUUID() + "->" + (zombie.getTarget() == null ? "none" : zombie.getTarget().getUUID())
                        + ",distance=" + String.format(java.util.Locale.ROOT, "%.3f", zombie.distanceTo(player))
                        + ",los=" + zombie.hasLineOfSight(player));
            } else zombieStates.add(id + ",missing");
        }
        long nearbyProjectiles = level.getEntitiesOfClass(Projectile.class, fixtureBounds,
                entity -> entity.isAlive() && entity.distanceToSqr(player) <= 100.0).size();
        return new ServerSnapshot(level.getGameTime(), player.getUUID(),
                player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL,
                player.isAlive(), player.getHealth(), player.getFoodData().getFoodLevel(),
                player.getArmorValue(), player.getInventory().getSelectedSlot(),
                describeStack(player.getMainHandItem()), inventory, armor,
                player.containerMenu == player.inventoryMenu && player.inventoryMenu.getCarried().isEmpty()
                        && player.inventoryMenu.getSlot(1).getItem().isEmpty()
                        && player.inventoryMenu.getSlot(2).getItem().isEmpty()
                        && player.inventoryMenu.getSlot(3).getItem().isEmpty()
                        && player.inventoryMenu.getSlot(4).getItem().isEmpty(),
                nearby, List.copyOf(zombieStates), (int) nearbyProjectiles,
                position(player), List.copyOf(zombieTargets));
    }

    private ClientSnapshot clientSnapshot() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null || client.player.getUUID() == null) return null;
        List<String> trackedHostiles = mod.getEntityTracker().getHostiles().stream()
                .filter(Entity::isAlive).map(entity -> entity.getUUID().toString()).sorted().toList();
        List<String> trackedProjectiles = mod.getEntityTracker().getProjectiles().stream()
                .map(cached -> cached.projectileType + "@" + cached.position + "/" + cached.velocity).toList();
        List<String> zombieStates = new ArrayList<>();
        for (UUID id : zombieIds) {
            Entity entity = client.level.getEntity(id);
            if (entity instanceof Zombie zombie) {
                zombieStates.add(zombie.getUUID() + ",alive=" + zombie.isAlive() + ",noAi=" + zombie.isNoAi()
                        + ",pos=" + xyz(zombie) + ",health=" + zombie.getHealth());
            } else zombieStates.add(id + ",missing");
        }
        ArrayList<String> nearby = new ArrayList<>();
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity instanceof Monster && entity.isAlive() && entity.distanceToSqr(client.player) <= 100.0) {
                nearby.add(entity.getUUID().toString());
            }
        }
        nearby.sort(String::compareTo);
        return new ClientSnapshot(client.player.getUUID(), client.gameMode != null
                && client.gameMode.getPlayerMode() == GameType.SURVIVAL,
                client.player.isAlive(), client.player.getHealth(), client.player.getFoodData().getFoodLevel(),
                client.player.getArmorValue(), client.player.getInventory().getSelectedSlot(),
                describeStack(client.player.getMainHandItem()), inventory(client.player), armor(client.player),
                client.gui.screen() == null && client.player.containerMenu == client.player.inventoryMenu
                        && client.player.inventoryMenu.getCarried().isEmpty()
                        && client.player.inventoryMenu.getSlot(1).getItem().isEmpty()
                        && client.player.inventoryMenu.getSlot(2).getItem().isEmpty()
                        && client.player.inventoryMenu.getSlot(3).getItem().isEmpty()
                        && client.player.inventoryMenu.getSlot(4).getItem().isEmpty(),
                trackedHostiles, trackedProjectiles, List.copyOf(zombieStates), nearby,
                position(client.player));
    }

    private int firstMobDefenseEpochWithHostiles(MobDefenseCombatCapacityEventRecorder.Snapshot snapshot,
                                                  List<UUID> expectedHostiles) {
        Set<String> expected = expectedHostiles.stream().map(UUID::toString).collect(Collectors.toSet());
        for (int i = 0; i < snapshot.epochs().size(); i++) {
            var epoch = snapshot.epochs().get(i);
            List<MobDefenseCombatCapacityEventRecorder.HostileObservation> observed = epoch.mobDefenseHostiles();
            if (observed.size() == expected.size()
                    && observed.stream().map(MobDefenseCombatCapacityEventRecorder.HostileObservation::entityId)
                    .collect(Collectors.toSet()).equals(expected)) return i;
        }
        return -1;
    }

    private boolean hasExactNearbyZombies(MobDefenseCombatCapacityEventRecorder.Epoch epoch,
                                          List<UUID> expectedHostiles) {
        Set<String> expected = expectedHostiles.stream().map(UUID::toString).collect(Collectors.toSet());
        List<MobDefenseCombatCapacityEventRecorder.HostileObservation> observed = epoch.mobDefenseHostiles();
        return observed.size() == expected.size()
                && observed.stream().map(MobDefenseCombatCapacityEventRecorder.HostileObservation::entityId)
                .collect(Collectors.toSet()).equals(expected)
                && observed.stream().allMatch(hostile -> hostile.entityType().equals(Zombie.class.getName())
                && hostile.alive() && hostile.withinAnnoyingRange() && Math.abs(hostile.distance() - 4.5) < 0.001);
    }

    private static List<String> inventory(net.minecraft.world.entity.player.Player player) {
        ArrayList<String> values = new ArrayList<>();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            values.add(slot + "=" + describeStack(stack));
        }
        return List.copyOf(values);
    }

    private static String describeStack(ItemStack stack) {
        return stack.isEmpty() ? "empty" : stack.getItem() + " x" + stack.getCount()
                + " damage=" + stack.getDamageValue() + " components=" + stack.getComponents();
    }

    private static List<String> armor(net.minecraft.world.entity.LivingEntity player) {
        ArrayList<String> values = new ArrayList<>();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR) values.add(slot + "=" + player.getItemBySlot(slot));
        }
        return List.copyOf(values);
    }

    private Verdict inspectEpochs(MobDefenseCombatCapacityEventRecorder.Snapshot snapshot, Candidate candidate) {
        if (snapshot == null || snapshot.capturing() || snapshot.overflowed() || snapshot.epochs().size() < 2) {
            return Verdict.incomplete("capture missing, still active, overflowed, or has fewer than two epochs");
        }
        var first = snapshot.epochs().get(candidate.firstIndex);
        var second = snapshot.epochs().get(candidate.secondIndex);
        if (!first.selectionIntercepted() || !first.returnObserved() || first.preSelection() == null
                || first.postSelection() == null || first.priorities().isEmpty()) {
            return Verdict.incomplete("first timer-arming epoch lacks priority/selection/return evidence");
        }
        if (first.schedulerEpoch() + 1 != second.schedulerEpoch()) {
            return Verdict.incomplete("first eligible scheduler epochs are not consecutive");
        }
        if (first.connectionTick() < 0 || second.connectionTick() <= first.connectionTick()) {
            return Verdict.incomplete("timer clock did not prove a later client-connection tick: first="
                    + first.connectionTick() + ", second=" + second.connectionTick());
        }
        if (!hasExactNearbyZombies(first, zombieIds) || !hasExactNearbyZombies(second, zombieIds)) {
            return Verdict.fail("actual Mob Defense priority boundary did not observe both nearby fixture zombies: first="
                    + first.mobDefenseHostiles() + ", second=" + second.mobDefenseHostiles());
        }
        if (first.preSelection() == null || first.postSelection() == null
                || !first.preSelection().chainName().equals("User Tasks")
                || !first.preSelection().chainType().equals(UserTaskChain.class.getName())
                || !first.preSelection().rootTaskType().equals(IdleTask.class.getName())
                || !first.preSelection().rootTaskIdentity().equals(first.postSelection().rootTaskIdentity())
                || !first.selectedPointerMatches()
                || !first.assignments().isEmpty()) {
            return Verdict.fail("timer-arming epoch did not retain the commandless User Tasks IdleTask selection: " + first);
        }
        if (!second.selectionIntercepted() || !second.returnObserved() || second.preSelection() == null
                || second.postSelection() == null || second.priorities().isEmpty()) {
            return Verdict.incomplete("second scheduler epoch lacks priority/selection/return evidence");
        }
        if (Float.isNaN(priority(first, "Mob Defense")) || Float.isNaN(priority(first, "User Tasks"))
                || Float.isNaN(priority(second, "Mob Defense")) || Float.isNaN(priority(second, "User Tasks"))) {
            return Verdict.incomplete("required priority decisions missing or duplicated in timer/eligible epochs");
        }
        if (priority(first, "Mob Defense") != 0.0f || priority(first, "User Tasks") != 50.0f) {
            return Verdict.fail("first timer-armed epoch expected Mob Defense=0/User Tasks=50; got " + first.priorities());
        }
        if (!first.combatCapacityDecisions().isEmpty()) {
            return Verdict.fail("timer-arming epoch unexpectedly evaluated combat capacity: "
                    + first.combatCapacityDecisions());
        }
        if (priority(second, "Mob Defense") != 80.0f || priority(second, "User Tasks") != 50.0f) {
            return Verdict.fail("immediately following eligible epoch expected Mob Defense=80/User Tasks=50; got " + second.priorities());
        }
        if (second.combatCapacityDecisions().size() != 1) {
            return Verdict.incomplete("expected one recorded live combat-capacity input: " + second.combatCapacityDecisions());
        }
        var capacity = second.combatCapacityDecisions().get(0);
        if (!capacity.swordId().equals("minecraft:wooden_sword") || capacity.damage() != 1.0f
                || capacity.armorValue() != 0) {
            return Verdict.fail("live capacity branch did not use wooden-sword damage with no armor: " + capacity);
        }
        if (second.priorities().stream().anyMatch(priority -> !priority.name().equals("Mob Defense")
                && priority.returnedPriority() >= 80.0f)) {
            return Verdict.fail("another chain supplied priority >=80: " + second.priorities());
        }
        if (second.assignments().size() != 1) return Verdict.incomplete("expected one Mob Defense assignment: " + second.assignments());
        var assignment = second.assignments().get(0);
        if (assignment.detail().contains("runAwayAccessor=unavailable:")) {
            return Verdict.incomplete("RunAway task constructor evidence is unavailable: " + assignment.detail());
        }
        if (!assignment.chainIdentity().equals(second.preSelection().chainIdentity())
                || !assignment.taskType().equals(RunAwayFromHostilesTask.class.getName())
                || !assignment.detail().equals("distance=30.0;includeSkeletons=true")) {
            return Verdict.fail("assignment did not select RunAwayFromHostilesTask(distance=30, includeSkeletons=true): " + assignment);
        }
        if (!second.preSelection().chainType().equals(MobDefenseChain.class.getName())
                || !second.preSelection().rootTaskType().equals(RunAwayFromHostilesTask.class.getName())
                || !second.preSelection().rootTaskIdentity().equals(assignment.taskIdentity())
                || second.preSelection().rootTaskStarted() || !second.selectedPointerMatches()) {
            return Verdict.fail("pre-tick scheduler selection did not corroborate the assigned, unstarted MobDefense root: " + second.preSelection());
        }
        if (!second.postSelection().chainIdentity().equals(second.preSelection().chainIdentity())
                || !second.postSelection().rootTaskIdentity().equals(second.preSelection().rootTaskIdentity())) {
            return Verdict.fail("post-return scheduler pointer disagrees with pre-tick selection: " + second.postSelection());
        }
        return Verdict.pass();
    }

    private void finishSuccessfully() {
        // Keep diagnostic settings, both zombies, and task suppression active through archival.
        // Forcefield remains OFF because TaskRunner still evaluates priorities until process exit.
        phase = Phase.DONE;
        success.run();
    }

    private void fail(String message) {
        if (phase == Phase.DONE || phase == Phase.FAILED) return;
        phase = Phase.FAILED;
        if (MobDefenseCombatCapacityEventRecorder.isCapturing()) {
            capturedEvents = MobDefenseCombatCapacityEventRecorder.closeAndSnapshot();
            append.accept("MOB_DEFENSE_CAPACITY_EPOCHS\t" + renderEvents(capturedEvents));
        }
        MobDefenseCombatCapacityEventRecorder.holdTaskTicksForDiagnostics();
        // The packaged runner archives failed logs, TSV, and screenshots after SUMMARY FAIL.
        // Preserve the fixture and suppression latch until that archive is captured.
        failure.accept(message);
    }

    private void incomplete(String detail) {
        fail("EVIDENCE_INCOMPLETE\t" + detail);
    }

    private boolean timedOut(int limit) { return clientTicks() - phaseStarted > limit; }
    private static long clientTicks() { return Minecraft.getInstance().level == null ? 0 : Minecraft.getInstance().level.getGameTime(); }

    private String renderEvents(MobDefenseCombatCapacityEventRecorder.Snapshot snapshot) {
        if (snapshot == null) return "missing";
        return "capture=" + snapshot.captureId() + ",overflow=" + snapshot.overflowed() + ",epochs=" + snapshot.epochs();
    }

    private static float priority(MobDefenseCombatCapacityEventRecorder.Epoch epoch, String name) {
        List<MobDefenseCombatCapacityEventRecorder.ChainPriority> matches = epoch.priorities().stream()
                .filter(entry -> entry.name().equals(name)).toList();
        return matches.size() == 1 ? matches.get(0).returnedPriority() : Float.NaN;
    }

    private static String xyz(Entity entity) {
        return Double.toString(entity.getX()) + "," + Double.toString(entity.getY()) + ","
                + Double.toString(entity.getZ());
    }

    private static Position position(Entity entity) {
        return new Position(entity.getX(), entity.getY(), entity.getZ());
    }

    private record Verdict(String incomplete, String failure) {
        static Verdict incomplete(String message) { return new Verdict(message, null); }
        static Verdict fail(String message) { return new Verdict(null, message); }
        static Verdict pass() { return new Verdict(null, null); }
    }

    private record Candidate(int firstIndex, int secondIndex) { }

    private record Position(double x, double y, double z) { }

    private record ServerSnapshot(long gameTime, UUID playerId, boolean survival, boolean alive, float health,
                                  int food, int armorValue, int selectedSlot, String mainHand,
                                  List<String> inventory, List<String> armor,
                                  boolean uiClean, List<String> nearbyHostileIds, List<String> zombies,
                                  int nearbyProjectileCount, Position playerPosition, List<String> zombieTargets) {
        ServerSnapshot {
            inventory = List.copyOf(inventory); armor = List.copyOf(armor);
            nearbyHostileIds = List.copyOf(nearbyHostileIds); zombies = List.copyOf(zombies); zombieTargets = List.copyOf(zombieTargets);
        }
        String setupProblems(List<UUID> expected, UUID player) {
            ArrayList<String> problems = new ArrayList<>();
            if (!playerId.equals(player)) problems.add("player UUID changed");
            if (!survival || !alive || health != 20.0f || food != 20 || armorValue != 0) problems.add("player state invalid");
            if (selectedSlot != 0 || !mainHand.contains("wooden_sword x1 ")) problems.add("selected main hand is not the sole wooden sword");
            if (inventory.stream().filter(value -> value.contains("wooden_sword x1 ")).count() != 1
                    || inventory.stream().filter(value -> !value.endsWith("=empty")).count() != 1) problems.add("inventory not exactly one wooden sword");
            if (!uiClean) problems.add("cursor/grid/menu dirty");
            if (nearbyHostileIds.size() != 2 || !Set.copyOf(nearbyHostileIds).equals(expected.stream().map(UUID::toString).collect(Collectors.toSet()))) problems.add("nearby server hostile IDs mismatch");
            if (zombies.size() != 2 || zombies.stream().anyMatch(value -> value.contains("missing") || !value.contains("alive=true") || !value.contains("noAi=true"))
                    || zombieTargets.size() != 2 || zombieTargets.stream().anyMatch(value -> !value.contains("->" + player + ",distance=4.500,los=true"))) problems.add("zombie identity/target/range/LOS/alive/AI invalid");
            if (nearbyProjectileCount != 0) problems.add("nearby server projectiles=" + nearbyProjectileCount);
            return String.join(",", problems);
        }
        String setupBaseProblems(UUID player) {
            ArrayList<String> problems = new ArrayList<>();
            if (!playerId.equals(player)) problems.add("player UUID changed");
            if (!survival || !alive || health != 20.0f || food != 20 || armorValue != 0) problems.add("player state invalid");
            if (selectedSlot != 0 || !mainHand.contains("wooden_sword x1 ")) problems.add("selected main hand is not the sole wooden sword");
            if (inventory.stream().filter(value -> value.contains("wooden_sword x1 ")).count() != 1
                    || inventory.stream().filter(value -> !value.endsWith("=empty")).count() != 1) problems.add("inventory not exactly one wooden sword");
            if (!uiClean) problems.add("cursor/grid/menu dirty");
            if (nearbyHostileIds.size() != 0 || nearbyProjectileCount != 0) problems.add("arena not cleared before fixture");
            return String.join(",", problems);
        }
        boolean validTerminal(List<UUID> expected, UUID player) { return setupProblems(expected, player).isEmpty() && nearbyProjectileCount == 0; }
    }

    private record ClientSnapshot(UUID playerId, boolean survival, boolean alive, float health, int food,
                                  int armorValue, int selectedSlot, String mainHand,
                                  List<String> inventory, List<String> armor, boolean uiClean,
                                  List<String> trackedHostileIds, List<String> trackedProjectileIds,
                                  List<String> zombies, List<String> nearbyHostileIds, Position playerPosition) {
        ClientSnapshot {
            inventory = List.copyOf(inventory); armor = List.copyOf(armor);
            trackedHostileIds = List.copyOf(trackedHostileIds); trackedProjectileIds = List.copyOf(trackedProjectileIds);
            zombies = List.copyOf(zombies); nearbyHostileIds = List.copyOf(nearbyHostileIds);
        }
        boolean isSynchronizedWith(ServerSnapshot server, List<UUID> expectedHostiles) {
            Set<String> expected = expectedHostiles.stream().map(UUID::toString).collect(Collectors.toSet());
            return server != null && playerId.equals(server.playerId) && survival == server.survival && alive == server.alive
                    && Math.abs(health - server.health) < 0.01f && food == server.food && armorValue == server.armorValue
                    && selectedSlot == server.selectedSlot && mainHand.equals(server.mainHand)
                    && inventory.equals(server.inventory) && armor.equals(server.armor) && uiClean && server.uiClean
                    && zombies.equals(server.zombies) && nearbyHostileIds.equals(server.nearbyHostileIds)
                    && playerPosition.equals(server.playerPosition)
                    && trackedHostileIds.size() == expected.size()
                    && Set.copyOf(trackedHostileIds).equals(expected)
                    && nearbyHostileIds.size() == expected.size()
                    && Set.copyOf(nearbyHostileIds).equals(expected)
                    && trackedProjectileIds.isEmpty();
        }
        boolean validTerminal(List<UUID> expected, UUID player) {
            return playerId.equals(player) && survival && alive && health == 20.0f && food == 20 && armorValue == 0
                    && selectedSlot == 0 && mainHand.contains("wooden_sword x1 ")
                    && uiClean && inventory.stream().filter(value -> !value.endsWith("empty")).count() == 1
                    && inventory.stream().filter(value -> value.contains("wooden_sword x1 ")).count() == 1
                    && trackedHostileIds.size() == 2 && Set.copyOf(trackedHostileIds).equals(expected.stream().map(UUID::toString).collect(Collectors.toSet()))
                    && trackedProjectileIds.isEmpty() && nearbyHostileIds.size() == 2
                    && Set.copyOf(nearbyHostileIds).equals(expected.stream().map(UUID::toString).collect(Collectors.toSet()))
                    && zombies.size() == 2 && zombies.stream().noneMatch(value -> value.contains("missing") || !value.contains("alive=true") || !value.contains("noAi=true"));
        }
        @Override public String toString() {
            return "player=" + playerId + ",survival=" + survival + ",alive=" + alive + ",health=" + health
                    + ",food=" + food + ",armor=" + armorValue + ",inventory=" + inventory + ",equipment=" + armor
                    + ",uiClean=" + uiClean + ",trackedHostiles=" + trackedHostileIds + ",trackedProjectiles=" + trackedProjectileIds
                    + ",nearbyHostiles=" + nearbyHostileIds + ",zombies=" + zombies + ",pos=" + playerPosition;
        }
    }

    private static final class SettingsState {
        private SettingsState() { }

        static void applyDiagnostic(Settings settings) throws ReflectiveOperationException {
            // Keep the chain disabled while the old world is being cleared. Enable it immediately
            // before opening capture and spawning the two controlled hostiles. The packaged
            // runner exits the disposable test client without restoring these diagnostic values.
            set(settings, "mobDefense", false); set(settings, "dodgeProjectiles", true);
            set(settings, "killOrAvoidAnnoyingHostiles", true);
            set(settings, "killAnnoyingHostileWhenCloseForSeconds", 0.0);
            set(settings, "forceFieldStrategy", KillAura.Strategy.OFF);
        }
        static void enableDiagnosticMobDefense(Settings settings) {
            try {
                set(settings, "mobDefense", true);
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("could not enable Mob Defense", error);
            }
            if (!settings.isMobDefense() || !settings.isDodgeProjectiles()
                    || !settings.shouldDealWithAnnoyingHostiles()
                    || settings.getKillHostileWhenCloseForSeconds() != 0.0
                    || settings.getForceFieldStrategy() != KillAura.Strategy.OFF) {
                throw new IllegalStateException("diagnostic Mob Defense settings did not enable");
            }
        }
        private static void set(Settings settings, String name, Object value) throws ReflectiveOperationException {
            Field field = Settings.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(settings, value);
        }
    }
}
