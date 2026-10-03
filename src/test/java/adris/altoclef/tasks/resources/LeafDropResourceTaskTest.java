package adris.altoclef.tasks.resources;

import adris.altoclef.TaskCatalogue;
import adris.altoclef.testing.MinecraftTestBootstrap;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.util.helpers.ItemHelper;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.MangrovePropaguleBlock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

class LeafDropResourceTaskTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    void saplingCatalogueUsesDropPreservingCollectors() {
        for (String key : List.of("oak_sapling", "spruce_sapling", "birch_sapling", "jungle_sapling",
                "acacia_sapling", "dark_oak_sapling", "cherry_sapling", "pale_oak_sapling", "azalea")) {
            assertInstanceOf(CollectSaplingTask.class, TaskCatalogue.getItemTask(key, 2), key);
        }
        assertInstanceOf(CollectMatureMangrovePropaguleTask.class,
                TaskCatalogue.getItemTask("mangrove_propagule", 2));
    }

    @Test
    void hangingRootsCatalogueUsesShearsCollectorAndShearsAreEffective() {
        assertInstanceOf(ShearAndCollectBlockTask.class, TaskCatalogue.getItemTask("hanging_roots", 1));
        assertTrue(ItemHelper.areShearsEffective(Blocks.HANGING_ROOTS));
    }

    @Test
    void smallDripleafCatalogueUsesShearsCollectorAndShearsAreEffective() {
        assertInstanceOf(ShearAndCollectBlockTask.class, TaskCatalogue.getItemTask("small_dripleaf", 1));
        assertTrue(ItemHelper.areShearsEffective(Blocks.SMALL_DRIPLEAF));
    }

    @Test
    void specializedLeafAndConcreteCollectorsDoNotGetReplacedByGenericMineIfPresent() throws Exception {
        Field mineIfPresent = ResourceTask.class.getDeclaredField("_mineIfPresent");
        mineIfPresent.setAccessible(true);
        for (String key : List.of("oak_sapling", "spruce_sapling", "birch_sapling", "jungle_sapling",
                "acacia_sapling", "dark_oak_sapling", "cherry_sapling", "pale_oak_sapling", "azalea",
                "mangrove_propagule")) {
            assertNull(mineIfPresent.get(TaskCatalogue.getItemTask(key, 1)), key);
        }
        for (DyeColor dyeColor : DyeColor.values()) {
            String key = ItemHelper.getColorfulItems(dyeColor.getMapColor()).colorName + "_concrete";
            assertNull(mineIfPresent.get(TaskCatalogue.getItemTask(key, 1)), key);
        }
    }

    @Test
    void collectorAvoidsShearsAndSilkTouchWhileAllowingOrdinaryTools() {
        var leaves = Blocks.OAK_LEAVES.defaultBlockState();
        assertTrue(CollectSaplingTask.shouldAvoidTool(leaves, new ItemStack(Items.SHEARS)));
        assertFalse(CollectSaplingTask.shouldAvoidTool(leaves, new ItemStack(Items.IRON_AXE)));
        assertFalse(CollectSaplingTask.shouldAvoidTool(leaves, ItemStack.EMPTY));

        var enchantments = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        var silkTouch = MinecraftTestBootstrap.registries()
                .lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.SILK_TOUCH);
        enchantments.set(silkTouch, 1);
        ItemStack enchantedTool = new ItemStack(Items.DIAMOND_AXE);
        enchantedTool.set(net.minecraft.core.component.DataComponents.ENCHANTMENTS, enchantments.toImmutable());
        assertTrue(CollectSaplingTask.shouldAvoidTool(leaves, enchantedTool));
    }

    @Test
    void mangroveCollectorAcceptsOnlyMaturePropagules() {
        var immature = Blocks.MANGROVE_PROPAGULE.defaultBlockState().setValue(MangrovePropaguleBlock.AGE, 3);
        var mature = Blocks.MANGROVE_PROPAGULE.defaultBlockState().setValue(MangrovePropaguleBlock.AGE, 4);

        assertFalse(CollectMatureMangrovePropaguleTask.isMature(immature));
        assertTrue(CollectMatureMangrovePropaguleTask.isMature(mature));
        assertFalse(CollectMatureMangrovePropaguleTask.isMature(Blocks.OAK_SAPLING.defaultBlockState()));
    }
}
