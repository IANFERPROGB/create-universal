package dev.createuniversal.space;

import dev.createuniversal.CreateUniversal;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Los gigantes gaseosos no tienen suelo (idea 16, versión sencilla; la destrucción completa con restos es la alpha.9):
 * al acercarse a sus nubes sale un aviso, y lo que se mete en ellas lo aplasta la presión: la nave revienta como en los
 * choques ({@link ShipDestruction}) y quien vaya dentro muere, salvo con el traje completo.
 */
public final class GasGiants {
    public static final ResourceKey<DamageType> PRESSURE = ResourceKey.create(Registries.DAMAGE_TYPE, CreateUniversal.id("pressure"));
    private static final String[] GIANTS = {"jupiter", "saturn", "uranus", "neptune"};
    /** A esta distancia de las nubes empieza el aviso. */
    private static final double WARNING = 500.0;
    /** Cuánto se puede hundir el centro de la nave en las nubes antes de que la aplaste la presión. */
    private static final double CRUSH_DEPTH = 20.0;

    private GasGiants() {}

    public static void tick(ServerLevel space, double time) {
        boolean warnNow = space.getGameTime() % 20 == 0;
        for (String id : GIANTS) {
            SolarSystem.Body body = SolarSystem.get(id);
            if (body == null) {
                continue;
            }
            Vec3 center = SolarSystem.position(body, time);
            double crush = body.radius() - CRUSH_DEPTH;
            Component name = Component.translatable("create_universal.body." + id);

            for (List<ServerSubLevel> group : groupsWithin(space, center, crush)) {
                crush(space, group, name);
            }
            for (ServerPlayer player : space.players()) {
                double d = player.position().distanceTo(center);
                if (d < crush && player.isAlive() && !player.isSpectator()) {
                    player.hurt(pressure(space), Float.MAX_VALUE);
                } else if (warnNow && d < body.radius() + WARNING) {
                    player.displayClientMessage(Component.translatable("create_universal.message.gas_giant_warning", name)
                            .withStyle(ChatFormatting.RED), true);
                }
            }
        }
    }

    private static DamageSource pressure(ServerLevel level) {
        return new DamageSource(level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(PRESSURE));
    }

    /** Naves enteras (con sus piezas unidas) con alguna pieza dentro de la esfera. */
    private static List<List<ServerSubLevel>> groupsWithin(ServerLevel level, Vec3 center, double radius) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        List<List<ServerSubLevel>> result = new ArrayList<>();
        if (container == null) {
            return result;
        }
        Set<UUID> taken = new HashSet<>();
        for (ServerSubLevel ship : new ArrayList<>(container.getAllSubLevels())) {
            if (ship.isRemoved() || taken.contains(ship.getUniqueId()) || centerOf(ship).distanceTo(center) >= radius
                    || Moonlets.isMoonlet(ship)) {
                continue;
            }
            List<ServerSubLevel> group = ShipGroup.of(ship);
            group.forEach(s -> taken.add(s.getUniqueId()));
            result.add(group);
        }
        return result;
    }

    private static void crush(ServerLevel level, List<ServerSubLevel> group, Component name) {
        if (ShipDestruction.isDebris(group)) {
            group.forEach(piece -> ShipDestruction.burnUp(level, piece));
            return;
        }
        ShipDestruction.destroy(level, group, PRESSURE, Component.translatable("create_universal.message.gas_giant_crushed", name));
    }

    private static boolean plotContains(List<ServerSubLevel> group, Entity e) {
        for (ServerSubLevel ship : group) {
            if (ship.getPlot().contains(e.position())) {
                return true;
            }
        }
        return false;
    }

    private static Vec3 centerOf(ServerSubLevel ship) {
        BoundingBox3dc b = ship.boundingBox();
        return new Vec3((b.minX() + b.maxX()) * 0.5, (b.minY() + b.maxY()) * 0.5, (b.minZ() + b.maxZ()) * 0.5);
    }
}
