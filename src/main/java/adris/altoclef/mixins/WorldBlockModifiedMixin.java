package adris.altoclef.mixins;

import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.BlockPlaceEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayDeque;
import java.util.Deque;

@Mixin(LevelChunk.class)
public abstract class WorldBlockModifiedMixin {
    @Unique private static final ThreadLocal<Deque<BlockState>> altoclef$previousStates =
            ThreadLocal.withInitial(ArrayDeque::new);

    @Inject(method = "setBlockState", at = @At("HEAD"))
    private void altoclef$capturePreviousState(BlockPos pos, BlockState state, int flags,
                                               CallbackInfoReturnable<BlockState> cir) {
        altoclef$previousStates.get().push(((LevelChunk) (Object) this).getBlockState(pos));
    }

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void altoclef$publishPlacement(BlockPos pos, BlockState state, int flags,
                                           CallbackInfoReturnable<BlockState> cir) {
        LevelChunk chunk = (LevelChunk) (Object) this;
        Deque<BlockState> states = altoclef$previousStates.get();
        BlockState before = states.isEmpty() ? null : states.pop();
        if (states.isEmpty()) altoclef$previousStates.remove();
        if (chunk.getLevel() instanceof ClientLevel && before != null
                && !before.isSolid() && state.isSolid() && cir.getReturnValue() != null) {
            EventBus.publish(new BlockPlaceEvent(pos, state));
        }
    }
}
