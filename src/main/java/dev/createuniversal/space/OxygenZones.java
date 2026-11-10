package dev.createuniversal.space;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Salas con oxígeno (alpha.7.0.3): desde cada oxigenador de Create Deep Seas que esté funcionando
 * (el difusor con señal de redstone y oxígeno en su depósito, o el oxigenador creativo) se rellena el aire de la sala
 * donde está, con la misma regla de Deep Seas para lo que es pared (lo que tiene colisión; puertas, trampillas y
 * portillos solo si están cerrados). Si el relleno se sale de la nave o pasa de {@link #MAX_CELLS} bloques, la sala no está
 * cerrada y no hay aire.
 *
 * Funciona en naves de Sable (la sala se guarda en coordenadas de la nave y se mueve con ella) y en el suelo (una base en
 * la Luna). Se recalcula cada 2 segundos mientras el oxigenador funciona; si deja de funcionar, la sala se queda sin aire.
 */
public final class OxygenZones {
    /** Tamaño máximo de una sala (bloques de aire). */
    public static final int MAX_CELLS = 32768;
    private static final long RESCAN_TICKS = 40;
    private static final long FORGET_TICKS = 40;

    /** Sala de un oxigenador. {@code ship} = nave donde está (celdas en sus coordenadas internas), o null en el suelo. */
    private record Zone(ResourceKey<Level> dimension, UUID ship, LongOpenHashSet cells, long scannedAt) {}

    private static final Map<String, Zone> ZONES = new ConcurrentHashMap<>();
    private static final Map<String, Long> LAST_SEEN = new ConcurrentHashMap<>();

    private OxygenZones() {}

    private static String key(Level level, BlockPos pos) {
        return level.dimension().location() + "@" + pos.asLong();
    }

    /** Lo llama cada oxigenador en su tick de servidor. */
    public static void sourceTick(Level level, BlockPos pos, boolean running) {
        if (level.isClientSide) {
            return;
        }
        String k = key(level, pos);
        if (!running) {
            ZONES.remove(k);
            LAST_SEEN.remove(k);
            return;
        }
        long now = level.getGameTime();
        LAST_SEEN.put(k, now);
        Zone zone = ZONES.get(k);
        if (zone == null || now - zone.scannedAt() >= RESCAN_TICKS) {
            Zone fresh = scan(level, pos, now);
            if (fresh == null) {
                ZONES.remove(k);
            } else {
                ZONES.put(k, fresh);
            }
        }
    }

    /** Cada tick del servidor: se olvidan las salas de oxigenadores que ya no existen (rotos o descargados). */
    public static void tick(Level anyLevel) {
        long now = anyLevel.getGameTime();
        LAST_SEEN.entrySet().removeIf(e -> {
            if (now - e.getValue() > FORGET_TICKS) {
                ZONES.remove(e.getKey());
                return true;
            }
            return false;
        });
    }

    /** ¿Hay aire de un oxigenador en ese punto del mundo? */
    public static boolean contains(Level level, Vec3 worldPos) {
        if (ZONES.isEmpty()) {
            return false;
        }
        for (Zone zone : ZONES.values()) {
            if (zone.dimension() != level.dimension()) {
                continue;
            }
            BlockPos cell;
            if (zone.ship() == null) {
                cell = BlockPos.containing(worldPos);
            } else {
                var container = SubLevelContainer.getContainer(level);
                SubLevel ship = container != null ? container.getSubLevel(zone.ship()) : null;
                if (ship == null || ship.isRemoved()) {
                    continue;
                }
                Vector3d local = new Vector3d(worldPos.x, worldPos.y, worldPos.z);
                ship.logicalPose().transformPositionInverse(local);
                cell = BlockPos.containing(local.x, local.y, local.z);
            }
            if (zone.cells().contains(cell.asLong())) {
                return true;
            }
        }
        return false;
    }

    /** Rellena el aire alrededor del oxigenador. Null si no hay una sala cerrada. */
    private static Zone scan(Level level, BlockPos source, long now) {
        SubLevel ship = Sable.HELPER.getContaining(level, source);
        LevelPlot plot = ship != null ? ship.getPlot() : null;
        var bounds = plot != null ? plot.getBoundingBox() : null;
        LongOpenHashSet cells = new LongOpenHashSet();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        for (Direction d : Direction.values()) {
            BlockPos start = source.relative(d);
            Boolean open = permeable(level, plot, start);
            if (open == null) {
                return null;
            }
            if (open && cells.add(start.asLong())) {
                queue.add(start);
            }
        }
        while (!queue.isEmpty()) {
            BlockPos cur = queue.poll();
            for (Direction d : Direction.values()) {
                BlockPos next = cur.relative(d);
                if (cells.contains(next.asLong())) {
                    continue;
                }
                if (bounds != null && (next.getX() < bounds.minX() || next.getX() > bounds.maxX() || next.getY() < bounds.minY()
                        || next.getY() > bounds.maxY() || next.getZ() < bounds.minZ() || next.getZ() > bounds.maxZ())) {
                    return null; // se sale de la nave: abierta al exterior
                }
                if (bounds == null && level.isOutsideBuildHeight(next)) {
                    return null;
                }
                Boolean open = permeable(level, plot, next);
                if (open == null) {
                    return null; // chunk sin cargar: no se sabe, mejor sin aire
                }
                if (open) {
                    cells.add(next.asLong());
                    if (cells.size() > MAX_CELLS) {
                        return null;
                    }
                    queue.add(next);
                }
            }
        }
        return cells.isEmpty() ? null : new Zone(level.dimension(), ship != null ? ship.getUniqueId() : null, cells, now);
    }

    /** La misma regla que Deep Seas: deja pasar el aire lo que no tiene colisión, y las puertas abiertas. Null = sin cargar. */
    private static Boolean permeable(Level level, LevelPlot plot, BlockPos pos) {
        // En una nave, los bloques están en su zona interna (como los lee Deep Seas); en el suelo, en el mundo.
        LevelChunk chunk = plot != null ? plot.getChunk(plot.toLocal(new net.minecraft.world.level.ChunkPos(pos)))
                : level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) {
            return null;
        }
        BlockState state = chunk.getBlockState(pos);
        if (state.isAir()) {
            return true;
        }
        if (state.getBlock() instanceof DoorBlock || state.getBlock() instanceof TrapDoorBlock || state.getBlock() instanceof FenceGateBlock) {
            return state.getValue(BlockStateProperties.OPEN);
        }
        return state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
    }
}
