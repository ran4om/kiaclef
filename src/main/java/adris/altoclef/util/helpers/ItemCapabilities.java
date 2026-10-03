package adris.altoclef.util.helpers;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.level.block.Blocks;

/** Reads equipment and food capabilities from the modern item component model. */
public final class ItemCapabilities {
    private ItemCapabilities() {}
    public static boolean isTool(Item item) {
        return item.components().has(DataComponents.TOOL)
                || item.components().has(DataComponents.WEAPON);
    }
    public static String toolKind(Item item) {
        String path = BuiltInRegistries.ITEM.getKey(item).getPath();
        for (String suffix : new String[]{"pickaxe", "axe", "shovel", "hoe", "sword", "spear"}) {
            if (path.endsWith("_" + suffix)) return suffix;
        }
        return path;
    }
    public static int miningLevel(Item item) {
        ItemStack stack = item.getDefaultInstance();
        if (stack.isCorrectToolForDrops(Blocks.OBSIDIAN.defaultBlockState())) return 3;
        if (stack.isCorrectToolForDrops(Blocks.DIAMOND_ORE.defaultBlockState())) return 2;
        if (stack.isCorrectToolForDrops(Blocks.IRON_ORE.defaultBlockState())) return 1;
        return 0;
    }
    public static double attackDamage(Item item) {
        return item.components().getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS,
                net.minecraft.world.item.component.ItemAttributeModifiers.EMPTY)
                .compute(Attributes.ATTACK_DAMAGE, 1.0, EquipmentSlot.MAINHAND);
    }
    public static boolean isWearable(Item item) {
        return item.components().has(DataComponents.EQUIPPABLE);
    }
    public static EquipmentSlot equipmentSlot(Item item) {
        var equippable = item.components().get(DataComponents.EQUIPPABLE);
        return equippable == null ? null : equippable.slot();
    }
    public static boolean isArmor(Item item) {
        EquipmentSlot slot = equipmentSlot(item);
        return slot == EquipmentSlot.HEAD || slot == EquipmentSlot.CHEST
                || slot == EquipmentSlot.LEGS || slot == EquipmentSlot.FEET;
    }
    public static FoodProperties food(Item item) { return item.components().get(DataComponents.FOOD); }
    public static boolean isFood(Item item) { return food(item) != null; }
}
