package dev.createuniversal.space;

import dev.createuniversal.CreateUniversal;
import dev.createuniversal.network.AssistantHudPayload;
import dev.createuniversal.network.AssistantInfoPayload;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * El Asistente de nave (alpha.12, D61, D64): revisa la nave en la que está y cuenta lo que lleva.
 *
 * <ul>
 *   <li><b>Motores:</b> los propulsores de Create Propulsion, con hacia dónde empujan, si funcionan donde están (los de
 *       líquido, a la mitad sin aire ni oxidante) y su combustible.</li>
 *   <li><b>Depósitos:</b> los tanques de fluidos de Create.</li>
 *   <li><b>"Otros":</b> bloques que parecen motores (por su nombre) y que el asistente no sabe manejar. El jugador puede
 *       marcarlos: cuentan como motores y los mueve la salida de redstone del asistente.</li>
 *   <li><b>Casco:</b> la masa de la nave frente a la mayor que se le ha visto.</li>
 * </ul>
 */
public final class ShipAssistant {
    private static final boolean PROPULSION = ModList.get().isLoaded("createpropulsion");
    /** Nombres de bloque que suenan a motor (para la lista de "Otros"). */
    private static final Pattern ENGINE_LIKE = Pattern.compile(
            "thrust|engine|rocket|jet|propel|motor|booster|turbine|drive|nozzle|burner|combustor|afterburner|ion_");

    /** Un propulsor; {@code items} = combustible sólido que le queda (objetos, contando el que está quemando). */
    public record Engine(BlockPos pos, String kind, Direction push, int efficiency, boolean active, int fuel, int fuelCapacity, int items) {}

    /** Lo que lleva la nave. Los depósitos solo cuentan si llevan combustible (o están vacíos); la energía, en FE. */
    public record Result(List<Engine> engines, TreeMap<String, Integer> others, long tankFluid, long tankCapacity, double mass,
                         long energy, long energyCapacity) {
        public int solidItems() {
            int n = 0;
            for (Engine e : engines) {
                n += e.items();
            }
            return n;
        }

        public long fuel() {
            long f = tankFluid;
            for (Engine e : engines) {
                f += e.fuel();
            }
            return f;
        }

        public long fuelCapacity() {
            long c = tankCapacity;
            for (Engine e : engines) {
                c += e.fuelCapacity();
            }
            return c;
        }

        /** Motores que funcionan aquí (los marcados de "Otros" cuentan como que sí). */
        public int working(Set<String> marked) {
            int n = 0;
            for (Engine e : engines) {
                n += e.efficiency() > 0 ? 1 : 0;
            }
            for (var o : others.entrySet()) {
                n += marked.contains(o.getKey()) ? o.getValue() : 0;
            }
            return n;
        }

        public int total(Set<String> marked) {
            int n = engines.size();
            for (var o : others.entrySet()) {
                n += marked.contains(o.getKey()) ? o.getValue() : 0;
            }
            return n;
        }
    }

    private ShipAssistant() {}

    /** Revisa la nave entera (todos los bloques con datos de su zona interna). */
    public static Result scan(ServerLevel level, ServerSubLevel ship) {
        List<Engine> engines = new ArrayList<>();
        TreeMap<String, Integer> others = new TreeMap<>();
        long fluid = 0;
        long capacity = 0;
        long energy = 0;
        long energyCapacity = 0;
        var plot = ship.getPlot();
        for (int cx = plot.getChunkMin().x; cx <= plot.getChunkMax().x; cx++) {
            for (int cz = plot.getChunkMin().z; cz <= plot.getChunkMax().z; cz++) {
                if (!(level.getChunk(cx, cz, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false) instanceof LevelChunk chunk)) {
                    continue;
                }
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    // Energía (baterías, propulsores iónicos...): todo lo que la guarde.
                    var es = level.getCapability(net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK, be.getBlockPos(), null);
                    if (es != null && es.getMaxEnergyStored() > 0) {
                        energy += es.getEnergyStored();
                        energyCapacity += es.getMaxEnergyStored();
                    }
                    if (PROPULSION) {
                        Engine e = PropulsionEngines.engine(be);
                        if (e != null) {
                            engines.add(e);
                            continue;
                        }
                    }
                    if (be instanceof com.simibubi.create.content.fluids.tank.FluidTankBlockEntity tank) {
                        var content = tank.getTankInventory().getFluid();
                        // Solo los depósitos de combustible (o vacíos): un tanque de agua no es combustible.
                        if (tank.isController() && (!PROPULSION || content.isEmpty() || PropulsionEngines.isFuel(content.getFluid()))) {
                            fluid += content.getAmount();
                            capacity += tank.getTankInventory().getCapacity();
                        }
                        continue;
                    }
                    ResourceLocation id = BuiltInRegistries.BLOCK.getKey(be.getBlockState().getBlock());
                    if (id.getNamespace().equals("create") || id.getNamespace().equals(CreateUniversal.MOD_ID)
                            || id.getNamespace().equals("createpropulsion")) {
                        continue;
                    }
                    if (ENGINE_LIKE.matcher(id.getPath()).find()) {
                        others.merge(id.toString(), 1, Integer::sum);
                    }
                }
            }
        }
        double mass = ship.getMassTracker() != null ? ship.getMassTracker().getMass() : 0.0;
        return new Result(engines, others, fluid, capacity, mass, energy, energyCapacity);
    }

    /** Velocidad de la nave (bloques/s) respecto al astro en cuya zona está (en el espacio) o al suelo. */
    public static double speed(ServerLevel level, ServerSubLevel ship) {
        var mass = ship.getMassTracker();
        Vector3d com = mass != null ? new Vector3d(mass.getCenterOfMass()) : new Vector3d();
        Vector3d v = Sable.HELPER.getVelocity(level, ship, com, new Vector3d());
        Vec3 vel = new Vec3(v.x, v.y, v.z);
        if (level.dimension() == CUDimensions.SPACE) {
            double time = SpaceLayout.time(level);
            SolarSystem.Body body = SolarSystem.frameBody(OrbitalFrame.centerOf(ship), time);
            if (body != null) {
                vel = vel.subtract(SolarSystem.velocity(body, time));
            }
        }
        return vel.length();
    }

    /** Si alguien de a bordo ha descubierto un astro nuevo desde {@code since} (tiempo del Overworld). */
    public static boolean discoveredSince(ServerLevel level, ServerSubLevel ship, long since) {
        for (Entity e : ShipDestruction.aboard(level, ShipGroup.of(ship))) {
            Long at = Navigation.LAST_DISCOVERY.get(e.getUUID());
            if (at != null && at >= since) {
                return true;
            }
        }
        return false;
    }

    /** Manda el panel de arriba de la pantalla a todos los que van a bordo. */
    public static void sendHud(ServerLevel level, ServerSubLevel ship, AssistantHudPayload hud) {
        for (Entity e : ShipDestruction.aboard(level, ShipGroup.of(ship))) {
            if (e instanceof ServerPlayer p) {
                PacketDistributor.sendToPlayer(p, hud);
            }
        }
    }

    /** Potencia de 0 a 1 para un propulsor que el asistente sabe manejar; negativo = devolverle el control a la redstone. */
    public static void setPower(BlockEntity be, float power) {
        if (PROPULSION && be != null) {
            PropulsionEngines.setPower(be, power);
        }
    }

    /** La ficha completa para la pantalla del asistente. */
    public static AssistantInfoPayload info(BlockPos pos, Result r, Set<String> marked, boolean gyroReady, boolean piloting) {
        TreeMap<String, Integer> groups = new TreeMap<>();
        for (Engine e : r.engines()) {
            String key = e.kind() + "|" + e.push().getName() + "|" + e.efficiency() + "|" + (e.active() ? 1 : 0);
            groups.merge(key, 1, Integer::sum);
        }
        List<AssistantInfoPayload.EngineRow> rows = new ArrayList<>();
        for (var g : groups.entrySet()) {
            String[] p = g.getKey().split("\\|");
            rows.add(new AssistantInfoPayload.EngineRow(p[0], p[1], Integer.parseInt(p[2]), p[3].equals("1"), g.getValue()));
        }
        List<AssistantInfoPayload.OtherRow> others = new ArrayList<>();
        for (var o : r.others().entrySet()) {
            others.add(new AssistantInfoPayload.OtherRow(o.getKey(), o.getValue(), marked.contains(o.getKey())));
        }
        return new AssistantInfoPayload(pos, rows, others, r.fuel(), r.fuelCapacity(), r.mass(), gyroReady, piloting, r.energy(),
                r.energyCapacity(), r.solidItems());
    }
}
