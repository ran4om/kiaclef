package adris.altoclef.mixins.baritonecompat;

import baritone.utils.accessor.IChunkArray;
import adris.altoclef.util.helpers.BaritoneChunkSnapshotCopy;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Baritone's legacy snapshot copy calls ClientChunkCache.Storage.replace for each chunk.
 * In current Minecraft that also updates live chunk event-tracking sets and unload callbacks,
 * even though this cache is only a read-only snapshot. Copy the storage data directly instead.
 */
@Mixin(targets = "net.minecraft.client.multiplayer.ClientChunkCache$Storage")
abstract class BaritoneChunkSnapshotMixin {
    @Shadow @Final private AtomicReferenceArray<LevelChunk> chunks;
    @Shadow private int viewCenterX;
    @Shadow private int viewCenterZ;
    @Shadow private int chunkCount;

    @Shadow private boolean inRange(int x, int z) { throw new AssertionError(); }
    @Shadow private int getIndex(int x, int z) { throw new AssertionError(); }

    @Inject(method = "copyFrom(Lbaritone/utils/accessor/IChunkArray;)V", at = @At("HEAD"), cancellable = true,
            remap = false)
    private void altoclef$copySnapshotData(IChunkArray source, CallbackInfo ci) {
        viewCenterX = source.centerX();
        viewCenterZ = source.centerZ();
        BaritoneChunkSnapshotCopy.copy(source, chunks, this::inRange, this::getIndex,
                ignored -> chunkCount++);
        ci.cancel();
    }
}
