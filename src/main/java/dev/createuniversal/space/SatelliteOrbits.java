package dev.createuniversal.space;

import dev.createuniversal.CreateUniversal;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Modo satélite (D13, sección 4.6): una nave que se queda casi parada junto a un astro empieza a orbitarlo a la altura
 * que tenía, en una órbita circular lenta. Cuanto más grande el astro, más lenta (Tierra ~5 bloques/s, Luna ~3, Júpiter
 * ~18). La órbita se rompe en cuanto la nave cambia de velocidad por su cuenta (propulsores, un choque).
 *
 * <ul>
 *   <li>Astro: el de la zona de influencia ({@link SolarSystem#frameBody}); fuera de todas, el Sol. Fobos y Deimos no.</li>
 *   <li>Se activa tras {@link #CALM_TICKS} casi parada y sin jugadores flotando sueltos cerca (para no dejar atrás a
 *       quien construye o repara la nave por fuera).</li>
 *   <li>Mientras dura, en cada paso de física se le da la aceleración hacia el centro que la mantiene en el círculo,
 *       con una pequeña corrección si se aleja o se acerca.</li>
 *   <li>No entran: las naves fijadas por el Estabilizador orbital, las que esperan tras un viaje, Fobos y Deimos y las
 *       que están posadas en ellos.</li>
 * </ul>
 */
public final class SatelliteOrbits {
    /** Por debajo de esta velocidad respecto al astro la nave cuenta como parada (bloques/s). */
    private static final double CALM_SPEED = 0.5;
    /** Cuánto tiene que estar parada para entrar en órbita. */
    private static final int CALM_TICKS = 200;
    /** Un cambio de velocidad mayor que este (bloques/s) rompe la órbita. */
    private static final double BREAK_DELTA = 1.5;
    /** Vuelta de una nave rozando la Tierra, en segundos; crece con el tamaño del astro y la altura. */
    private static final double EARTH_PERIOD_S = 20 * 60;
    private static final double EARTH_RADIUS = 1056;
    /** Sin jugadores flotando sueltos a menos de esta distancia. */
    private static final double FREE_PLAYER_RANGE = 96;

    private static final class Orbit {
        final SolarSystem.Body body;
        final double radius;
        final double speed;
        final List<UUID> pieces;

        Orbit(SolarSystem.Body body, double radius, double speed, List<UUID> pieces) {
            this.body = body;
            this.radius = radius;
            this.speed = speed;
            this.pieces = pieces;
        }
    }

    /** Órbitas activas, por la primera pieza del grupo. */
    private static final Map<UUID, Orbit> ORBITS = new HashMap<>();
    /** Pieza → órbita de su grupo, para el paso de física. */
    private static final Map<UUID, Orbit> BY_PIECE = new HashMap<>();
    private static final Map<UUID, Integer> CALM = new HashMap<>();

    private SatelliteOrbits() {}

    public static boolean inOrbit(ServerSubLevel ship) {
        return BY_PIECE.containsKey(ship.getUniqueId());
    }

    private static Vec3 center(SolarSystem.Body body, double time) {
        return body != null ? SolarSystem.position(body, time) : new Vec3(0.0, SolarSystem.ECLIPTIC_Y, 0.0);
    }

    private static Vec3 velocity(SolarSystem.Body body, double time) {
        return body != null ? SolarSystem.velocity(body, time) : Vec3.ZERO;
    }

    private static SolarSystem.Body orbitBody(Vec3 pos, double time) {
        SolarSystem.Body body = SolarSystem.frameBody(pos, time);
        if (body != null && ("phobos".equals(body.id()) || "deimos".equals(body.id()))) {
            body = SolarSystem.get(body.parent());
        }
        return body != null ? body : SolarSystem.get("sun");
    }

    /** Velocidad de la órbita circular a esa distancia del centro del astro (bloques/s). */
    static double orbitSpeed(SolarSystem.Body body, double r) {
        double radius = Math.max(body.radius(), 1.0);
        double period = EARTH_PERIOD_S * Math.sqrt(radius / EARTH_RADIUS) * Math.pow(Math.max(r / radius, 1.0), 1.5);
        return 2.0 * Math.PI * r / period;
    }

    /** Cada tick del servidor en el espacio: entra y sale de las órbitas. */
    public static void tick(ServerLevel space) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(space);
        if (container == null) {
            ORBITS.clear();
            BY_PIECE.clear();
            return;
        }
        double time = SpaceLayout.time(space);
        Set<UUID> seen = new HashSet<>();
        Set<UUID> keep = new HashSet<>();
        for (ServerSubLevel ship : new ArrayList<>(container.getAllSubLevels())) {
            if (ship.isRemoved() || seen.contains(ship.getUniqueId())) {
                continue;
            }
            List<ServerSubLevel> group = ShipGroup.of(ship);
            group.forEach(s -> seen.add(s.getUniqueId()));
            UUID key = group.get(0).getUniqueId();
            if (excluded(group)) {
                breakOrbit(key, space, null);
                CALM.remove(key);
                continue;
            }
            RigidBodyHandle handle = container.physicsSystem().getPhysicsHandle(group.get(0));
            if (handle == null || !handle.isValid()) {
                continue;
            }
            Vector3d lv = handle.getLinearVelocity(new Vector3d());
            Vec3 pos = centerOf(group);
            Orbit orbit = ORBITS.get(key);
            if (orbit != null) {
                Vec3 rel = new Vec3(lv.x, lv.y, lv.z).subtract(velocity(orbit.body, time));
                Vec3 radial = pos.subtract(center(orbit.body, time)).normalize();
                double radialSpeed = rel.dot(radial);
                if (Math.abs(rel.length() - orbit.speed) > BREAK_DELTA || Math.abs(radialSpeed) > BREAK_DELTA) {
                    breakOrbit(key, space, group);
                } else {
                    keep.add(key);
                }
                continue;
            }
            SolarSystem.Body body = orbitBody(pos, time);
            Vec3 rel = new Vec3(lv.x, lv.y, lv.z).subtract(velocity(body, time));
            if (rel.length() > CALM_SPEED || freePlayerNear(space, group)) {
                CALM.remove(key);
                continue;
            }
            int calm = CALM.merge(key, 1, Integer::sum);
            if (calm >= CALM_TICKS) {
                CALM.remove(key);
                enterOrbit(space, container, group, body, pos, time);
                keep.add(key);
            }
        }
        ORBITS.keySet().removeIf(k -> {
            if (!keep.contains(k)) {
                Orbit o = ORBITS.get(k);
                o.pieces.forEach(BY_PIECE::remove);
                return true;
            }
            return false;
        });
        CALM.keySet().retainAll(seen);
    }

    private static boolean excluded(List<ServerSubLevel> group) {
        for (ServerSubLevel s : group) {
            if (OrbitalStabilizers.isPinned(s) || ShipAnchor.isHeld(s) || Moonlets.isMoonlet(s) || MoonletGrip.isStuck(s)) {
                return true;
            }
        }
        return false;
    }

    private static boolean freePlayerNear(ServerLevel level, List<ServerSubLevel> group) {
        Vec3 c = centerOf(group);
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator() || p.position().distanceToSqr(c) > FREE_PLAYER_RANGE * FREE_PLAYER_RANGE) {
                continue;
            }
            if (p.getVehicle() == null && !aboard(p, group)) {
                return true;
            }
        }
        return false;
    }

    private static boolean aboard(ServerPlayer p, List<ServerSubLevel> group) {
        for (ServerSubLevel s : group) {
            BoundingBox3dc b = s.boundingBox();
            if (new AABB(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()).inflate(1.5).contains(p.position())) {
                return true;
            }
        }
        return false;
    }

    private static void enterOrbit(ServerLevel level, ServerSubLevelContainer container, List<ServerSubLevel> group,
                                   SolarSystem.Body body, Vec3 pos, double time) {
        Vec3 d = pos.subtract(center(body, time));
        double r = d.length();
        if (r < 1.0) {
            return;
        }
        Vec3 radial = d.scale(1.0 / r);
        // Sentido de las órbitas del sistema solar (antihorario visto desde arriba); sobre un polo, hacia +X.
        Vec3 axis = Math.abs(radial.y) > 0.95 ? new Vec3(0, 0, 1) : new Vec3(0, 1, 0);
        Vec3 tangent = axis.cross(radial).normalize();
        double speed = orbitSpeed(body, r);
        List<UUID> pieces = new ArrayList<>();
        Vector3d kick = new Vector3d(tangent.x * speed, tangent.y * speed, tangent.z * speed);
        for (ServerSubLevel s : group) {
            pieces.add(s.getUniqueId());
            RigidBodyHandle h = container.physicsSystem().getPhysicsHandle(s);
            if (h != null && h.isValid()) {
                h.addLinearAndAngularVelocity(kick, new Vector3d());
            }
        }
        Orbit orbit = new Orbit(body, r, speed, pieces);
        ORBITS.put(group.get(0).getUniqueId(), orbit);
        pieces.forEach(id -> BY_PIECE.put(id, orbit));
        Component name = Component.translatable("create_universal.body." + body.id());
        tell(level, group, Component.translatable("create_universal.message.orbit_enter", name,
                String.format(java.util.Locale.ROOT, "%.1f", speed)).withStyle(ChatFormatting.AQUA));
        CreateUniversal.LOGGER.info("Nave {} en órbita de {} a {} bloques del centro, {} b/s", group.get(0).getUniqueId(),
                body.id(), Math.round(r), String.format(java.util.Locale.ROOT, "%.2f", speed));
    }

    private static void breakOrbit(UUID key, ServerLevel level, List<ServerSubLevel> group) {
        Orbit orbit = ORBITS.remove(key);
        if (orbit == null) {
            return;
        }
        orbit.pieces.forEach(BY_PIECE::remove);
        if (group != null) {
            tell(level, group, Component.translatable("create_universal.message.orbit_leave").withStyle(ChatFormatting.GRAY));
        }
    }

    private static void tell(ServerLevel level, List<ServerSubLevel> group, Component message) {
        for (ServerPlayer p : level.players()) {
            if (aboard(p, group) || (p.getVehicle() != null && aboard(p, group))) {
                p.displayClientMessage(message, true);
            }
        }
    }

    /** Paso de física: la aceleración hacia el centro que mantiene el círculo. */
    public static void prePhysicsTick(SubLevelPhysicsSystem system, double timeStep) {
        if (BY_PIECE.isEmpty() || system.getLevel().dimension() != CUDimensions.SPACE) {
            return;
        }
        ServerSubLevelContainer container = SubLevelContainer.getContainer(system.getLevel());
        if (container == null) {
            return;
        }
        double time = SpaceLayout.time(system.getLevel());
        for (Orbit orbit : ORBITS.values()) {
            List<ServerSubLevel> pieces = new ArrayList<>();
            for (UUID id : orbit.pieces) {
                SubLevel s = container.getSubLevel(id);
                if (s instanceof ServerSubLevel ship && !ship.isRemoved()) {
                    pieces.add(ship);
                }
            }
            if (pieces.isEmpty()) {
                continue;
            }
            RigidBodyHandle first = system.getPhysicsHandle(pieces.get(0));
            if (first == null || !first.isValid()) {
                continue;
            }
            Vec3 d = centerOf(pieces).subtract(center(orbit.body, time));
            double r = d.length();
            if (r < 1.0) {
                continue;
            }
            Vec3 radial = d.scale(1.0 / r);
            Vector3d lv = first.getLinearVelocity(new Vector3d());
            Vec3 rel = new Vec3(lv.x, lv.y, lv.z).subtract(velocity(orbit.body, time));
            double vr = rel.dot(radial);
            double vt2 = Math.max(rel.lengthSqr() - vr * vr, 0.0);
            // Centrípeta para la velocidad que lleva, más un muelle suave hacia el radio de la órbita.
            double a = -vt2 / r - 0.02 * (r - orbit.radius) - 0.3 * vr;
            Vector3d dv = new Vector3d(radial.x, radial.y, radial.z).mul(a * timeStep);
            for (ServerSubLevel ship : pieces) {
                RigidBodyHandle h = system.getPhysicsHandle(ship);
                if (h != null && h.isValid()) {
                    h.addLinearAndAngularVelocity(dv, new Vector3d());
                }
            }
        }
    }

    private static Vec3 centerOf(List<ServerSubLevel> group) {
        double x = 0, y = 0, z = 0;
        for (ServerSubLevel s : group) {
            BoundingBox3dc b = s.boundingBox();
            x += (b.minX() + b.maxX()) * 0.5;
            y += (b.minY() + b.maxY()) * 0.5;
            z += (b.minZ() + b.maxZ()) * 0.5;
        }
        return new Vec3(x / group.size(), y / group.size(), z / group.size());
    }
}
