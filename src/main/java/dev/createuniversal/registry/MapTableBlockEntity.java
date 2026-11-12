package dev.createuniversal.registry;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * Mesa de mapa (D47, alpha.11.1): varias mesas que forman un cuadrado perfecto a la misma altura (2×2, 3×3...) son una sola
 * pantalla; la de la esquina noroeste dibuja el mapa sobre todas. Si no forman un cuadrado, cada una dibuja el suyo. Solo
 * hace falta en el cliente (se mira cada medio segundo).
 */
public class MapTableBlockEntity extends BlockEntity {
    /** Lado máximo de una mesa. */
    public static final int MAX_SIZE = 24;

    private int size = 1;
    private boolean master = true;

    public MapTableBlockEntity(BlockPos pos, BlockState state) {
        super(CUMachines.MAP_TABLE_BE.get(), pos, state);
    }

    public int size() {
        return size;
    }

    public boolean isMaster() {
        return master;
    }

    public static void clientTick(Level level, BlockPos pos, BlockState state, MapTableBlockEntity be) {
        if (level.getGameTime() % 10 == Math.floorMod(pos.asLong(), 10)) {
            be.recompute();
        }
    }

    /** Busca las mesas unidas a esta (a la misma altura) y mira si forman un cuadrado. */
    void recompute() {
        if (level == null) {
            return;
        }
        Set<Long> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(worldPosition);
        seen.add(worldPosition.asLong());
        int minX = worldPosition.getX();
        int maxX = minX;
        int minZ = worldPosition.getZ();
        int maxZ = minZ;
        while (!queue.isEmpty() && seen.size() <= MAX_SIZE * MAX_SIZE) {
            BlockPos p = queue.poll();
            minX = Math.min(minX, p.getX());
            maxX = Math.max(maxX, p.getX());
            minZ = Math.min(minZ, p.getZ());
            maxZ = Math.max(maxZ, p.getZ());
            for (BlockPos n : new BlockPos[] {p.east(), p.west(), p.north(), p.south()}) {
                if (Math.abs(n.getX() - worldPosition.getX()) > MAX_SIZE || Math.abs(n.getZ() - worldPosition.getZ()) > MAX_SIZE) {
                    continue;
                }
                if (!seen.contains(n.asLong()) && level.getBlockState(n).getBlock() instanceof MapTableBlock) {
                    seen.add(n.asLong());
                    queue.add(n);
                }
            }
        }
        int w = maxX - minX + 1;
        int h = maxZ - minZ + 1;
        if (w == h && w <= MAX_SIZE && seen.size() == w * h) {
            size = w;
            master = worldPosition.getX() == minX && worldPosition.getZ() == minZ;
        } else {
            size = 1;
            master = true;
        }
    }
}
