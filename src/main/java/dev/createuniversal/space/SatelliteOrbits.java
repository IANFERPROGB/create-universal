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
 * Modo satélite (D13, sección 4.6; rehecho en la alpha.9.5): una nave que está en la zona de un
 * planeta o una luna y no usa propulsores entra sola en órbita a la altura a la que esté, y orbita para siempre. Para
 * salir hay que encender los propulsores; al dejar de empujar unos segundos, si sigue en la zona, vuelve a quedar en
 * órbita a la nueva altura.
 *
 * <ul>
 *   <li><b>Zona:</b> la de influencia del astro ({@link SolarSystem#frameBody}). Fobos y Deimos cuentan como Marte; lejos de
 *       todo (solo el Sol) no hay órbita.</li>
 *   <li><b>Órbita:</b> circular, a la distancia de la captura, en el plano que llevaba la nave (o el del astro si iba
 *       parada). Velocidad: Tierra ~5 b/s, Luna ~3, Júpiter ~18 ({@link #orbitSpeed}).</li>
 *   <li><b>Para siempre:</b> en cada paso de física se le pone a la nave la velocidad de la órbita (más la del astro, que
 *       también se mueve). Así no se va frenando ni se pierde aunque el motor de física la duerma. La captura es suave
 *       (unos 2 s).</li>
 *   <li><b>Propulsores:</b> si entre un paso y el siguiente la nave cambia de velocidad por su cuenta (empuje, choque),
 *       se suelta. Se vuelve a capturar tras {@link #CAPTURE_TICKS} sin empuje.</li>
 *   <li>No entran: las naves fijadas por el Estabilizador orbital, las que esperan tras un viaje, Fobos y Deimos, las
 *       posadas en ellos y los restos.</li>
 * </ul>
 */
public final class SatelliteOrbits {
    /** Ticks sin empuje para entrar (o volver a entrar) en órbita. */
    private static final int CAPTURE_TICKS = 60;
    /** Aceleración propia (bloques/s²) que cuenta como empuje: por encima, la nave se suelta de la órbita. */
    private static final double THRUST_ACCEL = 0.6;
    /** Segundos que tarda la captura en llevar la nave a la velocidad de la órbita. */
    private static final double BLEND_SECONDS = 2.0;
    /** Vuelta de una nave rozando la Tierra, en segundos; crece con el tamaño del astro y la altura. */
    private static final double EARTH_PERIOD_S = 20 * 60;
    private static final double EARTH_RADIUS = 1056;

    private static final class Orbit {
        final SolarSystem.Body body;
        final double radius;
        final double speed;
        /** Normal del plano de la órbita: la dirección de avance es eje × radial. */
        final Vec3 axis;
        final List<UUID> pieces;
        double elapsed;
        Vector3d lastSet;
        volatile boolean broken;

        Orbit(SolarSystem.Body body, double radius, Vec3 axis, List<UUID> pieces) {
            this.body = body;
            this.radius = radius;
            this.speed = orbitSpeed(body, radius);
            this.axis = axis;
            this.pieces = pieces;
        }
    }

    /** Órbitas activas, por la clave de su grupo de piezas. */
    private static final Map<UUID, Orbit> ORBITS = new HashMap<>();
    private static final Map<UUID, Orbit> BY_PIECE = new HashMap<>();
    /** Ticks seguidos sin empuje de cada grupo que no está en órbita. */
    private static final Map<UUID, Integer> CALM = new HashMap<>();
    /** Velocidad del tick anterior, para ver si empuja. */
    private static final Map<UUID, Vec3> LAST_VELOCITY = new HashMap<>();

    private SatelliteOrbits() {}

    public static boolean inOrbit(ServerSubLevel ship) {
        return BY_PIECE.containsKey(ship.getUniqueId());
    }

    private static Vec3 center(SolarSystem.Body body, double time) {
        return SolarSystem.position(body, time);
    }

    /** El planeta o la luna de cuya zona es {@code pos}, o null (Fobos y Deimos cuentan como Marte). */
    private static SolarSystem.Body orbitBody(Vec3 pos, double time) {
        SolarSystem.Body body = SolarSystem.frameBody(pos, time);
        if (body != null && ("phobos".equals(body.id()) || "deimos".equals(body.id()))) {
            body = SolarSystem.get(body.parent());
        }
        return body == null || body.isSun() ? null : body;
    }

    /** Velocidad de la órbita circular a esa distancia del centro del astro (bloques/s). */
    static double orbitSpeed(SolarSystem.Body body, double r) {
        double radius = Math.max(body.radius(), 1.0);
        double period = EARTH_PERIOD_S * Math.sqrt(radius / EARTH_RADIUS) * Math.pow(Math.max(r / radius, 1.0), 1.5);
        return 2.0 * Math.PI * r / period;
    }

    /** Clave estable de un grupo de piezas: el menor de sus UUID. */
    private static UUID key(List<ServerSubLevel> group) {
        UUID best = null;
        for (ServerSubLevel s : group) {
            if (best == null || s.getUniqueId().compareTo(best) < 0) {
                best = s.getUniqueId();
            }
        }
        return best;
    }

    /** Cada tick del servidor en el espacio: capturas y salidas de órbita. */
    public static void tick(ServerLevel space) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(space);
        if (container == null) {
            ORBITS.clear();
            BY_PIECE.clear();
            return;
        }
        double time = SpaceLayout.time(space);
        Set<UUID> seen = new HashSet<>();
        Set<UUID> keys = new HashSet<>();
        for (ServerSubLevel ship : new ArrayList<>(container.getAllSubLevels())) {
            if (ship.isRemoved() || seen.contains(ship.getUniqueId())) {
                continue;
            }
            List<ServerSubLevel> group = ShipGroup.of(ship);
            group.forEach(s -> seen.add(s.getUniqueId()));
            UUID key = key(group);
            keys.add(key);
            Orbit orbit = ORBITS.get(key);
            Vec3 pos = centerOf(group);
            SolarSystem.Body body = orbitBody(pos, time);
            if (excluded(group) || body == null) {
                release(key, space, null, false);
                CALM.remove(key);
                LAST_VELOCITY.remove(key);
                continue;
            }
            if (orbit != null) {
                if (orbit.broken || orbit.body != body) {
                    release(key, space, group, true);
                }
                continue;
            }
            RigidBodyHandle handle = container.physicsSystem().getPhysicsHandle(group.get(0));
            if (handle == null || !handle.isValid()) {
                continue;
            }
            Vector3d lv = handle.getLinearVelocity(new Vector3d());
            Vec3 velocity = new Vec3(lv.x, lv.y, lv.z);
            Vec3 last = LAST_VELOCITY.put(key, velocity);
            // Lo que cambia la velocidad de un tick a otro, quitando lo que la cambia el astro (que también acelera).
            Vec3 bodyChange = SolarSystem.acceleration(body, time).scale(1.0 / 20.0);
            boolean pushing = last != null && velocity.subtract(last).subtract(bodyChange).length() * 20.0 > THRUST_ACCEL;
            int calm = pushing ? 0 : CALM.getOrDefault(key, 0) + 1;
            CALM.put(key, calm);
            if (calm >= CAPTURE_TICKS) {
                CALM.remove(key);
                capture(space, group, key, body, pos, velocity, time);
            }
        }
        ORBITS.keySet().removeIf(k -> {
            if (!keys.contains(k)) {
                ORBITS.get(k).pieces.forEach(BY_PIECE::remove);
                return true;
            }
            return false;
        });
        CALM.keySet().retainAll(keys);
        LAST_VELOCITY.keySet().retainAll(keys);
    }

    private static boolean excluded(List<ServerSubLevel> group) {
        for (ServerSubLevel s : group) {
            if (OrbitalStabilizers.isPinned(s) || ShipAnchor.isHeld(s) || Moonlets.isMoonlet(s) || MoonletGrip.isStuck(s)
                    || ShipDestruction.isDebris(s)) {
                return true;
            }
        }
        return false;
    }

    private static void capture(ServerLevel level, List<ServerSubLevel> group, UUID key, SolarSystem.Body body, Vec3 pos,
                                Vec3 velocity, double time) {
        Vec3 d = pos.subtract(center(body, time));
        double r = d.length();
        if (r < body.radius()) {
            return;
        }
        Vec3 radial = d.scale(1.0 / r);
        // Plano de la órbita: el del movimiento que llevaba la nave respecto al astro; si iba casi parada, el del astro
        // (antihorario visto desde arriba, como el sistema solar; sobre un polo, otro).
        Vec3 rel = velocity.subtract(SolarSystem.velocity(body, time));
        Vec3 tangential = rel.subtract(radial.scale(rel.dot(radial)));
        Vec3 axis;
        if (tangential.length() > 0.3) {
            axis = radial.cross(tangential).normalize();
        } else {
            Vec3 up = Math.abs(radial.y) > 0.95 ? new Vec3(0, 0, 1) : new Vec3(0, 1, 0);
            axis = up.subtract(radial.scale(up.dot(radial))).normalize();
        }
        List<UUID> pieces = new ArrayList<>();
        group.forEach(s -> pieces.add(s.getUniqueId()));
        Orbit orbit = new Orbit(body, r, axis, pieces);
        ORBITS.put(key, orbit);
        pieces.forEach(id -> BY_PIECE.put(id, orbit));
        Component name = Component.translatable("create_universal.body." + body.id());
        tell(level, group, Component.translatable("create_universal.message.orbit_enter", name,
                String.format(java.util.Locale.ROOT, "%.1f", orbit.speed)).withStyle(ChatFormatting.AQUA));
        CreateUniversal.LOGGER.info("Nave {} en órbita de {} a {} bloques del centro ({} sobre el suelo), {} b/s", key, body.id(),
                Math.round(r), Math.round(r - body.radius()), String.format(java.util.Locale.ROOT, "%.2f", orbit.speed));
    }

    private static void release(UUID key, ServerLevel level, List<ServerSubLevel> group, boolean announce) {
        Orbit orbit = ORBITS.remove(key);
        if (orbit == null) {
            return;
        }
        orbit.pieces.forEach(BY_PIECE::remove);
        if (announce && group != null) {
            tell(level, group, Component.translatable("create_universal.message.orbit_leave").withStyle(ChatFormatting.GRAY));
            CreateUniversal.LOGGER.info("Nave {} sale de la órbita de {}", key, orbit.body.id());
        }
    }

    private static void tell(ServerLevel level, List<ServerSubLevel> group, Component message) {
        for (ServerPlayer p : level.players()) {
            for (ServerSubLevel s : group) {
                BoundingBox3dc b = s.boundingBox();
                if (new AABB(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()).inflate(2.0).contains(p.position())) {
                    p.displayClientMessage(message, true);
                    break;
                }
            }
        }
    }

    /** Paso de física: la velocidad de la órbita, y si la nave empuja por su cuenta, se suelta. */
    public static void prePhysicsTick(SubLevelPhysicsSystem system, double timeStep) {
        if (ORBITS.isEmpty() || system.getLevel().dimension() != CUDimensions.SPACE) {
            return;
        }
        ServerSubLevelContainer container = SubLevelContainer.getContainer(system.getLevel());
        if (container == null) {
            return;
        }
        double time = SpaceLayout.time(system.getLevel());
        for (Orbit orbit : ORBITS.values()) {
            if (orbit.broken) {
                continue;
            }
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
            Vector3d current = first.getLinearVelocity(new Vector3d());
            // ¿Ha cambiado de velocidad por su cuenta desde el paso anterior? (propulsores, un choque)
            if (orbit.lastSet != null && timeStep > 0.0
                    && new Vector3d(current).sub(orbit.lastSet).length() / timeStep > THRUST_ACCEL * 2.0) {
                orbit.broken = true;
                continue;
            }
            Vec3 d = centerOf(pieces).subtract(center(orbit.body, time));
            double r = d.length();
            if (r < 1.0) {
                continue;
            }
            Vec3 radial = d.scale(1.0 / r);
            Vec3 tangent = orbit.axis.cross(radial);
            if (tangent.lengthSqr() < 1.0e-6) {
                continue;
            }
            tangent = tangent.normalize();
            // Velocidad de la órbita más una corrección suave hacia su radio, más la del astro.
            double back = Math.max(-2.0, Math.min(2.0, -0.5 * (r - orbit.radius)));
            Vec3 target = tangent.scale(orbit.speed).add(radial.scale(back)).add(SolarSystem.velocity(orbit.body, time));
            double blend = orbit.elapsed < BLEND_SECONDS ? Math.min(1.0, timeStep / (BLEND_SECONDS * 0.35)) : 1.0;
            Vector3d wanted = new Vector3d(current).lerp(new Vector3d(target.x, target.y, target.z), blend);
            Vector3d delta = new Vector3d(wanted).sub(current);
            for (ServerSubLevel ship : pieces) {
                RigidBodyHandle h = system.getPhysicsHandle(ship);
                if (h != null && h.isValid()) {
                    h.addLinearAndAngularVelocity(delta, new Vector3d());
                }
            }
            orbit.lastSet = wanted;
            orbit.elapsed += timeStep;
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
