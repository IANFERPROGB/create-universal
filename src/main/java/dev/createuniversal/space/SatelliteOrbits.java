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
 * Modo satélite (D13; rehecho en la alpha.9.6): dentro de la zona de un planeta o una luna, el
 * astro curva el camino de la nave y la hace dar vueltas a su alrededor, pero <b>la velocidad es de la nave</b>:
 *
 * <ul>
 *   <li>Con un impulso hacia un lado, da vueltas hacia ese lado y a esa velocidad. Si frena, da vueltas más despacio.
 *       Los propulsores siempre mandan: no hay que "romper" la órbita.</li>
 *   <li>Si se queda parada del todo (sin empujar {@link #START_SECONDS} s), empieza a dar vueltas sola en el sentido del
 *       astro, hasta {@link #defaultSpeed}.</li>
 *   <li>La altura se mantiene sin empujar (sin espirales). Empujando hacia arriba o hacia abajo se cambia, y se queda en la
 *       nueva.</li>
 *   <li>Fuera de la zona del astro (o cerca solo del Sol) no hay órbita.</li>
 * </ul>
 *
 * Cómo: en cada paso de física, la parte de la velocidad que va alrededor del astro se gira para que siga siendo
 * tangente (el astro curva el camino sin cambiar la rapidez), y la parte que va hacia arriba o abajo se lleva a la altura
 * guardada. Si la nave cambia de velocidad por su cuenta (propulsores, un choque), la altura guardada pasa a ser la actual.
 * Así no se va frenando ni se pierde aunque el motor de física la duerma.
 */
public final class SatelliteOrbits {
    /** Segundos parada del todo y sin empujar para empezar a dar vueltas sola. */
    private static final double START_SECONDS = 3.0;
    /** Por debajo de esta rapidez alrededor del astro (b/s) cuenta como parada. */
    private static final double STOPPED = 0.25;
    /** Aceleración con la que empieza a dar vueltas sola (b/s²). */
    private static final double START_ACCEL = 1.5;
    /** Cambio de velocidad propio (b/s²) que cuenta como empuje. */
    private static final double THRUST_ACCEL = 1.0;
    /** Una vuelta rozando la Tierra (s); crece con el tamaño del astro y la altura. */
    private static final double EARTH_PERIOD_S = 15 * 60;
    private static final double EARTH_RADIUS = 1056;

    private static final class Orbit {
        final SolarSystem.Body body;
        final List<UUID> pieces;
        /** Altura (distancia al centro) que se mantiene sin empujar. */
        double radius;
        /** Normal del plano por defecto (antihorario visto desde arriba) para cuando empieza sola. */
        final Vec3 axis;
        double calmSeconds;
        /** Arrancando sola tras estar parada: acelera hasta la rapidez por defecto. */
        boolean spinningUp;
        Vector3d lastSet;

        Orbit(SolarSystem.Body body, double radius, Vec3 axis, List<UUID> pieces) {
            this.body = body;
            this.radius = radius;
            this.axis = axis;
            this.pieces = pieces;
        }
    }

    private static final Map<UUID, Orbit> ORBITS = new HashMap<>();
    private static final Map<UUID, Orbit> BY_PIECE = new HashMap<>();

    private SatelliteOrbits() {}

    public static boolean inOrbit(ServerSubLevel ship) {
        return BY_PIECE.containsKey(ship.getUniqueId());
    }

    /** El planeta o la luna de cuya zona es {@code pos}, o null (Fobos y Deimos cuentan como Marte). */
    private static SolarSystem.Body orbitBody(Vec3 pos, double time) {
        SolarSystem.Body body = SolarSystem.frameBody(pos, time);
        if (body != null && ("phobos".equals(body.id()) || "deimos".equals(body.id()))) {
            body = SolarSystem.get(body.parent());
        }
        return body == null || body.isSun() ? null : body;
    }

    /**
     * Rapidez con la que da vueltas sola una nave que estaba parada (b/s): una vuelta en 15 min rozando la Tierra, más
     * lenta en astros más grandes y algo más rápida lejos (para que se note): Tierra ~8 b/s a 150 bloques del suelo,
     * ~28 a 14.000; Luna ~4.
     */
    static double defaultSpeed(SolarSystem.Body body, double r) {
        double radius = Math.max(body.radius(), 1.0);
        double period = EARTH_PERIOD_S * Math.sqrt(radius / EARTH_RADIUS) * Math.sqrt(Math.max(r / radius, 1.0));
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

    /** Cada tick del servidor en el espacio: qué naves están en la zona de qué astro. */
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
                release(key, space, group, orbit != null && body == null);
                continue;
            }
            if (orbit != null && orbit.body == body) {
                continue;
            }
            release(key, space, group, false);
            start(space, group, key, body, pos, time);
        }
        ORBITS.keySet().removeIf(k -> {
            if (!keys.contains(k)) {
                ORBITS.get(k).pieces.forEach(BY_PIECE::remove);
                return true;
            }
            return false;
        });
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

    private static void start(ServerLevel level, List<ServerSubLevel> group, UUID key, SolarSystem.Body body, Vec3 pos, double time) {
        Vec3 d = pos.subtract(SolarSystem.position(body, time));
        double r = d.length();
        if (r < body.radius()) {
            return;
        }
        Vec3 radial = d.scale(1.0 / r);
        Vec3 up = Math.abs(radial.y) > 0.95 ? new Vec3(0, 0, 1) : new Vec3(0, 1, 0);
        Vec3 axis = up.subtract(radial.scale(up.dot(radial))).normalize();
        List<UUID> pieces = new ArrayList<>();
        group.forEach(s -> pieces.add(s.getUniqueId()));
        Orbit orbit = new Orbit(body, r, axis, pieces);
        ORBITS.put(key, orbit);
        pieces.forEach(id -> BY_PIECE.put(id, orbit));
        tell(level, group, Component.translatable("create_universal.message.orbit_enter",
                Component.translatable("create_universal.body." + body.id())).withStyle(ChatFormatting.AQUA));
        CreateUniversal.LOGGER.info("Nave {} en la zona de {} a {} bloques del suelo", key, body.id(), Math.round(r - body.radius()));
    }

    private static void release(UUID key, ServerLevel level, List<ServerSubLevel> group, boolean announce) {
        Orbit orbit = ORBITS.remove(key);
        if (orbit == null) {
            return;
        }
        orbit.pieces.forEach(BY_PIECE::remove);
        if (announce && group != null) {
            tell(level, group, Component.translatable("create_universal.message.orbit_leave",
                    Component.translatable("create_universal.body." + orbit.body.id())).withStyle(ChatFormatting.GRAY));
            CreateUniversal.LOGGER.info("Nave {} sale de la zona de {}", key, orbit.body.id());
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

    /** Paso de física: el astro curva el camino y mantiene la altura; la rapidez es de la nave. */
    public static void prePhysicsTick(SubLevelPhysicsSystem system, double timeStep) {
        if (ORBITS.isEmpty() || system.getLevel().dimension() != CUDimensions.SPACE || timeStep <= 0.0) {
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
            Vector3d cur = first.getLinearVelocity(new Vector3d());
            Vec3 current = new Vec3(cur.x, cur.y, cur.z);
            Vec3 d = centerOf(pieces).subtract(SolarSystem.position(orbit.body, time));
            double r = d.length();
            if (r < 1.0) {
                continue;
            }
            Vec3 radial = d.scale(1.0 / r);
            Vec3 bodyVelocity = SolarSystem.velocity(orbit.body, time);

            // ¿Empuja por su cuenta? Entonces la altura que se mantiene es la de ahora.
            boolean pushing = orbit.lastSet != null
                    && new Vector3d(cur).sub(orbit.lastSet).length() / timeStep > THRUST_ACCEL;
            if (pushing) {
                orbit.radius = r;
                orbit.calmSeconds = 0.0;
                orbit.spinningUp = false;
            }

            Vec3 rel = current.subtract(bodyVelocity);
            double vr = rel.dot(radial);
            Vec3 around = rel.subtract(radial.scale(vr));
            double speed = around.length();

            // Parada del todo un rato: empieza a dar vueltas sola en el sentido del astro.
            if (!pushing && !orbit.spinningUp) {
                if (speed < STOPPED) {
                    orbit.calmSeconds += timeStep;
                    if (orbit.calmSeconds >= START_SECONDS) {
                        orbit.spinningUp = true;
                    }
                } else {
                    orbit.calmSeconds = 0.0;
                }
            }
            Vec3 direction;
            if (speed > 1.0e-4) {
                direction = around.scale(1.0 / speed);
            } else {
                direction = orbit.axis.cross(radial);
                direction = direction.lengthSqr() < 1.0e-8 ? Vec3.ZERO : direction.normalize();
            }
            if (orbit.spinningUp) {
                double full = defaultSpeed(orbit.body, r);
                speed = Math.min(full, speed + START_ACCEL * timeStep);
                if (speed >= full) {
                    orbit.spinningUp = false;
                    orbit.calmSeconds = 0.0;
                }
            }

            // El astro curva el camino: la parte que va alrededor sigue tangente con la misma rapidez.
            Vec3 aroundNew = direction.scale(speed);
            // Altura: sin empujar, se lleva poco a poco a la guardada.
            double vrNew = vr;
            if (!pushing) {
                double wanted = Math.max(-3.0, Math.min(3.0, -0.4 * (r - orbit.radius)));
                vrNew = vr + (wanted - vr) * Math.min(1.0, timeStep / 0.6);
            }
            Vec3 target = bodyVelocity.add(aroundNew).add(radial.scale(vrNew));
            Vector3d delta = new Vector3d(target.x - cur.x, target.y - cur.y, target.z - cur.z);
            for (ServerSubLevel ship : pieces) {
                RigidBodyHandle h = system.getPhysicsHandle(ship);
                if (h != null && h.isValid()) {
                    h.addLinearAndAngularVelocity(delta, new Vector3d());
                }
            }
            orbit.lastSet = new Vector3d(target.x, target.y, target.z);
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
