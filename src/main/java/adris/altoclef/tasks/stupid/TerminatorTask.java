package adris.altoclef.tasks.stupid;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.construction.PlaceBlockTask;
import adris.altoclef.tasks.construction.PlaceStructureBlockTask;
import adris.altoclef.tasks.entity.DoToClosestEntityTask;
import adris.altoclef.tasks.misc.EquipArmorTask;
import adris.altoclef.tasks.entity.KillPlayerTask;
import adris.altoclef.tasks.movement.RunAwayFromEntitiesTask;
import adris.altoclef.tasks.movement.SearchChunksExploreTask;
import adris.altoclef.tasks.resources.CollectFoodTask;
import adris.altoclef.tasks.slot.MoveItemToSlotFromInventoryTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.ui.MessagePriority;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.time.TimerGame;
import adris.altoclef.util.helpers.BaritoneHelper;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import adris.altoclef.util.slots.PlayerSlot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Gears up, then roams around the world to punk the nearest eligible player.
 */
public class TerminatorTask extends Task {

    private static final int FEAR_SEE_DISTANCE = 30;
    private static final int FEAR_DISTANCE = 20;
    private static final int RUN_AWAY_DISTANCE = 80;

    private static final int MIN_BUILDING_BLOCKS = 16;
    private static final int PREFERRED_BUILDING_BLOCKS = 32;
    private static final int ARROWS_TO_COLLECT = 64;
    private static final int FOOD_UNITS_TO_COLLECT = 80;

    private static final Item[] GEAR_TO_COLLECT = new Item[]{
            Items.DIAMOND_PICKAXE, Items.DIAMOND_AXE, Items.DIAMOND_SHOVEL, Items.DIAMOND_SWORD,
            Items.BOW, Items.SHIELD
    };
    private final Task _prepareDiamondMiningEquipmentTask = TaskCatalogue.getSquashedItemTask(
            new ItemTarget(Items.IRON_PICKAXE, 1)
    );
    private final Task _foodTask = new CollectFoodTask(FOOD_UNITS_TO_COLLECT);
    private final Task _equipShieldTask = new MoveItemToSlotFromInventoryTask(
            new ItemTarget(Items.SHIELD), PlayerSlot.OFFHAND_SLOT
    );
    private final TimerGame _runAwayExtraTime = new TimerGame(10);
    private final Predicate<Player> _canTerminate;
    private final ScanChunksInRadius _scanTask;
    private final TimerGame _funnyMessageTimer = new TimerGame(10);
    private Vec3 _closestPlayerLastPos;
    private Vec3 _closestPlayerLastObservePos;
    private Task _runAwayTask;
    private String _currentVisibleTarget;

    private Task _armorTask;
    private boolean _initialFoodCollected;

    public TerminatorTask(BlockPos center, double scanRadius, Predicate<Player> canTerminate) {
        _canTerminate = canTerminate;
        _scanTask = new ScanChunksInRadius(center, scanRadius);
    }

    public TerminatorTask(BlockPos center, double scanRadius) {
        this(center, scanRadius, accept -> true);
    }

    @Override
    protected void onStart(AltoClef mod) {
        mod.getBehaviour().push();
        mod.getBehaviour().setForceFieldPlayers(true);
    }

    @Override
    protected Task onTick(AltoClef mod) {

        Optional<Entity> closest = mod.getEntityTracker().getClosestEntity(mod.getPlayer().position(), toPunk -> shouldPunk(mod, (Player) toPunk), Player.class);

        if (closest.isPresent()) {
            _closestPlayerLastPos = closest.get().position();
            _closestPlayerLastObservePos = mod.getPlayer().position();
        }

        if (!isReadyToPunk(mod)) {

            if (_runAwayTask != null && _runAwayTask.isActive() && !_runAwayTask.isFinished(mod)) {
                // If our last "scare" was too long ago or there are no more nearby players...
                boolean noneRemote = (closest.isEmpty() || !closest.get().closerThan(mod.getPlayer(), FEAR_DISTANCE));
                if (_runAwayExtraTime.elapsed() && noneRemote) {
                    Debug.logMessage("Stop running away, we're good.");
                    // Stop running away.
                    _runAwayTask = null;
                } else {
                    return _runAwayTask;
                }
            }

            // See if there's anyone nearby.
            if (mod.getEntityTracker().getClosestEntity(mod.getPlayer().position(), entityAccept -> {
                if (!shouldPunk(mod, (Player) entityAccept)) {
                    return false;
                }
                if (entityAccept.closerThan(mod.getPlayer(), 15)) {
                    // We're close, count us.
                    return true;
                } else {
                    // Too far away.
                    if (!entityAccept.closerThan(mod.getPlayer(), FEAR_DISTANCE)) return false;
                    // We may be far and obstructed, check.
                    return LookHelper.seesPlayer(entityAccept, mod.getPlayer(), FEAR_SEE_DISTANCE);
                }
            }, Player.class).isPresent()) {
                // RUN!

                _runAwayExtraTime.reset();
                try {
                    _runAwayTask = new RunAwayFromPlayersTask(() -> {
                        Stream<Player> stream = mod.getEntityTracker().getTrackedEntities(Player.class).stream();
                        synchronized (BaritoneHelper.MINECRAFT_LOCK) {
                            return stream.filter(toAccept -> shouldPunk(mod, toAccept)).collect(Collectors.toList());
                        }
                    }, RUN_AWAY_DISTANCE);
                } catch (ConcurrentModificationException e) {
                    // oof
                    Debug.logWarning("Duct tape over ConcurrentModificationException (see log)");
                    e.printStackTrace();
                }
                setDebugState("Running away from players.");
                return _runAwayTask;
            }
        } else {
            // We can totally punk
            if (_runAwayTask != null) {
                _runAwayTask = null;
                Debug.logMessage("Stopped running away because we can now punk.");
            }

            // Refill only when low on food or health; food is not part of the initial PvP kit.
            if ((mod.getPlayer().getFoodData().getFoodLevel() < (20 - 3 * 2) || mod.getPlayer().getHealth() < 10)
                    && StorageHelper.calculateInventoryFoodScore(mod) <= 0) {
                return _foodTask;
            }

            if (mod.getEntityTracker().getClosestEntity(mod.getPlayer().position(), toPunk -> shouldPunk(mod, (Player) toPunk), Player.class).isPresent()) {
                setDebugState("Punking.");
                return new DoToClosestEntityTask(
                    entity -> {
                        if (entity instanceof Player) {
                            tryDoFunnyMessageTo(mod, (Player) entity);
                            return new KillPlayerTask(entity.getName().getString());
                        }
                        // Should never happen.
                        Debug.logWarning("This should never happen.");
                        return _scanTask;
                    },
                    interact -> shouldPunk(mod, (Player) interact),
                    Player.class
                );
            }
        }

        // Get stacked first
        // Equip diamond armor asap
        if (_armorTask != null && _armorTask.isActive() && !_armorTask.isFinished(mod)) {
            setDebugState("Collecting Diamond Armor");
            return _armorTask;
        }

        // Get iron pickaxes first
        if (!mod.getItemStorage().hasItem(Items.DIAMOND_PICKAXE) && mod.getItemStorage().getItemCount(Items.DIAMOND) < 3) {
            if (!mod.getItemStorage().hasItem(Items.IRON_PICKAXE) || (_prepareDiamondMiningEquipmentTask.isActive() && !_prepareDiamondMiningEquipmentTask.isFinished(mod))) {
                setDebugState("Getting iron pickaxes to mine diamonds");
                return _prepareDiamondMiningEquipmentTask;
            }
        }

        // Collect a food reserve before beginning the spree. Refill later only when needed.
        if (!_initialFoodCollected) {
            if (StorageHelper.calculateInventoryFoodScore(mod) < FOOD_UNITS_TO_COLLECT
                    || (_foodTask.isActive() && !_foodTask.isFinished(mod))) {
                setDebugState("Collecting food");
                return _foodTask;
            }
            _initialFoodCollected = true;
        }

        if (((mod.getPlayer().getFoodData().getFoodLevel() < (20 - 3 * 2) || mod.getPlayer().getHealth() < 10)
                && StorageHelper.calculateInventoryFoodScore(mod) <= 0)
                || (_foodTask.isActive() && !_foodTask.isFinished(mod))) {
            setDebugState("Collecting food");
            return _foodTask;
        }

        // If we're not all equip, do equip
        if (!StorageHelper.isArmorEquippedAll(mod, ItemHelper.DIAMOND_ARMORS)) {
            _armorTask = new EquipArmorTask(ItemHelper.DIAMOND_ARMORS);
            return _armorTask;
        }

        // Get gear one by one...
        for (Item gear : GEAR_TO_COLLECT) {
            if (!mod.getItemStorage().hasItem(gear)) {
                setDebugState("Collecting gear");
                return TaskCatalogue.getItemTask(gear, 1);
            }
        }

        if (mod.getItemStorage().getItemCount(Items.ARROW) < ARROWS_TO_COLLECT) {
            setDebugState("Collecting arrows");
            return TaskCatalogue.getItemTask(Items.ARROW, ARROWS_TO_COLLECT);
        }

        if (StorageHelper.getItemStackInSlot(PlayerSlot.OFFHAND_SLOT).getItem() != Items.SHIELD) {
            setDebugState("Equipping shield");
            return _equipShieldTask;
        }

        if (PlaceStructureBlockTask.getMaterialCount(mod) < MIN_BUILDING_BLOCKS) {
            setDebugState("Collecting building materials");
            return PlaceBlockTask.getMaterialTask(PREFERRED_BUILDING_BLOCKS);
        }


        setDebugState("Scanning for players...");
        _currentVisibleTarget = null;
        if (_scanTask.failedSearch()) {
            Debug.logMessage("Re-searching missed places.");
            _scanTask.resetSearch(mod);
        }

        return _scanTask;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getBehaviour().pop();
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof TerminatorTask;
    }

    @Override
    protected String toDebugString() {
        return "Killing Spree";
    }

    private boolean isReadyToPunk(AltoClef mod) {
        if (mod.getPlayer().getHealth() <= 5) return false; // We need to heal.
        if (!_initialFoodCollected) return false;
        if (!StorageHelper.isArmorEquippedAll(mod, ItemHelper.DIAMOND_ARMORS)) return false;
        for (Item gear : GEAR_TO_COLLECT) {
            if (!mod.getItemStorage().hasItem(gear)) return false;
        }
        return mod.getItemStorage().getItemCount(Items.ARROW) >= ARROWS_TO_COLLECT
                && StorageHelper.getItemStackInSlot(PlayerSlot.OFFHAND_SLOT).getItem() == Items.SHIELD
                && PlaceStructureBlockTask.getMaterialCount(mod) >= MIN_BUILDING_BLOCKS;
    }

    private boolean shouldPunk(AltoClef mod, Player player) {
        if (player == null || player.isDeadOrDying()) return false;
        if (player.isCreative() || player.isSpectator()) return false;
        return !mod.getButler().isUserAuthorized(player.getName().getString()) && _canTerminate.test(player);
    }

    private void tryDoFunnyMessageTo(AltoClef mod, Player player) {
        if (_funnyMessageTimer.elapsed()) {
            if (LookHelper.seesPlayer(player, mod.getPlayer(), 80)) {
                String name = player.getName().getString();
                if (_currentVisibleTarget == null || !_currentVisibleTarget.equals(name)) {
                    _currentVisibleTarget = name;
                    _funnyMessageTimer.reset();
                    String funnyMessage = getRandomFunnyMessage();
                    mod.getMessageSender().enqueueWhisper(name, funnyMessage, MessagePriority.ASAP);
                }
            }
        }
    }

    private String getRandomFunnyMessage() {
        return "Prepare to get punked, kid";
    }

    private class ScanChunksInRadius extends SearchChunksExploreTask {

        private final BlockPos _center;
        private final double _radius;

        public ScanChunksInRadius(BlockPos center, double radius) {
            _center = center;
            _radius = radius;
        }

        @Override
        protected boolean isChunkWithinSearchSpace(AltoClef mod, ChunkPos pos) {
            double cx = (pos.getMinBlockX() + pos.getMaxBlockX()) / 2.0;
            double cz = (pos.getMinBlockZ() + pos.getMaxBlockZ()) / 2.0;
            double dx = _center.getX() - cx,
                    dz = _center.getZ() - cz;
            return dx * dx + dz * dz < _radius * _radius;
        }

        @Override
        protected ChunkPos getBestChunkOverride(AltoClef mod, List<ChunkPos> chunks) {
            // Prioritise the chunk we last saw a player in.
            if (_closestPlayerLastPos != null) {
                double lowestScore = Double.POSITIVE_INFINITY;
                ChunkPos bestChunk = null;
                for (ChunkPos toSearch : chunks) {
                    double cx = (toSearch.getMinBlockX() + toSearch.getMaxBlockX() + 1) / 2.0, cz = (toSearch.getMinBlockZ() + toSearch.getMaxBlockZ() + 1) / 2.0;
                    double px = mod.getPlayer().getX(), pz = mod.getPlayer().getZ();
                    double distanceSq = (cx - px) * (cx - px) + (cz - pz) * (cz - pz);
                    double pdx = _closestPlayerLastPos.x - cx, pdz = _closestPlayerLastPos.z - cz;
                    double distanceToLastPlayerPos = pdx * pdx + pdz * pdz;
                    Vec3 direction = _closestPlayerLastPos.subtract(_closestPlayerLastObservePos).multiply(1, 0, 1).normalize();
                    double dirx = direction.x, dirz = direction.z;
                    double correctDistance = pdx * dirx + pdz * dirz;
                    double tempX = dirx * correctDistance,
                            tempZ = dirz * correctDistance;
                    double perpendicularDistance = ((pdx - tempX) * (pdx - tempX)) + ((pdz - tempZ) * (pdz - tempZ));
                    double score = distanceSq + distanceToLastPlayerPos * 0.6 - correctDistance * 2 + perpendicularDistance * 0.5;
                    if (score < lowestScore) {
                        lowestScore = score;
                        bestChunk = toSearch;
                    }
                }
                return bestChunk;
            }
            return super.getBestChunkOverride(mod, chunks);
        }

        @Override
        protected boolean isEqual(Task other) {
            if (other instanceof ScanChunksInRadius scan) {
                return scan._center.equals(_center) && Math.abs(scan._radius - _radius) <= 1;
            }
            return false;
        }

        @Override
        protected String toDebugString() {
            return "Scanning around a radius";
        }
    }

    private static class RunAwayFromPlayersTask extends RunAwayFromEntitiesTask {

        public RunAwayFromPlayersTask(Supplier<List<Entity>> toRunAwayFrom, double distanceToRun) {
            super(toRunAwayFrom, distanceToRun, true, 0.1);
            // More lenient progress checker
            _checker = new MovementProgressChecker(2);
        }

        @Override
        protected boolean isEqual(Task other) {
            return other instanceof RunAwayFromPlayersTask;
        }

        @Override
        protected String toDebugString() {
            return "Running away from players";
        }
    }
}
