package dev.createuniversal.space;

import dev.createuniversal.CreateUniversal;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.ModList;
import org.joml.Vector3d;

/**
 * Anula la gravedad que el motor de física de Immersive Portals: Aeronautics aplica a las naves del espacio.
 *
 * Medido en el juego (seguimiento de la alpha.2): con el motor del espacio creado con gravedad 0 y {@code dimension_physics}
 * a 0, las naves seguían perdiendo 11 bloques/s de velocidad vertical cada segundo, justo la gravedad del Overworld. Sable
 * por su parte usa los valores correctos; el motor nativo {@code ipa_rapier}, que fusiona en un solo paso la física de
 * todas las dimensiones, aplica la gravedad del Overworld a todas. Como no se puede tocar su código nativo, en cada paso de
 * física se suma a cada nave del espacio la velocidad contraria (lo mismo que hacía Cosmonautics con su antigravedad).
 *
 * Solo actúa si el motor propio de Immersive Portals está activo: si se quita el mod o se arregla, no hay nada que compensar.
 */
public final class SpaceGravityCompensation {
    private static Boolean ipaBackend;
    private static boolean logged;
    // (un aviso por arranque basta: el valor es el mismo en todas las dimensiones)

    private SpaceGravityCompensation() {}

    /** Llamado desde el paso de física de Sable (ver {@link ShipAnchor#register()}). */
    public static void prePhysicsTick(SubLevelPhysicsSystem system, double timeStep) {
        ServerLevel level = system.getLevel();
        boolean space = level.dimension() == CUDimensions.SPACE;
        dev.createuniversal.space.surface.PlanetSurface surface = dev.createuniversal.space.surface.PlanetSurface.byDimension(level.dimension());
        if ((!space && surface == null) || !ipaBackendActive()) {
            return;
        }
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return;
        }
        // Gravedad que se está colando: la del Overworld. Se quita y se pone la que toca (0 en el espacio, la de la Luna...).
        Vector3d leaked = DimensionPhysicsData.getGravity(level.getServer().overworld());
        Vector3d wanted = space ? new Vector3d() : new Vector3d(leaked).mul(surface.gravity());
        Vector3d correction = new Vector3d(wanted).sub(leaked).mul(timeStep);
        if (!logged) {
            logged = true;
            CreateUniversal.LOGGER.info("Compensando en {} la gravedad del motor de Immersive Portals: {} por paso de {} s",
                    level.dimension().location(), leaked, timeStep);
        }
        Vector3d noSpin = new Vector3d();
        for (ServerSubLevel ship : container.getAllSubLevels()) {
            if (ship.isRemoved() || ShipAnchor.isHeld(ship)) {
                continue;
            }
            RigidBodyHandle handle = system.getPhysicsHandle(ship);
            if (handle != null && handle.isValid()) {
                handle.addLinearAndAngularVelocity(correction, noSpin);
            }
        }
    }

    private static boolean ipaBackendActive() {
        if (ipaBackend == null) {
            ipaBackend = false;
            if (ModList.get().isLoaded("ip_aeronautics")) {
                try {
                    Class<?> provider = Class.forName("qouteall.imm_ptl.core.compat.sable_physics.IPPhysicsPipelineProvider");
                    ipaBackend = (Boolean) provider.getMethod("isOwnBackendActive").invoke(null);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    CreateUniversal.LOGGER.warn("No se pudo saber si Immersive Portals usa su propio motor de física: se supone que sí", e);
                    ipaBackend = true;
                }
            }
            CreateUniversal.LOGGER.info("Motor de física de Immersive Portals activo: {}", ipaBackend);
        }
        return ipaBackend;
    }
}
