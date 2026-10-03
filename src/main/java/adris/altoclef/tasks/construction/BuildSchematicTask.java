package adris.altoclef.tasks.construction;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.squashed.CataloguedResourceTask;
import adris.altoclef.tasks.slot.ClickSlotTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskFailure;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.CraftingTableSlot;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.slots.Slot;
import adris.altoclef.util.schematic.SchematicBlockStateMatcher;
import adris.altoclef.util.schematic.SchematicMaterialCounter;
import adris.altoclef.util.schematic.SchematicSnapshot;
import adris.altoclef.util.schematic.MultiblockRepairPlanner;
import adris.altoclef.util.schematic.SubstitutedStaticSchematic;
import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.api.process.IBuilderProcess;
import baritone.api.schematic.ISchematic;
import baritone.api.schematic.IStaticSchematic;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Gathers a schematic's placeable materials with AltoClef tasks, then delegates construction to Baritone. */
public final class BuildSchematicTask extends Task implements TaskFailure {
    private static final int BUILDER_START_TIMEOUT_TICKS = 100;
    private static final int FAILURE_CLEANUP_TIMEOUT_TICKS = 6000;
    private static final int RESERVED_FREE_SLOTS = 8;
    private final BuilderProgressWatchdog _builderWatchdog = new BuilderProgressWatchdog();

    private final SchematicSnapshot _snapshot;
    private baritone.api.schematic.IStaticSchematic _materialSchematic;
    private baritone.api.schematic.IStaticSchematic _batchMaterialSchematic;
    private CataloguedResourceTask _materialTask;
    private SpecialSchematicBlockPlacementTask _specialPlacementTask;
    private baritone.api.schematic.IStaticSchematic _initialBatchSchematic;
    private baritone.api.schematic.IStaticSchematic _initialVerificationSchematic;
    private List<BlockPos> _builderProgressPositions = List.of();
    private final Set<BlockPos> _pendingSpecialPositions = new HashSet<>();
    private final Set<BlockPos> _temporarilyRemovedPositions = new HashSet<>();
    private Set<BlockPos> _postBuildSpecialPositions = Set.of();
    private boolean _postBuildSpecialPlacements;
    private baritone.api.schematic.IStaticSchematic _batchSchematic;
    private Map<Block, List<Block>> _selectedSubstitutions = Map.of();
    private boolean _buildRequested;
    private boolean _builderLaunchPending;
    private boolean _builderWasActive;
    private boolean _completed;
    private boolean _successCleanupPending;
    private int _successCleanupTicks;
    private boolean _failed;
    private boolean _cancelled;
    private int _activationWaitTicks;
    private int _batchNumber;
    private int _remainingBeforeBatch;
    private BlockPos _multiblockRepairAnchor;
    private String _failureReason;
    private boolean _failureCleanupActive;
    private boolean _failureCleanupWaitingForMaterialsChildStop;
    private String _failureCleanupOriginalReason;
    private String _failureCleanupFailure;
    private int _failureCleanupTicks;
    private FailureCleanupPhase _failureCleanupPhase = FailureCleanupPhase.MATERIALS;
    private CataloguedResourceTask _failureCleanupMaterialTask;
    private IStaticSchematic _failureCleanupSchematic;
    private List<BlockPos> _failureCleanupOrdinaryPositions = List.of();
    private int _failureCleanupOrdinaryIndex;
    private BlockPos _failureCleanupBuilderPosition;
    private boolean _failureCleanupBuilderStarted;
    private boolean _failureCleanupBuilderIssued;
    private int _failureCleanupBuilderWaitTicks;
    private Set<BlockPos> _failureCleanupDoubleSlabPositions = Set.of();
    private Set<BlockPos> _failureCleanupTargets = Set.of();
    private List<BlockPos> _failureCleanupDoubleSlabList = List.of();
    private int _failureCleanupDoubleSlabIndex;
    private SpecialSchematicBlockPlacementTask _failureCleanupSlabTask;
    private Set<BlockPos> _failureCleanupStartedPositions = new HashSet<>();
    private final Set<BlockPos> _failureCleanupSlabsStartedPositions = new HashSet<>();
    private final Set<BlockPos> _failureCleanupFailures = new HashSet<>();

    private enum FailureCleanupPhase { MATERIALS, ORDINARY_BLOCKS, DOUBLE_SLABS }

    public BuildSchematicTask(String name, baritone.api.schematic.IStaticSchematic schematic, BlockPos origin) {
        this(new SchematicSnapshot(name, schematic, origin));
    }

    public BuildSchematicTask(SchematicSnapshot snapshot) {
        _snapshot = snapshot;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return _completed || _failed;
    }

    @Override
    protected void onResetForNewRun() {
        _completed = false;
        _successCleanupPending = false;
        _successCleanupTicks = 0;
        _failed = false;
        _cancelled = false;
        _failureReason = null;
    }

    @Override
    protected void onStart(AltoClef mod) {
        // AltoClef may interrupt a user task temporarily (for example, to eat). Re-plan from
        // the live inventory after the interruption instead of treating a stopped builder as done.
        _cancelled = false;
        _buildRequested = false;
        _builderLaunchPending = false;
        _builderWasActive = false;
        _activationWaitTicks = 0;
        _builderWatchdog.reset();
        _failureReason = null;
        _specialPlacementTask = null;
        _postBuildSpecialPlacements = false;
        _pendingSpecialPositions.clear();
        _postBuildSpecialPositions = Set.of();
        _builderProgressPositions = List.of();
        if (_successCleanupPending) {
            setDebugState("Resuming successful-build inventory cleanup");
            return;
        }
        if (_materialSchematic == null && !prepareMaterialSchematic(mod)) return;
        if (_failureCleanupActive) {
            resetFailureCleanupForResume(mod);
            return;
        }
        prepareBatch(mod);
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (_failureCleanupActive) {
            return tickFailureCleanup(mod);
        }
        if (_successCleanupPending) {
            return tickSuccessfulCompletionCleanup(mod);
        }
        if (_completed || _failed || _cancelled) {
            return null;
        }

        if (!_buildRequested) {
            if (_multiblockRepairAnchor != null) {
                return startMultiblockRepair();
            }
            if (_batchSchematic == null) {
                prepareBatch(mod);
                if (_multiblockRepairAnchor != null) {
                    return startMultiblockRepair();
                }
                if (_completed || _failed || _successCleanupPending || _batchSchematic == null) {
                    return null;
                }
            }
            if (!_postBuildSpecialPlacements && _materialTask != null) {
                if (!_materialTask.isFinished(mod)) {
                    TaskFailure.Snapshot childFailure = unhandledChildFailure(_materialTask, mod);
                    if (childFailure != null) {
                        fail(mod, "Schematic material task failed: " + failureReason(childFailure));
                        return null;
                    }
                    setDebugState("Gathering materials for batch " + _batchNumber + " of " + _snapshot.name());
                    return _materialTask;
                }
                if (_materialTask.hasFailed()) {
                    fail(mod, "Could not gather schematic materials: " + _materialTask.getFailureReason());
                    return null;
                }
            }
            if (_postBuildSpecialPlacements) {
                if (_specialPlacementTask == null) {
                    _specialPlacementTask = new SpecialSchematicBlockPlacementTask(
                            _batchMaterialSchematic, _snapshot.origin(), Set.of(), _materialSchematic);
                }
                if (!_specialPlacementTask.isFinished(mod)) {
                    TaskFailure.Snapshot childFailure = unhandledChildFailure(_specialPlacementTask, mod);
                    if (childFailure != null) {
                        _temporarilyRemovedPositions.addAll(
                                _specialPlacementTask.getTemporarilyRemovedPositions());
                        refreshTemporaryRemovedPositions(mod);
                        fail(mod, "Schematic special placement task failed: " + failureReason(childFailure));
                        return null;
                    }
                    setDebugState("Finishing paired block placements after building their supports");
                    return _specialPlacementTask;
                }
                if (_specialPlacementTask.hasFailed()) {
                    _temporarilyRemovedPositions.addAll(_specialPlacementTask.getTemporarilyRemovedPositions());
                    refreshTemporaryRemovedPositions(mod);
                    String reason = _specialPlacementTask.getFailureReason();
                    fail(mod, reason);
                    return null;
                }
                _postBuildSpecialPlacements = false;
                _temporarilyRemovedPositions.addAll(_specialPlacementTask.getTemporarilyRemovedPositions());
                if (verifyPlacedBlocks(mod, _batchMaterialSchematic)) {
                    _pendingSpecialPositions.removeAll(_postBuildSpecialPositions);
                    _postBuildSpecialPositions = Set.of();
                    prepareBatch(mod);
                } else if (onlyTrackedRemovedMismatches(
                        getMismatchedPositions(mod, _batchMaterialSchematic),
                        _temporarilyRemovedPositions,
                        getEligibleTemporaryRestorations(mod, _temporarilyRemovedPositions))) {
                    _pendingSpecialPositions.removeAll(_postBuildSpecialPositions);
                    _postBuildSpecialPositions = Set.of();
                    Debug.logMessage("Special placement temporarily removed only %d authored neighbor cells; "
                                    + "planning their restoration in ordinary schematic batches.",
                            _temporarilyRemovedPositions.size());
                    prepareBatch(mod);
                } else {
                    fail(mod, "Special block placement did not match the schematic's exact world states.");
                }
                return null;
            }
            if (countSelectedPositions(_initialBatchSchematic) == 0) {
                processReadySpecialPlacements(mod);
                return null;
            }
            if (shouldDeferBuilderLaunch()) {
                setDebugState("Waiting for resource task cleanup before starting Baritone");
                return null;
            }
            startBuild(mod);
            return null;
        }

        IBuilderProcess builder = mod.getClientBaritone().getBuilderProcess();
        if (builder.isActive()) {
            _builderWasActive = true;
            boolean paused = builder.isPaused();
            if (paused) {
                setDebugState("Baritone builder paused; waiting for it to resume");
            }
            int pausedTimeoutSeconds = mod.getModSettings().getSchematicBuildPausedTimeoutSeconds();
            int noProgressTimeoutSeconds = mod.getModSettings().getSchematicBuildNoProgressTimeoutSeconds();
            int sampledRemaining = noProgressTimeoutSeconds > 0
                    && _builderWatchdog.shouldSamplePositionsNextTick(paused)
                    ? countUnsatisfiedBuilderPositions(mod) : -1;
            BuilderProgressWatchdog.Timeout timeout = _builderWatchdog.tick(
                    true, paused, sampledRemaining, builder.getMinLayer(), builder.getMaxLayer(),
                    pausedTimeoutSeconds, noProgressTimeoutSeconds);
            if (timeout == BuilderProgressWatchdog.Timeout.PAUSED) {
                fail(mod, "Baritone's builder remained paused beyond its configured timeout.");
            } else if (timeout == BuilderProgressWatchdog.Timeout.NO_PROGRESS) {
                fail(mod, "Baritone's builder made no placement or layer progress beyond its configured timeout.");
            } else if (!paused) {
                setDebugState("Building " + _snapshot.name());
            }
            return null;
        }

        if (!_builderWasActive) {
            if (verifyPlacedBlocks(mod, _batchMaterialSchematic)) {
                processReadySpecialPlacements(mod);
                return null;
            }
            if (++_activationWaitTicks >= BUILDER_START_TIMEOUT_TICKS) {
                fail(mod, "Baritone's builder did not start.");
            }
            return null;
        }

        if (verifyPlacedBlocks(mod, _initialVerificationSchematic)) {
            if (verifyPlacedBlocks(mod, _batchMaterialSchematic)) {
                processReadySpecialPlacements(mod);
            } else {
                processReadySpecialPlacements(mod);
            }
        } else if (verifyPlacedBlocks(mod, _batchMaterialSchematic)) {
            processReadySpecialPlacements(mod);
        } else {
            int remainingNow = countUnsatisfiedPositions(mod);
            if (remainingNow < _remainingBeforeBatch) {
                // Baritone can stop after exhausting the current inventory batch. Recount the
                // live world and schedule the next bounded material/build pass.
                prepareBatch(mod);
            } else {
                fail(mod, "Baritone stopped without placing any remaining schematic blocks.");
            }
        }
        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        if (_completed || _failed) {
            return;
        }
        if (_specialPlacementTask != null) {
            _temporarilyRemovedPositions.addAll(_specialPlacementTask.getTemporarilyRemovedPositions());
        }
        if (_failureCleanupSlabTask != null) {
            _temporarilyRemovedPositions.addAll(_failureCleanupSlabTask.getTemporarilyRemovedPositions());
        }
        refreshTemporaryRemovedPositions(mod);
        _cancelled = true;
        if (_buildRequested || _failureCleanupBuilderStarted) {
            IBuilderProcess builder = mod.getClientBaritone().getBuilderProcess();
            if (builder.isActive()) {
                builder.onLostControl();
            }
        }
        if (_temporarilyRemovedPositions.isEmpty() && !_failureCleanupActive) {
            Debug.logMessage("Schematic build stopped: %s", _snapshot.name());
        } else {
            Debug.logMessage("Schematic build stopped: %s; temporary authored neighbor restorations still outstanding at %s%s",
                    _snapshot.name(), _temporarilyRemovedPositions,
                    _failureCleanupActive ? " (cleanup will re-plan if this task resumes)" : "");
        }
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof BuildSchematicTask task
                && _snapshot.name().equals(task._snapshot.name())
                && _snapshot.origin().equals(task._snapshot.origin())
                && _snapshot.schematic() == task._snapshot.schematic();
    }

    @Override
    protected String toDebugString() {
        return "Build schematic: " + _snapshot.name();
    }

    public boolean wasCancelled() {
        return _cancelled;
    }

    @Override
    public boolean hasFailed() {
        return _failed;
    }

    static TaskFailure.Snapshot unhandledChildFailure(Task child, AltoClef mod) {
        if (child == null || child.shouldDeferFailure(mod)) return null;
        return child.getFailureSnapshot();
    }

    private static String failureReason(TaskFailure.Snapshot snapshot) {
        return snapshot.reason() == null || snapshot.reason().isBlank()
                ? "a descendant task failed." : snapshot.reason();
    }

    @Override
    public String getFailureReason() {
        return _failureReason;
    }

    private void prepareBatch(AltoClef mod) {
        _builderWatchdog.reset();
        if (mod.getWorld() == null || mod.getPlayer() == null) {
            setDebugState("Waiting for a world before planning schematic materials");
            return;
        }
        refreshTemporaryRemovedPositions(mod);
        _multiblockRepairAnchor = findMultiblockRepairAnchor(mod);
        if (_multiblockRepairAnchor != null) {
            _batchSchematic = null;
            _batchMaterialSchematic = null;
            _materialTask = null;
            _buildRequested = false;
            _builderWasActive = false;
            _activationWaitTicks = 0;
            Debug.logMessage("Repairing partial multiblock at %s before gathering schematic materials.",
                    _multiblockRepairAnchor.toShortString());
            return;
        }
        Map<Item, Integer> remaining = getRemainingMaterials(mod);
        if (!hasUnsatisfiedPositions(mod) && verifyPlacedBlocks(mod, _materialSchematic)) {
            _batchSchematic = null;
            _batchMaterialSchematic = null;
            _buildRequested = false;
            _successCleanupPending = true;
            _successCleanupTicks = 0;
            setDebugState("Returning carried items and clearing crafting grids");
            return;
        }

        if (remaining.isEmpty() && countUnsatisfiedOrdinaryPositions(mod) == 0) {
            if (scheduleReadySpecialPlacements(mod)) return;
            if (!_pendingSpecialPositions.isEmpty()) {
                BlockPos first = _pendingSpecialPositions.iterator().next();
                fail(mod, "All ordinary materials are placed, but the remaining paired block near "
                        + first.toShortString() + " still has no valid support or hinge arrangement.");
            } else {
                fail(mod, "The remaining schematic positions cannot be placed as supported standalone states.");
            }
            return;
        }

        _remainingBeforeBatch = countUnsatisfiedPositions(mod);
        Map<Item, Integer> batchBudget = fitCurrentInventory(mod, remaining);
        if (!remaining.isEmpty() && batchBudget.isEmpty()) {
            fail(mod, "No free player inventory capacity for the remaining schematic materials.");
            return;
        }

        List<BlockState> approxPlaceable = getApproxPlaceable(mod);
        ISchematic effectiveSchematic = _materialSchematic;
        SchematicBlockStateMatcher.Options matchOptions = getBlockStateMatchOptions();
        java.util.function.Predicate<BlockPos> satisfied = local ->
                _pendingSpecialPositions.contains(local)
                        || isSpecialPlacementState(_materialSchematic.getDirect(local.getX(), local.getY(), local.getZ()))
                        || isPositionSatisfied(mod, local, approxPlaceable, effectiveSchematic, matchOptions);
        _batchSchematic = SchematicMaterialCounter.createBatch(
                _snapshot.schematic(), _materialSchematic, batchBudget, satisfied);
        _batchMaterialSchematic = SchematicMaterialCounter.createBatch(
                _materialSchematic, batchBudget, satisfied);
        _initialBatchSchematic = _batchSchematic;
        _initialVerificationSchematic = _batchMaterialSchematic;
        _builderProgressPositions = collectSelectedPositions(_initialVerificationSchematic);
        Map<Item, Integer> batchMaterials = SchematicMaterialCounter.count(_batchMaterialSchematic);
        if (batchMaterials.isEmpty() && countSelectedPositions(_batchSchematic) == 0) {
            fail(mod, remaining.isEmpty()
                    ? "The remaining schematic positions are unsupported standalone states (for example, an orphaned multiblock half)."
                    : "Could not form a schematic batch from the available inventory space.");
            return;
        }

        List<ItemTarget> targets = new ArrayList<>(batchMaterials.size());
        for (Map.Entry<Item, Integer> entry : batchMaterials.entrySet()) {
            if (!TaskCatalogue.taskExists(entry.getKey())) {
                fail(mod, "No resource task is registered for " + entry.getKey() + ".");
                return;
            }
            targets.add(new ItemTarget(entry.getKey(), entry.getValue()));
        }

        _materialTask = new CataloguedResourceTask(targets.toArray(ItemTarget[]::new));
        _specialPlacementTask = null;
        _batchNumber++;
        _buildRequested = false;
        _builderLaunchPending = false;
        _builderWasActive = false;
        _activationWaitTicks = 0;
        Debug.logMessage("Prepared schematic batch %d (%d material units; %d remain). Gathering before building.",
                _batchNumber, totalUnits(batchMaterials), _remainingBeforeBatch);
    }

    private BlockPos findMultiblockRepairAnchor(AltoClef mod) {
        List<BlockState> approxPlaceable = getApproxPlaceable(mod);
        SchematicBlockStateMatcher.Options matchOptions = getBlockStateMatchOptions();
        return MultiblockRepairPlanner.findRepairAnchor(_materialSchematic,
                local -> mod.getWorld().getBlockState(_snapshot.origin().offset(local)),
                local -> {
                    BlockState actual = mod.getWorld().getBlockState(_snapshot.origin().offset(local));
                    return _materialSchematic.desiredState(local.getX(), local.getY(), local.getZ(),
                            actual, approxPlaceable);
                }, matchOptions).map(_snapshot.origin()::offset).orElse(null);
    }

    private Task startMultiblockRepair() {
        BlockPos anchor = _multiblockRepairAnchor;
        _multiblockRepairAnchor = null;
        setDebugState("Removing partial multiblock anchor before rebuilding it.");
        return new DestroyBlockTask(anchor);
    }

    private Map<Item, Integer> getRemainingMaterials(AltoClef mod) {
        if (mod.getWorld() == null) return Map.of();
        List<BlockState> approxPlaceable = getApproxPlaceable(mod);
        ISchematic effectiveSchematic = _materialSchematic;
        SchematicBlockStateMatcher.Options matchOptions = getBlockStateMatchOptions();
        return SchematicMaterialCounter.countMissing(_materialSchematic,
                local -> _pendingSpecialPositions.contains(local)
                        || isSpecialPlacementState(_materialSchematic.getDirect(local.getX(), local.getY(), local.getZ()))
                        || isPositionSatisfied(mod, local, approxPlaceable, effectiveSchematic, matchOptions));
    }

    private boolean isPositionSatisfied(AltoClef mod, BlockPos local, List<BlockState> approxPlaceable,
                                       ISchematic effectiveSchematic,
                                       SchematicBlockStateMatcher.Options matchOptions) {
        if (mod.getWorld() == null) return false;
        BlockState target = _materialSchematic.getDirect(local.getX(), local.getY(), local.getZ());
        if (target == null) return true;
        if (!_materialSchematic.inSchematic(local.getX(), local.getY(), local.getZ(), target)) return true;
        BlockPos worldPos = _snapshot.origin().offset(local);
        BlockState actual = mod.getWorld().getBlockState(worldPos);
        BlockState desired = effectiveSchematic.desiredState(
                local.getX(), local.getY(), local.getZ(), actual, approxPlaceable);
        return requiresExactSpecialState(desired)
                ? actual.equals(desired)
                : SchematicBlockStateMatcher.matches(actual, desired, matchOptions);
    }

    private boolean hasUnsatisfiedPositions(AltoClef mod) {
        return countUnsatisfiedPositions(mod) > 0;
    }

    private int countUnsatisfiedPositions(AltoClef mod) {
        List<BlockState> approxPlaceable = getApproxPlaceable(mod);
        ISchematic effectiveSchematic = _materialSchematic;
        SchematicBlockStateMatcher.Options matchOptions = getBlockStateMatchOptions();
        int unsatisfied = 0;
        for (int x = 0; x < _snapshot.schematic().widthX(); x++) {
            for (int y = 0; y < _snapshot.schematic().heightY(); y++) {
                for (int z = 0; z < _snapshot.schematic().lengthZ(); z++) {
                    if (!isPositionSatisfied(mod, new BlockPos(x, y, z), approxPlaceable,
                            effectiveSchematic, matchOptions)) unsatisfied++;
                }
            }
        }
        return unsatisfied;
    }

    private int countUnsatisfiedOrdinaryPositions(AltoClef mod) {
        List<BlockState> approxPlaceable = getApproxPlaceable(mod);
        SchematicBlockStateMatcher.Options matchOptions = getBlockStateMatchOptions();
        int unsatisfied = 0;
        for (int x = 0; x < _materialSchematic.widthX(); x++) {
            for (int y = 0; y < _materialSchematic.heightY(); y++) {
                for (int z = 0; z < _materialSchematic.lengthZ(); z++) {
                    BlockPos local = new BlockPos(x, y, z);
                    BlockState desired = _materialSchematic.getDirect(x, y, z);
                    if (desired != null && !isSpecialPlacementState(desired)
                            && !isPositionSatisfied(mod, local, approxPlaceable, _materialSchematic, matchOptions)) {
                        unsatisfied++;
                    }
                }
            }
        }
        return unsatisfied;
    }

    private Task tickSuccessfulCompletionCleanup(AltoClef mod) {
        if (++_successCleanupTicks > FAILURE_CLEANUP_TIMEOUT_TICKS) {
            _successCleanupPending = false;
            fail(mod, "Schematic blocks are placed, but carried items or crafting grids could not be safely cleared.");
            return null;
        }
        if (mod.getPlayer() == null) {
            setDebugState("Waiting for the player before final inventory cleanup");
            return null;
        }

        ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
        if (!cursor.isEmpty()) {
            var destination = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursor, true);
            if (destination.isPresent()) {
                setDebugState("Returning carried items to the player inventory");
                return new ClickSlotTask(destination.get());
            }
            setDebugState("Waiting for inventory capacity; preserving carried items");
            return null;
        }

        AbstractContainerMenu menu = mod.getPlayer().containerMenu;
        Task clearGrid = firstCraftingGridClearTask(mod, menu);
        if (clearGrid != null) return clearGrid;
        if (!playerCraftingGridEmpty(menu)) {
            setDebugState("Waiting for inventory capacity; preserving crafting-grid items");
            return null;
        }

        if (menu != mod.getPlayer().inventoryMenu) {
            setDebugState("Closing the crafting or container screen after clearing its cursor and grid");
            if (Minecraft.getInstance().gui.screen() == null) {
                // A handler can remain open without a visible GUI (for example, while the
                // client is transitioning screens). Close it only after the cursor and any
                // crafting inputs have already been verified empty above.
                mod.getPlayer().closeContainer();
            } else {
                StorageHelper.closeScreen();
            }
            return null;
        }

        if (!isSuccessfulCleanupComplete(StorageHelper.getItemStackInCursorSlot().isEmpty(),
                playerCraftingGridEmpty(menu), menu == mod.getPlayer().inventoryMenu)) {
            setDebugState("Waiting for cursor, crafting grid, and container cleanup to synchronize");
            return null;
        }
        if (hasUnsatisfiedPositions(mod) || !verifyPlacedBlocks(mod, _materialSchematic)) {
            // The world can change while cleanup is suspended (for example, while another
            // task temporarily takes control). Re-plan from the current world before reporting
            // completion, matching the normal interrupted-build resume behavior.
            _successCleanupPending = false;
            _successCleanupTicks = 0;
            prepareBatch(mod);
            return null;
        }
        _successCleanupPending = false;
        _completed = true;
        Debug.logMessage("Schematic build complete: %s", _snapshot.name());
        return null;
    }

    private static Task firstCraftingGridClearTask(AltoClef mod, AbstractContainerMenu menu) {
        if (menu instanceof InventoryMenu) {
            for (int index = 0; index < 4; index++) {
                Slot slot = PlayerSlot.getCraftInputSlot(index);
                Task clear = clearGridSlot(mod, slot);
                if (clear != null) return clear;
            }
        } else if (menu instanceof CraftingMenu) {
            for (int index = 0; index < 9; index++) {
                Slot slot = CraftingTableSlot.getInputSlot(index, true);
                Task clear = clearGridSlot(mod, slot);
                if (clear != null) return clear;
            }
        }
        return null;
    }

    private static Task clearGridSlot(AltoClef mod, Slot slot) {
        ItemStack stack = StorageHelper.getItemStackInSlot(slot);
        if (stack.isEmpty()) return null;
        if (mod.getItemStorage().getSlotThatCanFitInPlayerInventory(stack, true).isEmpty()) {
            return null;
        }
        return new ClickSlotTask(slot);
    }

    private static boolean playerCraftingGridEmpty(AbstractContainerMenu menu) {
        if (menu instanceof InventoryMenu) {
            for (int index = 1; index <= 4; index++) {
                if (!menu.getSlot(index).getItem().isEmpty()) return false;
            }
        } else if (menu instanceof CraftingMenu) {
            for (int index = 1; index <= 9; index++) {
                if (!menu.getSlot(index).getItem().isEmpty()) return false;
            }
        }
        return true;
    }

    static boolean isSuccessfulCleanupComplete(boolean cursorEmpty,
                                               boolean craftingGridEmpty,
                                               boolean playerInventoryMenu) {
        return cursorEmpty && craftingGridEmpty && playerInventoryMenu;
    }

    /** Counts only the selected cells in the ordinary Baritone stage of the current batch. */
    private int countUnsatisfiedBuilderPositions(AltoClef mod) {
        if (mod.getWorld() == null) return 0;
        List<BlockState> approxPlaceable = getApproxPlaceable(mod);
        ISchematic effectiveSchematic = _materialSchematic;
        SchematicBlockStateMatcher.Options matchOptions = getBlockStateMatchOptions();
        int unsatisfied = 0;
        for (BlockPos local : _builderProgressPositions) {
            if (!isPositionSatisfied(mod, local, approxPlaceable, effectiveSchematic, matchOptions)) {
                unsatisfied++;
            }
        }
        return unsatisfied;
    }

    /**
     * Captures the batch's selected cells once. Keep selected AIR positions: clearing
     * source air is real schematic work even though it has no material cost.
     */
    static List<BlockPos> collectSelectedPositions(IStaticSchematic schematic) {
        List<BlockPos> positions = new ArrayList<>();
        for (int x = 0; x < schematic.widthX(); x++) {
            for (int y = 0; y < schematic.heightY(); y++) {
                for (int z = 0; z < schematic.lengthZ(); z++) {
                    BlockState state = schematic.getDirect(x, y, z);
                    if (state != null && schematic.inSchematic(x, y, z, state)) {
                        positions.add(new BlockPos(x, y, z));
                    }
                }
            }
        }
        return List.copyOf(positions);
    }

    private static int countSelectedPositions(baritone.api.schematic.IStaticSchematic schematic) {
        int selected = 0;
        for (int x = 0; x < schematic.widthX(); x++) {
            for (int y = 0; y < schematic.heightY(); y++) {
                for (int z = 0; z < schematic.lengthZ(); z++) {
                    if (schematic.inSchematic(x, y, z, schematic.getDirect(x, y, z))) selected++;
                }
            }
        }
        return selected;
    }

    private Map<Item, Integer> fitCurrentInventory(AltoClef mod, Map<Item, Integer> remaining) {
        Inventory inventory = mod.getPlayer().getInventory();
        Map<Item, Integer> held = new java.util.HashMap<>();
        Map<Item, Integer> stackSpace = new java.util.HashMap<>();
        int emptySlots = 0;
        int playerSlots = Math.min(36, inventory.getContainerSize());
        for (int slot = 0; slot < playerSlots; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                emptySlots++;
            } else if (remaining.containsKey(stack.getItem())) {
                Item item = stack.getItem();
                held.merge(item, stack.getCount(), Integer::sum);
                stackSpace.merge(item, Math.max(0, stack.getMaxStackSize() - stack.getCount()), Integer::sum);
            }
        }
        return SchematicMaterialCounter.fitBatch(remaining, held, stackSpace,
                Math.max(0, emptySlots - RESERVED_FREE_SLOTS));
    }

    private void processReadySpecialPlacements(AltoClef mod) {
        if (!scheduleReadySpecialPlacements(mod)) prepareBatch(mod);
    }

    /** Selects only currently placeable special blocks; each special task gathers its own item JIT. */
    private boolean scheduleReadySpecialPlacements(AltoClef mod) {
        Set<BlockPos> candidates = collectUnsatisfiedSpecialPositions(mod);
        if (candidates.isEmpty()) {
            _pendingSpecialPositions.clear();
            return false;
        }
        IStaticSchematic candidateSchematic = schematicWithOnlyCells(_materialSchematic, candidates);
        Set<BlockPos> deferred = SpecialSchematicBlockPlacementTask.findDeferredCells(
                candidateSchematic, _materialSchematic, _snapshot.origin(), mod.getWorld());
        Set<BlockPos> ready = readySpecialPositions(candidates, deferred);
        _pendingSpecialPositions.clear();
        _pendingSpecialPositions.addAll(deferred);
        if (ready.isEmpty()) return false;

        _postBuildSpecialPositions = ready;
        _batchSchematic = schematicWithOnlyCells(_snapshot.schematic(), ready);
        _batchMaterialSchematic = schematicWithOnlyCells(_materialSchematic, ready);
        _materialTask = null;
        _specialPlacementTask = null;
        _postBuildSpecialPlacements = true;
        _buildRequested = false;
        _builderWasActive = false;
        _activationWaitTicks = 0;
        return true;
    }

    static Set<BlockPos> readySpecialPositions(Set<BlockPos> candidates, Set<BlockPos> deferred) {
        Set<BlockPos> ready = new HashSet<>(candidates);
        ready.removeAll(deferred);
        return Set.copyOf(ready);
    }

    private Set<BlockPos> collectUnsatisfiedSpecialPositions(AltoClef mod) {
        Set<BlockPos> candidates = new HashSet<>();
        List<BlockState> approxPlaceable = getApproxPlaceable(mod);
        for (int x = 0; x < _materialSchematic.widthX(); x++) {
            for (int y = 0; y < _materialSchematic.heightY(); y++) {
                for (int z = 0; z < _materialSchematic.lengthZ(); z++) {
                    BlockPos local = new BlockPos(x, y, z);
                    BlockState state = _materialSchematic.getDirect(x, y, z);
                    if (state == null || !isSpecialPlacementState(state)
                            || !_materialSchematic.inSchematic(x, y, z, state)) continue;
                    if (state.getBlock() instanceof DoorBlock) {
                        if (state.getValue(DoorBlock.HALF) != net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER) continue;
                        BlockState upper = y + 1 < _materialSchematic.heightY()
                                ? _materialSchematic.getDirect(x, y + 1, z) : null;
                        if (upper == null || upper.getBlock() != state.getBlock()
                                || !upper.hasProperty(DoorBlock.HALF)
                                || upper.getValue(DoorBlock.HALF)
                                != net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER) continue;
                        BlockPos world = _snapshot.origin().offset(local);
                        BlockState actualLower = mod.getWorld().getBlockState(world);
                        BlockState actualUpper = mod.getWorld().getBlockState(world.above());
                        BlockState desiredLower = _materialSchematic.desiredState(x, y, z, actualLower, approxPlaceable);
                        BlockState desiredUpper = _materialSchematic.desiredState(x, y + 1, z, actualUpper, approxPlaceable);
                        if (!actualLower.equals(desiredLower) || !actualUpper.equals(desiredUpper)) {
                            candidates.add(local);
                            candidates.add(local.above());
                        }
                    } else {
                        BlockPos world = _snapshot.origin().offset(local);
                        BlockState actual = mod.getWorld().getBlockState(world);
                        BlockState desired = _materialSchematic.desiredState(x, y, z, actual, approxPlaceable);
                        if (!actual.equals(desired)) candidates.add(local);
                    }
                }
            }
        }
        return Set.copyOf(candidates);
    }

    private static boolean isSpecialPlacementState(BlockState state) {
        return state != null && (state.getBlock() instanceof DoorBlock
                || state.getBlock() instanceof SlabBlock
                && state.getValue(SlabBlock.TYPE) == SlabType.DOUBLE);
    }

    static IStaticSchematic schematicWithOnlyCells(IStaticSchematic source, Set<BlockPos> included) {
        Set<BlockPos> selected = Set.copyOf(included);
        return new IStaticSchematic() {
            @Override
            public BlockState getDirect(int x, int y, int z) {
                return selected.contains(new BlockPos(x, y, z))
                        ? source.getDirect(x, y, z) : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
            }

            @Override
            public BlockState desiredState(int x, int y, int z, BlockState current,
                                           List<BlockState> approxPlaceable) {
                return selected.contains(new BlockPos(x, y, z))
                        ? source.desiredState(x, y, z, current, approxPlaceable) : current;
            }

            @Override
            public boolean inSchematic(int x, int y, int z, BlockState current) {
                return selected.contains(new BlockPos(x, y, z)) && source.inSchematic(x, y, z, current);
            }

            @Override public int widthX() { return source.widthX(); }
            @Override public int heightY() { return source.heightY(); }
            @Override public int lengthZ() { return source.lengthZ(); }
        };
    }

    private void startBuild(AltoClef mod) {
        IBuilderProcess builder = mod.getClientBaritone().getBuilderProcess();
        if (builder.isActive()) {
            fail(mod, "Baritone is already running another build.");
            return;
        }

        _buildRequested = true;
        _builderWatchdog.start(countUnsatisfiedBuilderPositions(mod), builder.getMinLayer(), builder.getMaxLayer());
        Settings settings = BaritoneAPI.getSettings();
        Map<Block, List<Block>> configuredSubstitutions = settings.buildSubstitutes.value;
        settings.buildSubstitutes.value = _selectedSubstitutions;
        try {
            builder.build(_snapshot.name() + " [batch " + _batchNumber + "]", _initialBatchSchematic, _snapshot.origin());
        } finally {
            settings.buildSubstitutes.value = configuredSubstitutions;
        }
        setDebugState("Waiting for Baritone builder to start");
    }

    boolean shouldDeferBuilderLaunch() {
        if (shouldWaitForBuilderLaunch(_builderLaunchPending, hasActiveChildTask())) {
            _builderLaunchPending = true;
            return true;
        }
        _builderLaunchPending = false;
        return false;
    }

    static boolean shouldWaitForBuilderLaunch(boolean launchPending, boolean activeDescendant) {
        return !launchPending || activeDescendant;
    }

    private boolean hasActiveChildTask() {
        return thisOrChildSatisfies(task -> task != this && task.isActive());
    }

    private boolean verifyPlacedBlocks(AltoClef mod, baritone.api.schematic.IStaticSchematic schematic) {
        if (mod.getWorld() == null) return false;
        return getMismatchedPositions(mod, schematic).isEmpty();
    }

    private Set<BlockPos> getMismatchedPositions(AltoClef mod, IStaticSchematic schematic) {
        if (mod.getWorld() == null) {
            return collectSelectedPositions(schematic).stream()
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
        Set<BlockPos> mismatches = new HashSet<>();
        List<BlockState> approxPlaceable = getApproxPlaceable(mod);
        ISchematic effectiveSchematic = _materialSchematic;
        SchematicBlockStateMatcher.Options matchOptions = getBlockStateMatchOptions();
        for (int x = 0; x < schematic.widthX(); x++) {
            for (int y = 0; y < schematic.heightY(); y++) {
                for (int z = 0; z < schematic.lengthZ(); z++) {
                    if (!schematic.inSchematic(x, y, z, schematic.getDirect(x, y, z))) {
                        continue;
                    }
                    BlockState target = schematic.getDirect(x, y, z);
                    if (target == null) {
                        continue;
                    }
                    BlockPos worldPos = _snapshot.origin().offset(x, y, z);
                    BlockState actual = mod.getWorld().getBlockState(worldPos);
                    BlockState desired = effectiveSchematic.desiredState(x, y, z, actual, approxPlaceable);
                    if (requiresExactSpecialState(desired) && !actual.equals(desired)
                            || !requiresExactSpecialState(desired)
                            && !SchematicBlockStateMatcher.matches(actual, desired, matchOptions)) {
                        mismatches.add(new BlockPos(x, y, z));
                    }
                }
            }
        }
        return Set.copyOf(mismatches);
    }

    static boolean onlyTrackedRemovedMismatches(Set<BlockPos> mismatches,
                                                Set<BlockPos> trackedRemovedPositions,
                                                Set<BlockPos> eligibleRestorationPositions) {
        return !mismatches.isEmpty()
                && trackedRemovedPositions.containsAll(mismatches)
                && eligibleRestorationPositions.containsAll(mismatches);
    }

    private Set<BlockPos> getEligibleTemporaryRestorations(AltoClef mod, Set<BlockPos> tracked) {
        if (mod.getWorld() == null) return Set.of();
        return selectTemporaryRestorationTargets(_materialSchematic, tracked, _snapshot.origin(),
                worldPos -> mod.getWorld().getBlockState(worldPos));
    }

    static Set<BlockPos> selectTemporaryRestorationTargets(IStaticSchematic schematic,
                                                           Set<BlockPos> tracked,
                                                           BlockPos origin,
                                                           Function<BlockPos, BlockState> actualState) {
        Set<BlockPos> eligible = new HashSet<>();
        for (BlockPos local : tracked) {
            if (local.getX() < 0 || local.getX() >= schematic.widthX()
                    || local.getY() < 0 || local.getY() >= schematic.heightY()
                    || local.getZ() < 0 || local.getZ() >= schematic.lengthZ()) continue;
            BlockState desired = schematic.getDirect(local.getX(), local.getY(), local.getZ());
            if (!isEligibleTemporaryRestorationState(desired)
                    || !schematic.inSchematic(local.getX(), local.getY(), local.getZ(), desired)) continue;
            BlockPos worldPos = origin.offset(local);
            BlockState actual = actualState.apply(worldPos);
            if (actual != null && actual.isAir()) eligible.add(local);
        }
        return Set.copyOf(eligible);
    }

    private Set<BlockPos> getEligibleInProgressDoubleSlabRestorations(AltoClef mod,
                                                                       Set<BlockPos> tracked,
                                                                       Set<BlockPos> started) {
        if (mod.getWorld() == null) return Set.of();
        return selectInProgressDoubleSlabRestorations(_materialSchematic, tracked, started,
                _snapshot.origin(), mod.getWorld()::getBlockState);
    }

    static Set<BlockPos> selectInProgressDoubleSlabRestorations(IStaticSchematic schematic,
                                                                Set<BlockPos> tracked,
                                                                Set<BlockPos> started,
                                                                BlockPos origin,
                                                                Function<BlockPos, BlockState> actualState) {
        Set<BlockPos> eligible = new HashSet<>();
        for (BlockPos local : tracked) {
            if (!started.contains(local)
                    || local.getX() < 0 || local.getX() >= schematic.widthX()
                    || local.getY() < 0 || local.getY() >= schematic.heightY()
                    || local.getZ() < 0 || local.getZ() >= schematic.lengthZ()) continue;
            BlockState desired = schematic.getDirect(local.getX(), local.getY(), local.getZ());
            if (desired == null || !schematic.inSchematic(
                    local.getX(), local.getY(), local.getZ(), desired)) continue;
            BlockPos world = origin.offset(local);
            if (isPartialDoubleSlab(actualState.apply(world), desired)) eligible.add(local);
        }
        return Set.copyOf(eligible);
    }

    static boolean isPartialDoubleSlab(BlockState actual, BlockState desired) {
        return actual != null && desired != null
                && desired.getBlock() instanceof SlabBlock
                && desired.hasProperty(SlabBlock.TYPE)
                && desired.getValue(SlabBlock.TYPE) == SlabType.DOUBLE
                && actual.getBlock() == desired.getBlock()
                && actual.hasProperty(SlabBlock.TYPE)
                && actual.getValue(SlabBlock.TYPE) != SlabType.DOUBLE;
    }

    static boolean isEligibleTemporaryRestorationState(BlockState state) {
        if (state == null || state.isAir() || state.getBlock() instanceof DoorBlock) return false;
        Item item = state.getBlock().asItem();
        return item != Items.AIR && (TaskCatalogue.taskExists(item) || isSpecialPlacementState(state));
    }

    private void refreshTemporaryRemovedPositions(AltoClef mod) {
        if (mod.getWorld() == null || _temporarilyRemovedPositions.isEmpty()) return;
        List<BlockState> approxPlaceable = getApproxPlaceable(mod);
        SchematicBlockStateMatcher.Options matchOptions = getBlockStateMatchOptions();
        _temporarilyRemovedPositions.removeIf(local -> isPositionSatisfied(
                mod, local, approxPlaceable, _materialSchematic, matchOptions));
    }

    private static List<BlockState> getApproxPlaceable(AltoClef mod) {
        try {
            List<BlockState> approxPlaceable = mod.getClientBaritone().getBuilderProcess().getApproxPlaceable();
            return approxPlaceable == null ? List.of() : approxPlaceable;
        } catch (NullPointerException exception) {
            // Baritone 1.19.0 initializes this list on the builder's first tick,
            // after build() has already made the process active. Its getter
            // copies the field directly, so calls during initial planning (or
            // between build() and that tick) throw instead of returning null.
            return List.of();
        }
    }

    private static boolean requiresExactSpecialState(BlockState state) {
        return state != null && (state.getBlock() instanceof DoorBlock
                || state.getBlock() instanceof SlabBlock
                && state.getValue(SlabBlock.TYPE) == SlabType.DOUBLE);
    }

    private boolean prepareMaterialSchematic(AltoClef mod) {
        Map<Block, List<Block>> configured = BaritoneAPI.getSettings().buildSubstitutes.value;
        if (configured.isEmpty()) {
            _materialSchematic = _snapshot.schematic();
            return true;
        }
        try {
            _selectedSubstitutions = SubstitutedStaticSchematic.selectSupportedSubstitutions(
                    _snapshot.schematic(), configured, TaskCatalogue::taskExists);
        } catch (IllegalArgumentException exception) {
            fail(mod, exception.getMessage());
            return false;
        }
        _materialSchematic = new SubstitutedStaticSchematic(_snapshot.schematic(), _selectedSubstitutions);
        return true;
    }

    private static SchematicBlockStateMatcher.Options getBlockStateMatchOptions() {
        Settings settings = BaritoneAPI.getSettings();
        return new SchematicBlockStateMatcher.Options(
                settings.buildIgnoreExisting.value,
                settings.buildIgnoreDirection.value,
                settings.buildIgnoreProperties.value,
                settings.okIfWater.value,
                settings.okIfAir.value,
                settings.buildIgnoreBlocks.value,
                settings.buildValidSubstitutes.value);
    }

    private static int totalUnits(Map<Item, Integer> materials) {
        long total = materials.values().stream().mapToLong(Integer::longValue).sum();
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    private void fail(AltoClef mod, String reason) {
        if (_failureCleanupActive) {
            if (_failureCleanupFailure == null) _failureCleanupFailure = reason;
            return;
        }
        if (_buildRequested) {
            IBuilderProcess builder = mod.getClientBaritone().getBuilderProcess();
            if (builder.isActive()) {
                builder.onLostControl();
            }
        }
        if (mod.getWorld() != null && !_temporarilyRemovedPositions.isEmpty()) {
            if (_specialPlacementTask != null) {
                _temporarilyRemovedPositions.addAll(_specialPlacementTask.getTemporarilyRemovedPositions());
            }
            refreshTemporaryRemovedPositions(mod);
            Set<BlockPos> safeTargets = getEligibleTemporaryRestorations(mod, _temporarilyRemovedPositions);
            if (!safeTargets.isEmpty()) {
                beginFailureCleanup(mod, reason, safeTargets);
                return;
            }
        }
        finalizeFailure(mod, reason + unresolvedTemporaryRestorationSuffix(mod));
    }

    private void beginFailureCleanup(AltoClef mod, String reason, Set<BlockPos> safeTargets) {
        _failureCleanupActive = true;
        _failureCleanupWaitingForMaterialsChildStop = false;
        _failureCleanupOriginalReason = reason;
        _failureCleanupFailure = null;
        _failureCleanupTicks = 0;
        _failureCleanupFailures.clear();
        _failureCleanupStartedPositions.addAll(safeTargets);
        _failureCleanupTargets = Set.copyOf(safeTargets);
        _failureCleanupSchematic = schematicWithOnlyCells(_materialSchematic, _failureCleanupTargets);
        _failureCleanupOrdinaryPositions = _failureCleanupTargets.stream()
                .filter(local -> !isSpecialPlacementState(_materialSchematic.getDirect(
                        local.getX(), local.getY(), local.getZ())))
                .sorted(BlockPos::compareTo).toList();
        _failureCleanupDoubleSlabPositions = _failureCleanupTargets.stream()
                .filter(local -> isSpecialPlacementState(_materialSchematic.getDirect(
                        local.getX(), local.getY(), local.getZ())))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        _failureCleanupDoubleSlabList = _failureCleanupDoubleSlabPositions.stream()
                .sorted(BlockPos::compareTo).toList();
        _failureCleanupOrdinaryIndex = 0;
        _failureCleanupDoubleSlabIndex = 0;
        _failureCleanupBuilderPosition = null;
        _failureCleanupBuilderStarted = false;
        _failureCleanupBuilderIssued = false;
        _failureCleanupBuilderWaitTicks = 0;
        _failureCleanupSlabTask = null;
        _failureCleanupPhase = FailureCleanupPhase.MATERIALS;

        IStaticSchematic ordinarySchematic = schematicWithOnlyCells(
                _materialSchematic, Set.copyOf(_failureCleanupOrdinaryPositions));
        Map<Item, Integer> materials = SchematicMaterialCounter.count(ordinarySchematic);
        List<ItemTarget> targets = new ArrayList<>(materials.size());
        for (Map.Entry<Item, Integer> entry : materials.entrySet()) {
            if (!TaskCatalogue.taskExists(entry.getKey())) {
                _failureCleanupFailures.addAll(safeTargets);
                _failureCleanupFailure = "No resource task is registered for cleanup material "
                        + entry.getKey() + ".";
                finalizeFailure(mod, failureCleanupReason(mod));
                return;
            }
            targets.add(new ItemTarget(entry.getKey(), entry.getValue()));
        }
        _failureCleanupMaterialTask = targets.isEmpty()
                ? null : new CataloguedResourceTask(targets.toArray(ItemTarget[]::new));
        setDebugState("Restoring temporarily removed authored schematic neighbors");
        Debug.logMessage("Schematic build failed: %s; restoring %d tracked authored neighbor cells before reporting failure.",
                reason, safeTargets.size());
    }

    private void resetFailureCleanupForResume(AltoClef mod) {
        _failureCleanupWaitingForMaterialsChildStop = false;
        _failureCleanupMaterialTask = null;
        _failureCleanupBuilderPosition = null;
        _failureCleanupBuilderStarted = false;
        _failureCleanupBuilderIssued = false;
        _failureCleanupBuilderWaitTicks = 0;
        _failureCleanupSlabTask = null;
        _failureCleanupTicks = 0;
        _failureCleanupFailures.clear();
        refreshTemporaryRemovedPositions(mod);
        Set<BlockPos> safeTargets = new HashSet<>(
                getEligibleTemporaryRestorations(mod, _temporarilyRemovedPositions));
        safeTargets.addAll(getEligibleInProgressDoubleSlabRestorations(
                mod, _temporarilyRemovedPositions, _failureCleanupSlabsStartedPositions));
        if (safeTargets.isEmpty()) {
            finalizeFailure(mod, failureCleanupReason(mod));
            return;
        }
        beginFailureCleanup(mod, _failureCleanupOriginalReason, safeTargets);
    }

    private Task tickFailureCleanup(AltoClef mod) {
        if (++_failureCleanupTicks > FAILURE_CLEANUP_TIMEOUT_TICKS) {
            _failureCleanupFailure = "Temporary-neighbor restoration timed out after "
                    + FAILURE_CLEANUP_TIMEOUT_TICKS + " ticks.";
            _failureCleanupFailures.addAll(getEligibleTemporaryRestorations(mod, _temporarilyRemovedPositions));
            finalizeFailure(mod, failureCleanupReason(mod));
            return null;
        }
        if (mod.getWorld() == null || mod.getPlayer() == null) {
            setDebugState("Waiting for a world and player to restore temporary schematic neighbors");
            return null;
        }
        if (_failureCleanupPhase == FailureCleanupPhase.MATERIALS) {
            if (_failureCleanupMaterialTask != null && !_failureCleanupMaterialTask.isFinished(mod)) {
                TaskFailure.Snapshot childFailure = unhandledChildFailure(_failureCleanupMaterialTask, mod);
                if (childFailure != null) {
                    _failureCleanupFailure = "Cleanup material task failed: " + failureReason(childFailure);
                    _failureCleanupFailures.addAll(_failureCleanupTargets);
                    finalizeFailure(mod, failureCleanupReason(mod));
                    return null;
                }
                setDebugState("Gathering materials to restore authored schematic neighbors");
                return _failureCleanupMaterialTask;
            }
            if (_failureCleanupMaterialTask != null && _failureCleanupMaterialTask.hasFailed()) {
                _failureCleanupFailure = "Could not gather cleanup materials: "
                        + _failureCleanupMaterialTask.getFailureReason();
                _failureCleanupFailures.addAll(_failureCleanupTargets);
                finalizeFailure(mod, failureCleanupReason(mod));
                return null;
            }
            _failureCleanupPhase = FailureCleanupPhase.ORDINARY_BLOCKS;
            _failureCleanupWaitingForMaterialsChildStop = true;
            // The completed material task may still own a DestroyBlockTask whose onStop
            // cancels Baritone. Let Task.tick stop that child before starting cleanup builds.
            return null;
        }
        if (_failureCleanupPhase == FailureCleanupPhase.ORDINARY_BLOCKS) {
            if (_failureCleanupWaitingForMaterialsChildStop) {
                if (hasActiveChildTask()) return null;
                _failureCleanupWaitingForMaterialsChildStop = false;
            }
            Task ordinaryTask = tickFailureCleanupOrdinaryBlock(mod);
            if (ordinaryTask != null) return ordinaryTask;
            if (_failureCleanupOrdinaryIndex < _failureCleanupOrdinaryPositions.size()) return null;
            _failureCleanupPhase = FailureCleanupPhase.DOUBLE_SLABS;
        }
        if (_failureCleanupPhase == FailureCleanupPhase.DOUBLE_SLABS) {
            Task slabTask = tickFailureCleanupDoubleSlab(mod);
            if (slabTask != null) return slabTask;
            if (_failureCleanupDoubleSlabIndex < _failureCleanupDoubleSlabList.size()) return null;
            refreshTemporaryRemovedPositions(mod);
            finalizeFailure(mod, failureCleanupReason(mod));
        }
        return null;
    }

    private Task tickFailureCleanupOrdinaryBlock(AltoClef mod) {
        if (_failureCleanupBuilderPosition == null) {
            while (_failureCleanupOrdinaryIndex < _failureCleanupOrdinaryPositions.size()) {
                BlockPos local = _failureCleanupOrdinaryPositions.get(_failureCleanupOrdinaryIndex);
                BlockState desired = _materialSchematic.getDirect(local.getX(), local.getY(), local.getZ());
                BlockPos world = _snapshot.origin().offset(local);
                BlockState actual = mod.getWorld().getBlockState(world);
                if (actual.equals(desired)) {
                    _temporarilyRemovedPositions.remove(local);
                    _failureCleanupOrdinaryIndex++;
                    continue;
                }
                if (!actual.isAir()) {
                    _failureCleanupFailures.add(local);
                    _failureCleanupOrdinaryIndex++;
                    continue;
                }
                _failureCleanupBuilderPosition = local;
                _failureCleanupBuilderStarted = false;
                _failureCleanupBuilderIssued = false;
                _failureCleanupBuilderWaitTicks = 0;
                break;
            }
            if (_failureCleanupOrdinaryIndex >= _failureCleanupOrdinaryPositions.size()) return null;
        }

        BlockPos local = _failureCleanupBuilderPosition;
        BlockState desired = _materialSchematic.getDirect(local.getX(), local.getY(), local.getZ());
        BlockPos world = _snapshot.origin().offset(local);
        BlockState actual = mod.getWorld().getBlockState(world);
        if (actual.equals(desired)) {
            _temporarilyRemovedPositions.remove(local);
            stopFailureCleanupBuilder(mod);
            advanceFailureCleanupOrdinaryBlock();
            return null;
        }
        if (!actual.isAir()) {
            stopFailureCleanupBuilder(mod);
            _failureCleanupFailures.add(local);
            advanceFailureCleanupOrdinaryBlock();
            return null;
        }

        IBuilderProcess builder = mod.getClientBaritone().getBuilderProcess();
        if (builder.isActive()) {
            _failureCleanupBuilderStarted = true;
            if (++_failureCleanupBuilderWaitTicks > BUILDER_START_TIMEOUT_TICKS * 12) {
                stopFailureCleanupBuilder(mod);
                _failureCleanupFailures.add(local);
                advanceFailureCleanupOrdinaryBlock();
                return null;
            }
            return null;
        }
        if (_failureCleanupBuilderIssued) {
            if (++_failureCleanupBuilderWaitTicks >= BUILDER_START_TIMEOUT_TICKS) {
                _failureCleanupFailures.add(local);
                advanceFailureCleanupOrdinaryBlock();
            }
            return null;
        }
        if (++_failureCleanupBuilderWaitTicks >= BUILDER_START_TIMEOUT_TICKS) {
            _failureCleanupFailures.add(local);
            advanceFailureCleanupOrdinaryBlock();
            return null;
        }

        IStaticSchematic singleCell = schematicWithOnlyCells(_materialSchematic, Set.of(local));
        Map<Block, List<Block>> configuredSubstitutions = BaritoneAPI.getSettings().buildSubstitutes.value;
        BaritoneAPI.getSettings().buildSubstitutes.value = _selectedSubstitutions;
        try {
            builder.build(_snapshot.name() + " [failure neighbor cleanup]", singleCell, _snapshot.origin());
            _failureCleanupBuilderIssued = true;
        } finally {
            BaritoneAPI.getSettings().buildSubstitutes.value = configuredSubstitutions;
        }
        return null;
    }

    private Task tickFailureCleanupDoubleSlab(AltoClef mod) {
        while (_failureCleanupDoubleSlabIndex < _failureCleanupDoubleSlabList.size()) {
            BlockPos local = _failureCleanupDoubleSlabList.get(_failureCleanupDoubleSlabIndex);
            BlockState desired = _materialSchematic.getDirect(local.getX(), local.getY(), local.getZ());
            BlockPos world = _snapshot.origin().offset(local);
            BlockState actual = mod.getWorld().getBlockState(world);
            if (_failureCleanupSlabTask != null) {
                if (!_failureCleanupSlabTask.isFinished(mod)) {
                    TaskFailure.Snapshot childFailure = unhandledChildFailure(_failureCleanupSlabTask, mod);
                    if (childFailure != null) {
                        _failureCleanupFailure = "Cleanup slab task failed: " + failureReason(childFailure);
                        _failureCleanupFailures.add(local);
                        _temporarilyRemovedPositions.addAll(
                                _failureCleanupSlabTask.getTemporarilyRemovedPositions());
                        _failureCleanupDoubleSlabIndex++;
                        _failureCleanupSlabTask = null;
                        continue;
                    }
                    return _failureCleanupSlabTask;
                }
                if (_failureCleanupSlabTask.hasFailed()
                        || !mod.getWorld().getBlockState(world).equals(desired)) {
                    _failureCleanupFailures.add(local);
                } else {
                    _temporarilyRemovedPositions.remove(local);
                }
                _failureCleanupDoubleSlabIndex++;
                _failureCleanupSlabTask = null;
                continue;
            }
            if (actual.equals(desired)) {
                _temporarilyRemovedPositions.remove(local);
                _failureCleanupDoubleSlabIndex++;
                continue;
            }
            boolean ownedPartialSlab = _failureCleanupSlabsStartedPositions.contains(local)
                    && isPartialDoubleSlab(actual, desired);
            if (!actual.isAir() && !ownedPartialSlab) {
                _failureCleanupFailures.add(local);
                _failureCleanupDoubleSlabIndex++;
                continue;
            }
            IStaticSchematic oneSlab = schematicWithOnlyCells(_materialSchematic, Set.of(local));
            if (actual.isAir()) {
                _failureCleanupSlabsStartedPositions.add(local);
            }
            _failureCleanupSlabTask = new SpecialSchematicBlockPlacementTask(
                    oneSlab, _snapshot.origin(), Set.of(), _materialSchematic, true, ownedPartialSlab);
            return _failureCleanupSlabTask;
        }
        return null;
    }

    private void advanceFailureCleanupOrdinaryBlock() {
        _failureCleanupOrdinaryIndex++;
        _failureCleanupBuilderPosition = null;
        _failureCleanupBuilderStarted = false;
        _failureCleanupBuilderIssued = false;
        _failureCleanupBuilderWaitTicks = 0;
    }

    private void stopFailureCleanupBuilder(AltoClef mod) {
        IBuilderProcess builder = mod.getClientBaritone().getBuilderProcess();
        if (builder.isActive()) builder.onLostControl();
        _failureCleanupBuilderStarted = false;
    }

    private String failureCleanupReason(AltoClef mod) {
        refreshTemporaryRemovedPositions(mod);
        Set<BlockPos> unresolved = Set.copyOf(_temporarilyRemovedPositions);
        int restored = Math.max(0, _failureCleanupStartedPositions.size() - unresolved.size());
        StringBuilder result = new StringBuilder(_failureCleanupOriginalReason == null
                ? "Schematic build failed." : _failureCleanupOriginalReason);
        result.append(" Temporary-neighbor cleanup restored ").append(restored)
                .append(" of ").append(_failureCleanupStartedPositions.size()).append(" tracked authored cells.");
        if (_failureCleanupFailure != null) result.append(" Cleanup stopped because: ").append(_failureCleanupFailure);
        if (!unresolved.isEmpty()) result.append(" Unrestored tracked positions: ").append(unresolved).append('.');
        if (!_failureCleanupFailures.isEmpty()) result.append(" Cleanup could not safely place: ")
                .append(_failureCleanupFailures).append('.');
        return result.toString();
    }

    private String unresolvedTemporaryRestorationSuffix(AltoClef mod) {
        refreshTemporaryRemovedPositions(mod);
        return _temporarilyRemovedPositions.isEmpty() ? ""
                : " Temporary authored neighbor positions remain unrestored: "
                + _temporarilyRemovedPositions + ".";
    }

    private void finalizeFailure(AltoClef mod, String reason) {
        _failed = true;
        _failureReason = reason;
        _failureCleanupActive = false;
        Debug.logError("Schematic build failed: " + reason);
    }
}
