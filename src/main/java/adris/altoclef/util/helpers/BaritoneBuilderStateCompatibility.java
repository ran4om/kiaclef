package adris.altoclef.util.helpers;

import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.FrontAndTop;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Compatibility checks and opt-in diagnostics for Baritone door placement. */
public final class BaritoneBuilderStateCompatibility {
    private static final Logger PLACEMENT_LOG = LoggerFactory.getLogger("AltoClef/BuilderPlacementDiagnostics");
    private static final AtomicInteger PLACEMENT_DIAGNOSTIC_LINES = new AtomicInteger();
    private static final AtomicInteger CRAFTER_SEARCH_DIAGNOSTIC_LINES = new AtomicInteger();
    private static final AtomicInteger CRAFTER_CLICK_DIAGNOSTIC_LINES = new AtomicInteger();
    private static final AtomicInteger CRAFTER_ATTEMPT_DIAGNOSTIC_LINES = new AtomicInteger();
    private static final AtomicInteger CRAFTER_PREDICTION_DIAGNOSTIC_LINES = new AtomicInteger();
    private static final AtomicInteger CRAFTER_VALIDITY_DIAGNOSTIC_LINES = new AtomicInteger();
    private static final int MAX_PLACEMENT_DIAGNOSTIC_LINES = 400;
    private static final int MAX_CRAFTER_SEARCH_DIAGNOSTIC_LINES = 120;
    private static final int MAX_CRAFTER_CLICK_DIAGNOSTIC_LINES = 120;
    private static final int MAX_CRAFTER_ATTEMPT_DIAGNOSTIC_LINES = 120;
    private static final int MAX_CRAFTER_STAGE_DIAGNOSTIC_LINES = 120;
    private static final long CRAFTER_SEARCH_DIAGNOSTIC_INTERVAL_NANOS = 1_000_000_000L;
    private static long lastCrafterSearchDiagnosticNanos = Long.MIN_VALUE;
    private static String lastCrafterSearchCandidateSignature;
    private static long lastCrafterAttemptDiagnosticNanos = Long.MIN_VALUE;
    private static String lastCrafterAttemptSignature;
    private static long lastCrafterPredictionDiagnosticNanos = Long.MIN_VALUE;
    private static long lastCrafterValidityDiagnosticNanos = Long.MIN_VALUE;

    public static boolean isDoorItemCandidate(Collection<BlockState> candidates, BlockState wanted) {
        if (!(wanted.getBlock() instanceof DoorBlock)) {
            return false;
        }
        return isBlockItemCandidate(candidates, wanted);
    }

    /** Material presence only; actual placement and world-state checks remain strict. */
    public static boolean isBlockItemCandidate(Collection<BlockState> candidates, BlockState wanted) {
        if (wanted.getBlock().asItem() == net.minecraft.world.item.Items.AIR) return false;
        for (BlockState candidate : candidates) {
            if (candidate.getBlock() == wanted.getBlock()) {
                // Synthetic inventory contexts reflect the current player direction,
                // not every orientation that this exact block item can later place.
                return true;
            }
        }
        return false;
    }

    /**
     * Baritone's adjacent placement goal normally rejects positions one block below
     * the target. Allow those positions only when the selected placement support is
     * directly below the target; BuilderProcess has already validated that support.
     */
    public static boolean allowSameLevelForPlacement(boolean currentValue, BlockPos target,
                                                      BlockPos supportNeighbor) {
        return currentValue || target.below().equals(supportNeighbor);
    }

    /**
     * Returns grounded approach positions for crafter orientations whose native
     * placement context can be reached by standing along the requested front.
     * Baritone retains its native goal for unsupported or impossible orientations.
     */
    public static List<BlockPos> crafterApproachPositions(BlockPos target, BlockState wanted) {
        if (wanted == null || wanted.getBlock() != net.minecraft.world.level.block.Blocks.CRAFTER) {
            return List.of();
        }
        FrontAndTop orientation = wanted.getValues()
                .filter(value -> "orientation".equals(value.property().getName()))
                .map(value -> value.value())
                .filter(FrontAndTop.class::isInstance)
                .map(FrontAndTop.class::cast)
                .findFirst().orElse(null);
        if (orientation == null) return List.of();

        Direction front = orientation.front();
        Direction top = orientation.top();
        if (top == Direction.UP && front.getAxis().isHorizontal()) {
            return List.of(
                    target.relative(front).below(),
                    target.relative(front, 2).below(),
                    target.relative(front, 3).below());
        }
        if (front == Direction.DOWN && top.getAxis().isHorizontal()) {
            // Look straight up from two blocks below the target. The support block
            // is above the crafter, so the player's feet and floor must be clear.
            return List.of(target.below(2));
        }
        if (front == Direction.UP && top.getAxis().isHorizontal()) {
            // Keep the requested cardinal side while allowing a one-block lateral
            // sidestep around occluding blocks. Baritone's native ray and predicted
            // state checks still decide whether any candidate can actually place.
            Direction side = top.getOpposite();
            Direction lateral = top.getClockWise();
            return List.of(
                    target.above(2).relative(side, 2),
                    target.above(2).relative(side, 2).relative(lateral),
                    target.above(2).relative(side, 2).relative(lateral.getOpposite()),
                    target.above(2).relative(side, 3),
                    target.above(2).relative(side, 3).relative(lateral),
                    target.above(2).relative(side, 3).relative(lateral.getOpposite()));
        }
        return List.of();
    }

    /**
     * Baritone's native GoalPlace fallback does not describe a usable approach for
     * a crafter that is supported from above. Only replace that fallback while the
     * desired DOWN-front crafter target is still air and the upper support has
     * passed Baritone's native canPlaceAgainst check.
     */
    public static boolean shouldOverrideGoalPlaceForDownCrafter(BlockState current,
                                                                 BlockState wanted,
                                                                 boolean upperSupportCanPlaceAgainst) {
        if (current == null || !current.isAir() || !upperSupportCanPlaceAgainst
                || wanted == null || wanted.getBlock() != net.minecraft.world.level.block.Blocks.CRAFTER) {
            return false;
        }
        FrontAndTop orientation = wanted.getValues()
                .filter(value -> "orientation".equals(value.property().getName()))
                .map(value -> value.value())
                .filter(FrontAndTop.class::isInstance)
                .map(FrontAndTop.class::cast)
                .findFirst().orElse(null);
        return orientation != null && orientation.front() == Direction.DOWN
                && orientation.top().getAxis().isHorizontal();
    }

    /** Logs the exact state Baritone predicts for a door item placement when explicitly enabled. */
    public static BlockState diagnoseDoorPlacementState(Block block, BlockPlaceContext context,
                                                          BlockState predicted) {
        if ((block instanceof DoorBlock || block instanceof CrafterBlock) && diagnosticsEnabled()) {
            BlockState support = context.getLevel().getBlockState(context.getClickedPos());
            String details = "block=" + block
                    + ", clicked=" + context.getClickedPos()
                    + ", face=" + context.getClickedFace()
                    + ", direction=" + context.getHorizontalDirection()
                    + ", player=" + (context.getPlayer() == null ? "null" : context.getPlayer().position())
                    + ", support=" + support
                    + ", predicted=" + predicted;
            if (block instanceof CrafterBlock) {
                logCrafterStageDiagnostic("placement prediction", details,
                        CRAFTER_PREDICTION_DIAGNOSTIC_LINES, true);
            } else {
                logPlacement("placement prediction", details);
            }
        }
        return predicted;
    }

    /** Logs a door's predicted and schematic states at Baritone's actual placement-validity check. */
    public static void diagnoseDoorPlacementValidity(BlockState predicted, BlockState wanted,
                                                      boolean assumePlacement) {
        if ((predicted != null && (predicted.getBlock() instanceof DoorBlock || predicted.getBlock() instanceof CrafterBlock))
                || (wanted != null && (wanted.getBlock() instanceof DoorBlock || wanted.getBlock() instanceof CrafterBlock))) {
            if (diagnosticsEnabled()) {
                String details = "predicted=" + predicted
                        + ", wanted=" + wanted
                        + ", assumePlacement=" + assumePlacement
                        + ", sameBlock=" + (predicted != null && wanted != null
                                && predicted.getBlock() == wanted.getBlock());
                boolean crafter = (predicted != null && predicted.getBlock() instanceof CrafterBlock)
                        || (wanted != null && wanted.getBlock() instanceof CrafterBlock);
                if (crafter) {
                    logCrafterStageDiagnostic("validity comparison", details,
                            CRAFTER_VALIDITY_DIAGNOSTIC_LINES, false);
                } else {
                    logPlacement("validity comparison", details);
                }
            }
        }
    }

    /**
     * Crafter orientation is derived from the player's look when the server handles
     * the placement packet. Keep BuilderProcess from clicking if the exact context
     * at click time no longer produces the schematic's requested orientation.
     */
    public static boolean crafterOrientationMatches(BlockState predicted, BlockState wanted) {
        if (predicted == null || wanted == null
                || predicted.getBlock() != net.minecraft.world.level.block.Blocks.CRAFTER
                || wanted.getBlock() != net.minecraft.world.level.block.Blocks.CRAFTER) {
            return false;
        }
        FrontAndTop predictedOrientation = crafterOrientation(predicted);
        return predictedOrientation != null && predictedOrientation.equals(crafterOrientation(wanted));
    }

    /** Crafter item placement is accepted only for the exact state requested by the schematic. */
    public static boolean crafterPlacementMatches(BlockState predicted, BlockState wanted) {
        return crafterOrientationMatches(predicted, wanted) && predicted.equals(wanted);
    }

    private static FrontAndTop crafterOrientation(BlockState state) {
        return state.getValues().filter(value -> "orientation".equals(value.property().getName()))
                .map(value -> value.value())
                .filter(FrontAndTop.class::isInstance)
                .map(FrontAndTop.class::cast)
                .findFirst().orElse(null);
    }

    /** Logs the exact client-side context used immediately before Baritone's crafter click. */
    public static void diagnoseCrafterClick(BlockPlaceContext context, BlockHitResult hit,
                                             BlockState predicted,
                                             BlockState wanted, float requestedYaw,
                                             float requestedPitch, boolean allowed) {
        if (!diagnosticsEnabled()) return;
        var player = context.getPlayer();
        var target = context.getClickedPos();
        logCrafterClickDiagnostic("click context", "target=" + target
                + ", supportHit=" + hit.getBlockPos()
                + ", face=" + context.getClickedFace()
                + ", hit=" + hit.getLocation()
                + ", playerFeet=" + (player == null ? "null" : player.blockPosition())
                + ", yaw=" + (player == null ? "null" : player.getYRot())
                + ", pitch=" + (player == null ? "null" : player.getXRot())
                + ", requestedYaw=" + requestedYaw
                + ", requestedPitch=" + requestedPitch
                + ", canPlace=" + context.canPlace()
                + ", predicted=" + predicted
                + ", wanted=" + wanted
                + ", orientationMatches=" + crafterOrientationMatches(predicted, wanted)
                + ", decision=" + (allowed ? "accept" : "reject")
                + ", reason=" + crafterClickDecisionReason(context, predicted, wanted, allowed));
    }

    /** Logs whether Baritone found an actionable placement after considering a crafter state. */
    public static void diagnoseCrafterSearch(Collection<BlockState> consideredStates,
                                              BlockPos playerFeet,
                                              BlockPos candidateTarget,
                                              BlockPos support,
                                              Direction face,
                                              Object rotation) {
        if (!diagnosticsEnabled() || consideredStates.stream()
                .noneMatch(state -> state.getBlock() instanceof CrafterBlock)) return;
        boolean found = candidateTarget != null;
        String crafterStates = consideredStates.stream()
                .filter(state -> state.getBlock() instanceof CrafterBlock)
                .map(Object::toString)
                .toList().toString();
        String details = "candidate=" + (found ? "found" : "none")
                + ", playerFeet=" + playerFeet
                + ", wanted=" + crafterStates
                + (found ? ", target=" + candidateTarget
                        + ", support=" + support
                        + ", face=" + face
                        + ", rotation=" + rotation : "");
        // Search can run repeatedly while Baritone recalculates a path. Emit a
        // stable candidate at most once per second (20 game ticks at normal TPS),
        // while still reporting candidate transitions immediately.
        String candidateSignature = (found ? "found" : "none") + "|" + crafterStates
                + "|" + candidateTarget + "|" + support + "|" + face + "|" + rotation;
        logCrafterSearchDiagnostic(candidateSignature, details);
    }

    /** Captures ray-trace outcomes for the schematic crafter target when no placement candidate emerges. */
    public static void diagnoseCrafterPlacementAttempt(BlockPos target, BlockState wanted,
                                                        List<String> supportCandidates,
                                                        List<String> rayAttempts,
                                                        BlockPos candidateTarget, BlockPos support,
                                                        Direction face, Object rotation) {
        if (!diagnosticsEnabled() || wanted == null || !(wanted.getBlock() instanceof CrafterBlock)) return;
        String rays = rayAttempts == null ? "[]" : rayAttempts.toString();
        String details = "target=" + target + ", wanted=" + wanted
                + ", candidate=" + (candidateTarget == null ? "none" : "found")
                + (candidateTarget == null ? "" : ", placementTarget=" + candidateTarget
                        + ", support=" + support + ", face=" + face + ", rotation=" + rotation)
                + ", supports=" + (supportCandidates == null ? "[]" : supportCandidates)
                + ", rayAttempts=" + rays;
        // The ray list can vary slightly as the aim processor tracks player motion.
        // Keep it in the snapshot, but rate-limit those variations to one line/sec.
        String signature = target + "|" + wanted + "|" + candidateTarget + "|" + support
                + "|" + face + "|" + rotation;
        logCrafterAttemptDiagnostic(signature, details);
    }

    private static String crafterClickDecisionReason(BlockPlaceContext context, BlockState predicted,
                                                       BlockState wanted, boolean allowed) {
        if (allowed) return "predicted state matches requested state";
        if (!context.canPlace()) return "placement context cannot place at target";
        if (!crafterOrientationMatches(predicted, wanted)) return "predicted orientation differs from requested orientation";
        if (predicted == null || wanted == null || !predicted.equals(wanted)) {
            return "predicted state differs from requested state";
        }
        return "builder hit or target intent did not match the actual click";
    }

    public static void diagnoseCrafterClickWithoutHit(Player player, ItemStack held) {
        if (!diagnosticsEnabled()) return;
        logCrafterClickDiagnostic("click context", "hit=none"
                + ", playerFeet=" + player.blockPosition()
                + ", yaw=" + player.getYRot()
                + ", pitch=" + player.getXRot()
                + ", held=" + held
                + ", predicted=null"
                + ", wanted=unavailable"
                + ", decision=reject, reason=no block hit available for crafter placement");
    }

    private static boolean diagnosticsEnabled() {
        return Boolean.getBoolean("altoclef.builderPlacementDiagnostics");
    }

    private static void logPlacement(String stage, String details) {
        if (PLACEMENT_DIAGNOSTIC_LINES.getAndIncrement() < MAX_PLACEMENT_DIAGNOSTIC_LINES) {
            PLACEMENT_LOG.info("[builder-placement-diagnostic] {}: {}", stage, details);
        }
    }

    private static synchronized void logCrafterSearchDiagnostic(String candidateSignature, String details) {
        long now = System.nanoTime();
        boolean candidateChanged = !java.util.Objects.equals(
                candidateSignature, lastCrafterSearchCandidateSignature);
        boolean intervalElapsed = lastCrafterSearchDiagnosticNanos == Long.MIN_VALUE
                || now - lastCrafterSearchDiagnosticNanos >= CRAFTER_SEARCH_DIAGNOSTIC_INTERVAL_NANOS;
        if (!candidateChanged && !intervalElapsed) return;

        int line = CRAFTER_SEARCH_DIAGNOSTIC_LINES.getAndIncrement();
        if (crafterSearchDiagnosticLineAllowed(line)) {
            PLACEMENT_LOG.info("[builder-placement-diagnostic] crafter search result: {}", details);
            lastCrafterSearchCandidateSignature = candidateSignature;
            lastCrafterSearchDiagnosticNanos = now;
        }
    }

    private static void logCrafterClickDiagnostic(String stage, String details) {
        int line = CRAFTER_CLICK_DIAGNOSTIC_LINES.getAndIncrement();
        if (crafterClickDiagnosticLineAllowed(line)) {
            PLACEMENT_LOG.info("[builder-placement-diagnostic] crafter {}: {}", stage, details);
        }
    }

    private static synchronized void logCrafterAttemptDiagnostic(String signature, String details) {
        long now = System.nanoTime();
        boolean changed = !java.util.Objects.equals(signature, lastCrafterAttemptSignature);
        boolean intervalElapsed = lastCrafterAttemptDiagnosticNanos == Long.MIN_VALUE
                || now - lastCrafterAttemptDiagnosticNanos >= CRAFTER_SEARCH_DIAGNOSTIC_INTERVAL_NANOS;
        if (!changed && !intervalElapsed) return;
        int line = CRAFTER_ATTEMPT_DIAGNOSTIC_LINES.getAndIncrement();
        if (line >= 0 && line < MAX_CRAFTER_ATTEMPT_DIAGNOSTIC_LINES) {
            PLACEMENT_LOG.info("[builder-placement-diagnostic] crafter candidate evaluation: {}", details);
            lastCrafterAttemptSignature = signature;
            lastCrafterAttemptDiagnosticNanos = now;
        }
    }

    private static synchronized void logCrafterStageDiagnostic(
            String stage, String details, AtomicInteger lineCounter, boolean prediction) {
        long now = System.nanoTime();
        long last = prediction ? lastCrafterPredictionDiagnosticNanos : lastCrafterValidityDiagnosticNanos;
        if (last != Long.MIN_VALUE && now - last < CRAFTER_SEARCH_DIAGNOSTIC_INTERVAL_NANOS) return;
        int line = lineCounter.getAndIncrement();
        if (line >= 0 && line < MAX_CRAFTER_STAGE_DIAGNOSTIC_LINES) {
            PLACEMENT_LOG.info("[builder-placement-diagnostic] crafter {}: {}", stage, details);
            if (prediction) lastCrafterPredictionDiagnosticNanos = now;
            else lastCrafterValidityDiagnosticNanos = now;
        }
    }

    /** Search and click logs have independent caps so search polling cannot hide a rejected click. */
    public static boolean crafterSearchDiagnosticLineAllowed(int line) {
        return line >= 0 && line < MAX_CRAFTER_SEARCH_DIAGNOSTIC_LINES;
    }

    public static boolean crafterClickDiagnosticLineAllowed(int line) {
        return line >= 0 && line < MAX_CRAFTER_CLICK_DIAGNOSTIC_LINES;
    }

    private BaritoneBuilderStateCompatibility() {}
}
