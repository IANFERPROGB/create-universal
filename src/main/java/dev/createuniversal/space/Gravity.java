package dev.createuniversal.space;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gravedad propia de una entidad dentro del área de un Estabilizador de gravedad (D50, D51), en el servidor y en el
 * cliente (que es quien mueve al jugador):
 *
 * <ul>
 *   <li>En una nave: "abajo" es una cara de la nave ({@code down}, en sus coordenadas) y gira con ella. Se le da a Sable
 *       como orientación propia de la entidad ({@code EntitySubLevelUtil.getCustomEntityOrientation}), y Sable gira con
 *       ella la cámara, el cuerpo, andar, saltar y el choque con los bloques de la nave. La gravedad y el rozamiento en esa
 *       dirección los pone {@code GravityTravelMixin}.</li>
 *   <li>En el suelo: solo hacia abajo; cambia la fuerza.</li>
 * </ul>
 */
public final class Gravity {
    /** Gravedad de la Tierra para los seres vivos en Minecraft (bloques/tick²). */
    public static final double EARTH = 0.08;

    /** @param ship nave (null = en el suelo) */
    public record State(UUID ship, Direction down, float strength) {}

    /** Servidor: por UUID de la entidad. */
    static final Map<UUID, State> SERVER = new ConcurrentHashMap<>();
    /** Cliente: por id de red de la entidad. */
    private static final Map<Integer, State> CLIENT = new ConcurrentHashMap<>();

    private Gravity() {}

    public static State state(Entity entity) {
        if (entity.level() == null) {
            return null;
        }
        return entity.level().isClientSide ? CLIENT.get(entity.getId()) : SERVER.get(entity.getUUID());
    }

    /** Llega del servidor (cliente). */
    public static void receive(int entityId, State state) {
        if (state == null) {
            CLIENT.remove(entityId);
        } else {
            CLIENT.put(entityId, state);
        }
    }

    public static void clearClient() {
        CLIENT.clear();
    }

    /** Orientación propia (gira el "arriba" del mundo al de la nave), o null si no hay o es en el suelo. */
    public static Quaterniond orientation(Entity entity, float partialTicks) {
        State s = state(entity);
        if (s == null || s.ship() == null) {
            return null;
        }
        SubLevel ship = ship(entity.level(), s.ship());
        if (ship == null) {
            return null;
        }
        // En el cliente, entre ticks, la pose dibujada (suave); si no, la del último tick.
        Quaterniond shipRot = entity.level().isClientSide && partialTicks < 1.0F
                ? dev.createuniversal.client.ClientShipPose.renderOrientation(ship)
                : new Quaterniond(ship.logicalPose().orientation());
        Direction up = s.down().getOpposite();
        Quaterniond local = new Quaterniond().rotationTo(0.0, 1.0, 0.0, up.getStepX(), up.getStepY(), up.getStepZ());
        return shipRot.mul(local);
    }

    /** "Arriba" de la entidad en coordenadas del mundo, o null si no hay gravedad propia girada. */
    public static Vector3d up(Entity entity) {
        Quaterniond q = orientation(entity, 1.0F);
        return q != null ? q.transform(new Vector3d(0.0, 1.0, 0.0)) : null;
    }

    static SubLevel ship(Level level, UUID id) {
        var container = SubLevelContainer.getContainer(level);
        SubLevel sub = container != null ? container.getSubLevel(id) : null;
        return sub == null || sub.isRemoved() ? null : sub;
    }
}
