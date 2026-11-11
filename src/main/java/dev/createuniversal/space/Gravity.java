package dev.createuniversal.space;

import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gravedad de una entidad dentro del área de un Estabilizador de gravedad (D50, D51, D91), en el servidor y en el cliente
 * (que es quien mueve al jugador):
 *
 * <ul>
 *   <li><b>En una nave</b> (alpha.9.4): "abajo" es la cara de la nave donde está el bloque y gira con ella, en cualquier
 *       ángulo, aunque la nave esté del revés. Se le da a Sable como orientación propia de la entidad
 *       ({@code SableGravityOrientationMixin}) y Sable gira con ella la cámara, el cuerpo, andar, saltar y el choque con
 *       la nave. La gravedad y el rozamiento en esa dirección los pone {@code GravityTravelMixin}. Dos fallos de Sable en
 *       ese camino (que disparaban al jugador) los corrigen {@code SableFeetPosMixin} y {@code SableTrackingPosMixin}.</li>
 *   <li><b>En el suelo</b>: la dirección la lleva Gravity Control (6 caras, ver {@link GravityFields}) y aquí solo la
 *       fuerza, que se aplica en {@code EntitySpaceGravityMixin}.</li>
 * </ul>
 */
public final class Gravity {
    /** Gravedad de la Tierra para los seres vivos en Minecraft (bloques/tick²). */
    public static final double EARTH = 0.08;

    /**
     * @param ship     nave (null = en el suelo)
     * @param down     hacia dónde tira, en coordenadas del bloque (las de la nave si va en una)
     * @param strength fuerza en "gravedades de la Tierra"
     */
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

    /**
     * Orientación propia (gira el "arriba" del mundo al de la nave), o null si no hay, es en el suelo o va sentado (sentado
     * ya gira Sable con el asiento). Para la cámara y el dibujo: en el cliente, entre ticks, con la pose dibujada.
     */
    public static Quaterniond orientation(Entity entity, float partialTicks) {
        State s = state(entity);
        if (s == null || s.ship() == null || entity.isPassenger()) {
            return null;
        }
        SubLevel ship = OxygenZones.ship(entity.level(), s.ship());
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

    /**
     * Para los choques: la orientación en ese momento del tick ({@code partialTicks} 0 = pose anterior de la nave, 1 = la
     * actual), igual que mueve Sable la nave dentro del tick. Nunca la dibujada, que va unos ticks por detrás.
     */
    public static Quaterniond physicsOrientation(Entity entity, float partialTicks) {
        State s = state(entity);
        if (s == null || s.ship() == null || entity.isPassenger()) {
            return null;
        }
        SubLevel ship = OxygenZones.ship(entity.level(), s.ship());
        if (ship == null) {
            return null;
        }
        Quaterniond shipRot = new Quaterniond(ship.lastPose().orientation())
                .slerp(ship.logicalPose().orientation(), Math.max(0.0F, Math.min(1.0F, partialTicks)));
        Direction up = s.down().getOpposite();
        return shipRot.mul(new Quaterniond().rotationTo(0.0, 1.0, 0.0, up.getStepX(), up.getStepY(), up.getStepZ()));
    }

    /** "Arriba" de la entidad en coordenadas del mundo, o null si no hay gravedad propia girada. */
    public static Vector3d up(Entity entity) {
        Quaterniond q = orientation(entity, 1.0F);
        return q != null ? q.transform(new Vector3d(0.0, 1.0, 0.0)) : null;
    }
}
