package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.AbstractDoToClosestObjectTask;
import adris.altoclef.tasks.resources.SatisfyMiningRequirementTask;
import adris.altoclef.tasks.slot.EnsureFreeInventorySlotTask;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StlHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.ToIntFunction;

public class PickupDroppedItemTask extends AbstractDoToClosestObjectTask<ItemEntity> implements ITaskRequiresGrounded {
    private static final int MAX_PICKAXE_FALLBACK_TICKS = 1200;

    private AltoClef _mod;

    /** The pickup task that owns the current pickaxe-first fallback, if any. */
    private static PickupDroppedItemTask pickaxeFirstOwner;

    private static final Set<Item> STONE_PICKAXE_RECIPE_PREREQUISITES = stonePickaxeRecipePrerequisites();
    private final ItemTarget[] _itemTargets;
    private final Set<ItemEntity> _blacklist = new HashSet<>();
    private final Set<ItemEntity> _pickaxeFallbackAttemptedDrops = new HashSet<>();
    private final MovementProgressChecker _progressChecker = new MovementProgressChecker(3);
    private final TimeoutWanderTask _wanderTask = new TimeoutWanderTask(20);
    private final boolean _freeInventoryIfFull;
    private final boolean _ignoreSatisfiedTargets;
    private boolean _collectingPickaxeForThisResource = false;
    private Task _pickaxeFirstTask;
    private int _pickaxeFallbackTicks;
    private ItemEntity _currentDrop = null;

    public PickupDroppedItemTask(ItemTarget[] itemTargets, boolean freeInventoryIfFull) {
        this(itemTargets, freeInventoryIfFull, false);
    }

    /**
     * @param ignoreSatisfiedTargets reject drops for targets already met in accessible inventory
     */
    public PickupDroppedItemTask(ItemTarget[] itemTargets, boolean freeInventoryIfFull,
                                 boolean ignoreSatisfiedTargets) {
        _itemTargets = itemTargets;
        _freeInventoryIfFull = freeInventoryIfFull;
        _ignoreSatisfiedTargets = ignoreSatisfiedTargets;
    }

    public PickupDroppedItemTask(ItemTarget target, boolean freeInventoryIfFull) {
        this(new ItemTarget[]{target}, freeInventoryIfFull);
    }

    public PickupDroppedItemTask(Item item, int targetCount, boolean freeInventoryIfFull) {
        this(new ItemTarget(item, targetCount), freeInventoryIfFull);
    }
    public PickupDroppedItemTask(Item item, int targetCount) {
        this(item, targetCount, true);
    }

    public static boolean isIsGettingPickaxeFirst(AltoClef mod) {
        return pickaxeFirstOwner != null && mod.getModSettings().shouldCollectPickaxeFirst();
    }

    public boolean isCollectingPickaxeForThis() {
        return _collectingPickaxeForThisResource;
    }

    @Override
    protected void onStart(AltoClef mod) {
        _pickaxeFallbackAttemptedDrops.clear();
        _progressChecker.reset();
    }

    @Override
    public void resetSearch() {
        super.resetSearch();
        _progressChecker.reset();
        _currentDrop = null;
        releasePickaxeFirstFallback();
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        releasePickaxeFirstFallback();
    }

    @Override
    protected Task onTick(AltoClef mod) {
        _mod = mod;
        reconcileCurrentDrop(mod);
        if (_wanderTask.isActive() && !_wanderTask.isFinished(mod)) {
            setDebugState("Wandering after blacklisting item...");
            _progressChecker.reset();
            return getRecoveryWanderTask(mod);
        }

        // Only the owner of a fallback may advance it. Nested pickups still
        // work normally, but cannot recursively start a second pickaxe task.
        if (isPickaxeFirstOwner(this) && !mod.getModSettings().shouldCollectPickaxeFirst()) {
            releasePickaxeFirstFallback();
        }
        if (isPickaxeFirstOwner(this) && !StorageHelper.miningRequirementMetInventory(mod, MiningRequirement.STONE)) {
            if (++_pickaxeFallbackTicks > MAX_PICKAXE_FALLBACK_TICKS) {
                Debug.logMessage("Stone pickaxe fallback timed out; marking the drop unreachable.");
                ItemEntity failedDrop = _currentDrop;
                releasePickaxeFirstFallback();
                if (failedDrop != null) {
                    _blacklist.add(failedDrop);
                    mod.getEntityTracker().requestEntityUnreachable(failedDrop);
                }
                return getRecoveryWanderTask(mod);
            }
            _progressChecker.reset();
            setDebugState("Collecting pickaxe first");
            return _pickaxeFirstTask;
        } else if (isPickaxeFirstOwner(this)) {
            releasePickaxeFirstFallback();
        }
        _collectingPickaxeForThisResource = false;

        if (!_progressChecker.check(mod)) {
            _progressChecker.reset();
            if (_currentDrop != null && !_currentDrop.getItem().isEmpty()) {
                // One fallback attempt per drop is enough. If the same drop is
                // still unreachable after getting a stone pickaxe, blacklist
                // it and use the normal wander/recovery path.
                if (canStartPickaxeFirstFallback(mod, _currentDrop) && claimPickaxeFirstOwner(this)) {
                    Debug.logMessage("Failed to pick up drop, will try to collect a stone pickaxe first and try again!");
                    _pickaxeFallbackAttemptedDrops.add(_currentDrop);
                    _collectingPickaxeForThisResource = true;
                    _pickaxeFirstTask = new SatisfyMiningRequirementTask(MiningRequirement.STONE);
                    return _pickaxeFirstTask;
                }

                Debug.logMessage(StlHelper.toString(_blacklist, element -> element == null ? "(null)" : element.getItem().getItem().getDescriptionId()));
                Debug.logMessage("Failed to pick up drop, suggesting it's unreachable.");
                releasePickaxeFirstFallback();
                _blacklist.add(_currentDrop);
                mod.getEntityTracker().requestEntityUnreachable(_currentDrop);
                return getRecoveryWanderTask(mod);
            }
        }

        return super.onTick(mod);
    }

    protected Task getRecoveryWanderTask(AltoClef mod) {
        return _wanderTask;
    }

    private boolean canStartPickaxeFirstFallback(AltoClef mod, ItemEntity drop) {
        return drop != null
                && mod.getModSettings().shouldCollectPickaxeFirst()
                && pickaxeFirstOwner == null
                && !_pickaxeFallbackAttemptedDrops.contains(drop)
                && !StorageHelper.miningRequirementMetInventory(mod, MiningRequirement.STONE)
                && !isStonePickaxeRecipePrerequisite(_itemTargets);
    }

    static boolean isStonePickaxeRecipePrerequisite(ItemTarget[] targets) {
        return Arrays.stream(targets)
                .filter(target -> target != null)
                .flatMap(target -> Arrays.stream(target.getMatches()))
                .anyMatch(STONE_PICKAXE_RECIPE_PREREQUISITES::contains);
    }

    public static ItemTarget[] getUnmetTargets(ItemTarget[] targets, ToIntFunction<ItemTarget> accessibleCount) {
        return Arrays.stream(targets)
                .filter(target -> target != null)
                .filter(target -> accessibleCount.applyAsInt(target) < target.getTargetCount())
                .toArray(ItemTarget[]::new);
    }

    public static boolean itemIsNeededByAnyTarget(Item item, ItemTarget[] targets,
                                                  ToIntFunction<ItemTarget> accessibleCount) {
        for (ItemTarget target : targets) {
            if (target != null && target.matches(item)
                    && accessibleCount.applyAsInt(target) < target.getTargetCount()) {
                return true;
            }
        }
        return false;
    }

    /** Override in task tests to supply inventory counts without a live Minecraft client. */
    protected int getAccessibleInventoryCount(AltoClef mod, ItemTarget target) {
        return StorageHelper.getAccessibleInventoryItemCount(mod, target);
    }

    static boolean claimPickaxeFirstOwner(PickupDroppedItemTask candidate) {
        if (candidate == null || pickaxeFirstOwner != null) {
            return false;
        }
        pickaxeFirstOwner = candidate;
        candidate._pickaxeFallbackTicks = 0;
        return true;
    }

    static boolean isPickaxeFirstOwner(PickupDroppedItemTask candidate) {
        return candidate != null && pickaxeFirstOwner == candidate;
    }

    static void releasePickaxeFirstOwner(PickupDroppedItemTask candidate) {
        if (pickaxeFirstOwner == candidate) {
            pickaxeFirstOwner = null;
        }
    }

    private static Set<Item> stonePickaxeRecipePrerequisites() {
        Set<Item> result = new HashSet<>(Arrays.asList(ItemHelper.LOG));
        result.addAll(Arrays.asList(ItemHelper.WOOD));
        result.addAll(Arrays.asList(ItemHelper.PLANKS));
        result.add(Items.BAMBOO);
        result.add(Items.BAMBOO_BLOCK);
        result.add(Items.COBBLESTONE);
        result.add(Items.STICK);
        result.add(Items.CRAFTING_TABLE);
        result.add(Items.WOODEN_PICKAXE);
        result.add(Items.STONE_PICKAXE);
        result.add(Items.IRON_PICKAXE);
        result.add(Items.GOLDEN_PICKAXE);
        result.add(Items.DIAMOND_PICKAXE);
        result.add(Items.NETHERITE_PICKAXE);
        return Set.copyOf(result);
    }

    private void releasePickaxeFirstFallback() {
        releasePickaxeFirstOwner(this);
        _collectingPickaxeForThisResource = false;
        _pickaxeFirstTask = null;
        _pickaxeFallbackTicks = 0;
    }


    @Override
    protected boolean isEqual(Task other) {
        // Same target items
        if (other instanceof PickupDroppedItemTask task) {
            return Arrays.equals(task._itemTargets, _itemTargets)
                    && task._freeInventoryIfFull == _freeInventoryIfFull
                    && task._ignoreSatisfiedTargets == _ignoreSatisfiedTargets;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        StringBuilder result = new StringBuilder();
        result.append("Pickup Dropped Items: [");
        int c = 0;
        for (ItemTarget target : _itemTargets) {
            result.append(target.toString());
            if (++c != _itemTargets.length) {
                result.append(", ");
            }
        }
        result.append("]");
        return result.toString();
    }

    @Override
    protected Vec3 getPos(AltoClef mod, ItemEntity obj) {
        if (!obj.onGround() && !obj.isInWater()) {
            // Assume we'll land down one or two blocks from here. We could do this more advanced but whatever.
            BlockPos p = obj.blockPosition();
            if (!WorldHelper.isSolid(mod, p.below(3))) {
                return obj.position().subtract(0,2,0);
            }
            return obj.position().subtract(0,1,0);
        }
        return obj.position();
    }

    @Override
    protected Optional<ItemEntity> getClosestTo(AltoClef mod, Vec3 pos) {
        return mod.getEntityTracker().getClosestItemDrop(
                pos,
                drop -> isValid(mod, drop),
                _itemTargets);
    }

    /** Includes this task's local blacklist and satisfied-target filter. */
    public Optional<ItemEntity> getClosestEligibleDrop(AltoClef mod) {
        return getClosestTo(mod, getOriginPos(mod));
    }

    /** Exposes the live pursuit to the opt-in runtime acceptance diagnostics. */
    public Optional<ItemEntity> getCurrentDropForDiagnostics() {
        return Optional.ofNullable(getCurrentPursuit());
    }

    /** Exposes the last goal entity, which can differ from the live pursuit after invalidation. */
    public Optional<ItemEntity> getLastSelectedDropForDiagnostics() {
        return Optional.ofNullable(_currentDrop);
    }

    /** Discard stale target and fallback state before timeout or unreachable handling. */
    void reconcileCurrentDrop(AltoClef mod) {
        ItemEntity pursuing = getCurrentPursuit();
        if (_currentDrop != null && (_currentDrop != pursuing || !isValid(mod, _currentDrop))) {
            if (_currentDrop == pursuing) {
                clearCurrentPursuit(_currentDrop);
            }
            _currentDrop = null;
            releasePickaxeFirstFallback();
            _progressChecker.reset();
        }
    }

    @Override
    protected Vec3 getOriginPos(AltoClef mod) {
        return mod.getPlayer().position();
    }

    @Override
    protected Task getGoalTask(ItemEntity itemEntity) {
        if (!itemEntity.equals(_currentDrop)) {
            if (isPickaxeFirstOwner(this)) {
                Debug.logMessage("New goal, releasing pickaxe-first fallback.");
                releasePickaxeFirstFallback();
            }
            _currentDrop = itemEntity;
            _progressChecker.reset();
        }
        // Ensure our inventory is free if we're close
        boolean touching = _mod.getEntityTracker().isCollidingWithPlayer(itemEntity);
        if (touching) {
            if (_freeInventoryIfFull) {
                if (_mod.getItemStorage().getSlotsThatCanFitInPlayerInventory(itemEntity.getItem(), false).isEmpty()) {
                    return new EnsureFreeInventorySlotTask();
                }
            }
        }
        return createGetToEntityTask(itemEntity);
    }

    protected GetToEntityTask createGetToEntityTask(ItemEntity itemEntity) {
        return new GetToEntityTask(itemEntity);
    }

    @Override
    protected boolean isValid(AltoClef mod, ItemEntity obj) {
        return obj.isAlive()
                && !_blacklist.contains(obj)
                && (!_ignoreSatisfiedTargets || itemIsNeededByAnyTarget(obj.getItem().getItem(), _itemTargets,
                    target -> getAccessibleInventoryCount(mod, target)));
    }

}
