package dev.createuniversal.space;

import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/**
 * Las naves que flotan junto a un astro se mueven con él (alpha.4): en cada paso de física reciben la aceleración orbital
 * del astro en cuya zona de influencia están ({@link SolarSystem#frameAcceleration}). Así una nave parada junto a la
 * Tierra sigue junto a la Tierra aunque esta recorra su órbita a ~16 bloques/s. Al jugador se le aplica lo mismo en su
 * cliente ({@code SpaceFrameClient}), que es quien mueve al jugador.
 */
public final class OrbitalFrame {
    private OrbitalFrame() {}

    /** Llamado desde el paso de física de Sable (ver {@link ShipAnchor#register()}). */
    public static void prePhysicsTick(SubLevelPhysicsSystem system, double timeStep) {
        ServerLevel level = system.getLevel();
        if (level.dimension() != CUDimensions.SPACE) {
            return;
        }
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return;
        }
        double time = SpaceLayout.time(level);
        Vector3d noSpin = new Vector3d();
        for (ServerSubLevel ship : container.getAllSubLevels()) {
            if (ship.isRemoved() || ShipAnchor.isHeld(ship) || Moonlets.isMoonlet(ship)) {
                continue;
            }
            Vec3 a = SolarSystem.frameAcceleration(centerOf(ship), time);
            if (a.lengthSqr() == 0.0) {
                continue;
            }
            RigidBodyHandle handle = system.getPhysicsHandle(ship);
            if (handle != null && handle.isValid()) {
                handle.addLinearAndAngularVelocity(new Vector3d(a.x, a.y, a.z).mul(timeStep), noSpin);
            }
        }
    }

    static Vec3 centerOf(ServerSubLevel ship) {
        BoundingBox3dc b = ship.boundingBox();
        return new Vec3((b.minX() + b.maxX()) * 0.5, (b.minY() + b.maxY()) * 0.5, (b.minZ() + b.maxZ()) * 0.5);
    }
}
