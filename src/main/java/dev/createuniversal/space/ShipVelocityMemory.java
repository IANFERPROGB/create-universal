package dev.createuniversal.space;

import dev.createuniversal.CreateUniversal;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Las naves del espacio no recuperan su velocidad al cargarse (al entrar en el mundo, o al volver a acercarse): el motor
 * de física las crea paradas. Pero en el espacio "parada" no es lo normal: va con su astro (la Tierra a ~16 bloques/s) y
 * quizá dando vueltas en órbita. Al cargar se quedaba quieta, la Tierra se le echaba encima y quien iba de pie la
 * atravesaba (alpha.9.9).
 *
 * Cada segundo se guarda en los datos de cada nave su velocidad respecto al astro cuya zona ocupa. La primera vez que se
 * ve una nave tras cargarla se le devuelve esa velocidad, sumada a la del astro en ese momento.
 */
public final class ShipVelocityMemory {
    private static final String KEY = "frame_velocity";
    private static final Set<UUID> KNOWN = new HashSet<>();

    private ShipVelocityMemory() {}

    /** Al cerrar el mundo: la próxima vez todas las naves cuentan como recién cargadas. */
    public static void clear() {
        KNOWN.clear();
    }

    public static void tick(ServerLevel space) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(space);
        if (container == null) {
            return;
        }
        double time = SpaceLayout.time(space);
        boolean save = space.getGameTime() % 20 == 0;
        Set<UUID> present = new HashSet<>();
        for (ServerSubLevel ship : container.getAllSubLevels()) {
            if (ship.isRemoved() || Moonlets.isMoonlet(ship)) {
                continue;
            }
            present.add(ship.getUniqueId());
            RigidBodyHandle handle = container.physicsSystem().getPhysicsHandle(ship);
            if (handle == null || !handle.isValid()) {
                continue;
            }
            Vec3 center = OrbitalFrame.centerOf(ship);
            SolarSystem.Body body = SolarSystem.frameBody(center, time);
            Vec3 bodyVelocity = body != null ? SolarSystem.velocity(body, time) : Vec3.ZERO;
            if (KNOWN.add(ship.getUniqueId())) {
                // Recién cargada (o recién llegada: esas las suelta ShipAnchor con su velocidad, no se tocan).
                if (!ShipAnchor.isHeld(ship) && !OrbitalStabilizers.isPinned(ship)) {
                    restore(ship, handle, bodyVelocity);
                }
                continue;
            }
            if (save) {
                Vector3d v = handle.getLinearVelocity(new Vector3d());
                CompoundTag ud = ship.getUserDataTag() != null ? ship.getUserDataTag() : new CompoundTag();
                CompoundTag ours = ud.getCompound(CreateUniversal.MOD_ID);
                CompoundTag mem = new CompoundTag();
                mem.putDouble("x", v.x - bodyVelocity.x);
                mem.putDouble("y", v.y - bodyVelocity.y);
                mem.putDouble("z", v.z - bodyVelocity.z);
                ours.put(KEY, mem);
                ud.put(CreateUniversal.MOD_ID, ours);
                ship.setUserDataTag(ud);
            }
        }
        KNOWN.retainAll(present);
    }

    private static void restore(ServerSubLevel ship, RigidBodyHandle handle, Vec3 bodyVelocity) {
        CompoundTag ud = ship.getUserDataTag();
        if (ud == null || !ud.getCompound(CreateUniversal.MOD_ID).contains(KEY)) {
            return;
        }
        CompoundTag mem = ud.getCompound(CreateUniversal.MOD_ID).getCompound(KEY);
        Vector3d target = new Vector3d(bodyVelocity.x + mem.getDouble("x"), bodyVelocity.y + mem.getDouble("y"),
                bodyVelocity.z + mem.getDouble("z"));
        Vector3d current = handle.getLinearVelocity(new Vector3d());
        handle.addLinearAndAngularVelocity(target.sub(current, new Vector3d()), new Vector3d());
        CreateUniversal.LOGGER.info("Nave {} cargada: recupera su velocidad ({} b/s respecto a su astro)", ship.getUniqueId(),
                String.format(java.util.Locale.ROOT, "%.1f", Math.sqrt(mem.getDouble("x") * mem.getDouble("x")
                        + mem.getDouble("y") * mem.getDouble("y") + mem.getDouble("z") * mem.getDouble("z"))));
    }
}
