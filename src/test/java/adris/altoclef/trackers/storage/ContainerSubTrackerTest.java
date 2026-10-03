package adris.altoclef.trackers.storage;

import adris.altoclef.testing.MinecraftTestBootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.client.gui.screens.inventory.BrewingStandScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerSubTrackerTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    void brewingStandBlockInteractionIsTracked() {
        assertTrue(ContainerSubTracker.isTrackedContainerBlock(Blocks.BREWING_STAND));
    }

    @Test
    void brewingStandScreenIsTrackedAlongsideExistingStorageScreens() {
        assertTrue(ContainerSubTracker.isTrackedContainerScreen(BrewingStandScreen.class));
        assertTrue(ContainerSubTracker.isTrackedContainerScreen(ContainerScreen.class));
        assertFalse(ContainerSubTracker.isTrackedContainerScreen(InventoryScreen.class));
    }

    @Test
    void blockMenuCompatibilityDistinguishesSameFamilyContainers() {
        assertTrue(ContainerSubTracker.containerMenuClassMatchesBlock(Blocks.CHEST, ChestMenu.class));
        assertTrue(ContainerSubTracker.containerMenuClassMatchesBlock(Blocks.BARREL, ChestMenu.class));
        assertTrue(ContainerSubTracker.containerMenuClassMatchesBlock(Blocks.DISPENSER, DispenserMenu.class));
        assertTrue(ContainerSubTracker.containerMenuClassMatchesBlock(Blocks.HOPPER, HopperMenu.class));
        assertTrue(ContainerSubTracker.containerMenuClassMatchesBlock(Blocks.FURNACE, AbstractFurnaceMenu.class));
        assertTrue(ContainerSubTracker.containerMenuClassMatchesBlock(Blocks.BREWING_STAND, BrewingStandMenu.class));
        assertTrue(ContainerSubTracker.containerMenuClassMatchesBlock(Blocks.SHULKER_BOX, ShulkerBoxMenu.class));
        assertFalse(ContainerSubTracker.containerMenuClassMatchesBlock(Blocks.BARREL, DispenserMenu.class));
        assertFalse(ContainerSubTracker.containerMenuClassMatchesBlock(Blocks.CHEST, CraftingMenu.class));
    }

    @Test
    void explicitInteractionBindsOneNewMenuAndCannotBindAReplacement() {
        ContainerMenuSession session = new ContainerMenuSession();
        Object world = new Object();
        Object player = new Object();
        Object inventoryMenu = new Object();
        Object chestMenu = new Object();
        Object replacementChestMenu = new Object();
        BlockPos chest = new BlockPos(4, 70, -2);

        session.noteInteraction(world, player, inventoryMenu, chest, Blocks.CHEST, 1);
        session.onScreenChanged(world, player, chestMenu, true, 1, true,
                block -> block == Blocks.CHEST, (position, block) -> position.equals(chest) && block == Blocks.CHEST);

        assertEquals(chest, session.getBoundContainer(world, player, chestMenu,
                (position, block) -> true).orElseThrow().position());
        assertTrue(session.getBoundContainer(world, player, replacementChestMenu,
                (position, block) -> true).isEmpty());
        session.onScreenChanged(world, player, replacementChestMenu, true, 2, true,
                block -> block == Blocks.CHEST, (position, block) -> true);
        assertTrue(session.getBoundContainer(world, player, replacementChestMenu,
                (position, block) -> true).isEmpty());
    }

    @Test
    void crosshairOnlyAndRepeatedSameTypeScreensStayUnbound() {
        ContainerMenuSession session = new ContainerMenuSession();
        Object world = new Object();
        Object player = new Object();
        Object firstMenu = new Object();
        Object secondMenu = new Object();

        session.onScreenChanged(world, player, firstMenu, true, 1, true, block -> true,
                (position, block) -> true);
        assertTrue(session.getBoundContainer(world, player, firstMenu, (position, block) -> true).isEmpty());

        session.onScreenChanged(world, player, secondMenu, true, 2, true, block -> true,
                (position, block) -> true);
        assertTrue(session.getBoundContainer(world, player, secondMenu, (position, block) -> true).isEmpty());
    }

    @Test
    void distinctPendingInteractionsAreRejectedButRepeatedSamePositionCanBind() {
        ContainerMenuSession ambiguous = new ContainerMenuSession();
        Object world = new Object();
        Object player = new Object();
        Object inventoryMenu = new Object();
        Object chestMenu = new Object();
        BlockPos firstChest = new BlockPos(1, 70, 1);
        BlockPos secondChest = new BlockPos(2, 70, 1);

        ambiguous.noteInteraction(world, player, inventoryMenu, firstChest, Blocks.CHEST, 1);
        ambiguous.noteInteraction(world, player, inventoryMenu, secondChest, Blocks.CHEST, 1);
        ambiguous.onScreenChanged(world, player, chestMenu, true, 1, true, block -> true,
                (position, block) -> true);
        assertTrue(ambiguous.getBoundContainer(world, player, chestMenu, (position, block) -> true).isEmpty());

        ContainerMenuSession repeated = new ContainerMenuSession();
        repeated.noteInteraction(world, player, inventoryMenu, firstChest, Blocks.CHEST, 1);
        repeated.noteInteraction(world, player, inventoryMenu, new BlockPos(1, 70, 1), Blocks.CHEST, 2);
        repeated.onScreenChanged(world, player, chestMenu, true, 2, true, block -> true,
                (position, block) -> true);
        assertEquals(firstChest, repeated.getBoundContainer(world, player, chestMenu,
                (position, block) -> true).orElseThrow().position());
    }

    @Test
    void wrongContextTypeAndChangedBlockRejectBinding() {
        Object world = new Object();
        Object otherWorld = new Object();
        Object player = new Object();
        Object otherPlayer = new Object();
        Object inventoryMenu = new Object();
        Object newMenu = new Object();
        BlockPos chest = new BlockPos(1, 70, 1);

        assertRejected(world, otherPlayer, world, player, inventoryMenu, newMenu, chest, true, true);
        assertRejected(otherWorld, player, world, player, inventoryMenu, newMenu, chest, true, true);
        assertRejected(world, player, world, player, inventoryMenu, newMenu, chest, false, true);
        assertRejected(world, player, world, player, inventoryMenu, newMenu, chest, true, false);
    }

    private static void assertRejected(Object interactionWorld, Object interactionPlayer,
                                       Object activeWorld, Object activePlayer,
                                       Object originMenu, Object newMenu, BlockPos chest,
                                       boolean menuMatches, boolean blockStillMatches) {
        ContainerMenuSession session = new ContainerMenuSession();
        session.noteInteraction(interactionWorld, interactionPlayer, originMenu, chest, Blocks.CHEST, 1);
        session.onScreenChanged(activeWorld, activePlayer, newMenu, true, 1, true,
                block -> menuMatches, (position, block) -> blockStillMatches);
        assertTrue(session.getBoundContainer(activeWorld, activePlayer, newMenu,
                (position, block) -> true).isEmpty());
    }

    @Test
    void closeClearsBindingAndTaskInterruptionDoesNot() {
        ContainerMenuSession session = new ContainerMenuSession();
        Object world = new Object();
        Object player = new Object();
        Object inventoryMenu = new Object();
        Object chestMenu = new Object();
        BlockPos chest = new BlockPos(4, 70, -2);
        session.noteInteraction(world, player, inventoryMenu, chest, Blocks.CHEST, 1);
        session.onScreenChanged(world, player, chestMenu, true, 1, true,
                block -> true, (position, block) -> true);

        assertTrue(session.getBoundContainer(world, player, chestMenu, (position, block) -> true).isPresent());
        // Suspending a task does not change the menu session.
        assertTrue(session.getBoundContainer(world, player, chestMenu, (position, block) -> true).isPresent());
        session.onScreenChanged(world, player, inventoryMenu, false, 2, false,
                block -> true, (position, block) -> true);
        assertTrue(session.getBoundContainer(world, player, chestMenu, (position, block) -> true).isEmpty());
    }

    @Test
    void nonBlockInteractionAndExpiredCandidateCannotBindAnUnrelatedMenu() {
        Object world = new Object();
        Object player = new Object();
        Object inventoryMenu = new Object();
        Object chestMenu = new Object();
        BlockPos chest = new BlockPos(4, 70, -2);

        ContainerMenuSession afterEntityUse = new ContainerMenuSession();
        afterEntityUse.noteInteraction(world, player, inventoryMenu, chest, Blocks.CHEST, 1);
        afterEntityUse.clearPendingInteraction();
        afterEntityUse.onScreenChanged(world, player, chestMenu, true, 2, true,
                block -> block == Blocks.CHEST, (position, block) -> true);
        assertTrue(afterEntityUse.getBoundContainer(world, player, chestMenu,
                (position, block) -> true).isEmpty());

        ContainerMenuSession expired = new ContainerMenuSession();
        expired.noteInteraction(world, player, inventoryMenu, chest, Blocks.CHEST, 1);
        expired.expirePendingInteraction(42);
        expired.onScreenChanged(world, player, chestMenu, true, 42, true,
                block -> block == Blocks.CHEST, (position, block) -> true);
        assertTrue(expired.getBoundContainer(world, player, chestMenu,
                (position, block) -> true).isEmpty());
    }
}
