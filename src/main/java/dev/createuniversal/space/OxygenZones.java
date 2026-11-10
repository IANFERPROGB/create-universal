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
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import org.joml.Vector3d;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Salas con oxígeno (alpha.7.0.3, ampliado en la 7.2): desde cada oxigenador de Create Deep Seas que esté funcionando
 * (el difusor con señal de redstone y oxígeno en su depósito, o el oxigenador creativo) se rellena el aire de la sala
 * donde está, con la misma regla de Deep Seas para lo que es pared (lo que tiene colisión; puertas, trampillas y
 * portillos solo si están cerrados). Si el relleno se sale de la nave o pasa de {@link #MAX_CELLS} bloques, la sala no está
 * cerrada.
 *
 * <ul>
 *   <li>Funciona en naves de Sable (la sala va en coordenadas de la nave y se mueve con ella) y en el suelo.</li>
 *   <li>La sala se llena poco a poco ({@link #FILL_PER_TICK} bloques de aire por tick): hasta que está llena no se
 *       respira. Al encender el oxigenador, o al volver a cerrar tras una descompresión, hay que esperar.</li>
 *   <li>Se comprueba cada medio segundo y, además, en cuanto se abre o cierra una puerta, se rompe o pone un bloque o hay
 *       una explosión cerca. Si una sala con aire se abre al vacío, el aire sale de golpe ({@link Decompression}).</li>
 * </ul>
 */
public final class OxygenZones {
    /** Tamaño máximo de una sala (bloques de aire). */
    public static final int MAX_CELLS = 32768;
    /** Bloques de aire que mete el oxigenador por tick (200 por segundo). */
    public static final int FILL_PER_TICK = 10;
    private static final long RESCAN_TICKS = 10;
    private static final long FORGET_TICKS = 40;

    /** Sala de un oxigenador. {@code ship} = nave donde está (celdas en sus coordenadas internas), o null en el suelo. */
    static final class Zone {
        final ResourceKey<Level> dimension;
        final UUID ship;
        final LongOpenHashSet cells;
        final long scannedAt;
        final int minX, minY, minZ, maxX, maxY, maxZ;
        /** Bloques de aire que hay ya dentro. */
        volatile double filled;

        Zone(ResourceKey<Level> dimension, UUID ship, LongOpenHashSet cells, long scannedAt) {
            this.dimension = dimension;
            this.ship = ship;
            this.cells = cells;
            this.scannedAt = scannedAt;
            int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE;
            int x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
            for (long c : cells) {
                x0 = Math.min(x0, BlockPos.getX(c));
                y0 = Math.min(y0, BlockPos.getY(c));
                z0 = Math.min(z0, BlockPos.getZ(c));
                x1 = Math.max(x1, BlockPos.getX(c));
                y1 = Math.max(y1, BlockPos.getY(c));
                z1 = Math.max(z1, BlockPos.getZ(c));
            }
            minX = x0;
            minY = y0;
            minZ = z0;
            maxX = x1;
            maxY = y1;
            maxZ = z1;
        }

        boolean full() {
            return filled >= cells.size();
        }

        boolean near(BlockPos p) {
            return p.getX() >= minX - 2 && p.getX() <= maxX + 2 && p.getY() >= minY - 2 && p.getY() <= maxY + 2
                    && p.getZ() >= minZ - 2 && p.getZ() <= maxZ + 2;
        }

        /** Centro de la sala, en sus coordenadas. */
        Vec3 center() {
            double x = 0, y = 0, z = 0;
            for (long c : cells) {
                x += BlockPos.getX(c);
                y += BlockPos.getY(c);
                z += BlockPos.getZ(c);
            }
            int n = Math.max(1, cells.size());
            return new Vec3(x / n + 0.5, y / n + 0.5, z / n + 0.5);
        }
    }

    /** Resultado de rellenar: la sala, o por dónde se escapa el aire (en coordenadas de la sala), o "no se sabe". */
    record Scan(Zone zone, BlockPos opening, boolean unknown) {}

    private static final Map<String, Zone> ZONES = new ConcurrentHashMap<>();
    private static final Map<String, Long> LAST_SEEN = new ConcurrentHashMap<>();
    private static final Set<String> DIRTY = ConcurrentHashMap.newKeySet();

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
        Zone old = ZONES.get(k);
        boolean dirty = DIRTY.remove(k);
        if (old == null || dirty || now - old.scannedAt >= RESCAN_TICKS) {
            Scan scan = scan(level, pos, now, old != null ? old.cells : null);
            if (scan.zone() != null) {
                // Lo que ya había dentro se queda (si la sala ha crecido, ahora le falta aire).
                scan.zone().filled = old != null ? Math.min(old.filled, scan.zone().cells.size()) : 0.0;
                ZONES.put(k, scan.zone());
            } else if (!scan.unknown()) {
                ZONES.remove(k);
                if (old != null && old.filled > 0 && Sunlight.handles(level)) {
                    Decompression.start(level, old, scan.opening());
                }
            }
        }
        Zone zone = ZONES.get(k);
        if (zone != null && !zone.full()) {
            zone.filled = Math.min(zone.cells.size(), zone.filled + FILL_PER_TICK);
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

    /** ¿Hay aire (sala llena) en ese punto del mundo? */
    public static boolean contains(Level level, Vec3 worldPos) {
        Zone zone = zoneAt(level, worldPos);
        return zone != null && zone.full();
    }

    /**
     * ¿Da alguna cara de este bloque a una sala llena de aire? La posición es la del bloque tal como está guardado: en una
     * nave, la de su zona interna; en el suelo, la del mundo.
     */
    public static boolean blockHasAir(Level level, BlockPos pos) {
        if (ZONES.isEmpty()) {
            return false;
        }
        SubLevel sub = Sable.HELPER.getContaining(level, pos);
        UUID ship = sub != null ? sub.getUniqueId() : null;
        for (Zone zone : ZONES.values()) {
            if (zone.dimension != level.dimension() || !java.util.Objects.equals(zone.ship, ship) || !zone.full() || !zone.near(pos)) {
                continue;
            }
            if (zone.cells.contains(pos.asLong())) {
                return true;
            }
            for (Direction d : Direction.values()) {
                if (zone.cells.contains(pos.relative(d).asLong())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Si estás en una sala que se está llenando, cuánto le falta (0..1); si no, -1. */
    public static double fillingAt(Level level, Vec3 worldPos) {
        Zone zone = zoneAt(level, worldPos);
        if (zone == null || zone.full()) {
            return -1.0;
        }
        return zone.filled / Math.max(1, zone.cells.size());
    }

    private static Zone zoneAt(Level level, Vec3 worldPos) {
        if (ZONES.isEmpty()) {
            return null;
        }
        Zone best = null;
        for (Zone zone : ZONES.values()) {
            if (zone.dimension != level.dimension()) {
                continue;
            }
            BlockPos cell = toZone(level, zone.ship, worldPos);
            if (cell != null && zone.cells.contains(cell.asLong()) && (best == null || zone.filled > best.filled)) {
                best = zone;
            }
        }
        return best;
    }

    /** Punto del mundo a coordenadas de la sala (las de la nave si va en una). Null si la nave ya no está. */
    static BlockPos toZone(Level level, UUID ship, Vec3 worldPos) {
        if (ship == null) {
            return BlockPos.containing(worldPos);
        }
        SubLevel sub = ship(level, ship);
        if (sub == null) {
            return null;
        }
        Vector3d local = new Vector3d(worldPos.x, worldPos.y, worldPos.z);
        sub.logicalPose().transformPositionInverse(local);
        return BlockPos.containing(local.x, local.y, local.z);
    }

    static SubLevel ship(Level level, UUID id) {
        var container = SubLevelContainer.getContainer(level);
        SubLevel sub = container != null ? container.getSubLevel(id) : null;
        return sub == null || sub.isRemoved() ? null : sub;
    }

    // ---------------------------------------------------------------- comprobar en cuanto algo cambia

    private static void markNear(LevelAccessor accessor, BlockPos pos) {
        if (!(accessor instanceof Level level) || level.isClientSide || ZONES.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Zone> e : ZONES.entrySet()) {
            if (e.getValue().dimension == level.dimension() && e.getValue().near(pos)) {
                DIRTY.add(e.getKey());
            }
        }
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        markNear(event.getLevel(), event.getPos());
    }

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        markNear(event.getLevel(), event.getPos());
    }

    @SubscribeEvent
    public static void onNeighborChange(BlockEvent.NeighborNotifyEvent event) {
        markNear(event.getLevel(), event.getPos());
    }

    /** Abrir o cerrar una puerta, trampilla o portillo no avisa a los vecinos: se mira al usarla. */
    @SubscribeEvent
    public static void onUse(PlayerInteractEvent.RightClickBlock event) {
        BlockState state = event.getLevel().getBlockState(event.getPos());
        if (state.getBlock() instanceof DoorBlock || state.getBlock() instanceof TrapDoorBlock || state.getBlock() instanceof FenceGateBlock) {
            markNear(event.getLevel(), event.getPos());
        }
    }

    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        for (BlockPos pos : event.getAffectedBlocks()) {
            markNear(event.getLevel(), pos);
        }
        markNear(event.getLevel(), BlockPos.containing(event.getExplosion().center()));
    }

    /** El área cerrada alrededor de un bloque (para el Estabilizador de gravedad), con la misma regla que las salas. */
    static Scan room(Level level, BlockPos source) {
        return scan(level, source, level.getGameTime(), null);
    }

    // ---------------------------------------------------------------- rellenar

    /**
     * Rellena el aire alrededor del oxigenador. Si se escapa, se da por dónde: la primera celda que no estaba en la sala
     * de antes (la puerta o el agujero nuevo) o, si no había sala, donde salió.
     */
    private static Scan scan(Level level, BlockPos source, long now, LongOpenHashSet before) {
        SubLevel ship = Sable.HELPER.getContaining(level, source);
        LevelPlot plot = ship != null ? ship.getPlot() : null;
        var bounds = plot != null ? plot.getBoundingBox() : null;
        LongOpenHashSet cells = new LongOpenHashSet();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        BlockPos firstNew = null;
        for (Direction d : Direction.values()) {
            BlockPos start = source.relative(d);
            Boolean open = permeable(level, plot, start);
            if (open == null) {
                return new Scan(null, null, true);
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
                boolean outside = bounds != null
                        ? next.getX() < bounds.minX() || next.getX() > bounds.maxX() || next.getY() < bounds.minY()
                                || next.getY() > bounds.maxY() || next.getZ() < bounds.minZ() || next.getZ() > bounds.maxZ()
                        : level.isOutsideBuildHeight(next);
                if (outside) {
                    return new Scan(null, firstNew != null ? firstNew : cur, false);
                }
                Boolean open = permeable(level, plot, next);
                if (open == null) {
                    return new Scan(null, null, true);
                }
                if (open) {
                    cells.add(next.asLong());
                    if (before != null && firstNew == null && !before.contains(next.asLong())) {
                        firstNew = next;
                    }
                    if (cells.size() > MAX_CELLS) {
                        return new Scan(null, firstNew != null ? firstNew : next, false);
                    }
                    queue.add(next);
                }
            }
        }
        if (cells.isEmpty()) {
            return new Scan(null, null, false);
        }
        return new Scan(new Zone(level.dimension(), ship != null ? ship.getUniqueId() : null, cells, now), null, false);
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
