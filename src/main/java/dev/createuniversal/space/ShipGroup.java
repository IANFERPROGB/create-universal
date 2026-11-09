package dev.createuniversal.space;

import dev.ryanhcode.sable.api.SubLevelHelper;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Piezas que tienen que cambiar de dimensión juntas.
 *
 * Sable solo conoce como "cadena" las naves unidas por sus propias uniones (rodamientos, bisagras...). Otras uniones, como
 * los remaches de Simulated Coasters, son articulaciones del motor de física que Sable no ve: un cohete con la cabina
 * remachada al cuerpo son dos naves sueltas para Sable. Si cada una sube por su cuenta, la de arriba se va primero, el
 * jugador se queda sin asiento y las dos llegan separadas. Aquí se juntan la cadena de Sable y cualquier nave que toque
 * a otra del grupo (cajas a menos de {@link #TOUCH} bloques), repetido hasta que no entra ninguna más.
 */
public final class ShipGroup {
    /** Separación máxima entre cajas para considerar que dos naves van pegadas. */
    private static final double TOUCH = 1.0;
    /** Tope de piezas por grupo, por si se juntan muchas naves aparcadas una contra otra. */
    private static final int MAX_SHIPS = 64;

    private ShipGroup() {}

    public static List<ServerSubLevel> of(ServerSubLevel root) {
        Set<ServerSubLevel> group = new LinkedHashSet<>();
        ServerLevel level = root.getLevel();
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        List<ServerSubLevel> all = container != null ? new ArrayList<>(container.getAllSubLevels()) : List.of();
        Deque<ServerSubLevel> queue = new ArrayDeque<>();
        queue.add(root);
        group.add(root);
        while (!queue.isEmpty() && group.size() < MAX_SHIPS) {
            ServerSubLevel ship = queue.poll();
            for (SubLevel linked : SubLevelHelper.getConnectedChain(ship)) {
                if (linked instanceof ServerSubLevel s && !s.isRemoved() && group.add(s)) {
                    queue.add(s);
                }
            }
            BoundingBox3dc a = ship.boundingBox();
            for (ServerSubLevel other : all) {
                if (other.isRemoved() || group.contains(other)) {
                    continue;
                }
                if (touches(a, other.boundingBox()) && group.add(other)) {
                    queue.add(other);
                }
            }
        }
        return new ArrayList<>(group);
    }

    /** ¿Llevan todas las piezas el tiempo suficiente en el mundo? (la Toolgun puede estar colocándolas aún) */
    public static boolean settled(List<ServerSubLevel> group, ServerLevel level) {
        boolean all = true;
        for (ServerSubLevel ship : group) {
            all &= ShipTracker.settled(ship, level);
        }
        return all;
    }

    private static boolean touches(BoundingBox3dc a, BoundingBox3dc b) {
        return a.minX() - TOUCH <= b.maxX() && b.minX() - TOUCH <= a.maxX()
                && a.minY() - TOUCH <= b.maxY() && b.minY() - TOUCH <= a.maxY()
                && a.minZ() - TOUCH <= b.maxZ() && b.minZ() - TOUCH <= a.maxZ();
    }
}
