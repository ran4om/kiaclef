package adris.altoclef.util;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MiningRequirementTest {
    @BeforeAll static void bootstrap() {
        adris.altoclef.testing.MinecraftTestBootstrap.initialize();
    }
    @Test void diamondNeedsAnIronOrBetterPickaxe() {
        assertEquals(MiningRequirement.IRON, MiningRequirement.getMinimumRequirementForBlock(Blocks.DIAMOND_ORE));
        assertFalse(MiningRequirement.IRON.isSatisfiedBy(Items.GOLDEN_PICKAXE));
        assertFalse(MiningRequirement.IRON.isSatisfiedBy(Items.STONE_PICKAXE));
        assertTrue(MiningRequirement.IRON.isSatisfiedBy(Items.IRON_PICKAXE));
        assertTrue(MiningRequirement.IRON.isSatisfiedBy(Items.DIAMOND_PICKAXE));
    }
    @Test void copperAndGoldUseTheirActualToolComponents() {
        assertTrue(MiningRequirement.WOOD.isSatisfiedBy(Items.GOLDEN_PICKAXE));
        assertFalse(MiningRequirement.STONE.isSatisfiedBy(Items.GOLDEN_PICKAXE));
        assertTrue(MiningRequirement.STONE.isSatisfiedBy(Items.COPPER_PICKAXE));
        assertFalse(MiningRequirement.DIAMOND.isSatisfiedBy(Items.IRON_PICKAXE));
        assertTrue(MiningRequirement.DIAMOND.isSatisfiedBy(Items.NETHERITE_PICKAXE));
    }
    @Test void rawResourcesHaveCorrectMinimumTools() {
        assertEquals(MiningRequirement.HAND, MiningRequirement.getMinimumRequirementForBlock(Blocks.OAK_LOG));
        assertEquals(MiningRequirement.STONE, MiningRequirement.getMinimumRequirementForBlock(Blocks.IRON_ORE));
        assertEquals(MiningRequirement.DIAMOND, MiningRequirement.getMinimumRequirementForBlock(Blocks.OBSIDIAN));
    }
}
