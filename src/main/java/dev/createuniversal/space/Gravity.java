package dev.createuniversal.space;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gravedad de una entidad dentro del área de un Estabilizador de gravedad (D50, D51, D91), en el servidor y en el cliente
 * (que es quien mueve al jugador). La dirección la lleva Gravity Control (dentro de nuestro jar, ver {@link GravityFields});
 * aquí se guarda la fuerza, que se aplica en {@code EntitySpaceGravityMixin} (también en el espacio, donde no hay gravedad).
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
}
