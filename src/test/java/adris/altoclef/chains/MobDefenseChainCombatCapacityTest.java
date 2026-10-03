package adris.altoclef.chains;

import adris.altoclef.testing.MinecraftTestBootstrap;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MobDefenseChainCombatCapacityTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    void preservesUpstreamMaterialTierScaleInsteadOfFullAttackAttribute() {
        assertEquals(0.0f, MobDefenseChain.combatCapacityDamage(null), 0.0001f);
        assertEquals(1.0f, MobDefenseChain.combatCapacityDamage(Items.WOODEN_SWORD), 0.0001f);
        assertEquals(1.0f, MobDefenseChain.combatCapacityDamage(Items.GOLDEN_SWORD), 0.0001f);
        assertEquals(2.0f, MobDefenseChain.combatCapacityDamage(Items.STONE_SWORD), 0.0001f);
        assertEquals(3.0f, MobDefenseChain.combatCapacityDamage(Items.IRON_SWORD), 0.0001f);
        assertEquals(4.0f, MobDefenseChain.combatCapacityDamage(Items.DIAMOND_SWORD), 0.0001f);
        assertEquals(5.0f, MobDefenseChain.combatCapacityDamage(Items.NETHERITE_SWORD), 0.0001f);
    }
}
