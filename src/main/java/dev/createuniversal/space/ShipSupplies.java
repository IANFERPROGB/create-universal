package dev.createuniversal.space;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Lo que gasta cada nave con gente a bordo o con dueño (alpha.13.1, D54, D55): cada 2 s se mide su combustible líquido, su oxígeno
 * (el de los oxigenadores y depósitos) y su velocidad, y con lo de los últimos ~16 s se saca a qué ritmo gasta y cuánto
 * acelera. Al dormir en el espacio, eso dice cuándo se acabaría el combustible o el oxígeno (para despertar 5 minutos
 * antes), cómo se mueve la nave si va empujando, y cuánto hay que quitar de sus depósitos tras el salto.
 */
public final class ShipSupplies {
    private static final boolean PROPULSION = ModList.get().isLoaded("createpropulsion");
    private static final int SAMPLE_TICKS = 40;
    private static final int KEEP = 8;

    private record Sample(long tick, double fuel, double oxygen, Vec3 vel) {}

    /** Ritmos medidos: gasto (mB/s, positivo = baja), cantidades ahora y aceleración (b/s²). */
    public record Rates(double fuel, double fuelRate, double oxygen, double oxygenRate, Vec3 accel) {
        public static final Rates NONE = new Rates(0, 0, 0, 0, Vec3.ZERO);
    }

    private static final Map<UUID, ArrayDeque<Sample>> SAMPLES = new HashMap<>();

    private ShipSupplies() {}

    /** Clave estable de un grupo de piezas: el menor de sus UUID. */
    static UUID key(List<ServerSubLevel> group) {
        UUID best = null;
        for (ServerSubLevel s : group) {
            if (best == null || s.getUniqueId().compareTo(best) < 0) {
                best = s.getUniqueId();
            }
        }
        return best;
    }

    /** Cada tick del espacio: cada 2 s, una medida de las naves con algún jugador a bordo. */
    public static void tick(ServerLevel space) {
        if (space.getGameTime() % SAMPLE_TICKS != 0) {
            return;
        }
        ServerSubLevelContainer container = SubLevelContainer.getContainer(space);
        if (container == null) {
            SAMPLES.clear();
            return;
        }
        Set<UUID> alive = new HashSet<>();
        Set<UUID> seen = new HashSet<>();
        for (ServerSubLevel s : new ArrayList<>(container.getAllSubLevels())) {
            if (s.isRemoved() || seen.contains(s.getUniqueId()) || Moonlets.isMoonlet(s)) {
                continue;
            }
            List<ServerSubLevel> group = ShipGroup.of(s);
            group.forEach(g -> seen.add(g.getUniqueId()));
            // Las de alguien (siempre cargadas, con avisos al dueño) y las que llevan gente.
            boolean players = ShipKeeper.owner(group) != null;
            if (!players) {
                for (Entity e : ShipDestruction.aboard(space, group)) {
                    players |= e instanceof ServerPlayer;
                }
            }
            if (!players) {
                continue;
            }
            UUID key = key(group);
            alive.add(key);
            double[] f = measure(space, group);
            ArrayDeque<Sample> q = SAMPLES.computeIfAbsent(key, k -> new ArrayDeque<>());
            q.addLast(new Sample(space.getGameTime(), f[0], f[1], Autopilot.velocity(space, group.get(0))));
            while (q.size() > KEEP) {
                q.removeFirst();
            }
        }
        SAMPLES.keySet().retainAll(alive);
    }

    public static Rates rates(List<ServerSubLevel> group) {
        ArrayDeque<Sample> q = SAMPLES.get(key(group));
        if (q == null || q.size() < 2) {
            return Rates.NONE;
        }
        Sample a = q.getFirst();
        Sample b = q.getLast();
        double dt = (b.tick() - a.tick()) / 20.0;
        if (dt <= 0) {
            return Rates.NONE;
        }
        double fuelRate = Math.max(0.0, (a.fuel() - b.fuel()) / dt);
        double oxygenRate = Math.max(0.0, (a.oxygen() - b.oxygen()) / dt);
        Vec3 accel = b.vel().subtract(a.vel()).scale(1.0 / dt);
        // Si no gasta combustible, no está empujando: lo que cambie la velocidad es el astro (ya se calcula aparte).
        if (fuelRate <= 0.0 || accel.length() < 0.05) {
            accel = Vec3.ZERO;
        }
        return new Rates(b.fuel(), fuelRate, b.oxygen(), oxygenRate, accel);
    }

    /** {combustible líquido, oxígeno} de la nave en mB. */
    private static double[] measure(ServerLevel level, List<ServerSubLevel> group) {
        double fuel = 0;
        double oxygen = 0;
        for (IFluidHandler h : handlers(level, group)) {
            for (int i = 0; i < h.getTanks(); i++) {
                FluidStack fs = h.getFluidInTank(i);
                if (fs.isEmpty()) {
                    continue;
                }
                if (isOxygen(fs.getFluid())) {
                    oxygen += fs.getAmount();
                } else if (isFuel(fs.getFluid())) {
                    fuel += fs.getAmount();
                }
            }
        }
        return new double[] {fuel, oxygen};
    }

    /** Quita lo que se habría gastado en {@code seconds} segundos (sin pasar de lo que hay). */
    public static void consume(ServerLevel level, List<ServerSubLevel> group, Rates r, double seconds) {
        double fuelLeft = Math.min(r.fuel(), r.fuelRate() * seconds);
        double oxygenLeft = Math.min(r.oxygen(), r.oxygenRate() * seconds);
        if (fuelLeft <= 0 && oxygenLeft <= 0) {
            return;
        }
        for (IFluidHandler h : handlers(level, group)) {
            for (int i = 0; i < h.getTanks() && (fuelLeft > 0 || oxygenLeft > 0); i++) {
                FluidStack fs = h.getFluidInTank(i);
                if (fs.isEmpty()) {
                    continue;
                }
                if (isOxygen(fs.getFluid()) && oxygenLeft > 0) {
                    oxygenLeft -= h.drain(fs.copyWithAmount((int) Math.min(fs.getAmount(), Math.ceil(oxygenLeft))),
                            IFluidHandler.FluidAction.EXECUTE).getAmount();
                } else if (isFuel(fs.getFluid()) && fuelLeft > 0) {
                    fuelLeft -= h.drain(fs.copyWithAmount((int) Math.min(fs.getAmount(), Math.ceil(fuelLeft))),
                            IFluidHandler.FluidAction.EXECUTE).getAmount();
                }
            }
        }
    }

    /** Todos los depósitos de fluidos de la nave, cada uno una vez (los tanques de Create, solo el principal). */
    private static List<IFluidHandler> handlers(ServerLevel level, List<ServerSubLevel> group) {
        Set<IFluidHandler> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<IFluidHandler> out = new ArrayList<>();
        for (ServerSubLevel ship : group) {
            var plot = ship.getPlot();
            for (int cx = plot.getChunkMin().x; cx <= plot.getChunkMax().x; cx++) {
                for (int cz = plot.getChunkMin().z; cz <= plot.getChunkMax().z; cz++) {
                    if (!(level.getChunk(cx, cz, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false) instanceof LevelChunk chunk)) {
                        continue;
                    }
                    for (BlockEntity be : chunk.getBlockEntities().values()) {
                        if (be instanceof com.simibubi.create.content.fluids.tank.FluidTankBlockEntity tank) {
                            if (tank.isController() && seen.add(tank.getTankInventory())) {
                                out.add(tank.getTankInventory());
                            }
                            continue;
                        }
                        IFluidHandler h = level.getCapability(Capabilities.FluidHandler.BLOCK, be.getBlockPos(), null);
                        if (h != null && seen.add(h)) {
                            out.add(h);
                        }
                    }
                }
            }
        }
        return out;
    }

    private static boolean isOxygen(Fluid fluid) {
        return BuiltInRegistries.FLUID.getKey(fluid).getPath().contains("oxygen");
    }

    private static boolean isFuel(Fluid fluid) {
        return PROPULSION && PropulsionEngines.isFuel(fluid);
    }
}
