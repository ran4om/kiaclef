package adris.altoclef.tasks.movement;

import adris.altoclef.util.Dimension;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class GetToYTaskTest {
    @Test
    void taskIdentityIncludesTargetDimension() {
        assertNotEquals(new GetToYTask(64, Dimension.OVERWORLD), new GetToYTask(64, Dimension.NETHER));
        assertEquals(new GetToYTask(64, Dimension.OVERWORLD), new GetToYTask(64, Dimension.OVERWORLD));
        assertNotEquals(new GetToYTask(64, Dimension.OVERWORLD), new GetToYTask(65, Dimension.OVERWORLD));
    }

    @Test
    void nullDimensionRemainsDistinctFromExplicitDimension() {
        assertNotEquals(new GetToYTask(64), new GetToYTask(64, Dimension.OVERWORLD));
        assertEquals(new GetToYTask(64), new GetToYTask(64));
    }
}
