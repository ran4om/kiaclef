package adris.altoclef.util.helpers;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LookHelperTest {
    @Test
    void aimsInsideBottomSlabTopFace() {
        var point = LookHelper.shapeFacePoint(new BlockPos(4, 10, 7),
                new AABB(0, 0, 0, 1, 0.5, 1), Direction.UP);
        assertEquals(4.5, point.x);
        assertTrue(point.y > 10.49 && point.y < 10.5);
        assertEquals(7.5, point.z);
    }

    @Test
    void aimsInsideTopSlabBottomFace() {
        var point = LookHelper.shapeFacePoint(BlockPos.ZERO,
                new AABB(0, 0.5, 0, 1, 1, 1), Direction.DOWN);
        assertTrue(point.y > 0.5 && point.y < 0.51);
    }

    @Test
    void aimsAtThinDoorOutlineRatherThanCubeBoundary() {
        var point = LookHelper.shapeFacePoint(BlockPos.ZERO,
                new AABB(0, 0, 0, 1, 1, 0.1875), Direction.SOUTH);
        assertTrue(point.z < 0.1875 && point.z > 0.18);
        assertEquals(0.5, point.x);
    }
}
