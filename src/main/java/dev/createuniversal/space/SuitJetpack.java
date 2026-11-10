package dev.createuniversal.space;

import com.simibubi.create.content.equipment.armor.BacktankUtil;
import dev.createuniversal.CreateUniversal;
import dev.createuniversal.registry.CUSuit;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForgeMod;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * La pechera del traje hace de jetpack: mientras la llevas puesta y le queda oxígeno, puedes volar
 * como en creativo. Volando gasta 2 de oxígeno por segundo, el doble que respirar, y suelta aire por la espalda (siseo y
 * vapor). Sin oxígeno, se acaba el vuelo.
 * Se hace con el atributo de vuelo de NeoForge, que respetan los demás mods.
 */
public final class SuitJetpack {
    private static final ResourceLocation ID = CreateUniversal.id("suit_jetpack");
    private static final AttributeModifier FLIGHT = new AttributeModifier(ID, 1.0, AttributeModifier.Operation.ADD_VALUE);

    private SuitJetpack() {}

    /** Al volar, el aire sale por el tanque: un siseo suave y unas bocanadas de vapor por la espalda. */
    private static void exhaust(Player player) {
        if (!(player.level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return;
        }
        net.minecraft.world.phys.Vec3 back = net.minecraft.world.phys.Vec3.directionFromRotation(0.0F, player.yBodyRot).scale(-0.35);
        double x = player.getX() + back.x;
        double y = player.getY() + 0.9;
        double z = player.getZ() + back.z;
        if (player.tickCount % 2 == 0) {
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.CLOUD, x, y, z, 2, 0.08, 0.05, 0.08, 0.02);
        }
        if (player.tickCount % 10 == 0) {
            com.simibubi.create.AllSoundEvents.STEAM.playOnServer(level, player.blockPosition(), 0.12F, 1.7F);
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        if (player.level().isClientSide) {
            return;
        }
        AttributeInstance flight = player.getAttribute(NeoForgeMod.CREATIVE_FLIGHT);
        if (flight == null) {
            return;
        }
        ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
        boolean active = !player.isCreative() && !player.isSpectator() && chest.is(CUSuit.CHESTPLATE.get())
                && BacktankUtil.hasAirRemaining(chest);
        if (active) {
            if (!flight.hasModifier(ID)) {
                flight.addTransientModifier(FLIGHT);
            }
            if (player.getAbilities().flying) {
                if (player.tickCount % 20 == 0) {
                    BacktankUtil.consumeAir(player, chest, 2);
                }
                exhaust(player);
            }
        } else if (flight.hasModifier(ID)) {
            flight.removeModifier(ID);
            if (!player.isCreative() && !player.isSpectator() && !player.mayFly() && player.getAbilities().flying) {
                player.getAbilities().flying = false;
                player.onUpdateAbilities();
            }
        }
    }
}
