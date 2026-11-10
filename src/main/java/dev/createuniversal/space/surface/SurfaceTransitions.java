package dev.createuniversal.space.surface;

import dev.createuniversal.space.SpaceGate;

import dev.createuniversal.CreateUniversal;
import dev.createuniversal.network.TransitionEffectPayload;
import dev.createuniversal.space.CUDimensions;
import dev.createuniversal.space.ShipTransfer;
import dev.createuniversal.space.SolarSystem;
import dev.createuniversal.space.SpaceLayout;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Paso entre el espacio y las superficies de la Luna y Marte (alpha.5, sección 4.8).
 *
 * <ul>
 *   <li>Bajada: al tocar {@link PlanetSurface#entryAltitude()} bloques sobre la esfera del astro en el espacio se aparece
 *       en el punto del globo al que se bajaba (latitud y longitud reales, D74), en el cielo de su dimensión.</li>
 *   <li>Subida: al pasar de {@link PlanetSurface#exitY()} se vuelve al espacio en órbita sobre ese mismo punto.</li>
 * </ul>
 * La Tierra sigue siendo el Overworld y va por {@code SpaceTransitions}.
 */
public final class SurfaceTransitions {
    /** Altura de llegada bajo la salida, para no volver a salir nada más llegar. */
    private static final int ARRIVAL_BELOW_EXIT = 150;
    /** Se llega a esta altura sobre el suelo real de ese punto (el suelo puede estar 2.000 bloques más abajo que la salida). */
    private static final double ARRIVAL_ABOVE_GROUND = 300.0;
    /** Margen sobre la altura de entrada al volver al espacio, para no volver a bajar en el acto. */
    private static final double SPACE_MARGIN = 120.0;

    private SurfaceTransitions() {}

    /** Velocidad (bloques/s, en el espacio) del sitio de cada dimensión: la del astro, la de la Tierra o 0 en el espacio. */
    public static Vec3 frameVelocity(Level level, double time) {
        if (level.dimension() == Level.OVERWORLD) {
            return SpaceLayout.earthVelocity(time);
        }
        PlanetSurface surface = PlanetSurface.byDimension(level.dimension());
        return surface != null ? SolarSystem.velocity(surface.body(), time) : Vec3.ZERO;
    }

    // ---------------------------------------------------------------- naves

    public static void tickShips(MinecraftServer server, ServerLevel space) {
        double time = SpaceLayout.time(space);
        for (PlanetSurface surface : PlanetSurface.ALL) {
            ServerLevel ground = server.getLevel(surface.dimension());
            if (ground == null) {
                continue;
            }
            SolarSystem.Body body = surface.body();
            Vec3 center = SolarSystem.position(body, time);
            double entry = body.radius() + surface.entryAltitude();
            for (ServerSubLevel ship : roots(space, s -> centerOf(s).distanceTo(center) < entry)) {
                Vec3 target = landingPoint(surface, centerOf(ship), time);
                safeTransfer(ship, ground, target);
            }
            for (ServerSubLevel ship : roots(ground, s -> centerOf(s).y >= surface.exitY()
                    && !dev.createuniversal.space.LandingLock.mustLand(dev.createuniversal.space.ShipGroup.of(s)))) {
                Vec3 c = centerOf(ship);
                safeTransfer(ship, space, orbitPoint(surface, c.x, c.z, time));
            }
        }
    }

    // ---------------------------------------------------------------- dar la vuelta al planeta (D75)

    /**
     * Si algo pasa del borde este u oeste del mapa aparece por el otro lado; si cruza un polo, aparece al otro lado del
     * polo (media vuelta de longitud) mirando hacia atrás, como al cruzar un polo de verdad.
     */
    public static void tickWrap(MinecraftServer server) {
        for (PlanetSurface surface : PlanetSurface.ALL) {
            ServerLevel level = server.getLevel(surface.dimension());
            if (level == null) {
                continue;
            }
            for (ServerPlayer player : new ArrayList<>(level.players())) {
                if (player.isPassenger()) {
                    continue;
                }
                double[] w = wrap(surface, player.getX(), player.getZ());
                if (w != null) {
                    float yaw = player.getYRot() + (float) w[2];
                    SpaceGate.travel(() -> player.teleportTo(level, w[0], player.getY(), w[1], yaw, player.getXRot()));
                }
            }
            for (ServerSubLevel ship : roots(level, s -> {
                Vec3 c = centerOf(s);
                return wrap(surface, c.x, c.z) != null;
            })) {
                Vec3 c = centerOf(ship);
                double[] w = wrap(surface, c.x, c.z);
                safeTransfer(ship, level, new Vec3(w[0], c.y, w[1]));
            }
        }
    }

    /** Nueva x, z y giro (grados) si (x, z) se ha salido del mapa; null si sigue dentro. */
    static double[] wrap(PlanetSurface surface, double x, double z) {
        double hw = surface.halfWidth();
        double hh = surface.halfHeight();
        double turn = 0.0;
        boolean moved = false;
        if (z < -hh || z > hh) {
            z = z < -hh ? -2.0 * hh - z : 2.0 * hh - z;
            x += hw;
            turn = 180.0;
            moved = true;
        }
        if (x > hw || x < -hw) {
            x = x - 2.0 * hw * Math.floor((x + hw) / (2.0 * hw));
            moved = true;
        }
        return moved ? new double[] {x, z, turn} : null;
    }

    // ---------------------------------------------------------------- jugadores sueltos

    /** @return true si ha movido al jugador */
    public static boolean tickPlayer(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        MinecraftServer server = player.server;
        if (level.dimension() == CUDimensions.SPACE) {
            double time = SpaceLayout.time(level);
            for (PlanetSurface surface : PlanetSurface.ALL) {
                SolarSystem.Body body = surface.body();
                if (player.position().distanceTo(SolarSystem.position(body, time)) < body.radius() + surface.entryAltitude()) {
                    ServerLevel ground = server.getLevel(surface.dimension());
                    if (ground != null) {
                        Vec3 target = landingPoint(surface, player.position(), time);
                        move(player, ground, target, frameVelocity(level, time).subtract(frameVelocity(ground, time)), TransitionEffectPayload.REENTRY);
                        return true;
                    }
                }
            }
            return false;
        }
        PlanetSurface surface = PlanetSurface.byDimension(level.dimension());
        if (surface != null && player.getY() >= surface.exitY() && !dev.createuniversal.space.LandingLock.playerMustLand(player)) {
            ServerLevel space = server.getLevel(CUDimensions.SPACE);
            if (space != null) {
                double time = SpaceLayout.time(space);
                Vec3 target = orbitPoint(surface, player.getX(), player.getZ(), time);
                move(player, space, target, frameVelocity(level, time), TransitionEffectPayload.EXIT);
                return true;
            }
        }
        return false;
    }

    private static void move(ServerPlayer player, ServerLevel target, Vec3 pos, Vec3 frameShift, int effect) {
        Vec3 motion = player.getDeltaMovement().add(frameShift.scale(1.0 / 20.0));
        CreateUniversal.LOGGER.info("{} pasa de {} a {} en {}", player.getScoreboardName(), player.level().dimension().location(),
                target.dimension().location(), pos);
        SpaceGate.travel(() -> player.changeDimension(new DimensionTransition(target, pos, motion, player.getYRot(), player.getXRot(), DimensionTransition.DO_NOTHING)));
        player.setDeltaMovement(motion);
        player.connection.send(new ClientboundSetEntityMotionPacket(player));
        PacketDistributor.sendToPlayer(player, new TransitionEffectPayload(effect));
        if (target.dimension() != CUDimensions.SPACE) {
            dev.createuniversal.space.LandingLock.markPlayer(player);
        }
    }

    // ---------------------------------------------------------------- del globo al mapa y al revés

    /** Punto del mapa de la superficie bajo una posición del espacio (latitud y longitud del astro girando). */
    public static Vec3 landingPoint(PlanetSurface surface, Vec3 spacePos, double time) {
        SolarSystem.Body body = surface.body();
        Vec3 rel = spacePos.subtract(SolarSystem.position(body, time));
        Quaterniond q = SolarSystem.orientation(body, time, true, 0.0);
        Vector3d local = new Quaterniond(q).conjugate().transform(new Vector3d(rel.x, rel.y, rel.z));
        double[] latLon = SolarSystem.latLon(local);
        double x = surface.x(latLon[1]);
        double z = surface.z(latLon[0]);
        double ground = PlanetTerrain.get(surface).surfaceY(x, z);
        double y = Math.min(surface.exitY() - ARRIVAL_BELOW_EXIT, ground + ARRIVAL_ABOVE_GROUND);
        return new Vec3(x, y, z);
    }

    /** Punto del espacio en órbita sobre un sitio del mapa de la superficie. */
    public static Vec3 orbitPoint(PlanetSurface surface, double x, double z, double time) {
        SolarSystem.Body body = surface.body();
        Quaterniond q = SolarSystem.orientation(body, time, true, 0.0);
        Vector3d dir = q.transform(SolarSystem.surfacePoint(surface.latitude(z), surface.longitude(x)));
        double r = body.radius() + surface.entryAltitude() + SPACE_MARGIN;
        return SolarSystem.position(body, time).add(dir.x * r, dir.y * r, dir.z * r);
    }

    // ---------------------------------------------------------------- utilidades

    private interface ShipTest {
        boolean test(ServerSubLevel ship);
    }

    private static List<ServerSubLevel> roots(ServerLevel level, ShipTest test) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        List<ServerSubLevel> result = new ArrayList<>();
        if (container == null) {
            return result;
        }
        Set<UUID> taken = new HashSet<>();
        for (ServerSubLevel ship : new ArrayList<>(container.getAllSubLevels())) {
            if (ship.isRemoved() || taken.contains(ship.getUniqueId()) || dev.createuniversal.space.Moonlets.isMoonlet(ship)
                    || !test.test(ship)) {
                continue;
            }
            List<ServerSubLevel> group = dev.createuniversal.space.ShipGroup.of(ship);
            group.forEach(s -> taken.add(s.getUniqueId()));
            if (dev.createuniversal.space.ShipGroup.settled(group, level)) {
                result.add(ship);
            }
        }
        return result;
    }

    private static Vec3 centerOf(ServerSubLevel ship) {
        BoundingBox3dc b = ship.boundingBox();
        return new Vec3((b.minX() + b.maxX()) * 0.5, (b.minY() + b.maxY()) * 0.5, (b.minZ() + b.maxZ()) * 0.5);
    }

    private static void safeTransfer(ServerSubLevel ship, ServerLevel target, Vec3 position) {
        try {
            ShipTransfer.transfer(ship, target, position, new CompoundTag());
        } catch (RuntimeException e) {
            CreateUniversal.LOGGER.error("Fallo al mover la nave {} a {}", ship.getUniqueId(), target.dimension().location(), e);
        }
    }
}
