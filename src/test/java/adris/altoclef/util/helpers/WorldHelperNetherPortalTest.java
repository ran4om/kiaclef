package adris.altoclef.util.helpers;

import adris.altoclef.testing.MinecraftTestBootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.PortalProcessor;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldHelperNetherPortalTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    void onlyTreatsAnActiveNetherPortalProcessorAsNetherPortal() {
        PortalProcessor nether = new PortalProcessor((net.minecraft.world.level.block.Portal) Blocks.NETHER_PORTAL, BlockPos.ZERO);
        nether.setAsInsidePortalThisTick(true);
        PortalProcessor end = new PortalProcessor((net.minecraft.world.level.block.Portal) Blocks.END_PORTAL, BlockPos.ZERO);
        end.setAsInsidePortalThisTick(true);

        assertTrue(WorldHelper.isInNetherPortal(nether));
        assertFalse(WorldHelper.isInNetherPortal(end));
        assertFalse(WorldHelper.isInNetherPortal((PortalProcessor) null));
    }
}
