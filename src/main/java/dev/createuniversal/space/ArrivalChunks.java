package dev.createuniversal.space;

import dev.createuniversal.CreateUniversal;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

import java.util.Collection;
import java.util.Comparator;

/**
 * Terreno cargado donde llega una nave (alpha.5.7).
 *
 * Al llegar a la Tierra lejos de cualquier jugador, Sable guardaba la nave como "descargada" en el acto porque su suelo
 * no estaba cargado, y desaparecía de la vista. Y si el terreno de debajo se genera tarde, una nave que va a chocar
 * podría acabar dentro de la piedra. Ahora, al llegar:
 * <ul>
 *   <li>Los chunks bajo la nave (su caja más uno alrededor) se cargan en el momento, con el terreno ya generado.</li>
 *   <li>Un ticket de 30 s mantiene cargada y activa la zona (caja más 3 chunks), hasta que el jugador la cargue por sí mismo.</li>
 * </ul>
 */
public final class ArrivalChunks {
    /** 30 s de juego. */
    private static final TicketType<ChunkPos> ARRIVAL = TicketType.create(CreateUniversal.MOD_ID + ":arrival",
            Comparator.comparingLong(ChunkPos::toLong), 600);
    /** Como mucho 11 x 11 chunks cargados en el momento por nave. */
    private static final int MAX_SYNC_RADIUS = 5;
    private static final int MAX_TICKET_RADIUS = 10;

    private ArrivalChunks() {}

    public static void load(ServerLevel level, Collection<ServerSubLevel> ships) {
        long start = System.nanoTime();
        int loaded = 0;
        for (ServerSubLevel ship : ships) {
            BoundingBox3dc b = ship.boundingBox();
            int minX = (int) Math.floor(b.minX()) >> 4;
            int maxX = (int) Math.floor(b.maxX()) >> 4;
            int minZ = (int) Math.floor(b.minZ()) >> 4;
            int maxZ = (int) Math.floor(b.maxZ()) >> 4;
            ChunkPos center = new ChunkPos((minX + maxX) >> 1, (minZ + maxZ) >> 1);
            int half = Math.max(maxX - minX, maxZ - minZ) / 2 + 1;
            int sync = Math.min(half, MAX_SYNC_RADIUS);
            for (int x = center.x - sync; x <= center.x + sync; x++) {
                for (int z = center.z - sync; z <= center.z + sync; z++) {
                    level.getChunk(x, z);
                    loaded++;
                }
            }
            level.getChunkSource().addRegionTicket(ARRIVAL, center, Math.min(half + 3, MAX_TICKET_RADIUS), center);
        }
        CreateUniversal.LOGGER.info("Terreno de llegada en {}: {} chunks cargados en {} ms", level.dimension().location(), loaded,
                (System.nanoTime() - start) / 1_000_000);
    }
}
