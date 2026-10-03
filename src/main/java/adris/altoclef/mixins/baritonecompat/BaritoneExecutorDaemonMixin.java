package adris.altoclef.mixins.baritonecompat;

import baritone.Baritone;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;

/** Prevents Baritone's cache workers from keeping the client JVM alive after shutdown. */
@Mixin(value = Baritone.class, remap = false)
abstract class BaritoneExecutorDaemonMixin {
    @Shadow @Final private static ThreadPoolExecutor threadPool;

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void altoclef$makeExecutorWorkersDaemon(CallbackInfo ci) {
        ThreadFactory originalFactory = threadPool.getThreadFactory();
        threadPool.setThreadFactory(task -> {
            Thread worker = originalFactory.newThread(task);
            worker.setDaemon(true);
            return worker;
        });
    }
}
