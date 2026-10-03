package adris.altoclef.util;

import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.resources.MineAndCollectTask;
import adris.altoclef.tasks.resources.CollectKelpTask;
import adris.altoclef.tasks.resources.ShearAndCollectBlockTask;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.resources.CollectAnyItemTask;
import adris.altoclef.tasks.CraftInInventoryTask;
import adris.altoclef.tasks.container.CraftInTableTask;
import adris.altoclef.tasks.container.SmeltInFurnaceTask;
import adris.altoclef.tasks.squashed.CataloguedResourceTask;
import adris.altoclef.util.helpers.ItemHelper;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.DynamicTest;
import java.util.stream.Stream;
import java.lang.reflect.Field;
import static org.junit.jupiter.api.Assertions.*;

class TaskCatalogueTest {
    @BeforeAll static void bootstrap() { adris.altoclef.testing.MinecraftTestBootstrap.initialize(); }
    @Test void diamondTaskKeepsItsIronPrerequisite() throws Exception {
        var task = TaskCatalogue.getItemTask("diamond", 3);
        assertInstanceOf(MineAndCollectTask.class, task);
        Field requirement = MineAndCollectTask.class.getDeclaredField("_requirement");
        requirement.setAccessible(true);
        assertEquals(MiningRequirement.IRON, requirement.get(task));
        assertEquals(3, task.getItemTargets()[0].getTargetCount());
        assertTrue(task.getItemTargets()[0].matches(Items.DIAMOND));
    }

    @Test void ordinarySurvivalBlockDropsAreRegisteredBeforeRecipeFallback() throws Exception {
        for (MiningPath path : java.util.List.of(
                new MiningPath("moss_block", Items.MOSS_BLOCK, Blocks.MOSS_BLOCK, Dimension.OVERWORLD, MiningRequirement.HAND),
                new MiningPath("end_stone", Items.END_STONE, Blocks.END_STONE, Dimension.END, MiningRequirement.WOOD))) {
            MineAndCollectTask task = assertInstanceOf(MineAndCollectTask.class,
                    TaskCatalogue.getItemTask(path.name(), 2), path.name());
            assertTrue(task.getItemTargets()[0].matches(path.target()), path.name());
            assertEquals(2, task.getItemTargets()[0].getTargetCount(), path.name());

            Field sources = MineAndCollectTask.class.getDeclaredField("_blocksToMine");
            sources.setAccessible(true);
            assertArrayEquals(new Block[]{path.source()}, (Block[]) sources.get(task), path.name());
            Field requirement = MineAndCollectTask.class.getDeclaredField("_requirement");
            requirement.setAccessible(true);
            assertEquals(path.requirement(), requirement.get(task), path.name());
            Field dimension = ResourceTask.class.getDeclaredField("_targetDimension");
            dimension.setAccessible(true);
            assertEquals(path.dimension(), dimension.get(task), path.name());
            Field forcedDimension = ResourceTask.class.getDeclaredField("_forceDimension");
            forcedDimension.setAccessible(true);
            assertTrue((Boolean) forcedDimension.get(task), path.name());
        }
    }
    @Test void kelpCanBeCollectedSmeltedAndCraftedIntoDriedKelpBlocks() throws Exception {
        CollectKelpTask kelp = assertInstanceOf(CollectKelpTask.class,
                TaskCatalogue.getItemTask("kelp", 3));
        assertTrue(kelp.getItemTargets()[0].matches(Items.KELP));
        assertEquals(3, kelp.getItemTargets()[0].getTargetCount());
        assertTrue(TaskCatalogue.taskExists(Items.KELP));

        SmeltInFurnaceTask driedKelp = assertInstanceOf(SmeltInFurnaceTask.class,
                TaskCatalogue.getItemTask(Items.DRIED_KELP, 3));
        assertTrue(driedKelp.getTargets()[0].getItem().matches(Items.DRIED_KELP));
        assertEquals(3, driedKelp.getTargets()[0].getItem().getTargetCount());
        assertTrue(driedKelp.getTargets()[0].getMaterial().matches(Items.KELP));
        assertEquals(3, driedKelp.getTargets()[0].getMaterial().getTargetCount());

        CraftInTableTask driedKelpBlock = assertInstanceOf(CraftInTableTask.class,
                TaskCatalogue.getItemTask(Items.DRIED_KELP_BLOCK, 2));
        assertEquals(Items.DRIED_KELP_BLOCK, driedKelpBlock.getRecipeTargets()[0].getOutputItem());
        assertEquals(2, driedKelpBlock.getRecipeTargets()[0].getTargetCount());
        var recipe = driedKelpBlock.getRecipeTargets()[0].getRecipe();
        assertEquals(1, recipe.outputCount());
        assertEquals(9, recipe.getSlotCount());
        for (int slot = 0; slot < recipe.getSlotCount(); slot++) {
            assertTrue(recipe.getSlot(slot).matches(Items.DRIED_KELP), "slot " + slot);
        }
    }

    @Test void azaleaLeavesUseShearsForTheirExactVanillaLeafDrops() throws Exception {
        assertAzaleaLeafTask("azalea_leaves", Items.AZALEA_LEAVES, Blocks.AZALEA_LEAVES);
        assertAzaleaLeafTask("flowering_azalea_leaves", Items.FLOWERING_AZALEA_LEAVES,
                Blocks.FLOWERING_AZALEA_LEAVES);
    }

    private static void assertAzaleaLeafTask(String name, Item target, Block source) throws Exception {
        assertTrue(TaskCatalogue.taskExists(name), name);
        ShearAndCollectBlockTask task = assertInstanceOf(ShearAndCollectBlockTask.class,
                TaskCatalogue.getItemTask(name, 3), name);
        assertEquals(1, task.getItemTargets().length, name);
        assertTrue(task.getItemTargets()[0].matches(target), name);
        assertEquals(3, task.getItemTargets()[0].getTargetCount(), name);

        Field sources = MineAndCollectTask.class.getDeclaredField("_blocksToMine");
        sources.setAccessible(true);
        assertArrayEquals(new Block[]{source}, (Block[]) sources.get(task), name);

        Field requirement = MineAndCollectTask.class.getDeclaredField("_requirement");
        requirement.setAccessible(true);
        assertEquals(MiningRequirement.HAND, requirement.get(task), name);
    }

    @Test void essentialPrerequisiteTasksAreConstructible() {
        for (String name : new String[]{"log", "planks", "stick", "crafting_table", "wooden_pickaxe", "cobblestone", "stone_pickaxe", "raw_iron", "furnace", "iron_ingot", "iron_pickaxe", "diamond"}) {
            assertTrue(TaskCatalogue.taskExists(name), name);
            assertNotNull(TaskCatalogue.getItemTask(name, 1), name);
        }
    }
    @Test void vanillaFallbackRegistersPreviouslyUncataloguedCraftableItems() {
        assertTrue(TaskCatalogue.taskExists(Items.CRAFTER));
        CraftInTableTask task = assertInstanceOf(CraftInTableTask.class,
                TaskCatalogue.getItemTask(Items.CRAFTER, 1));
        assertEquals(Items.CRAFTER, task.getRecipeTargets()[0].getOutputItem());
        assertEquals(1, task.getRecipeTargets()[0].getRecipe().outputCount());
        assertEquals(9, task.getRecipeTargets()[0].getRecipe().getSlotCount());
    }
    @Test void terracottaSmeltingUsesReachableClayBlockPrerequisite() {
        assertTrue(TaskCatalogue.taskExists(Items.CLAY_BALL));
        assertTrue(TaskCatalogue.taskExists(Items.CLAY));
        CraftInInventoryTask clay = assertInstanceOf(CraftInInventoryTask.class,
                TaskCatalogue.getItemTask(Items.CLAY, 1));
        assertEquals(Items.CLAY, clay.getRecipeTarget().getOutputItem());
        assertEquals(4, java.util.stream.IntStream.range(0, clay.getRecipeTarget().getRecipe().getSlotCount())
                .filter(slot -> clay.getRecipeTarget().getRecipe().getSlot(slot).matches(Items.CLAY_BALL)).count());

        SmeltInFurnaceTask task = assertInstanceOf(SmeltInFurnaceTask.class,
                TaskCatalogue.getItemTask(Items.TERRACOTTA, 2));
        var target = task.getTargets()[0];
        assertTrue(target.getItem().matches(Items.TERRACOTTA));
        assertEquals(2, target.getItem().getTargetCount());
        assertTrue(target.getMaterial().matches(Items.CLAY));
        assertEquals(2, target.getMaterial().getTargetCount());
    }
    @Test void allDyedTerracottaRecipesResolveFromClayAndMatchingDye() {
        var colors = ItemHelper.getColorfulItems();
        assertEquals(16, colors.size());
        for (var color : colors) {
            assertTrue(TaskCatalogue.taskExists(color.dye), color.colorName + " dye");
            assertTrue(TaskCatalogue.taskExists(color.terracotta), color.colorName + " terracotta");
            CraftInTableTask task = assertInstanceOf(CraftInTableTask.class,
                    TaskCatalogue.getItemTask(color.terracotta, 1));
            var target = task.getRecipeTargets()[0];
            assertEquals(color.terracotta, target.getOutputItem(), color.colorName);
            assertEquals(8, target.getRecipe().outputCount(), color.colorName);
            long terracottaSlots = java.util.stream.IntStream.range(0, target.getRecipe().getSlotCount())
                    .filter(slot -> target.getRecipe().getSlot(slot).matches(Items.TERRACOTTA)).count();
            long dyeSlots = java.util.stream.IntStream.range(0, target.getRecipe().getSlotCount())
                    .filter(slot -> target.getRecipe().getSlot(slot).matches(color.dye)).count();
            assertEquals(8, terracottaSlots, color.colorName + " terracotta ingredients");
            assertEquals(1, dyeSlots, color.colorName + " dye ingredient");
        }
    }
    @Test void allGlazedTerracottaColorsSmeltTheirMatchingTerracotta() {
        var colors = ItemHelper.getColorfulItems();
        assertEquals(16, colors.size());
        for (var color : colors) {
            assertTrue(TaskCatalogue.taskExists(color.terracotta), color.colorName + " terracotta input");
            SmeltInFurnaceTask task = assertInstanceOf(SmeltInFurnaceTask.class,
                    TaskCatalogue.getItemTask(color.glazedTerracotta, 1));
            var target = task.getTargets()[0];
            assertTrue(target.getItem().matches(color.glazedTerracotta), color.colorName + " output");
            assertTrue(target.getMaterial().matches(color.terracotta), color.colorName + " input");
        }
    }
    @Test void vanillaFallbackCanCraftCopperToolsFromGatherableCopper() {
        assertTrue(TaskCatalogue.taskExists(Items.COPPER_AXE));
        CraftInTableTask task = assertInstanceOf(CraftInTableTask.class,
                TaskCatalogue.getItemTask(Items.COPPER_AXE, 1));
        assertEquals(Items.COPPER_AXE, task.getRecipeTargets()[0].getOutputItem());
        assertTrue(TaskCatalogue.taskExists("copper_ingot"));
    }
    @TestFactory Stream<DynamicTest> everyRegisteredResourceCanCreateItsTask() {
        return TaskCatalogue.resourceNames().stream().sorted().map(name ->
                DynamicTest.dynamicTest(name, () -> {
                    var task = TaskCatalogue.getItemTask(name, 1);
                    assertNotNull(task, name);
                    assertNotNull(task.getItemTargets(), name);
                    assertTrue(task.getItemTargets().length > 0, name);
                }));
    }
    @Test void listKeepsAllRequestedTargets() {
        var task = new CataloguedResourceTask(new ItemTarget(Items.DIAMOND, 3), new ItemTarget(Items.OAK_PLANKS, 16), new ItemTarget(Items.IRON_INGOT, 8));
        var targets = task.getItemTargets();
        assertEquals(3, targets.length);
        assertEquals(3, targets[0].getTargetCount());
        assertEquals(16, targets[1].getTargetCount());
        assertEquals(8, targets[2].getTargetCount());
    }

    @Test void multiMatchTargetResolvesToNonRecursiveWildcardCollector() {
        Item[] variants = {Items.OAK_PLANKS, Items.BIRCH_PLANKS};

        var task = TaskCatalogue.getItemTask(new ItemTarget(variants, 3));

        assertInstanceOf(CollectAnyItemTask.class, task);
        assertEquals(3, task.getItemTargets()[0].getTargetCount());
        assertArrayEquals(variants, task.getItemTargets()[0].getMatches());
    }

    @Test void emptyTargetIsSkippedWhenBuildingCataloguedResourceList() {
        var task = assertDoesNotThrow(() -> new CataloguedResourceTask(ItemTarget.EMPTY));

        assertEquals(1, task.getItemTargets().length);
        assertEquals(0, task.getItemTargets()[0].getMatches().length);
    }

    @Test void mixedMaterialListCanIncludeARecursiveWildcardWithoutOverflow() {
        ItemTarget woodVariants = new ItemTarget(new Item[]{Items.OAK_PLANKS, Items.BIRCH_PLANKS}, 4);

        var task = assertDoesNotThrow(() -> new CataloguedResourceTask(
                new ItemTarget(Items.DIAMOND, 3), woodVariants, new ItemTarget(Items.STICK, 2)));

        assertEquals(3, task.getItemTargets().length);
        assertEquals(4, task.getItemTargets()[1].getTargetCount());
        assertArrayEquals(woodVariants.getMatches(), task.getItemTargets()[1].getMatches());
    }

    @Test void emptyTargetAndTargetsWithoutRegisteredMatchesFailClearlyWhenResolved() {
        IllegalArgumentException empty = assertThrows(IllegalArgumentException.class,
                () -> TaskCatalogue.getItemTask(ItemTarget.EMPTY));
        assertTrue(empty.getMessage().contains("No registered collection task matches"));

        ItemTarget emptyPositive = new ItemTarget(new Item[0], 1);
        assertThrows(IllegalArgumentException.class,
                () -> new CataloguedResourceTask(emptyPositive));

        assertFalse(TaskCatalogue.taskExists(Items.BARRIER));
        assertFalse(TaskCatalogue.taskExists(Items.AIR));
        IllegalArgumentException unavailable = assertThrows(IllegalArgumentException.class,
                () -> TaskCatalogue.getItemTask(new ItemTarget(new Item[]{Items.BARRIER, Items.AIR}, 1)));
        assertTrue(unavailable.getMessage().contains("No registered collection task matches"));
    }

    @Test void modernOverworldWoodFamiliesAreGatherableAndCraftable() {
        assertTrue(java.util.Arrays.asList(ItemHelper.PLANKS).containsAll(java.util.List.of(
                Items.MANGROVE_PLANKS, Items.CHERRY_PLANKS, Items.PALE_OAK_PLANKS)));
        assertTrue(java.util.Arrays.asList(ItemHelper.LOG).containsAll(java.util.List.of(
                Items.MANGROVE_LOG, Items.CHERRY_LOG, Items.PALE_OAK_LOG,
                Items.STRIPPED_MANGROVE_LOG, Items.STRIPPED_CHERRY_LOG, Items.STRIPPED_PALE_OAK_LOG,
                Items.MANGROVE_WOOD, Items.CHERRY_WOOD, Items.PALE_OAK_WOOD,
                Items.STRIPPED_MANGROVE_WOOD, Items.STRIPPED_CHERRY_WOOD, Items.STRIPPED_PALE_OAK_WOOD)));

        for (var family : java.util.List.of(
                new WoodFamily("mangrove", Items.MANGROVE_LOG, Items.MANGROVE_PLANKS,
                        Items.MANGROVE_DOOR, Items.MANGROVE_SLAB, Items.MANGROVE_STAIRS, Items.MANGROVE_FENCE),
                new WoodFamily("cherry", Items.CHERRY_LOG, Items.CHERRY_PLANKS,
                        Items.CHERRY_DOOR, Items.CHERRY_SLAB, Items.CHERRY_STAIRS, Items.CHERRY_FENCE),
                new WoodFamily("pale_oak", Items.PALE_OAK_LOG, Items.PALE_OAK_PLANKS,
                        Items.PALE_OAK_DOOR, Items.PALE_OAK_SLAB, Items.PALE_OAK_STAIRS, Items.PALE_OAK_FENCE))) {
            assertEquals(family.log(), ItemHelper.planksToLog(family.planks()));
            assertEquals(family.planks(), ItemHelper.logToPlanks(family.log()));
            assertCatalogueTarget(family.name() + "_log", family.log());
            assertCatalogueTarget(family.name() + "_door", family.door());
            assertCatalogueTarget(family.name() + "_slab", family.slab());
            assertCatalogueTarget(family.name() + "_stairs", family.stairs());
            assertCatalogueTarget(family.name() + "_fence", family.fence());
        }
    }

    @Test void bambooHasItsOwnGatheringAndCraftingPath() {
        assertTrue(java.util.Arrays.asList(ItemHelper.PLANKS).contains(Items.BAMBOO_PLANKS));
        assertFalse(java.util.Arrays.asList(ItemHelper.WOOD_PLANKS).contains(Items.BAMBOO_PLANKS));
        assertFalse(java.util.Arrays.asList(ItemHelper.LOG).contains(Items.BAMBOO_BLOCK));
        assertNull(ItemHelper.logToPlanks(Items.BAMBOO_BLOCK));

        for (var bambooItem : java.util.List.of(
                new BambooItem("bamboo_block", Items.BAMBOO_BLOCK),
                new BambooItem("stripped_bamboo_block", Items.STRIPPED_BAMBOO_BLOCK),
                new BambooItem("bamboo_planks", Items.BAMBOO_PLANKS),
                new BambooItem("bamboo_mosaic", Items.BAMBOO_MOSAIC),
                new BambooItem("bamboo_mosaic_slab", Items.BAMBOO_MOSAIC_SLAB),
                new BambooItem("bamboo_mosaic_stairs", Items.BAMBOO_MOSAIC_STAIRS),
                new BambooItem("bamboo_raft", Items.BAMBOO_RAFT),
                new BambooItem("bamboo_button", Items.BAMBOO_BUTTON),
                new BambooItem("bamboo_sign", Items.BAMBOO_SIGN),
                new BambooItem("bamboo_hanging_sign", Items.BAMBOO_HANGING_SIGN),
                new BambooItem("bamboo_shelf", Items.BAMBOO_SHELF),
                new BambooItem("bamboo_pressure_plate", Items.BAMBOO_PRESSURE_PLATE),
                new BambooItem("bamboo_stairs", Items.BAMBOO_STAIRS),
                new BambooItem("bamboo_slab", Items.BAMBOO_SLAB),
                new BambooItem("bamboo_door", Items.BAMBOO_DOOR),
                new BambooItem("bamboo_trapdoor", Items.BAMBOO_TRAPDOOR),
                new BambooItem("bamboo_fence", Items.BAMBOO_FENCE),
                new BambooItem("bamboo_fence_gate", Items.BAMBOO_FENCE_GATE))) {
            assertCatalogueTarget(bambooItem.name(), bambooItem.item());
        }
    }

    @Test void correctedVanillaRecipesUse26Point2IngredientsAndYieldCounts() {
        CraftInInventoryTask quartzPillar = assertInstanceOf(CraftInInventoryTask.class,
                TaskCatalogue.getItemTask("quartz_pillar", 1));
        assertEquals(Items.QUARTZ_PILLAR, quartzPillar.getRecipeTarget().getOutputItem());
        assertEquals(2, quartzPillar.getRecipeTarget().getRecipe().outputCount());
        assertRecipeSlots(quartzPillar.getRecipeTarget().getRecipe(),
                Items.QUARTZ_BLOCK, null, Items.QUARTZ_BLOCK, null);

        CraftInInventoryTask redNetherBricks = assertInstanceOf(CraftInInventoryTask.class,
                TaskCatalogue.getItemTask("red_nether_bricks", 1));
        assertEquals(1, redNetherBricks.getRecipeTarget().getRecipe().outputCount());
        assertRecipeSlots(redNetherBricks.getRecipeTarget().getRecipe(),
                Items.NETHER_BRICK, Items.NETHER_WART, Items.NETHER_WART, Items.NETHER_BRICK);

        CraftInTableTask lead = assertInstanceOf(CraftInTableTask.class, TaskCatalogue.getItemTask("lead", 1));
        RecipeTarget leadRecipe = lead.getRecipeTargets()[0];
        assertEquals(Items.LEAD, leadRecipe.getOutputItem());
        assertEquals(2, leadRecipe.getRecipe().outputCount());
        assertRecipeSlots(leadRecipe.getRecipe(),
                Items.STRING, Items.STRING, null,
                Items.STRING, Items.STRING, null,
                null, null, Items.STRING);

        CraftInTableTask brickWall = assertInstanceOf(CraftInTableTask.class,
                TaskCatalogue.getItemTask("brick_wall", 1));
        RecipeTarget wallRecipe = brickWall.getRecipeTargets()[0];
        assertEquals(6, wallRecipe.getRecipe().outputCount());
        assertRecipeSlots(wallRecipe.getRecipe(),
                Items.BRICKS, Items.BRICKS, Items.BRICKS,
                Items.BRICKS, Items.BRICKS, Items.BRICKS,
                null, null, null);
    }

    private static void assertRecipeSlots(CraftingRecipe recipe, Item... expectedSlots) {
        assertEquals(expectedSlots.length, recipe.getSlotCount());
        for (int i = 0; i < expectedSlots.length; i++) {
            Item expected = expectedSlots[i];
            ItemTarget actual = recipe.getSlot(i);
            if (expected == null) {
                assertTrue(actual.isEmpty(), "slot " + i + " should be empty");
            } else {
                assertArrayEquals(new Item[]{expected}, actual.getMatches(), "slot " + i);
            }
        }
    }

    private static void assertCatalogueTarget(String name, net.minecraft.world.item.Item item) {
        assertTrue(TaskCatalogue.taskExists(name), name);
        var task = TaskCatalogue.getItemTask(name, 1);
        assertNotNull(task, name);
        assertTrue(task.getItemTargets()[0].matches(item), name);
    }

    private record WoodFamily(String name, net.minecraft.world.item.Item log, net.minecraft.world.item.Item planks,
                              net.minecraft.world.item.Item door, net.minecraft.world.item.Item slab,
                              net.minecraft.world.item.Item stairs, net.minecraft.world.item.Item fence) {}

    private record MiningPath(String name, Item target, Block source, Dimension dimension,
                              MiningRequirement requirement) {}

    private record BambooItem(String name, net.minecraft.world.item.Item item) {}
}
