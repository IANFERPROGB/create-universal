package dev.createuniversal.space;

import dev.createuniversal.CreateUniversal;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.platform.SableEventPlatform;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Espera de llegada tras un cambio de dimensión (D10).
 *
 * Problema: la nave llega a toda velocidad, a menudo acelerando por sus motores, y el cliente del jugador todavía no la
 * tiene cargada. Quien viaja de pie la atraviesa o se queda atrás; en gravedad cero nada le aprieta contra el suelo para
 * que Sable lo vuelva a "pegar" a la nave.
 *
 * Solución (lo que hace Cosmonautics, cuyas naves llegan paradas):
 * <ol>
 *   <li>La nave se congela en el punto de llegada: en cada paso de física se la recoloca y se le quita la velocidad.</li>
 *   <li>Los jugadores que iban de pie quedan anclados en su mismo punto de la nave.</li>
 *   <li>Se espera a que el cliente de cada jugador tenga la nave cargada (Sable lo indica en {@code getTrackingPlayers})
 *       y un margen más. El tiempo se mide con reloj real: tras el traslado el servidor recupera ticks atrasados muy deprisa.</li>
 *   <li>Al soltar, nave y jugadores recuperan a la vez la velocidad que llevaba la nave.</li>
 * </ol>
 */
public final class ShipAnchor {
    /** Margen tras ver la nave cargada en todos los clientes. */
    private static final long SETTLE_MS = 750;
    /** Mínimo congelado, aunque los clientes ya la tengan. */
    private static final long MIN_HOLD_MS = 1000;
    /** Máximo congelado pase lo que pase. */
    private static final long MAX_HOLD_MS = 15000;

    private static final class Hold {
        final UUID ship;
        final ServerLevel level;
        final Vector3d position;
        final Quaterniond orientation;
        final Vector3d linearVelocity;
        final Vector3d angularVelocity;
        final Map<UUID, Vec3> standing = new HashMap<>();
        final List<UUID> passengers = new ArrayList<>();
        final long startMs = System.currentTimeMillis();
        /** En el espacio la nave se congela respecto al astro más cercano, que sigue moviéndose por su órbita. */
        final SolarSystem.Body frame;
        final double startTime;
        long allTrackingSinceMs = -1;
        /** Piezas unidas que llegaron juntas: se sueltan todas a la vez para que no se separen ni tiren de sus uniones. */
        UUID group;

        Hold(ServerSubLevel ship, ServerLevel level, Vector3d linear, Vector3d angular) {
            this.ship = ship.getUniqueId();
            this.level = level;
            this.position = new Vector3d(ship.logicalPose().position());
            this.orientation = new Quaterniond(ship.logicalPose().orientation());
            this.linearVelocity = new Vector3d(linear);
            this.angularVelocity = new Vector3d(angular);
            this.startTime = SpaceLayout.time(level);
            this.frame = level.dimension() == CUDimensions.SPACE
                    ? SolarSystem.frameBody(new Vec3(position.x, position.y, position.z), startTime) : null;
        }

        /** Dónde debe estar la nave ahora: el punto de llegada, arrastrado con su astro. */
        Vector3d positionAt(double time) {
            if (frame == null) {
                return position;
            }
            Vec3 shift = SolarSystem.position(frame, time).subtract(SolarSystem.position(frame, startTime));
            return new Vector3d(position).add(shift.x, shift.y, shift.z);
        }
    }

    private static final Map<UUID, Hold> HOLDS = new HashMap<>();
    private static boolean registered;

    private ShipAnchor() {}

    /** Se llama una vez al arrancar el mod: engancha el congelado al paso de física de Sable. */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        SableEventPlatform.INSTANCE.onPhysicsTick(ShipAnchor::prePhysicsTick);
        SableEventPlatform.INSTANCE.onPhysicsTick(SpaceGravityCompensation::prePhysicsTick);
        SableEventPlatform.INSTANCE.onPhysicsTick(OrbitalFrame::prePhysicsTick);
    }

    /** ¿Está la nave congelada en la espera de llegada? */
    public static boolean isHeld(ServerSubLevel ship) {
        return HOLDS.containsKey(ship.getUniqueId());
    }

    /** Congela la nave recién llegada. */
    public static void holdShip(ServerSubLevel ship, ServerLevel level, Vector3d linearVelocity, Vector3d angularVelocity) {
        HOLDS.put(ship.getUniqueId(), new Hold(ship, level, linearVelocity, angularVelocity));
    }

    /** Marca las naves de un mismo grupo (piezas unidas por remaches, rodamientos...): se sueltan a la vez. */
    public static void group(List<ServerSubLevel> ships) {
        UUID id = UUID.randomUUID();
        for (ServerSubLevel ship : ships) {
            Hold hold = HOLDS.get(ship.getUniqueId());
            if (hold != null) {
                hold.group = id;
            }
        }
    }

    /** Ancla a un jugador de pie en su punto de la nave (coordenadas del plot) mientras la nave esté congelada. */
    public static void anchorStanding(ServerPlayer player, ServerSubLevel ship, Vec3 plotPos) {
        Hold hold = HOLDS.get(ship.getUniqueId());
        if (hold != null) {
            hold.standing.put(player.getUUID(), plotPos);
        }
    }

    /** Registra a un jugador sentado: también hay que esperar a que su cliente tenga la nave. */
    public static void addPassenger(ServerPlayer player, ServerSubLevel ship) {
        Hold hold = HOLDS.get(ship.getUniqueId());
        if (hold != null) {
            hold.passengers.add(player.getUUID());
        }
    }

    private static void prePhysicsTick(SubLevelPhysicsSystem system, double timeStep) {
        if (HOLDS.isEmpty()) {
            return;
        }
        ServerSubLevelContainer container = SubLevelContainer.getContainer(system.getLevel());
        if (container == null) {
            return;
        }
        for (Hold hold : HOLDS.values()) {
            if (hold.level != system.getLevel()) {
                continue;
            }
            SubLevel s = container.getSubLevel(hold.ship);
            if (!(s instanceof ServerSubLevel ship) || ship.isRemoved()) {
                continue;
            }
            RigidBodyHandle handle = system.getPhysicsHandle(ship);
            if (handle == null || !handle.isValid()) {
                continue;
            }
            handle.teleport(hold.positionAt(SpaceLayout.time(system.getLevel())), hold.orientation);
            Vector3d lin = handle.getLinearVelocity(new Vector3d()).negate();
            Vector3d ang = handle.getAngularVelocity(new Vector3d()).negate();
            handle.addLinearAndAngularVelocity(lin, ang);
        }
    }

    /** Cada tick del servidor: mantiene anclados a los jugadores y suelta las naves cuando toca. */
    public static void tick() {
        if (HOLDS.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        List<UUID> released = new ArrayList<>();
        // Listas para soltar: por grupo, solo cuando lo están todas sus piezas (o alguna ha llegado al máximo).
        Map<UUID, Boolean> groupReady = new HashMap<>();
        java.util.Set<UUID> groupTimedOut = new java.util.HashSet<>();
        Map<UUID, ServerSubLevel> alive = new HashMap<>();
        for (Hold hold : HOLDS.values()) {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(hold.level);
            SubLevel s = container != null ? container.getSubLevel(hold.ship) : null;
            if (!(s instanceof ServerSubLevel ship) || ship.isRemoved()) {
                released.add(hold.ship);
                continue;
            }
            for (Map.Entry<UUID, Vec3> e : hold.standing.entrySet()) {
                if (hold.level.getPlayerByUUID(e.getKey()) instanceof ServerPlayer p && p.level() == hold.level) {
                    Vec3 pos = ship.logicalPose().transformPosition(e.getValue());
                    p.connection.teleport(pos.x, pos.y, pos.z, p.getYRot(), p.getXRot());
                    p.setDeltaMovement(Vec3.ZERO);
                    p.resetFallDistance();
                }
            }

            boolean allTracking = true;
            for (UUID player : allPlayers(hold)) {
                if (!ship.getTrackingPlayers().contains(player)) {
                    allTracking = false;
                    break;
                }
            }
            if (allTracking && hold.allTrackingSinceMs < 0) {
                hold.allTrackingSinceMs = now;
            }
            long held = now - hold.startMs;
            boolean ready = held >= MIN_HOLD_MS && hold.allTrackingSinceMs >= 0 && now - hold.allTrackingSinceMs >= SETTLE_MS;
            alive.put(hold.ship, ship);
            UUID key = hold.group != null ? hold.group : hold.ship;
            groupReady.merge(key, ready, (a, b) -> a && b);
            if (held >= MAX_HOLD_MS) {
                groupTimedOut.add(key);
            }
        }
        for (Hold hold : HOLDS.values()) {
            ServerSubLevel ship = alive.get(hold.ship);
            UUID key = hold.group != null ? hold.group : hold.ship;
            boolean timedOut = groupTimedOut.contains(key);
            if (ship != null && (Boolean.TRUE.equals(groupReady.get(key)) || timedOut)) {
                release(hold, ship, SubLevelContainer.getContainer(hold.level), now - hold.startMs, !timedOut);
                released.add(hold.ship);
            }
        }
        released.forEach(HOLDS::remove);
    }

    private static List<UUID> allPlayers(Hold hold) {
        List<UUID> all = new ArrayList<>(hold.standing.keySet());
        all.addAll(hold.passengers);
        return all;
    }

    private static void release(Hold hold, ServerSubLevel ship, ServerSubLevelContainer container, long heldMs, boolean clientsReady) {
        RigidBodyHandle handle = container.physicsSystem().getPhysicsHandle(ship);
        if (handle != null && handle.isValid()) {
            handle.addLinearAndAngularVelocity(hold.linearVelocity, hold.angularVelocity);
        }
        Vec3 tickVelocity = new Vec3(hold.linearVelocity.x, hold.linearVelocity.y, hold.linearVelocity.z).scale(1.0 / 20.0);
        for (UUID id : hold.standing.keySet()) {
            if (hold.level.getPlayerByUUID(id) instanceof ServerPlayer p && p.level() == hold.level) {
                p.setDeltaMovement(tickVelocity);
                p.connection.send(new ClientboundSetEntityMotionPacket(p));
            }
        }
        CreateUniversal.LOGGER.info("Nave {} sale de la espera de llegada tras {} ms ({}), velocidad devuelta {} b/s, {} de pie, {} sentados",
                hold.ship, heldMs, clientsReady ? "clientes listos" : "tiempo máximo", hold.linearVelocity,
                hold.standing.size(), hold.passengers.size());
    }
}
