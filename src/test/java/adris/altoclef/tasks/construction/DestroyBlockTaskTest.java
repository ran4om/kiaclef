package adris.altoclef.tasks.construction;

import adris.altoclef.AltoClef;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DestroyBlockTaskTest {
    @Test
    void directOnlyBreakRequiresGroundedPlayerAndReach() {
        assertTrue(DestroyBlockTask.canDirectlyBreak(true, true));
        assertFalse(DestroyBlockTask.canDirectlyBreak(false, true));
        assertFalse(DestroyBlockTask.canDirectlyBreak(true, false));
        assertFalse(DestroyBlockTask.canDirectlyBreak(false, false));
    }

    @Test
    void directOnlyModeHasNoRecoveryTravelAndIsNotEqualToGenericDestroy() {
        AltoClef mod = new AltoClef() {};
        BlockPos target = BlockPos.ZERO;
        DestroyBlockTask direct = new DestroyBlockTask(target, true);
        DestroyBlockTask sameMode = new DestroyBlockTask(target, true);
        DestroyBlockTask generic = new DestroyBlockTask(target);

        assertEquals(direct, sameMode);
        assertNotEquals(direct, generic);
        assertNull(direct.getRecoveryWanderTask(mod, target));
        assertNull(direct.getDangerousBreakRecoveryTask(mod, target));
    }
}
