package adris.altoclef.mixins.baritonecompat;

import baritone.process.BuilderProcess;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(BuilderProcess.BuilderCalculationContext.class)
public interface BaritoneBuilderCalculationContextAccessor {
    @Invoker("getSchematic")
    BlockState altoclef$getSchematic(int x, int y, int z, BlockState currentState);
}
