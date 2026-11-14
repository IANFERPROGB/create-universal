package dev.createuniversal.space;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Piloto automático del Asistente de nave (alpha.12.1, D63, D64, D92).
 *
 * <ul>
 *   <li><b>Ruta:</b> cada tick quiere ir hacia el destino a la mayor velocidad con la que todavía puede frenar a tiempo
 *       (v = √(2·a·d), hasta la de crucero), llegando con la velocidad del destino (los astros se mueven). Si un astro (o
 *       el Sol) se cruza en la línea recta, apunta a un punto a su lado. Como todo se recalcula cada tick, sigue a los
 *       astros y corrige sola.</li>
 *   <li><b>Empuje:</b> solo los propulsores principales (los que más empujan hacia un mismo lado de la nave), y solo
 *       cuando la nave ya apunta bien (menos de 15°).</li>
 *   <li><b>Giroscopio:</b> en cada paso de física gira la nave para que ese lado mire hacia donde hay que empujar, con
 *       una aceleración de giro limitada por las RPM del asistente y la masa de la nave, y frena el resto del giro.</li>
 *   <li><b>Llegada:</b> a un astro, a la altura de órbita baja (como al salir de un planeta); a lo demás, a 48 bloques.</li>
 * </ul>
 */
public final class Autopilot {
    /** Velocidad máxima por defecto (se cambia en la pantalla del asistente). */
    public static final int CRUISE = 250;
    /** Velocidades máximas que se pueden elegir (bloques/s). */
    public static final int[] SPEEDS = {25, 50, 75, 99, 125, 150, 200, 250, 300, 400, 500, 750, 1000, 1500, 2000, 3000};
    private static final double ALIGN_DEG = 15.0;

    /** Lo que el giroscopio tiene que hacer con una nave en el próximo paso de física. */
    private record Steer(ServerLevel level, Vector3d localAxis, Vector3d wantWorld, double alphaMax) {}

    private static final Map<UUID, Steer> STEER = new ConcurrentHashMap<>();

    /** El resultado de un tick de guiado. */
    public record Command(String status, double throttle, boolean aligned, double eta, Vector3d thrustWorld) {}

    private Autopilot() {}

    /** Dónde está ahora el destino, cómo se mueve y a qué distancia hay que pararse. Null si ya no existe. */
    public record Goal(Vec3 pos, Vec3 vel, double stop, SolarSystem.Body body) {}

    public static Goal goal(String target, Vec3 fixed, double time) {
        if (target.startsWith("body:")) {
            SolarSystem.Body b = SolarSystem.get(target.substring(5));
            if (b == null) {
                return null;
            }
            double stop = b.isSun() ? b.radius() * 2.2 : b.radius() + Math.max(SpaceLayout.ARRIVAL_ALTITUDE, b.radius() * 0.1);
            return new Goal(SolarSystem.position(b, time), SolarSystem.velocity(b, time), stop, b);
        }
        return fixed == null ? null : new Goal(fixed, Vec3.ZERO, 48.0, null);
    }

    /** Centro de la nave en el mundo. */
    public static Vec3 center(ServerSubLevel ship) {
        return OrbitalFrame.centerOf(ship);
    }

    /** Velocidad de la nave en el mundo (bloques/s). */
    public static Vec3 velocity(ServerLevel level, ServerSubLevel ship) {
        var mass = ship.getMassTracker();
        Vector3d com = mass != null ? new Vector3d(mass.getCenterOfMass()) : new Vector3d();
        Vector3d v = Sable.HELPER.getVelocity(level, ship, com, new Vector3d());
        return new Vec3(v.x, v.y, v.z);
    }

    /**
     * Un tick de guiado. {@code main} es el lado de la nave hacia el que empujan los propulsores principales y
     * {@code accel} la aceleración que dan a tope (bloques/s²).
     */
    public static Command guide(ServerLevel level, ServerSubLevel ship, Goal goal, Direction main, double accel, double rpm, double cruise) {
        Vec3 p = OrbitalFrame.centerOf(ship);
        Vec3 v = velocity(level, ship);
        Vec3 r = goal.pos().subtract(p);
        double dist = r.length() - goal.stop();
        Vec3 u = v.subtract(goal.vel());
        double a = Math.max(0.5, accel) * 0.7;
        Quaterniond q = new Quaterniond(ship.logicalPose().orientation());
        Vector3d local = new Vector3d(main.getStepX(), main.getStepY(), main.getStepZ());
        Vector3d axisWorld = q.transform(new Vector3d(local));

        if (dist < 6.0 && u.length() < 3.0) {
            hold(level, ship, local, axisWorld, rpm);
            return new Command("arrived", 0, false, 0, null);
        }

        Vec3 dir = avoid(level, p, goal, r);
        double speed = Math.min(cruise, Math.sqrt(2.0 * a * Math.max(0.0, dist)));
        Vec3 want = goal.vel().add(dir.scale(speed));
        Vec3 err = want.subtract(v);
        double closing = u.dot(r.normalize());
        double eta = dist / Math.max(1.0, Math.max(closing, Math.min(speed, 20.0)));
        if (err.length() < 0.5) {
            hold(level, ship, local, axisWorld, rpm);
            return new Command("cruising", 0, true, eta, null);
        }
        Vector3d thrust = new Vector3d(err.x, err.y, err.z).normalize();
        STEER.put(ship.getUniqueId(), new Steer(level, local, thrust, alphaMax(ship, rpm)));
        double cos = axisWorld.dot(thrust);
        boolean aligned = cos > Math.cos(Math.toRadians(ALIGN_DEG));
        double throttle = Math.max(0.05, Math.min(1.0, err.length() / Math.max(0.5, accel * 0.6)));
        String status = !aligned ? "turning" : closing > speed + 1.0 ? "braking" : "flying";
        return new Command(status, aligned ? throttle * cos : 0.0, aligned, eta, thrust);
    }

    /** Sin empujar: el giroscopio solo frena el giro de la nave. */
    private static void hold(ServerLevel level, ServerSubLevel ship, Vector3d local, Vector3d axisWorld, double rpm) {
        STEER.put(ship.getUniqueId(), new Steer(level, local, axisWorld, alphaMax(ship, rpm)));
    }

    /**
     * Pide al giroscopio que el lado {@code main} de la nave mire hacia {@code want} (mundo). Devuelve el coseno del
     * ángulo que falta (1 = ya apunta bien).
     */
    public static double steer(ServerLevel level, ServerSubLevel ship, Direction main, Vector3d want, double rpm) {
        Vector3d local = new Vector3d(main.getStepX(), main.getStepY(), main.getStepZ());
        Vector3d axisWorld = new Quaterniond(ship.logicalPose().orientation()).transform(new Vector3d(local));
        STEER.put(ship.getUniqueId(), new Steer(level, local, new Vector3d(want).normalize(), alphaMax(ship, rpm)));
        return axisWorld.dot(new Vector3d(want).normalize());
    }

    public static void release(UUID ship) {
        STEER.remove(ship);
    }

    /** Aceleración de giro máxima (rad/s²): 0,6 a 64 RPM para una nave de unas 200 t, menos cuanto más pesada. */
    private static double alphaMax(ServerSubLevel ship, double rpm) {
        double mass = ship.getMassTracker() != null ? ship.getMassTracker().getMass() : 200_000.0;
        return 0.6 * (Math.abs(rpm) / 64.0) * Math.min(2.0, Math.sqrt(200_000.0 / Math.max(1_000.0, mass)));
    }

    /**
     * Hacia dónde ir: recto al destino o, si un astro (o el Sol) se cruza en el camino, hacia un punto a su lado. Si la
     * nave está dentro del margen de un astro y se aleja de él, no lo cuenta (al despegar de una órbita).
     */
    private static Vec3 avoid(ServerLevel level, Vec3 p, Goal goal, Vec3 r) {
        double time = SpaceLayout.time(level);
        double len2 = r.lengthSqr();
        double bestT = Double.POSITIVE_INFINITY;
        Vec3 waypoint = null;
        for (SolarSystem.Body b : SolarSystem.bodies()) {
            if (b == goal.body()) {
                continue;
            }
            Vec3 c = SolarSystem.position(b, time);
            double safe = b.isSun() ? b.radius() * 2.0 : b.radius() * 1.3 + 150.0;
            Vec3 pc = p.subtract(c);
            if (pc.length() < safe && r.dot(pc) >= 0) {
                continue;
            }
            double t = len2 < 1.0e-6 ? 0.0 : Math.max(0.0, Math.min(1.0, c.subtract(p).dot(r) / len2));
            if (t <= 0.0 || t >= 1.0) {
                continue;
            }
            Vec3 closest = p.add(r.scale(t));
            Vec3 off = closest.subtract(c);
            if (off.length() >= safe || t >= bestT) {
                continue;
            }
            if (off.lengthSqr() < 1.0e-6) {
                off = r.cross(new Vec3(0, 1, 0));
                if (off.lengthSqr() < 1.0e-6) {
                    off = new Vec3(1, 0, 0);
                }
            }
            bestT = t;
            waypoint = c.add(off.normalize().scale(safe * 1.25));
        }
        Vec3 aim = waypoint != null ? waypoint : goal.pos();
        Vec3 rocks = rocks(p, aim, time);
        return (rocks != null ? rocks : aim).subtract(p).normalize();
    }

    /**
     * Campos de rocas (cinturón de asteroides, anillos de Saturno): si el camino de los próximos 30.000 bloques entra en
     * uno, apunta por encima (o por debajo, según de qué lado venga) de su plano. Si la nave ya está dentro, no lo cuenta.
     */
    private static Vec3 rocks(Vec3 p, Vec3 aim, double time) {
        Vec3 seg = aim.subtract(p);
        double len = Math.min(seg.length(), 30_000.0);
        if (len < 1.0) {
            return null;
        }
        Vec3 dir = seg.normalize();
        for (SpaceRocks.Field f : SpaceRocks.fields()) {
            Vec3 c = SpaceRocks.center(f, time);
            Quaterniond inv = SpaceRocks.frame(f, time).conjugate(new Quaterniond());
            double margin = 150.0;
            Vector3d start = inv.transform(new Vector3d(p.x - c.x, p.y - c.y, p.z - c.z));
            if (inside(f, start, margin)) {
                continue;
            }
            for (int k = 1; k <= 64; k++) {
                Vec3 s = p.add(dir.scale(len * k / 64.0));
                Vector3d l = inv.transform(new Vector3d(s.x - c.x, s.y - c.y, s.z - c.z));
                if (inside(f, l, margin)) {
                    double side = start.y >= 0 ? 1.0 : -1.0;
                    l.y = side * (f.half() + margin + 400.0);
                    Vector3d w = SpaceRocks.frame(f, time).transform(l);
                    return new Vec3(c.x + w.x, c.y + w.y, c.z + w.z);
                }
            }
        }
        return null;
    }

    private static boolean inside(SpaceRocks.Field f, Vector3d local, double margin) {
        double r = Math.hypot(local.x, local.z);
        return r > f.rMin() - margin && r < f.rMax() + margin && Math.abs(local.y) < f.half() + margin;
    }

    /** Paso de física: el giroscopio. */
    public static void prePhysicsTick(SubLevelPhysicsSystem system, double timeStep) {
        if (STEER.isEmpty()) {
            return;
        }
        ServerSubLevelContainer container = SubLevelContainer.getContainer(system.getLevel());
        if (container == null) {
            return;
        }
        for (Map.Entry<UUID, Steer> e : STEER.entrySet()) {
            Steer steer = e.getValue();
            if (steer.level() != system.getLevel()) {
                continue;
            }
            SubLevel s = container.getSubLevel(e.getKey());
            if (!(s instanceof ServerSubLevel ship) || ship.isRemoved() || OrbitalStabilizers.isPinned(ship)) {
                continue;
            }
            RigidBodyHandle handle = system.getPhysicsHandle(ship);
            if (handle == null || !handle.isValid()) {
                continue;
            }
            Quaterniond q = new Quaterniond(ship.logicalPose().orientation());
            Vector3d axis = q.transform(new Vector3d(steer.localAxis()));
            Vector3d want = new Vector3d(steer.wantWorld()).normalize();
            Vector3d turn = axis.cross(want, new Vector3d());
            double sin = turn.length();
            double angle = Math.atan2(sin, axis.dot(want));
            Vector3d omegaWant = new Vector3d();
            if (sin > 1.0e-6) {
                // Velocidad de giro con la que aún da tiempo a frenar el giro al llegar: ω = √(2·α·θ).
                double w = Math.min(1.2, Math.sqrt(2.0 * steer.alphaMax() * 0.8 * angle));
                omegaWant.set(turn).normalize().mul(w);
            } else if (angle > 3.0) {
                // Al revés del todo: cualquier eje perpendicular sirve.
                Vector3d perp = axis.cross(new Vector3d(0, 1, 0), new Vector3d());
                if (perp.lengthSquared() < 1.0e-6) {
                    perp = axis.cross(new Vector3d(1, 0, 0), new Vector3d());
                }
                omegaWant.set(perp).normalize().mul(0.5);
            }
            Vector3d omega = handle.getAngularVelocity(new Vector3d());
            Vector3d delta = omegaWant.sub(omega, new Vector3d());
            double max = steer.alphaMax() * timeStep;
            if (delta.length() > max) {
                delta.normalize().mul(max);
            }
            handle.addLinearAndAngularVelocity(new Vector3d(), delta);
        }
    }
}
