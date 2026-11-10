package dev.createuniversal.space;

import com.simibubi.create.AllSoundEvents;
import dev.createuniversal.CreateUniversal;
import dev.ryanhcode.sable.sublevel.SubLevel;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Descompresión explosiva (D52, alpha.7.2): cuando una sala con aire se abre al vacío (una puerta, una trampilla, un
 * bloque roto, una explosión), el aire sale de golpe por la abertura durante unos 2 segundos y arrastra hacia ella, y
 * hacia fuera, a jugadores, criaturas y objetos sueltos. Arrastra más cuanto más cerca de la abertura y cuanto más aire
 * había. Quien va sentado no sale volando. Golpe de viento, siseo de vapor y un chorro de vapor por la abertura.
 * Luego la sala se queda sin aire hasta que se cierre y el oxigenador la vuelva a llenar.
 */
public final class Decompression {
    private static final int DURATION = 40;

    /** Una descompresión en marcha. Abertura, dirección y celdas en coordenadas de la sala (de la nave si va en una). */
    private record Gust(ResourceKey<Level> dimension, UUID ship, LongOpenHashSet cells, Vec3 opening, Vec3 outward,
                        double strength, int[] age) {}

    private static final List<Gust> ACTIVE = new ArrayList<>();

    private Decompression() {}

    static void start(Level level, OxygenZones.Zone zone, BlockPos openingCell) {
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        Vec3 center = zone.center();
        Vec3 opening = openingCell != null ? Vec3.atCenterOf(openingCell) : center;
        Vec3 outward = opening.subtract(center);
        outward = outward.lengthSqr() < 1e-6 ? new Vec3(0, 1, 0) : outward.normalize();
        double strength = 0.35 + 0.65 * Math.min(1.0, zone.filled / 1500.0);
        Gust gust = new Gust(zone.dimension, zone.ship, zone.cells, opening, outward, strength, new int[] {0});
        ACTIVE.add(gust);
        Vec3 world = toWorld(server, gust, opening, false);
        if (world != null) {
            server.playSound(null, world.x, world.y, world.z, SoundEvents.WIND_CHARGE_BURST.value(), SoundSource.BLOCKS, 2.0F, 0.6F);
            AllSoundEvents.STEAM.playOnServer(server, BlockPos.containing(world), 2.0F, 0.5F);
        }
        CreateUniversal.LOGGER.info("Descompresión en {}: sala de {} bloques con {} de aire, abertura en {}", level.dimension().location(),
                zone.cells.size(), Math.round(zone.filled), opening);
    }

    public static void tick(MinecraftServer server) {
        if (ACTIVE.isEmpty()) {
            return;
        }
        ACTIVE.removeIf(g -> ++g.age()[0] > DURATION || !step(server, g));
    }

    /** Un tick de chorro de aire. Falso si ya no tiene sentido (la nave o la dimensión ya no están). */
    private static boolean step(MinecraftServer server, Gust g) {
        ServerLevel level = server.getLevel(g.dimension());
        if (level == null) {
            return false;
        }
        Vec3 opening = toWorld(level, g, g.opening(), false);
        Vec3 out = toWorld(level, g, g.outward(), true);
        if (opening == null || out == null) {
            return false;
        }
        double fade = 1.0 - g.age()[0] / (double) DURATION;
        Vec3 target = opening.add(out.scale(3.0));
        AABB area = new AABB(opening, opening).inflate(24.0);
        for (Entity e : level.getEntities((Entity) null, area, e -> e instanceof LivingEntity || e instanceof ItemEntity)) {
            if (e.isPassenger() || e instanceof Player p && (p.isCreative() || p.isSpectator())) {
                continue;
            }
            double dist = e.position().distanceTo(opening);
            BlockPos cell = OxygenZones.toZone(level, g.ship(), e.position().add(0, e.getBbHeight() * 0.5, 0));
            boolean inside = cell != null && g.cells().contains(cell.asLong());
            if (!inside && dist > 3.0) {
                continue;
            }
            Vec3 dir = target.subtract(e.position().add(0, e.getBbHeight() * 0.5, 0));
            if (dir.lengthSqr() < 1e-6) {
                dir = out;
            }
            double push = g.strength() * fade * 0.22 / (1.0 + dist / 6.0);
            Vec3 v = e.getDeltaMovement().add(dir.normalize().scale(push));
            double speed = v.length();
            if (speed > 1.6) {
                v = v.scale(1.6 / speed);
            }
            e.setDeltaMovement(v);
            e.hurtMarked = true;
        }
        // Chorro de vapor por la abertura, hacia fuera.
        if (g.age()[0] % 2 == 0) {
            for (int i = 0; i < 4; i++) {
                double s = 0.4 + level.random.nextDouble() * 0.6 * fade;
                level.sendParticles(ParticleTypes.CLOUD, opening.x + (level.random.nextDouble() - 0.5) * 0.6,
                        opening.y + (level.random.nextDouble() - 0.5) * 0.6, opening.z + (level.random.nextDouble() - 0.5) * 0.6,
                        0, out.x, out.y, out.z, s);
            }
        }
        return true;
    }

    /** Coordenadas de la sala al mundo (posición o dirección). Null si la nave ya no está. */
    private static Vec3 toWorld(Level level, Gust g, Vec3 v, boolean direction) {
        if (g.ship() == null) {
            return v;
        }
        SubLevel ship = OxygenZones.ship(level, g.ship());
        if (ship == null) {
            return null;
        }
        Vector3d p = new Vector3d(v.x, v.y, v.z);
        if (direction) {
            ship.logicalPose().orientation().transform(p);
        } else {
            ship.logicalPose().transformPosition(p);
        }
        return new Vec3(p.x, p.y, p.z);
    }
}
