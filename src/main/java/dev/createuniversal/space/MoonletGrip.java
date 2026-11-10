package dev.createuniversal.space;

import dev.createuniversal.CreateUniversal;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.callback.BlockSubLevelCollisionCallback;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Engancharse a Fobos o Deimos al tocarlos: en cuanto una pieza de una nave choca con un bloque de
 * la luna, toda la nave queda pegada en ese sitio, como si fueran un solo cuerpo, y viaja y gira con la luna. No hay
 * ninguna ayuda al acercarse: hay que llegar hasta ella.
 *
 * <ul>
 *   <li>El choque lo avisa Sable a través del bloque de la luna ({@link #CALLBACK}): llega la posición del bloque de la
 *       nave que ha tocado, y de ahí se saca la nave.</li>
 *   <li>Pegada, en cada paso de física se la coloca en su sitio respecto a la luna y se le da la velocidad de ese punto de
 *       la luna. Se separa 5 cm hacia fuera al engancharse para que no siga chocando.</li>
 *   <li>Para soltarse basta con acelerar (motores, hélices...): se mide cuánto se aparta la nave en cada paso de la
 *       velocidad que se le dio; si lo hace de forma seguida, se suelta y durante 3 s no se vuelve a enganchar.</li>
 * </ul>
 */
public final class MoonletGrip {
    /** Cuánto empuje acumulado (bloques/s, con olvido) hace falta para soltarse. */
    private static final double RELEASE = 1.0;
    private static final double FORGET = 0.85;
    private static final long COOLDOWN_TICKS = 60;
    private static final double LIFT = 0.05;

    /** Estado de una luna en este paso de física. */
    record MoonState(UUID id, String name, Vector3d pos, Quaterniond rot, Vector3d vel, Vector3d spin) {}

    private static final class Stuck {
        final UUID moon;
        final String moonName;
        final UUID group;
        final Vector3d relPos;
        final Quaterniond relRot;
        Vector3d lastSet;
        double push;

        Stuck(UUID moon, String moonName, UUID group, Vector3d relPos, Quaterniond relRot) {
            this.moon = moon;
            this.moonName = moonName;
            this.group = group;
            this.relPos = relPos;
            this.relRot = relRot;
        }
    }

    /** Bloques de naves que han tocado una luna (posiciones en la zona interna de la nave), del paso de física. */
    private static final ConcurrentLinkedQueue<Long> CONTACTS = new ConcurrentLinkedQueue<>();
    private static final Map<UUID, Stuck> STUCK = new HashMap<>();
    private static final Map<UUID, Long> COOLDOWN = new HashMap<>();

    /** Lo que Sable llama cuando algo choca con un bloque de Fobos o Deimos. Solo apunta el choque; nada más. */
    public static final BlockSubLevelCollisionCallback CALLBACK = new BlockSubLevelCollisionCallback() {
        @Override
        public CollisionResult sable$onCollision(BlockPos pos, @Nullable BlockPos other, Vector3d hit, double impactVelocity) {
            // Se apuntan los dos bloques: el que sea de una nave (y no de la luna) es el bueno.
            if (CONTACTS.size() < 256) {
                CONTACTS.add(pos.asLong());
                if (other != null) {
                    CONTACTS.add(other.asLong());
                }
            }
            return CollisionResult.NONE;
        }
    };

    private MoonletGrip() {}

    public static boolean isStuck(ServerSubLevel ship) {
        return STUCK.containsKey(ship.getUniqueId());
    }

    /** Se llama en cada paso de física del espacio, después de colocar las lunas. */
    static void step(SubLevelPhysicsSystem system, List<MoonState> moons) {
        ServerLevel level = system.getLevel();
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            CONTACTS.clear();
            return;
        }
        long now = level.getGameTime();
        Map<UUID, MoonState> byId = new HashMap<>();
        for (MoonState m : moons) {
            byId.put(m.id(), m);
        }

        // 1. Choques nuevos.
        Long packed;
        while ((packed = CONTACTS.poll()) != null) {
            SubLevel s = Sable.HELPER.getContaining(level, BlockPos.of(packed));
            if (!(s instanceof ServerSubLevel ship) || ship.isRemoved() || STUCK.containsKey(ship.getUniqueId())
                    || Moonlets.isMoonlet(ship) || ShipAnchor.isHeld(ship)) {
                continue;
            }
            Long until = COOLDOWN.get(ship.getUniqueId());
            if (until != null && until > now) {
                continue;
            }
            MoonState moon = nearest(moons, ship);
            if (moon != null) {
                grip(ShipGroup.of(ship), moon);
            }
        }

        // 2. Naves pegadas: mismo sitio respecto a la luna, misma velocidad que ese punto de la luna.
        if (STUCK.isEmpty()) {
            return;
        }
        List<UUID> releaseGroups = new ArrayList<>();
        List<UUID> lost = new ArrayList<>();
        for (Map.Entry<UUID, Stuck> e : STUCK.entrySet()) {
            Stuck st = e.getValue();
            MoonState moon = byId.get(st.moon);
            SubLevel s = container.getSubLevel(e.getKey());
            if (moon == null || !(s instanceof ServerSubLevel ship) || ship.isRemoved()) {
                lost.add(e.getKey());
                continue;
            }
            RigidBodyHandle handle = system.getPhysicsHandle(ship);
            if (handle == null || !handle.isValid()) {
                continue;
            }
            Vector3d current = handle.getLinearVelocity(new Vector3d());
            if (st.lastSet != null) {
                st.push = st.push * FORGET + current.distance(st.lastSet);
                if (st.push > RELEASE) {
                    releaseGroups.add(st.group);
                    continue;
                }
            }
            Vector3d arm = moon.rot().transform(new Vector3d(st.relPos));
            Vector3d pos = new Vector3d(moon.pos()).add(arm);
            Quaterniond rot = new Quaterniond(moon.rot()).mul(st.relRot);
            Vector3d vel = new Vector3d(moon.spin()).cross(arm).add(moon.vel());
            handle.teleport(pos, rot);
            Vector3d ang = new Vector3d(moon.spin()).sub(handle.getAngularVelocity(new Vector3d()));
            handle.addLinearAndAngularVelocity(new Vector3d(vel).sub(current), ang);
            st.lastSet = vel;
        }
        lost.forEach(STUCK::remove);
        for (UUID group : releaseGroups) {
            release(group, now, "empuje");
        }
    }

    private static MoonState nearest(List<MoonState> moons, ServerSubLevel ship) {
        MoonState best = null;
        double bestD = Double.POSITIVE_INFINITY;
        Vector3d p = new Vector3d(ship.logicalPose().position());
        for (MoonState m : moons) {
            double d = p.distance(m.pos());
            if (d < bestD) {
                bestD = d;
                best = m;
            }
        }
        return best;
    }

    private static void grip(List<ServerSubLevel> group, MoonState moon) {
        UUID groupId = UUID.randomUUID();
        Quaterniond inv = new Quaterniond(moon.rot()).conjugate();
        for (ServerSubLevel piece : group) {
            if (Moonlets.isMoonlet(piece)) {
                continue;
            }
            Vector3d world = new Vector3d(piece.logicalPose().position()).sub(moon.pos());
            if (world.lengthSquared() > 1e-9) {
                world.add(new Vector3d(world).normalize().mul(LIFT));
            }
            Vector3d relPos = inv.transform(world);
            Quaterniond relRot = new Quaterniond(inv).mul(piece.logicalPose().orientation());
            STUCK.put(piece.getUniqueId(), new Stuck(moon.id(), moon.name(), groupId, relPos, relRot));
        }
        CreateUniversal.LOGGER.info("Nave {} ({} piezas) enganchada a {}", group.get(0).getUniqueId(), group.size(), moon.name());
    }

    private static void release(UUID group, long now, String why) {
        List<UUID> pieces = new ArrayList<>();
        String moon = "?";
        for (Map.Entry<UUID, Stuck> e : STUCK.entrySet()) {
            if (e.getValue().group.equals(group)) {
                pieces.add(e.getKey());
                moon = e.getValue().moonName;
            }
        }
        for (UUID id : pieces) {
            STUCK.remove(id);
            COOLDOWN.put(id, now + COOLDOWN_TICKS);
        }
        if (COOLDOWN.size() > 1024) {
            COOLDOWN.values().removeIf(t -> t < now);
        }
        if (!pieces.isEmpty()) {
            CreateUniversal.LOGGER.info("Nave {} se suelta de {} ({})", pieces.get(0), moon, why);
        }
    }

    /** La luna deja de existir (nadie cerca, o se cierra el servidor): las naves pegadas a ella se sueltan. */
    static void releaseAllFrom(UUID moon, long now) {
        List<UUID> groups = new ArrayList<>();
        for (Stuck st : STUCK.values()) {
            if (st.moon.equals(moon) && !groups.contains(st.group)) {
                groups.add(st.group);
            }
        }
        for (UUID g : groups) {
            release(g, now, "la luna ya no está");
        }
    }

    static void clear() {
        STUCK.clear();
        COOLDOWN.clear();
        CONTACTS.clear();
    }
}
