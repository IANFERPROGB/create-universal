package dev.createuniversal.space;

import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.server.level.ServerLevel;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Naves fijadas por un Estabilizador orbital encendido (D37, D41): en cada paso de física se quedan en el punto y la
 * orientación que tenían al encenderlo, en coordenadas absolutas del mundo. En el espacio no las arrastra su astro ni
 * entran en órbita, y los propulsores no las mueven. Los astros siguen su camino: si uno llega hasta la nave, la
 * destruye (alpha.9.1).
 *
 * El bloque lo pide en cada tick mientras tiene rotación suficiente y señal de redstone; si deja de pedirlo, la nave
 * se suelta (parada).
 */
public final class OrbitalStabilizers {
    /** Ticks sin que el bloque lo pida para soltar la nave. */
    private static final long RELEASE_AFTER = 3;

    private static final class Pin {
        final ServerLevel level;
        final Vector3d position;
        final Quaterniond orientation;
        long lastRequest;

        Pin(ServerLevel level, ServerSubLevel ship, long now) {
            this.level = level;
            this.position = new Vector3d(ship.logicalPose().position());
            this.orientation = new Quaterniond(ship.logicalPose().orientation());
            this.lastRequest = now;
        }
    }

    private static final Map<UUID, Pin> PINS = new HashMap<>();

    private OrbitalStabilizers() {}

    /** Llamado por el bloque encendido: fija la nave entera (todas sus piezas unidas). */
    public static void hold(ServerLevel level, SubLevel sub) {
        if (!(sub instanceof ServerSubLevel ship) || ship.isRemoved()) {
            return;
        }
        List<ServerSubLevel> group = ShipGroup.of(ship);
        if (ShipDestruction.isDebris(group)) {
            return;
        }
        long now = level.getGameTime();
        for (ServerSubLevel piece : group) {
            Pin pin = PINS.get(piece.getUniqueId());
            if (pin == null || pin.level != level) {
                PINS.put(piece.getUniqueId(), new Pin(level, piece, now));
            } else {
                pin.lastRequest = now;
            }
        }
    }

    public static boolean isPinned(ServerSubLevel ship) {
        return PINS.containsKey(ship.getUniqueId());
    }

    public static boolean isPinned(List<ServerSubLevel> group) {
        for (ServerSubLevel ship : group) {
            if (isPinned(ship)) {
                return true;
            }
        }
        return false;
    }

    /** Cada tick del servidor: suelta las naves cuyo estabilizador ya no lo pide. */
    public static void tick() {
        if (!PINS.isEmpty()) {
            PINS.values().removeIf(pin -> pin.level.getGameTime() - pin.lastRequest > RELEASE_AFTER);
        }
    }

    /** Paso de física de Sable: recoloca y para las naves fijadas. */
    public static void prePhysicsTick(SubLevelPhysicsSystem system, double timeStep) {
        if (PINS.isEmpty()) {
            return;
        }
        ServerSubLevelContainer container = SubLevelContainer.getContainer(system.getLevel());
        if (container == null) {
            return;
        }
        for (Map.Entry<UUID, Pin> e : PINS.entrySet()) {
            Pin pin = e.getValue();
            if (pin.level != system.getLevel()) {
                continue;
            }
            SubLevel s = container.getSubLevel(e.getKey());
            if (!(s instanceof ServerSubLevel ship) || ship.isRemoved()) {
                continue;
            }
            RigidBodyHandle handle = system.getPhysicsHandle(ship);
            if (handle == null || !handle.isValid()) {
                continue;
            }
            handle.teleport(pin.position, pin.orientation);
            Vector3d lin = handle.getLinearVelocity(new Vector3d()).negate();
            Vector3d ang = handle.getAngularVelocity(new Vector3d()).negate();
            handle.addLinearAndAngularVelocity(lin, ang);
        }
    }
}
