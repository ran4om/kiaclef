package adris.altoclef.tasks;

import adris.altoclef.testing.MinecraftTestBootstrap;
import adris.altoclef.util.ItemTarget;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceTaskContainerSelectionTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    void satisfiedSeedOnlyContainerIsStaleButContainerWithNeededWheatIsEligible() {
        ItemTarget seeds = new ItemTarget(Items.WHEAT_SEEDS, 2);
        ItemTarget wheat = new ItemTarget(Items.WHEAT, 3);
        ItemTarget[] targets = {seeds, wheat};
        Map<ItemTarget, Integer> inventory = Map.of(seeds, 2, wheat, 1);
        Map<ItemTarget, Boolean> seedsOnlyCache = Map.of(seeds, true, wheat, false);

        ItemTarget[] inSeedsOnlyCache = ResourceTask.getUnmetTargetsInContainer(
                targets, inventory::get, target -> seedsOnlyCache.getOrDefault(target, false));
        assertEquals(0, inSeedsOnlyCache.length,
                "a cache holding only the already-satisfied seed target should be discarded");

        Map<ItemTarget, Boolean> wheatCache = Map.of(seeds, false, wheat, true);
        ItemTarget[] inWheatCache = ResourceTask.getUnmetTargetsInContainer(
                targets, inventory::get, target -> wheatCache.getOrDefault(target, false));
        assertEquals(1, inWheatCache.length);
        assertTrue(inWheatCache[0].matches(Items.WHEAT),
                "the unfinished wheat target should keep this container eligible");
    }
}
