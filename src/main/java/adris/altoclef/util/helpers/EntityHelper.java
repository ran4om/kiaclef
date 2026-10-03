package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;

/**
 * Helper functions to interpret entity state
 */
public class EntityHelper {

    public static final double ENTITY_GRAVITY = 0.08; // per second

    public static boolean isAngryAtPlayer(AltoClef mod, Entity mob) {
        boolean hostile = isGenerallyHostileToPlayer(mod, mob);
        if (mob instanceof LivingEntity entity) {
            return hostile && entity.hasLineOfSight(mod.getPlayer());
        }
        return hostile;
    }

    public static boolean isGenerallyHostileToPlayer(AltoClef mod, Entity hostile) {
        // TODO: Ignore on Peaceful difficulty.
        LocalPlayer player = mod.getPlayer();
        if (hostile instanceof EnderMan enderman) {
            return isAngryAtClientPlayer(enderman, player);
        }
        if (hostile instanceof Piglin) {
            // Angry if we're not wearing gold
            return !StorageHelper.isArmorEquipped(mod, ItemHelper.GOLDEN_ARMORS);
        }
        if (hostile instanceof ZombifiedPiglin) {
            return isAngryAtClientPlayer(hostile, player);
        }
        return !isTradingPiglin(hostile);
    }

    /**
     * NeutralMob.isAngryAt now requires a ServerLevel. On the client, use the
     * synced mob target when available and the persistent anger target as the
     * fallback, while still honoring the mob's anger timer.
     */
    private static boolean isAngryAtClientPlayer(Entity mob, LocalPlayer player) {
        if (player == null || !(mob instanceof NeutralMob neutral) || !neutral.isAngry()) {
            return false;
        }
        if (mob instanceof Mob targetable && targetable.getTarget() == player) {
            return true;
        }
        EntityReference<LivingEntity> angerTarget = neutral.getPersistentAngerTarget();
        return angerTarget != null && angerTarget.matches(player);
    }

    public static boolean isTradingPiglin(Entity entity) {
        if (entity instanceof Piglin pig) {
            for (EquipmentSlot hand : new EquipmentSlot[]{EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND}) {
                ItemStack stack = pig.getItemBySlot(hand);
                if (stack.getItem().equals(Items.GOLD_INGOT)) {
                    // We're trading with this one, ignore it.
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Calculate the resulting damage dealt to a player as a result of some damage.
     * If this player were to receive this damage, the player's health will be subtracted by the resulting value.
     */
    public static double calculateResultingPlayerDamage(Player player, DamageSource source, double damageAmount) {
        if (isInvulnerableToDamage(player, source)) {
            return 0;
        }

        // Matches LivingEntity.getDamageAfterArmorAbsorb. CombatRules now takes
        // the entity and source so modern armor effectiveness effects are used.
        if (!source.is(DamageTypeTags.BYPASSES_ARMOR)) {
            float armor = player.getArmorValue();
            float toughness = (float) player.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
            damageAmount = CombatRules.getDamageAfterAbsorb(player, (float) damageAmount, source, armor, toughness);
        }

        // Resistance is an effect; protection enchantments are handled by a
        // separate damage-type tag in current Minecraft.
        if (!source.is(DamageTypeTags.BYPASSES_EFFECTS)) {
            if (player.hasEffect(MobEffects.RESISTANCE)
                    && !source.is(DamageTypeTags.BYPASSES_RESISTANCE)) {
                int resistance = (player.getEffect(MobEffects.RESISTANCE).getAmplifier() + 1) * 5;
                damageAmount = Math.max(damageAmount * (25 - resistance) / 25.0, 0.0);
            }

            if (damageAmount > 0.0 && !source.is(DamageTypeTags.BYPASSES_ENCHANTMENTS)) {
                int protection = getClientArmorProtection(player, source);
                if (protection > 0) {
                    damageAmount = CombatRules.getDamageAfterMagicAbsorb((float) damageAmount, protection);
                }
            }
        }

        return Math.max(damageAmount - player.getAbsorptionAmount(), 0.0);
    }

    private static boolean isInvulnerableToDamage(Player player, DamageSource source) {
        if (player.isRemoved() || player.isSpectator()) {
            return true;
        }
        if (player.isCreative() && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            return true;
        }
        if (player.isInvulnerable() && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)
                && !source.isCreativePlayer()) {
            return true;
        }
        if (source.is(DamageTypeTags.IS_FIRE) && player.fireImmune()) {
            return true;
        }
        return false;
    }

    /**
     * The game computes conditional enchantment protection on the server. This
     * helper runs on the client, so reproduce the four vanilla armor protection
     * enchantments from their synchronized equipment components.
     */
    private static int getClientArmorProtection(Player player, DamageSource source) {
        Level level = player.level();
        var enchantments = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        Holder<Enchantment> protection = enchantments.getOrThrow(Enchantments.PROTECTION);
        Holder<Enchantment> fireProtection = enchantments.getOrThrow(Enchantments.FIRE_PROTECTION);
        Holder<Enchantment> blastProtection = enchantments.getOrThrow(Enchantments.BLAST_PROTECTION);
        Holder<Enchantment> projectileProtection = enchantments.getOrThrow(Enchantments.PROJECTILE_PROTECTION);
        Holder<Enchantment> featherFalling = enchantments.getOrThrow(Enchantments.FEATHER_FALLING);

        int result = 0;
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            var itemEnchantments = player.getItemBySlot(slot).getEnchantments();
            result += itemEnchantments.getLevel(protection);
            if (source.is(DamageTypeTags.IS_FIRE)) {
                result += itemEnchantments.getLevel(fireProtection) * 2;
            }
            if (source.is(DamageTypeTags.IS_EXPLOSION)) {
                result += itemEnchantments.getLevel(blastProtection) * 2;
            }
            if (source.is(DamageTypeTags.IS_PROJECTILE)) {
                result += itemEnchantments.getLevel(projectileProtection) * 2;
            }
            if (source.is(DamageTypeTags.IS_FALL)) {
                result += itemEnchantments.getLevel(featherFalling) * 3;
            }
        }
        return Math.min(result, 20);
    }
}
