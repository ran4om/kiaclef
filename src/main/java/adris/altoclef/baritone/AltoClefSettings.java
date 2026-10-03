package adris.altoclef.baritone;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * AltoClef's extension settings for the bundled Baritone build.
 *
 * <p>These policies used to live inside a private Baritone fork. Keeping them
 * on the AltoClef side lets the project consume upstream Baritone while its
 * mixins apply the policies at path planning, placement, input, and inventory
 * boundaries.</p>
 */
public final class AltoClefSettings {
    private static final AltoClefSettings INSTANCE = new AltoClefSettings();

    private final Object breakMutex = new Object();
    private final Object placeMutex = new Object();
    private final Object propertiesMutex = new Object();
    private final Object globalHeuristicMutex = new Object();

    private final Set<BlockPos> blocksToAvoidBreaking = ConcurrentHashMap.newKeySet();
    private final List<Predicate<BlockPos>> breakAvoiders = new CopyOnWriteArrayList<>();
    private final List<Predicate<BlockPos>> placeAvoiders = new CopyOnWriteArrayList<>();
    private final List<Predicate<BlockPos>> forceWalkOn = new CopyOnWriteArrayList<>();
    private final List<Predicate<BlockPos>> forceAvoidWalkThrough = new CopyOnWriteArrayList<>();
    private final List<BiPredicate<BlockState, ItemStack>> forceUseTool = new CopyOnWriteArrayList<>();
    private final List<BiPredicate<BlockState, ItemStack>> avoidUseTool = new CopyOnWriteArrayList<>();
    private final List<BiFunction<Double, BlockPos, Double>> globalHeuristics = new CopyOnWriteArrayList<>();
    private final Set<Item> protectedItems = ConcurrentHashMap.newKeySet();

    private volatile boolean allowFlowingWaterPass;
    private volatile boolean interactionPaused;
    private volatile boolean dontPlaceBucketButStillFall;
    private volatile boolean allowSwimThroughLava;
    private volatile boolean treatSoulSandAsOrdinaryBlock;
    /** Fluid policy used by Baritone's ray tracing, copied with AltoClef behavior state. */
    public volatile ClipContext.Fluid rayFluidHandling = ClipContext.Fluid.NONE;

    private AltoClefSettings() {
    }

    public static AltoClefSettings getInstance() {
        return INSTANCE;
    }

    public void avoidBlockBreak(BlockPos pos) {
        blocksToAvoidBreaking.add(pos.immutable());
    }

    public void avoidBlockBreak(Predicate<BlockPos> avoider) {
        breakAvoiders.add(avoider);
    }

    public void configurePlaceBucketButDontFall(boolean allow) {
        dontPlaceBucketButStillFall = allow;
    }

    public void treatSoulSandAsOrdinaryBlock(boolean enable) {
        treatSoulSandAsOrdinaryBlock = enable;
    }

    public void avoidBlockPlace(Predicate<BlockPos> avoider) {
        placeAvoiders.add(avoider);
    }

    public boolean shouldAvoidBreaking(int x, int y, int z) {
        return shouldAvoidBreaking(new BlockPos(x, y, z));
    }

    public boolean shouldAvoidBreaking(BlockPos pos) {
        if (blocksToAvoidBreaking.contains(pos)) return true;
        for (Predicate<BlockPos> avoider : breakAvoiders) {
            if (avoider.test(pos)) return true;
        }
        return false;
    }

    public boolean shouldAvoidPlacingAt(BlockPos pos) {
        for (Predicate<BlockPos> avoider : placeAvoiders) {
            if (avoider.test(pos)) return true;
        }
        return false;
    }

    public boolean shouldAvoidPlacingAt(int x, int y, int z) {
        return shouldAvoidPlacingAt(new BlockPos(x, y, z));
    }

    public boolean canWalkOnForce(int x, int y, int z) {
        return matchesPosition(forceWalkOn, new BlockPos(x, y, z));
    }

    public boolean canWalkThroughEndPortal(BlockState state, int x, int y, int z) {
        return state.is(net.minecraft.world.level.block.Blocks.END_PORTAL)
                && canWalkOnForce(x, y, z)
                && !shouldAvoidWalkThroughForce(x, y, z);
    }

    public boolean shouldAvoidWalkThroughForce(BlockPos pos) {
        return matchesPosition(forceAvoidWalkThrough, pos);
    }

    public boolean shouldAvoidWalkThroughForce(int x, int y, int z) {
        return shouldAvoidWalkThroughForce(new BlockPos(x, y, z));
    }

    public boolean shouldForceUseTool(BlockState state, ItemStack tool) {
        for (BiPredicate<BlockState, ItemStack> predicate : forceUseTool) {
            if (predicate.test(state, tool)) return true;
        }
        return false;
    }

    public boolean shouldAvoidUseTool(BlockState state, ItemStack tool) {
        for (BiPredicate<BlockState, ItemStack> predicate : avoidUseTool) {
            if (predicate.test(state, tool)) return true;
        }
        return false;
    }

    private static boolean matchesPosition(List<Predicate<BlockPos>> predicates, BlockPos pos) {
        for (Predicate<BlockPos> predicate : predicates) {
            if (predicate.test(pos)) return true;
        }
        return false;
    }

    public boolean shouldNotPlaceBucketButStillFall() {
        return dontPlaceBucketButStillFall;
    }

    public boolean shouldTreatSoulSandAsOrdinaryBlock() {
        return treatSoulSandAsOrdinaryBlock;
    }

    public boolean isInteractionPaused() {
        return interactionPaused;
    }

    public boolean isFlowingWaterPassAllowed() {
        return allowFlowingWaterPass;
    }

    public boolean canSwimThroughLava() {
        return allowSwimThroughLava;
    }

    public void setInteractionPaused(boolean paused) {
        interactionPaused = paused;
    }

    public void setFlowingWaterPass(boolean pass) {
        allowFlowingWaterPass = pass;
    }

    public void allowSwimThroughLava(boolean allow) {
        allowSwimThroughLava = allow;
    }

    /**
     * The legacy Baritone fork deliberately disabled custom heuristic changes
     * because they reduced path reliability. Retain this method's historical
     * behavior while preserving callback lists during behavior-state swaps.
     */
    public double applyGlobalHeuristic(double previous, int x, int y, int z) {
        return previous;
    }

    public Set<BlockPos> getBlocksToAvoidBreaking() {
        return blocksToAvoidBreaking;
    }

    public List<Predicate<BlockPos>> getBreakAvoiders() {
        return breakAvoiders;
    }

    public List<Predicate<BlockPos>> getPlaceAvoiders() {
        return placeAvoiders;
    }

    public List<Predicate<BlockPos>> getForceWalkOnPredicates() {
        return forceWalkOn;
    }

    public List<Predicate<BlockPos>> getForceAvoidWalkThroughPredicates() {
        return forceAvoidWalkThrough;
    }

    public List<BiPredicate<BlockState, ItemStack>> getForceUseToolPredicates() {
        return forceUseTool;
    }

    public List<BiPredicate<BlockState, ItemStack>> getAvoidUseToolPredicates() {
        return avoidUseTool;
    }

    public List<BiFunction<Double, BlockPos, Double>> getGlobalHeuristics() {
        return globalHeuristics;
    }

    public boolean isItemProtected(Item item) {
        return protectedItems.contains(item);
    }

    public Set<Item> getProtectedItems() {
        return protectedItems;
    }

    public void protectItem(Item item) {
        protectedItems.add(item);
    }

    public void stopProtectingItem(Item item) {
        protectedItems.remove(item);
    }

    public Object getBreakMutex() {
        return breakMutex;
    }

    public Object getPlaceMutex() {
        return placeMutex;
    }

    public Object getPropertiesMutex() {
        return propertiesMutex;
    }

    public Object getGlobalHeuristicMutex() {
        return globalHeuristicMutex;
    }
}
