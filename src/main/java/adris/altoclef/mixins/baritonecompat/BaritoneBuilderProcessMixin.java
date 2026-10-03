package adris.altoclef.mixins.baritonecompat;

import adris.altoclef.baritone.AltoClefSettings;
import adris.altoclef.util.helpers.BaritoneCrafterPlacementIntent;
import adris.altoclef.util.helpers.BaritoneBuilderStateCompatibility;
import baritone.behavior.LookBehavior;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.pathing.movement.MovementHelper;
import baritone.process.BuilderProcess;
import baritone.utils.InputOverrideHandler;
import baritone.api.utils.RayTraceUtils;
import baritone.api.utils.input.Input;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.FrontAndTop;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ClipContext;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Mixin(BuilderProcess.class)
abstract class BaritoneBuilderProcessMixin {
    @Unique
    private Rotation altoclef$placementRotation;

    @Unique
    private BlockPos altoclef$crafterCandidateTarget;

    @Unique
    private BlockState altoclef$crafterCandidateWanted;

    @Unique
    private List<String> altoclef$crafterRayAttempts;

    @Unique
    private List<String> altoclef$crafterSupportCandidates;

    @Inject(method = "searchForPlacables", at = @At("RETURN"))
    private void altoclef$diagnoseCrafterSearchResult(
            BuilderProcess.BuilderCalculationContext context,
            List<BlockState> consideredStates,
            CallbackInfoReturnable<Optional<BuilderProcess.Placement>> cir) {
        Minecraft minecraft = Minecraft.getInstance();
        var player = minecraft.player;
        Optional<BuilderProcess.Placement> selected = cir.getReturnValue();
        BlockPos target = null;
        BlockPos support = null;
        net.minecraft.core.Direction face = null;
        Rotation rotation = null;
        if (selected.isPresent()) {
            BaritoneBuilderPlacementAccessor placement =
                    (BaritoneBuilderPlacementAccessor) (Object) selected.get();
            support = placement.altoclef$getPlaceAgainst();
            face = placement.altoclef$getSide();
            target = support.relative(face);
            rotation = placement.altoclef$getRotation();
        }
        BaritoneBuilderStateCompatibility.diagnoseCrafterSearch(
                consideredStates,
                player == null ? null : player.blockPosition(),
                target, support, face, rotation);
    }

    @Shadow
    private static boolean containsBlockState(Collection<BlockState> candidates, BlockState wanted) {
        throw new AssertionError();
    }

    @Shadow
    private static boolean valid(BlockState predicted, BlockState wanted, boolean assumePlacement) {
        throw new AssertionError();
    }

    @Inject(method = "possibleToPlace", at = @At("HEAD"))
    private void altoclef$beginCrafterCandidateDiagnostics(
            BlockState wanted, int x, int y, int z,
            baritone.utils.BlockStateInterface bsi,
            CallbackInfoReturnable<Optional<BuilderProcess.Placement>> cir) {
        if (Boolean.getBoolean("altoclef.builderPlacementDiagnostics")
                && wanted.getBlock() instanceof CrafterBlock) {
            altoclef$crafterCandidateTarget = new BlockPos(x, y, z);
            altoclef$crafterCandidateWanted = wanted;
            altoclef$crafterRayAttempts = new java.util.ArrayList<>();
            altoclef$crafterSupportCandidates = new java.util.ArrayList<>();
            for (Direction direction : Direction.values()) {
                BlockPos support = altoclef$crafterCandidateTarget.relative(direction);
                altoclef$crafterSupportCandidates.add(support + "=" + bsi.get0(support));
            }
        } else {
            altoclef$crafterCandidateTarget = null;
            altoclef$crafterCandidateWanted = null;
            altoclef$crafterRayAttempts = null;
            altoclef$crafterSupportCandidates = null;
        }
    }

    @Redirect(method = "possibleToPlace",
            at = @At(value = "INVOKE",
                    target = "Lbaritone/api/utils/RayTraceUtils;rayTraceTowards(Lnet/minecraft/world/entity/Entity;Lbaritone/api/utils/Rotation;DZ)Lnet/minecraft/world/phys/HitResult;"))
    private HitResult altoclef$recordCrafterPlacementRay(
            Entity entity, Rotation rotation, double reach, boolean includeFluids) {
        HitResult hit = RayTraceUtils.rayTraceTowards(entity, rotation, reach, includeFluids);
        if (altoclef$crafterCandidateWanted != null) {
            String hitDescription = hit == null ? "null" : hit.getType() == HitResult.Type.BLOCK
                    ? ((BlockHitResult) hit).getBlockPos() + "/" + ((BlockHitResult) hit).getDirection()
                            + "@" + hit.getLocation()
                    : hit.getType() + "@" + hit.getLocation();
            altoclef$crafterRayAttempts.add("rotation=" + rotation + ",ray=" + hitDescription);
        }
        return hit;
    }

    @Inject(method = "possibleToPlace", at = @At("RETURN"))
    private void altoclef$finishCrafterCandidateDiagnostics(
            BlockState wanted, int x, int y, int z,
            baritone.utils.BlockStateInterface bsi,
            CallbackInfoReturnable<Optional<BuilderProcess.Placement>> cir) {
        if (altoclef$crafterCandidateWanted != null) {
            Optional<BuilderProcess.Placement> selected = cir.getReturnValue();
            BaritoneBuilderPlacementAccessor placement = selected.isPresent()
                    ? (BaritoneBuilderPlacementAccessor) (Object) selected.get() : null;
            BlockPos support = placement == null ? null : placement.altoclef$getPlaceAgainst();
            Direction face = placement == null ? null : placement.altoclef$getSide();
            BaritoneBuilderStateCompatibility.diagnoseCrafterPlacementAttempt(
                    altoclef$crafterCandidateTarget, altoclef$crafterCandidateWanted,
                    altoclef$crafterSupportCandidates, altoclef$crafterRayAttempts,
                    support == null ? null : support.relative(face), support, face,
                    placement == null ? null : placement.altoclef$getRotation());
        }
        altoclef$crafterCandidateTarget = null;
        altoclef$crafterCandidateWanted = null;
        altoclef$crafterRayAttempts = null;
        altoclef$crafterSupportCandidates = null;
    }

    @Inject(method = "onTick", at = @At("HEAD"), cancellable = true)
    private void altoclef$pauseBuilding(boolean calcFailed, boolean isSafeToCancel,
                                        CallbackInfoReturnable<PathingCommand> cir) {
        if (AltoClefSettings.getInstance().isInteractionPaused()) {
            cir.setReturnValue(new PathingCommand(null, PathingCommandType.REQUEST_PAUSE));
        }
    }

    @Inject(method = "onTick(ZZI)Lbaritone/api/process/PathingCommand;", at = @At("HEAD"))
    private void altoclef$beginCrafterPlacementTick(boolean calcFailed, boolean isSafeToCancel,
                                                    int ticksRemaining,
                                                    CallbackInfoReturnable<PathingCommand> cir) {
        BaritoneCrafterPlacementIntent.beginTick();
        altoclef$placementRotation = null;
    }

    @Inject(method = "onLostControl", at = @At("HEAD"))
    private void altoclef$clearCrafterPlacementIntent(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        BaritoneCrafterPlacementIntent.clear();
        altoclef$placementRotation = null;
    }

    @Redirect(method = "lambda$assemble$0",
            at = @At(value = "INVOKE",
                    target = "Lbaritone/process/BuilderProcess;containsBlockState(Ljava/util/Collection;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private static boolean altoclef$matchInventoryPlacementState(Collection<BlockState> candidates, BlockState wanted) {
        if (BaritoneBuilderStateCompatibility.isBlockItemCandidate(candidates, wanted)) {
            return true;
        }
        return containsBlockState(candidates, wanted);
    }

    @ModifyConstant(method = "searchForPlacables",
            slice = @Slice(
                    from = @At(value = "INVOKE", target = "Lbaritone/process/BuilderProcess;valid(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;Z)Z"),
                    to = @At(value = "INVOKE", target = "Lbaritone/process/BuilderProcess;possibleToPlace(Lnet/minecraft/world/level/block/state/BlockState;IIILbaritone/utils/BlockStateInterface;)Ljava/util/Optional;")),
            constant = @Constant(intValue = 1, ordinal = 0))
    private int altoclef$allowValidOverheadPlacementCandidates(int maxTargetHeight) {
        // Baritone skips a wanted block one block above the player's feet when the
        // cell above it is air. That excludes floor-supported blocks such as a
        // crafter before possibleToPlace can perform its native support and state
        // checks. Keep the skip comparison beyond both the normal +1 ceiling
        // and the crafter-only +2 ceiling so native placement checks run.
        return 3;
    }

    @ModifyConstant(method = "searchForPlacables",
            constant = @Constant(intValue = 1, ordinal = 0))
    private int altoclef$extendCrafterTargetScanForVerticalApproaches(
            int maximumY, BuilderProcess.BuilderCalculationContext context,
            List<BlockState> consideredStates) {
        // Vanilla crafter states with front=DOWN require a standing position two
        // blocks below their target, which lies just above Baritone's normal +1
        // target scan. Extend only searches that actually have a crafter candidate;
        // `consideredStates` is an output accumulator, not inventory candidates.
        // Gate using the actual inventory and let possibleToPlace continue to own
        // all support, ray trace, and state validation.
        var player = Minecraft.getInstance().player;
        boolean hasCrafter = player != null && java.util.stream.IntStream.range(0, player.getInventory().getContainerSize())
                .anyMatch(slot -> player.getInventory().getItem(slot).is(Items.CRAFTER));
        return hasCrafter ? 2 : maximumY;
    }

    @Redirect(method = "hasAnyItemThatWouldPlace",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/Block;getStateForPlacement(Lnet/minecraft/world/item/context/BlockPlaceContext;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState altoclef$diagnoseDoorPlacementPrediction(Block block, BlockPlaceContext context) {
        BlockState predicted = block.getStateForPlacement(context);
        return BaritoneBuilderStateCompatibility.diagnoseDoorPlacementState(block, context, predicted);
    }

    @Redirect(method = "hasAnyItemThatWouldPlace",
            at = @At(value = "INVOKE",
                    target = "Lbaritone/process/BuilderProcess;valid(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;Z)Z"))
    private static boolean altoclef$diagnoseDoorPlacementValidity(BlockState predicted, BlockState wanted,
                                                                   boolean assumePlacement) {
        BaritoneBuilderStateCompatibility.diagnoseDoorPlacementValidity(predicted, wanted, assumePlacement);
        return valid(predicted, wanted, assumePlacement);
    }

    @Redirect(method = "onTick(ZZI)Lbaritone/api/process/PathingCommand;",
            at = @At(value = "INVOKE",
                    target = "Lbaritone/utils/InputOverrideHandler;setInputForceState(Lbaritone/api/utils/input/Input;Z)V",
                    ordinal = 3))
    private void altoclef$validateCrafterClickAtCurrentRotation(
            InputOverrideHandler handler, Input input, boolean forced) {
        if (!forced || input != Input.CLICK_RIGHT) {
            handler.setInputForceState(input, forced);
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        var player = minecraft.player;
        if (player == null) {
            handler.setInputForceState(input, true);
            return;
        }
        ItemStack held = player.getMainHandItem();
        if (!(held.getItem() instanceof BlockItem blockItem)
                || !(blockItem.getBlock() instanceof CrafterBlock crafter)) {
            handler.setInputForceState(input, true);
            return;
        }

        HitResult hit = minecraft.hitResult;
        Rotation intended = altoclef$placementRotation;
        if (intended == null || minecraft.level == null || !(hit instanceof BlockHitResult blockHit)
                || blockHit.getType() != HitResult.Type.BLOCK) {
            BaritoneBuilderStateCompatibility.diagnoseCrafterClickWithoutHit(player, held);
            return;
        }

        float oldYaw = player.getYRot();
        float oldPitch = player.getXRot();
        BlockPos target;
        try {
            player.setYRot(intended.getYaw());
            player.setXRot(intended.getPitch());
            target = new BlockPlaceContext(
                    player, InteractionHand.MAIN_HAND, held, blockHit).getClickedPos().immutable();
        } finally {
            player.setYRot(oldYaw);
            player.setXRot(oldPitch);
        }
        BaritoneCrafterPlacementIntent.publish(this, player, target,
                blockHit.getBlockPos(), blockHit.getDirection(), intended.getYaw(), intended.getPitch());
        handler.setInputForceState(input, true);
    }

    @Redirect(method = "onTick(ZZI)Lbaritone/api/process/PathingCommand;",
            at = @At(value = "INVOKE",
                    target = "Lbaritone/behavior/LookBehavior;updateTarget(Lbaritone/api/utils/Rotation;Z)V",
                    ordinal = 1))
    private void altoclef$captureBuilderPlacementRotation(
            LookBehavior lookBehavior, Rotation rotation, boolean force) {
        altoclef$placementRotation = rotation;
        lookBehavior.updateTarget(rotation, force);
    }

    @ModifyArgs(method = "placementGoal",
            at = @At(value = "INVOKE",
                    target = "Lbaritone/process/BuilderProcess$GoalAdjacent;<init>(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;Z)V"))
    private void altoclef$allowLowerApproachForFloorSupportedPlacement(Args args) {
        BlockPos target = args.get(0);
        BlockPos supportNeighbor = args.get(1);
        boolean allowSameLevel = args.get(2);
        args.set(2, BaritoneBuilderStateCompatibility.allowSameLevelForPlacement(
                allowSameLevel, target, supportNeighbor));
    }

    @Inject(method = "placementGoal", at = @At("RETURN"), cancellable = true)
    private void altoclef$approachUprightCrafterFromRequestedFront(
            BlockPos target, BuilderProcess.BuilderCalculationContext context,
            CallbackInfoReturnable<Goal> cir) {
        Goal nativeGoal = cir.getReturnValue();
        boolean adjacentGoal = nativeGoal instanceof BuilderProcess.GoalAdjacent;
        boolean placeFallback = nativeGoal instanceof BuilderProcess.GoalPlace;
        if (!adjacentGoal && !placeFallback) return;

        BlockState current = context.get(target);
        BlockState wanted = ((BaritoneBuilderCalculationContextAccessor) (Object) context)
                .altoclef$getSchematic(target.getX(), target.getY(), target.getZ(), current);
        if (placeFallback && !BaritoneBuilderStateCompatibility.shouldOverrideGoalPlaceForDownCrafter(
                current, wanted, MovementHelper.canPlaceAgainst(context.bsi, target.above()))) return;

        List<BlockPos> approachPositions = BaritoneBuilderStateCompatibility
                .crafterApproachPositions(target, wanted).stream()
                .filter(position -> altoclef$isSafeFeetPosition(context, position))
                .filter(position -> altoclef$isReachableCrafterApproach(context, target, wanted, position))
                .toList();
        if (approachPositions.isEmpty()) return;

        Goal[] goals = approachPositions.stream()
                .map(GoalBlock::new)
                .toArray(Goal[]::new);
        cir.setReturnValue(new GoalComposite(goals));
    }

    private static boolean altoclef$isSafeFeetPosition(
            BuilderProcess.BuilderCalculationContext context, BlockPos position) {
        int x = position.getX();
        int y = position.getY();
        int z = position.getZ();
        return context.bsi.isLoaded(x, z)
                && MovementHelper.canWalkThrough(context, x, y, z)
                && MovementHelper.canWalkThrough(context, x, y + 1, z)
                && MovementHelper.canWalkOn(context, x, y - 1, z);
    }

    /**
     * Prove an UP-front staging point can see the actual support's top face from
     * the crouched eye position Baritone uses, within native reach, with a rotation
     * that makes vanilla predict the exact requested FrontAndTop orientation.
     */
    private static boolean altoclef$isReachableCrafterApproach(
            BuilderProcess.BuilderCalculationContext context, BlockPos target,
            BlockState wanted, BlockPos feet) {
        FrontAndTop wantedOrientation = wanted.getValue(
                net.minecraft.world.level.block.state.properties.BlockStateProperties.ORIENTATION);
        if (wantedOrientation.front() != Direction.UP) return true;

        Minecraft minecraft = Minecraft.getInstance();
        var player = minecraft.player;
        var level = minecraft.level;
        if (player == null || level == null || wantedOrientation.top().getAxis() != Direction.Axis.X
                && wantedOrientation.top().getAxis() != Direction.Axis.Z) return false;

        BlockPos support = target.below();
        BlockState supportState = context.bsi.get0(support);
        if (!MovementHelper.canPlaceAgainst(context.bsi, support)) return false;
        var shape = supportState.getShape(level, support);
        if (shape.isEmpty()) return false;
        var bounds = shape.bounds();
        Vec3 eye = new Vec3(feet.getX() + 0.5, feet.getY() + player.getEyeHeight(Pose.CROUCHING),
                feet.getZ() + 0.5);
        Vec3[] facePoints = {
                new Vec3(0.5, 1, 0.5),
                new Vec3(0.1, 1, 0.5),
                new Vec3(0.9, 1, 0.5),
                new Vec3(0.5, 1, 0.1),
                new Vec3(0.5, 1, 0.9)
        };
        double reach = BaritoneAPI.getProvider().getPrimaryBaritone().getPlayerContext()
                .playerController().getBlockReachDistance();
        Rotation currentRotation = BaritoneAPI.getProvider().getPrimaryBaritone().getPlayerContext()
                .playerRotations();
        for (Vec3 facePoint : facePoints) {
            Vec3 hitPoint = new Vec3(support.getX() + bounds.minX + (bounds.maxX - bounds.minX) * facePoint.x,
                    support.getY() + bounds.minY + (bounds.maxY - bounds.minY) * facePoint.y,
                    support.getZ() + bounds.minZ + (bounds.maxZ - bounds.minZ) * facePoint.z);
            if (eye.distanceToSqr(hitPoint) > reach * reach) continue;

            Rotation rotation = RotationUtils.calcRotationFromVec3d(eye, hitPoint, currentRotation);
            // CrafterBlock uses the nearest looking axis for front=UP; a downward
            // pitch steeper than 45 degrees selects DOWN, and yaw selects top.
            if (rotation.getPitch() <= 45.0F || rotation.getPitch() >= 90.0F
                    || Direction.fromYRot(rotation.getYaw()) != wantedOrientation.top()) continue;
            FrontAndTop predictedOrientation = FrontAndTop.fromFrontAndTop(
                    Direction.UP, Direction.fromYRot(rotation.getYaw()));
            if (predictedOrientation == null || !predictedOrientation.equals(wantedOrientation)) continue;

            Vec3 rayEnd = eye.add(RotationUtils.calcLookDirectionFromRotation(rotation).scale(reach));
            BlockHitResult hit = level.clip(new ClipContext(eye, rayEnd, ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE, player));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(support)
                    && hit.getDirection() == Direction.UP) return true;
        }
        return false;
    }
}
