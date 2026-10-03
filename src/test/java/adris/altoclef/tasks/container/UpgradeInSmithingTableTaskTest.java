package adris.altoclef.tasks.container;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UpgradeInSmithingTableTaskTest {
    @Test void upgradesOnlyRequireIngredientsForRemainingOutput() {
        assertEquals(2, UpgradeInSmithingTableTask.getRemainingUpgradeCount(2, 0, 0));
        assertEquals(1, UpgradeInSmithingTableTask.getRemainingUpgradeCount(2, 1, 0));
        assertEquals(0, UpgradeInSmithingTableTask.getRemainingUpgradeCount(2, 1, 1));
        assertEquals(0, UpgradeInSmithingTableTask.getRemainingUpgradeCount(2, 3, 0));
    }
}
