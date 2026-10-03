package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import baritone.api.BaritoneAPI;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.RayTraceUtils;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.ClipContext;

import java.util.Optional;

/**
 * Helper functions to interpret and change our player's look direction
 */
public interface LookHelper {

    static Optional<Rotation> getReach(BlockPos target, Direction side) {
        Optional<Rotation> reachable;
        IPlayerContext ctx = BaritoneAPI.getProvider().getPrimaryBaritone().getPlayerContext();
        if (side == null) {
            assert Minecraft.getInstance().player != null;
            reachable = RotationUtils.reachable(ctx.player(), target, ctx.playerController().getBlockReachDistance());
        } else {
            Vec3i sideVector = side.getUnitVec3i();
            var shape = ctx.world().getBlockState(target).getShape(ctx.world(), target);
            AABB bounds = shape.isEmpty() ? new AABB(0, 0, 0, 1, 1, 1) : shape.bounds();
            Vec3 sidePoint = shapeFacePoint(target, bounds, side);

            //reachable(this.ctx.player(), _target, this.ctx.playerController().getBlockReachDistance());
            reachable = RotationUtils.reachableOffset(ctx.player(), target, sidePoint, ctx.playerController().getBlockReachDistance(), false);

            // Check for right angle
            if (reachable.isPresent()) {
                // Note: If sneak, use RotationUtils.inferSneakingEyePosition
                Vec3 camPos = ctx.player().getEyePosition(1.0F);
                Vec3 vecToPlayerPos = camPos.subtract(sidePoint);

                double dot = vecToPlayerPos.normalize().dot(new Vec3(sideVector.getX(), sideVector.getY(), sideVector.getZ()));
                if (dot < 0) {
                    // We're perpendicular and cannot face.
                    return Optional.empty();
                }
            }
        }
        return reachable;
    }

    /** Aim just inside the real outline face so ray tracing hits partial blocks. */
    static Vec3 shapeFacePoint(BlockPos target, AABB bounds, Direction side) {
        double x = (bounds.minX + bounds.maxX) * 0.5;
        double y = (bounds.minY + bounds.maxY) * 0.5;
        double z = (bounds.minZ + bounds.maxZ) * 0.5;
        double inset = 1.0e-4;
        switch (side) {
            case UP -> y = bounds.maxY - inset;
            case DOWN -> y = bounds.minY + inset;
            case EAST -> x = bounds.maxX - inset;
            case WEST -> x = bounds.minX + inset;
            case SOUTH -> z = bounds.maxZ - inset;
            case NORTH -> z = bounds.minZ + inset;
        }
        return new Vec3(target.getX() + x, target.getY() + y, target.getZ() + z);
    }

    static Optional<Rotation> getReach(BlockPos target) {
        return getReach(target, null);
    }

    static EntityHitResult raycast(Entity from, Entity to, double reachDistance) {
        Vec3 fromPos = getCameraPos(from),
                toPos = getCameraPos(to);
        Vec3 direction = (toPos.subtract(fromPos).normalize().scale(reachDistance));
        AABB box = to.getBoundingBox();
        return ProjectileUtil.getEntityHitResult(from, fromPos, fromPos.add(direction), box, entity -> entity.equals(to), 0);
    }

    static boolean seesPlayer(Entity entity, Entity player, double maxRange, Vec3 entityOffs, Vec3 playerOffs) {
        return seesPlayerOffset(entity, player, maxRange, entityOffs, playerOffs) || seesPlayerOffset(entity, player, maxRange, entityOffs, new Vec3(0, -1, 0).add(playerOffs));
    }

    static boolean seesPlayer(Entity entity, Entity player, double maxRange) {
        return seesPlayer(entity, player, maxRange, Vec3.ZERO, Vec3.ZERO);
    }

    static boolean cleanLineOfSight(Entity entity, Vec3 start, Vec3 end, double maxRange) {
        return raycast(entity, start, end, maxRange).getType() == HitResult.Type.MISS;
    }

    static boolean cleanLineOfSight(Entity entity, Vec3 end, double maxRange) {
        Vec3 start = getCameraPos(entity);
        return cleanLineOfSight(entity, start, end, maxRange);
    }
    static boolean cleanLineOfSight(Vec3 end, double maxRange) {
        return cleanLineOfSight(Minecraft.getInstance().player, end, maxRange);
    }

    static boolean cleanLineOfSight(Entity entity, BlockPos block, double maxRange) {
        Vec3 center = WorldHelper.toVec3d(block);
        BlockHitResult hit = raycast(entity, getCameraPos(entity), center, maxRange);
        if (hit == null) return true;
        return switch (hit.getType()) {
            case MISS -> true;
            case BLOCK -> hit.getBlockPos().equals(block);
            case ENTITY -> false;
        };
    }

    static Vec3 toVec3d(Rotation rotation) {
        return RotationUtils.calcVec3dFromRotation(rotation);
    }

    static BlockHitResult raycast(Entity entity, Vec3 start, Vec3 end, double maxRange) {
        Vec3 delta = end.subtract(start);
        if (delta.lengthSqr() > maxRange * maxRange) {
            end = start.add(delta.normalize().scale(maxRange));
        }
        return entity.level().clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity));
    }

    static BlockHitResult raycast(Entity entity, Vec3 end, double maxRange) {
        Vec3 start = getCameraPos(entity);
        return raycast(entity, start, end, maxRange);
    }

    static Rotation getLookRotation(Entity entity) {
        float pitch = entity.getXRot();
        float yaw = entity.getYRot();
        return new Rotation(yaw, pitch);
    }
    static Rotation getLookRotation() {
        if (Minecraft.getInstance().player == null) {
            return new Rotation(0,0);
        }
        return getLookRotation(Minecraft.getInstance().player);
    }

    static Vec3 getCameraPos(Entity entity) {
        boolean isSneaking = false;
        if (entity instanceof Player player) {
            isSneaking = player.isShiftKeyDown();
        }
        return isSneaking ? RayTraceUtils.inferSneakingEyePosition(entity) : entity.getEyePosition(1.0F);
    }
    static Vec3 getCameraPos(AltoClef mod) {
        IPlayerContext ctx = BaritoneAPI.getProvider().getPrimaryBaritone().getPlayerContext();
        return ctx.player().getEyePosition(1);
    }

    //  1: Looking straight at pos
    //  0: pos is 90 degrees to the side
    // -1: pos is 180 degrees away (looking away completely)
    static double getLookCloseness(Entity entity, Vec3 pos) {
        Vec3 rotDirection = entity.getForward();
        Vec3 lookStart = getCameraPos(entity);
        Vec3 deltaToPos = pos.subtract(lookStart);
        Vec3 deltaDirection = deltaToPos.normalize();
        return rotDirection.dot(deltaDirection);
    }

    static boolean tryAvoidingInteractable(AltoClef mod) {
        if (isCollidingInteractable(mod)) {
            randomOrientation(mod);
            return false;
        }
        return true;
    }

    private static boolean seesPlayerOffset(Entity entity, Entity player, double maxRange, Vec3 offsetEntity, Vec3 offsetPlayer) {
        Vec3 start = getCameraPos(entity).add(offsetEntity);
        Vec3 end = getCameraPos(player).add(offsetPlayer);
        return cleanLineOfSight(entity, start, end, maxRange);
    }

    private static boolean isCollidingInteractable(AltoClef mod) {

        if (!(mod.getPlayer().containerMenu instanceof InventoryMenu)) {
            StorageHelper.closeScreen();
            return true;
        }

        HitResult result = Minecraft.getInstance().hitResult;
        if (result == null) return false;
        if (result.getType() == HitResult.Type.BLOCK) {
            return WorldHelper.isInteractableBlock(mod, BlockPos.containing(result.getLocation()));
        } else if (result.getType() == HitResult.Type.ENTITY) {
            if (result instanceof EntityHitResult) {
                Entity entity = ((EntityHitResult) result).getEntity();
                return entity instanceof AbstractVillager;
            }
        }
        return false;
    }

    static void randomOrientation(AltoClef mod) {
        Rotation r = new Rotation((float) Math.random() * 360f, -90 + (float) Math.random() * 180f);
        lookAt(mod, r);
    }

    static boolean isLookingAt(AltoClef mod, Rotation rotation) {
        return rotation.isReallyCloseTo(getLookRotation());
    }
    static boolean isLookingAt(AltoClef mod, BlockPos blockPos) {
        return mod.getClientBaritone().getPlayerContext().isLookingAt(blockPos);
    }

    static void lookAt(AltoClef mod, Rotation rotation) {
        mod.getClientBaritone().getLookBehavior().updateTarget(rotation, true);
        mod.getPlayer().setYRot(rotation.getYaw());
        mod.getPlayer().setXRot(rotation.getPitch());
    }
    static void lookAt(AltoClef mod, Vec3 toLook) {
        Rotation targetRotation = getLookRotation(mod, toLook);
        lookAt(mod, targetRotation);
    }
    static void lookAt(AltoClef mod, BlockPos toLook, Direction side) {
        Vec3 target = new Vec3(toLook.getX() + 0.5, toLook.getY() + 0.5, toLook.getZ() + 0.5);
        if (side != null) {
            target.add(side.getUnitVec3i().getX() * 0.5, side.getUnitVec3i().getY() * 0.5, side.getUnitVec3i().getZ() * 0.5);
        }
        lookAt(mod, target);
    }
    static void lookAt(AltoClef mod, BlockPos toLook) {
        lookAt(mod, toLook, null);
    }

    static Rotation getLookRotation(AltoClef mod, Vec3 toLook) {
        return RotationUtils.calcRotationFromVec3d(mod.getClientBaritone().getPlayerContext().playerHead(), toLook, mod.getClientBaritone().getPlayerContext().playerRotations());
    }
    static Rotation getLookRotation(AltoClef mod, BlockPos toLook) {
        return getLookRotation(mod, WorldHelper.toVec3d(toLook));
    }

}
