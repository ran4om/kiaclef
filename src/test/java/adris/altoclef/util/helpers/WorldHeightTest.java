package adris.altoclef.util.helpers;

import net.minecraft.world.level.LevelHeightAccessor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorldHeightTest {
    @Test
    void buildBoundsUseTheDimensionMinYAndInclusiveMaxY() {
        LevelHeightAccessor world = LevelHeightAccessor.create(-64, 384);

        assertEquals(-64, WorldHelper.getMinBuildY(world));
        assertEquals(319, WorldHelper.getMaxBuildY(world));
    }
}
