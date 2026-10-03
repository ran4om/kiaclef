package adris.altoclef.testing;

import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.tags.TagLoader;
import java.util.List;

/** Loads vanilla tags so component-based tool checks use real game data in unit tests. */
public final class MinecraftTestBootstrap {
    private static boolean ready;
    private static net.minecraft.core.HolderLookup.Provider registryProvider;
    public static synchronized void initialize() {
        if (ready) return;
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        try (var resources = new MultiPackResourceManager(PackType.SERVER_DATA,
                List.of(ServerPacksSource.createVanillaPackSource()))) {
            var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
            TagLoader.loadTagsForExistingRegistries(resources, registries).forEach(pending -> pending.apply());
            var dynamic = net.minecraft.resources.RegistryDataLoader.load(resources,
                    registries.listRegistries().toList(),
                    net.minecraft.resources.RegistryDataLoader.WORLDGEN_REGISTRIES, Runnable::run).join();
            TagLoader.loadTagsForExistingRegistries(resources, dynamic).forEach(pending -> pending.apply());
            var allRegistries = net.minecraft.core.HolderLookup.Provider.create(
                    java.util.stream.Stream.concat(registries.listRegistries(), dynamic.listRegistries()));
            registryProvider = allRegistries;
            BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(allRegistries).forEach(pending -> pending.apply());
        }
        ready = true;
    }
    public static net.minecraft.core.HolderLookup.Provider registries() {
        initialize();
        return registryProvider;
    }
    private MinecraftTestBootstrap() {}
}
