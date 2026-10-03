package adris.altoclef.trackers.storage;

import adris.altoclef.Debug;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.BlockInteractEvent;
import adris.altoclef.eventbus.events.NonBlockInteractEvent;
import adris.altoclef.eventbus.events.ScreenOpenEvent;
import adris.altoclef.trackers.Tracker;
import adris.altoclef.trackers.TrackerManager;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.BrewingStandBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.client.gui.screens.inventory.BlastFurnaceScreen;
import net.minecraft.client.gui.screens.inventory.BrewingStandScreen;
import net.minecraft.client.gui.screens.inventory.FurnaceScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.DispenserScreen;
import net.minecraft.client.gui.screens.inventory.HopperScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.client.gui.screens.inventory.SmokerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import adris.altoclef.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.function.Predicate;

/**
 * Keeps track of items in containers
 */
public class ContainerSubTracker extends Tracker {

    private static final boolean DIAGNOSTICS = Boolean.getBoolean("altoclef.containerSessionDiagnostics");

    private final ContainerMenuSession _containerSession = new ContainerMenuSession();
    private final HashMap<Dimension, HashMap<BlockPos, ContainerCache>> _containerCaches = new HashMap<>();
    private ContainerCache _enderChestCache;
    private boolean _hasSentError;
    // Client game time can jump backwards when the server resyncs it, so pending
    // interactions are aged with a monotonic client tick counter instead.
    private long _clientTick;

    public ContainerSubTracker(TrackerManager manager) {
        super(manager);
        for (Dimension dimension : Dimension.values()) {
            _containerCaches.put(dimension, new HashMap<>());
        }

        // Listen for when we interact with a block
        EventBus.subscribe(BlockInteractEvent.class, evt -> {
            BlockPos blockPos = evt.hitResult.getBlockPos();
            BlockState bs = evt.world.getBlockState(blockPos);
            Minecraft minecraft = Minecraft.getInstance();
            LocalPlayer player = minecraft.player;
            if (player != null && minecraft.level == evt.world) {
                onBlockInteract(evt.world, player, player.containerMenu, blockPos, bs.getBlock(), _clientTick);
            }
        });
        EventBus.subscribe(ScreenOpenEvent.class, evt -> {
            if (!evt.preOpen) onScreenChanged(evt.screen);
        });
        EventBus.subscribe(NonBlockInteractEvent.class, evt -> {
            if (DIAGNOSTICS) Debug.logInternal("[CONTAINER_SESSION] non-block interact clears pending, tick=" + _clientTick);
            _containerSession.clearPendingInteraction();
        });
    }

    private void onBlockInteract(Object world, Object player, Object originMenu,
                                 BlockPos pos, Block block, long currentTick) {
        if (DIAGNOSTICS) {
            Debug.logInternal("[CONTAINER_SESSION] interact pos=" + pos.toShortString() + ",block=" + block
                    + ",tracked=" + isTrackedContainerBlock(block) + ",tick=" + currentTick);
        }
        if (isTrackedContainerBlock(block)) {
            _containerSession.noteInteraction(world, player, originMenu, pos, block, currentTick);
        }
    }

    private void onScreenChanged(final Screen screen) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        AbstractContainerMenu playerMenu = player == null ? null : player.containerMenu;
        boolean trackedScreen = screen != null && isTrackedContainerScreen(screen.getClass());
        boolean screenMenuMatchesPlayerMenu = trackedScreen
                && screen instanceof MenuAccess<?> menuAccess
                && menuAccess.getMenu() == playerMenu;

        _containerSession.onScreenChanged(minecraft.level, player, playerMenu, trackedScreen, _clientTick,
                screenMenuMatchesPlayerMenu,
                block -> containerMenuMatchesBlock(block, playerMenu),
                (position, block) -> minecraft.level != null
                        && minecraft.level.getBlockState(position).getBlock() == block);
        _hasSentError = false;
        if (DIAGNOSTICS) {
            Debug.logInternal("[CONTAINER_SESSION] screen=" + (screen == null ? "null" : screen.getClass().getSimpleName())
                    + ",tracked=" + trackedScreen + ",menuMatches=" + screenMenuMatchesPlayerMenu
                    + ",bound=" + _containerSession.getBoundContainer(minecraft.level, player, playerMenu,
                    (position, block) -> true).isPresent() + ",tick=" + _clientTick);
        }
    }

    static boolean isTrackedContainerBlock(Block block) {
        return block instanceof AbstractFurnaceBlock
                || block instanceof ChestBlock
                || block.equals(Blocks.ENDER_CHEST)
                || block instanceof HopperBlock
                || block instanceof ShulkerBoxBlock
                || block instanceof DispenserBlock
                || block instanceof BarrelBlock
                || block instanceof BrewingStandBlock;
    }

    static boolean isTrackedContainerScreen(Class<?> screenClass) {
        return FurnaceScreen.class.isAssignableFrom(screenClass)
                || ContainerScreen.class.isAssignableFrom(screenClass)
                || DispenserScreen.class.isAssignableFrom(screenClass)
                || SmokerScreen.class.isAssignableFrom(screenClass)
                || BlastFurnaceScreen.class.isAssignableFrom(screenClass)
                || HopperScreen.class.isAssignableFrom(screenClass)
                || ShulkerBoxScreen.class.isAssignableFrom(screenClass)
                || BrewingStandScreen.class.isAssignableFrom(screenClass);
    }
    public void onServerTick() {
        _clientTick++;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            _containerSession.clear();
            return;
        }
        _containerSession.expirePendingInteraction(_clientTick);

        AbstractContainerMenu handler = minecraft.player.containerMenu;
        Optional<ContainerMenuSession.BoundContainer> openContainer = getBoundContainer(handler);
        if (openContainer.isEmpty()) return;
        ContainerMenuSession.BoundContainer bound = openContainer.get();
        BlockPos containerPos = bound.position();
        HashMap<BlockPos, ContainerCache> dimCache = _containerCaches.get(WorldHelper.getCurrentDimension());

        // Container Type Mismatch, reset.
        if (dimCache.containsKey(containerPos)) {
            ContainerType currentType = dimCache.get(containerPos).getContainerType();
            if (!ContainerType.screenHandlerMatches(currentType, handler)) {
                if (!_hasSentError) {
                    Debug.logMessage("Mismatched container screen at " + containerPos.toShortString()
                            + ", will overwrite container data: " + handler.getType() + " ?=> " + currentType);
                    _hasSentError = true;
                }
                dimCache.remove(containerPos);
            }
        }

        // New container found
        if (!dimCache.containsKey(containerPos)) {
            ContainerType interactType = ContainerType.getFromBlock(bound.block());
            ContainerCache newCache = new ContainerCache(WorldHelper.getCurrentDimension(), containerPos, interactType);
            dimCache.put(containerPos, newCache);
            if (interactType == ContainerType.ENDER_CHEST) {
                _enderChestCache = newCache;
            }
        }

        ContainerCache toUpdate = dimCache.get(containerPos);
        toUpdate.update(handler, stack -> { });
    }

    private Optional<ContainerMenuSession.BoundContainer> getBoundContainer(AbstractContainerMenu menu) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || menu == null) return Optional.empty();
        Screen screen = minecraft.gui.screen();
        if (screen == null || !isTrackedContainerScreen(screen.getClass())
                || !(screen instanceof MenuAccess<?> menuAccess)
                || menuAccess.getMenu() != menu) return Optional.empty();
        return _containerSession.getBoundContainer(minecraft.level, minecraft.player, menu,
                (position, block) -> minecraft.level.getBlockState(position).getBlock() == block);
    }

    public Optional<BlockPos> getContainerPositionForMenu(AbstractContainerMenu menu) {
        return getBoundContainer(menu).map(ContainerMenuSession.BoundContainer::position);
    }

    static boolean containerMenuMatchesBlock(Block block, AbstractContainerMenu menu) {
        return menu != null && containerMenuClassMatchesBlock(block, menu.getClass());
    }

    static boolean containerMenuClassMatchesBlock(Block block, Class<?> menuClass) {
        if (block == null || menuClass == null) return false;
        if (block instanceof ChestBlock || block == Blocks.ENDER_CHEST || block instanceof BarrelBlock) {
            return ChestMenu.class.isAssignableFrom(menuClass);
        }
        if (block instanceof ShulkerBoxBlock) return ShulkerBoxMenu.class.isAssignableFrom(menuClass);
        if (block instanceof AbstractFurnaceBlock) return AbstractFurnaceMenu.class.isAssignableFrom(menuClass);
        if (block instanceof BrewingStandBlock) return BrewingStandMenu.class.isAssignableFrom(menuClass);
        if (block instanceof DispenserBlock) return DispenserMenu.class.isAssignableFrom(menuClass);
        if (block instanceof HopperBlock) return HopperMenu.class.isAssignableFrom(menuClass);
        return false;
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean isContainerCacheValid(Dimension dimension, ContainerCache cache) {
        BlockPos pos = cache.getBlockPos();
        if (WorldHelper.getCurrentDimension() == dimension && _mod.getChunkTracker().isChunkLoaded(pos)) {
            ContainerType actualType = ContainerType.getFromBlock(_mod.getWorld().getBlockState(pos).getBlock());
            if (actualType == ContainerType.EMPTY) {
                return false;
            }
            return actualType == cache.getContainerType();
        }
        return true;
    }

    public Optional<ContainerCache> getContainerAtPosition(Dimension dimension, BlockPos pos) {
        Optional<ContainerCache> cache = Optional.ofNullable(_containerCaches.get(dimension).getOrDefault(pos, null));
        if (cache.isPresent() && !isContainerCacheValid(dimension, cache.get())) {
            _containerCaches.get(dimension).remove(pos);
            return Optional.empty();
        }
        return cache;
    }
    public Optional<ContainerCache> getContainerAtPosition(BlockPos pos) {
        return getContainerAtPosition(WorldHelper.getCurrentDimension(), pos);
    }
    public Optional<ContainerCache> getEnderChestStorage() {
        return Optional.ofNullable(_enderChestCache);
    }

    public List<ContainerCache> getCachedContainers(Predicate<ContainerCache> accept) {
        List<ContainerCache> result = new ArrayList<>();
        List<Pair<Dimension, BlockPos>> toRemove = new ArrayList<>();
        for (Dimension dim : _containerCaches.keySet()) {
            HashMap<BlockPos, ContainerCache> map = _containerCaches.get(dim);
            for (ContainerCache cache : map.values()) {
                if (!isContainerCacheValid(dim, cache)) {
                    toRemove.add(new Pair<>(dim, cache.getBlockPos()));
                    continue;
                }
                if (accept.test(cache))
                    result.add(cache);
            }
        }
        for (Pair<Dimension, BlockPos> remove : toRemove) {
            _containerCaches.get(remove.getLeft()).remove(remove.getRight());
        }
        return result;
    }
    public List<ContainerCache> getCachedContainers(ContainerType ...types) {
        Set<ContainerType> typeSet = new HashSet<>(Arrays.asList(types));
        return getCachedContainers(cache -> typeSet.contains(cache.getContainerType()));
    }

    public Optional<ContainerCache> getClosestTo(Vec3 pos, Predicate<ContainerCache> accept) {
        double bestDist = Double.POSITIVE_INFINITY;
        Dimension dim = WorldHelper.getCurrentDimension();

        List<BlockPos> toRemove = new ArrayList<>();

        ContainerCache bestCache = null;
        for (ContainerCache cache : _containerCaches.get(dim).values()) {
            if (!isContainerCacheValid(dim, cache)) {
                toRemove.add(cache.getBlockPos());
                continue;
            }
            double dist = cache.getBlockPos().distToCenterSqr(pos);
            if (dist < bestDist) {
                if (accept.test(cache)) {
                    bestDist = dist;
                    bestCache = cache;
                }
            }
        }
        // Clear anything invalid
        for (BlockPos remove : toRemove) {
            _containerCaches.get(dim).remove(remove);
        }
        return Optional.ofNullable(bestCache);
    }
    public Optional<ContainerCache> getClosestTo(Vec3 pos, ContainerType ...types) {
        Set<ContainerType> typeSet = new HashSet<>(Arrays.asList(types));
        return getClosestTo(pos, cache -> typeSet.contains(cache.getContainerType()));
    }

    public List<ContainerCache> getContainersWithItem(Item ...items) {
        return getCachedContainers(cache -> cache.hasItem(items));
    }
    public Optional<ContainerCache> getClosestWithItem(Vec3 pos, Item ...items) {
        return getClosestTo(pos, cache -> cache.hasItem(items));
    }

    public boolean hasItem(Predicate<ContainerCache> accept, Item ...items) {
        for (HashMap<BlockPos, ContainerCache> map : _containerCaches.values()) {
            for (ContainerCache cache : map.values()) {
                if (cache.hasItem(items) && accept.test(cache))
                    return true;
            }
        }
        return false;
    }
    public boolean hasItem(Item ...items) {
        return hasItem(cache -> true, items);
    }

    public BlockPos getLastBlockPosInteraction() {
        Minecraft minecraft = Minecraft.getInstance();
        AbstractContainerMenu menu = minecraft.player == null ? null : minecraft.player.containerMenu;
        return getBoundContainer(menu).map(ContainerMenuSession.BoundContainer::position).orElse(null);
    }

    @Override
    protected void updateState() {
        // umm lol
    }

    @Override
    protected void reset() {
        for (Dimension key : _containerCaches.keySet()) {
            _containerCaches.get(key).clear();
        }
        _containerSession.clear();
        _hasSentError = false;
    }

}
