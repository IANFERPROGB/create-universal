package dev.createuniversal.space;

import dev.createuniversal.CreateUniversal;
import dev.createuniversal.space.surface.PlanetSurface;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Peligros de cada astro (D29, D87, D88, alpha.7.1). Sin la mejora del traje que pide cada uno, daño poco a poco, para
 * que dé tiempo a volver a la nave; dentro de una sala con oxígeno (nave o base cerrada) no hay peligro.
 *
 * <ul>
 *   <li><b>Frío extremo</b> (aislamiento térmico): Marte, las lunas heladas de Júpiter, Saturno, Urano y Neptuno, y
 *       Mercurio de noche. Te vas congelando como en la nieve polvo.</li>
 *   <li><b>Radiación</b> (escudo de radiación): Ío, Europa y Ganímedes (los cinturones de Júpiter), Mercurio, y en el
 *       espacio cerca del Sol. Hambre y daño cada 2 s.</li>
 *   <li><b>Calor extremo</b> (refrigeración): Mercurio de día e Ío (volcanes). Daño cada segundo.</li>
 *   <li><b>Venus</b> (blindaje de Venus): presión, ácido y 465 °C. Sin él se muere en unos segundos.</li>
 * </ul>
 * La Luna, Fobos y Deimos solo piden el traje base (vacío).
 */
public final class Hazards {
    public static final ResourceKey<DamageType> HEAT = ResourceKey.create(Registries.DAMAGE_TYPE, CreateUniversal.id("heat"));
    public static final ResourceKey<DamageType> RADIATION = ResourceKey.create(Registries.DAMAGE_TYPE, CreateUniversal.id("radiation"));

    private enum Hazard {
        COLD(SuitUpgrades.THERMAL), RADIATION(SuitUpgrades.RADIATION), HEAT(SuitUpgrades.COOLING), VENUS(SuitUpgrades.VENUS);

        final String upgrade;

        Hazard(String upgrade) {
            this.upgrade = upgrade;
        }
    }

    private static final Set<String> COLD_BODIES = Set.of("mars", "europa", "ganymede", "callisto", "mimas", "enceladus", "tethys",
            "dione", "rhea", "titan", "iapetus", "miranda", "ariel", "umbriel", "titania", "oberon", "triton");
    private static final Set<String> RADIATION_BODIES = Set.of("io", "europa", "ganymede", "mercury");
    /** En el espacio, más cerca del Sol que esto (bloques), hay radiación (la órbita de Mercurio está a 93.750). */
    private static final double SUN_RADIATION = 70_000.0;

    private Hazards() {}

    private static List<Hazard> at(ServerPlayer player) {
        List<Hazard> list = new ArrayList<>();
        Level level = player.level();
        Vec3 pos = player.position();
        if (level.dimension() == CUDimensions.SPACE) {
            Vec3 sun = SolarSystem.position(SolarSystem.get("sun"), SpaceLayout.time(level));
            if (pos.distanceTo(sun) < SUN_RADIATION) {
                list.add(Hazard.RADIATION);
            }
            return list;
        }
        PlanetSurface surface = PlanetSurface.byDimension(level.dimension());
        if (surface == null) {
            return list;
        }
        String id = surface.bodyId();
        if (id.equals("venus")) {
            list.add(Hazard.VENUS);
            return list;
        }
        if (COLD_BODIES.contains(id)) {
            list.add(Hazard.COLD);
        }
        if (RADIATION_BODIES.contains(id)) {
            list.add(Hazard.RADIATION);
        }
        if (id.equals("io")) {
            list.add(Hazard.HEAT);
        }
        if (id.equals("mercury")) {
            Sunlight.Sun sun = Sunlight.at(level, pos);
            list.add(sun != null && sun.dir().y > 0.0 ? Hazard.HEAT : Hazard.COLD);
        }
        return list;
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.isCreative() || player.isSpectator() || !player.isAlive()) {
            return;
        }
        if (!Sunlight.handles(player.level())) {
            return;
        }
        List<Hazard> hazards = at(player);
        if (hazards.isEmpty() || OxygenZones.contains(player.level(), player.getEyePosition())) {
            return;
        }
        Hazard warn = null;
        for (Hazard h : hazards) {
            if (SuitUpgrades.protects(player, h.upgrade)) {
                continue;
            }
            warn = warn == null ? h : warn;
            apply(player, h);
        }
        if (warn != null && player.tickCount % 40 == 0) {
            player.displayClientMessage(Component.translatable("create_universal.hazard." + warn.name().toLowerCase(java.util.Locale.ROOT))
                    .withStyle(ChatFormatting.RED), true);
        }
    }

    private static void apply(ServerPlayer player, Hazard hazard) {
        switch (hazard) {
            case COLD -> {
                // La nieve polvo suma 1 por tick y Minecraft quita 2 fuera de ella: con +3 se va congelando poco a poco.
                if (player.canFreeze()) {
                    player.setTicksFrozen(Math.min(player.getTicksRequiredToFreeze() + 20, player.getTicksFrozen() + 3));
                }
            }
            case RADIATION -> {
                if (player.tickCount % 40 == 0) {
                    player.addEffect(new MobEffectInstance(MobEffects.HUNGER, 100, 1, false, false, true));
                    player.hurt(source(player, RADIATION), 1.0F);
                }
            }
            case HEAT -> {
                if (player.tickCount % 20 == 0) {
                    player.hurt(source(player, HEAT), 1.0F);
                }
            }
            case VENUS -> {
                if (player.tickCount % 10 == 0) {
                    player.hurt(source(player, GasGiants.PRESSURE), 2.0F);
                }
            }
        }
    }

    private static DamageSource source(ServerPlayer player, ResourceKey<DamageType> type) {
        return new DamageSource(player.level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(type));
    }
}
