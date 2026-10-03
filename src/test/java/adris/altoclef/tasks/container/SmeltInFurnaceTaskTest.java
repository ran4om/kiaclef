package adris.altoclef.tasks.container;

import adris.altoclef.testing.MinecraftTestBootstrap;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.SmeltTarget;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmeltInFurnaceTaskTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    void emptyTargetListIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SmeltInFurnaceTask(new SmeltTarget[0]));
    }

    @Test
    void selectsTheFirstOutputTargetThatStillNeedsItems() {
        SmeltTarget[] targets = targets();

        assertEquals(0, SmeltInFurnaceTask.firstIncompleteTargetIndex(targets,
                target -> target.matches(Items.IRON_INGOT) ? 1 : 0));
        assertEquals(1, SmeltInFurnaceTask.firstIncompleteTargetIndex(targets,
                target -> target.matches(Items.IRON_INGOT) ? 2 : 0));
        assertEquals(-1, SmeltInFurnaceTask.firstIncompleteTargetIndex(targets,
                target -> target.matches(Items.IRON_INGOT) ? 2 : 4));
    }

    @Test
    void conversionSlotsAcceptRequiredAndOptionalMaterialsAcrossAllTargets() {
        SmeltTarget[] targets = targets();

        assertTrue(SmeltInFurnaceTask.matchesAnyTargetMaterial(targets, Items.RAW_IRON));
        assertTrue(SmeltInFurnaceTask.matchesAnyTargetMaterial(targets, Items.COD));
        assertTrue(SmeltInFurnaceTask.matchesAnyTargetMaterial(targets, Items.SALMON));
        assertFalse(SmeltInFurnaceTask.matchesAnyTargetMaterial(targets, Items.DIAMOND));
    }

    @Test
    void partialOutputOnCursorIsRecognizedBeforeResourceCollection() {
        SmeltTarget[] targets = targets();

        assertTrue(SmeltInFurnaceTask.matchesAnyTargetOutput(targets, Items.IRON_INGOT));
        assertTrue(SmeltInFurnaceTask.matchesAnyTargetOutput(targets, Items.COOKED_COD));
        assertFalse(SmeltInFurnaceTask.matchesAnyTargetOutput(targets, Items.RAW_IRON));
    }

    @Test
    void fuelCollectionRequiresAnAcquiredFurnaceAndAnActualShortage() {
        assertFalse(SmeltInFurnaceTask.shouldCollectFuelNow(false, 0, 9));
        assertTrue(SmeltInFurnaceTask.shouldCollectFuelNow(true, 0, 9));
        assertFalse(SmeltInFurnaceTask.shouldCollectFuelNow(true, 9, 9));
        assertFalse(SmeltInFurnaceTask.shouldCollectFuelNow(true, 0, 9, true),
                "a pending slot transfer must not be replaced by fuel collection");
    }

    @Test
    void acquiredFurnaceKeepsFuelCollectionActiveAfterItsMenuCloses() {
        boolean furnaceOpen = false;
        boolean furnaceAcquired = true;

        assertFalse(SmeltInFurnaceTask.shouldAcquireFurnace(furnaceOpen, furnaceAcquired));
        assertTrue(SmeltInFurnaceTask.shouldCollectFuelNow(furnaceAcquired, 0, 9),
                "an acquired furnace keeps fuel collection active after its menu closes");
        assertTrue(SmeltInFurnaceTask.shouldAcquireFurnace(false, false));
        assertFalse(SmeltInFurnaceTask.shouldAcquireFurnace(true, false));
    }

    @Test
    void slotTransferNeedsBothDestinationReceiptAndAnEmptyCursor() {
        assertFalse(SmeltInFurnaceTask.isSlotTransferAcknowledged(1, 1, 8, 0, false),
                "an item only on the cursor has not reached the furnace");
        assertFalse(SmeltInFurnaceTask.isSlotTransferAcknowledged(1, 1, 8, 8, false),
                "a received item still needs cursor cleanup");
        assertFalse(SmeltInFurnaceTask.isSlotTransferAcknowledged(1, 0, 8, 8, true),
                "destination fuel alone must not account for the source transfer");
        assertTrue(SmeltInFurnaceTask.isSlotTransferAcknowledged(1, 1, 8, 8, true),
                "active furnace burn can acknowledge coal already consumed from the slot");
    }

    @Test
    void receiptCanTriggerCursorCleanupBeforeTheCursorIsEmpty() {
        assertTrue(SmeltInFurnaceTask.hasReceivedExpectedTransfer(3, 64, 3, 3),
                "placing the requested three items is enough to start returning excess cursor items");
        assertFalse(SmeltInFurnaceTask.isSlotTransferAcknowledged(3, 64, 3, 3, false),
                "the transfer is not finished until excess cursor items are returned");
    }

    @Test
    void elapsedBurnAloneCannotInventFuelReceipt() {
        assertFalse(SmeltInFurnaceTask.hasFuelCapacityIncrease(false, 0, 0));
        assertTrue(SmeltInFurnaceTask.hasFuelCapacityIncrease(false, 0, 7.9));
        assertTrue(SmeltInFurnaceTask.hasFuelCapacityIncrease(true, 7.9, 7.8));
        assertFalse(SmeltInFurnaceTask.hasReceivedExpectedTransfer(1, 1, 8, 0),
                "waiting without any capacity increase cannot create a fuel receipt");
        assertTrue(SmeltInFurnaceTask.hasReceivedExpectedTransfer(1, 1, 8, 7.995),
                "one consumed fuel tick is the maximum quantization loss before observation");
    }

    @Test
    void fuelReceiptSurvivesMenuReopenThroughObservedBurnAndCooking() {
        assertEquals(8, SmeltInFurnaceTask.observedFuelTransferReceipt(0, 7.5, 0, 0, 0, 0.5), 1.0e-6,
                "remaining burn plus observed cooking progress accounts for the inserted coal");
        assertFalse(SmeltInFurnaceTask.hasReceivedExpectedTransfer(1, 0, 8, 0),
                "source departure without furnace receipt is not accepted");
    }

    @Test
    void preexistingMaterialCookingDoesNotCountAsAReceiptForTheNewTransfer() {
        assertEquals(0, SmeltInFurnaceTask.materialTransferReceipt(5, 4, 1, 2),
                "one fewer input plus one newly produced output is net zero receipt");
        assertEquals(1, SmeltInFurnaceTask.materialTransferReceipt(5, 6, 1, 1));
    }

    @Test
    void materialTransferUsesAbsoluteInputSlotTarget() {
        ItemTarget material = new ItemTarget(Items.RAW_IRON, 5);
        ItemTarget transferTarget = SmeltInFurnaceTask.materialSlotTransferTarget(material, 9);

        assertEquals(9, transferTarget.getTargetCount());
        assertTrue(transferTarget.matches(Items.RAW_IRON));
    }

    @Test
    void ordinaryResetPreservesPlacementIntentButNewSmeltRunClearsEveryCachedChild() throws Exception {
        SmeltInFurnaceTask task = new SmeltInFurnaceTask(targets());
        var childrenField = SmeltInFurnaceTask.class.getDeclaredField("_doTasks");
        var placementField = DoStuffInContainerTask.class.getDeclaredField("_placeForceActive");
        childrenField.setAccessible(true);
        placementField.setAccessible(true);
        Object[] children = (Object[]) childrenField.get(task);

        for (Object child : children) placementField.setBoolean(child, true);
        task.reset();
        for (Object child : children) {
            assertTrue(placementField.getBoolean(child), "ordinary suspension preserves placement intent");
        }

        task.restartForNewRun();
        for (Object child : children) {
            assertFalse(placementField.getBoolean(child), "new run clears every cached child, including inactive ones");
        }
    }

    private static SmeltTarget[] targets() {
        return new SmeltTarget[]{
                new SmeltTarget(new ItemTarget(Items.IRON_INGOT, 2), new ItemTarget(Items.RAW_IRON, 2)),
                new SmeltTarget(new ItemTarget(Items.COOKED_COD, 4), new ItemTarget(Items.COD, 4), Items.SALMON)
        };
    }
}
