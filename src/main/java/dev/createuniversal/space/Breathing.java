package dev.createuniversal.space;

import com.maxenonyme.createsubmarine.submarine.compartment.CompartmentTracker;
import dev.createuniversal.CreateUniversal;
import dev.createuniversal.registry.CUSuit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingBreatheEvent;
import net.neoforged.neoforge.event.entity.living.LivingDrownEvent;

/**
 * Respirar fuera de la Tierra (D9, alpha.7.0). En el espacio y en todos los astros no hay aire que se pueda respirar
 * (Marte, Venus y Titán tienen atmósfera, pero no de oxígeno). Se respira:
 *
 * <ul>
 *   <li>dentro de un compartimento sellado de una nave con difusor de oxígeno de Create Deep Seas, o</li>
 *   <li>con el traje espacial completo y oxígeno en la pechera. El casco del traje es un casco de buceo de Create, así
 *       que es Create quien saca el aire de la pechera (1 por segundo), avisa cuando queda poco y lo cuenta.</li>
 * </ul>
 * El equipo de buceo normal de Create no basta en el vacío (no aguanta la falta de presión): hace falta el traje entero.
 * Sin aire se pierde el aliento el doble de rápido que bajo el agua y luego se recibe daño poco a poco (D88).
 */
public final class Breathing {
    public static final ResourceKey<DamageType> VACUUM = ResourceKey.create(Registries.DAMAGE_TYPE, CreateUniversal.id("vacuum"));

    private Breathing() {}

    /** ¿Está esta criatura donde no hay aire respirable, y le afecta? */
    public static boolean withoutAir(LivingEntity entity) {
        Level level = entity.level();
        if (!Sunlight.handles(level)) {
            return false;
        }
        if (entity instanceof Player p && (p.isCreative() || p.isSpectator())) {
            return false;
        }
        if (entity.canBreatheUnderwater()) {
            return false;
        }
        return !hasAirAt(level, entity.getEyePosition());
    }

    /** Aire respirable en ese punto: en la Tierra siempre; fuera, solo en un compartimento sellado con oxígeno. */
    public static boolean hasAirAt(Level level, Vec3 pos) {
        if (!Sunlight.handles(level)) {
            return true;
        }
        try {
            return CompartmentTracker.isInSealedExact(level, pos);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    public static boolean hasAirAt(Level level, BlockPos pos) {
        return hasAirAt(level, Vec3.atCenterOf(pos));
    }

    /** Primero: aquí no se respira. Así el casco de buceo del traje (de Create) saca el aire de la pechera. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void noAirFirst(LivingBreatheEvent event) {
        if (withoutAir(event.getEntity())) {
            event.setCanBreathe(false);
            event.setRefillAirAmount(0);
            event.setConsumeAirAmount(consume(event.getEntity()));
        }
    }

    /** El aliento se va el doble de rápido que bajo el agua; ya sin aliento, el daño llega una vez por segundo. */
    private static int consume(LivingEntity entity) {
        return entity.getAirSupply() > 0 ? 2 : 1;
    }

    /** Al final: si no lleva el traje entero, el equipo de buceo de Create no le salva. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void noAirLast(LivingBreatheEvent event) {
        LivingEntity entity = event.getEntity();
        if (event.canBreathe() && withoutAir(entity) && !CUSuit.wearsFullSuit(entity)) {
            event.setCanBreathe(false);
            event.setRefillAirAmount(0);
            event.setConsumeAirAmount(consume(entity));
        }
    }

    /** Sin aire, el daño es de asfixia en el vacío, no de ahogarse. */
    @SubscribeEvent
    public static void vacuumDamage(LivingDrownEvent event) {
        LivingEntity entity = event.getEntity();
        if (!event.isDrowning() || entity.isInWater() || !withoutAir(entity)) {
            return;
        }
        event.setCanceled(true);
        entity.setAirSupply(0);
        if (!entity.level().isClientSide) {
            entity.hurt(new DamageSource(entity.level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(VACUUM)),
                    event.getDamageAmount());
        }
    }
}
