package adris.altoclef.mixins.baritonecompat;

import adris.altoclef.baritone.AltoClefSettings;
import baritone.api.utils.RayTraceUtils;
import net.minecraft.world.level.ClipContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Restores AltoClef's configurable fluid policy for Baritone block ray traces. */
@Mixin(RayTraceUtils.class)
public abstract class BaritoneRayTraceFluidMixin {

    @ModifyArg(
            method = "rayTraceTowards(Lnet/minecraft/world/entity/Entity;Lbaritone/api/utils/Rotation;DZ)Lnet/minecraft/world/phys/HitResult;",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/ClipContext;<init>(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/level/ClipContext$Block;Lnet/minecraft/world/level/ClipContext$Fluid;Lnet/minecraft/world/entity/Entity;)V"
            ),
            index = 3
    )
    private static ClipContext.Fluid altoClef$useConfiguredFluidHandling(ClipContext.Fluid ignored) {
        return AltoClefSettings.getInstance().rayFluidHandling;
    }
}
