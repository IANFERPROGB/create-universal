package dev.createuniversal.space;

import dev.createuniversal.CreateUniversal;
import dev.ryanhcode.sable.api.SubLevelHelper;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelSerializer;
import dev.ryanhcode.sable.util.SableNBTUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Mueve una nave de Sable (y todo lo unido a ella) de una dimensión a otra con lo que lleva dentro (D10).
 *
 * Usa el propio serializador de Sable ({@link SubLevelSerializer}), que vuelve a crear la nave en el mismo índice de plot:
 * los bloques y block entities conservan sus coordenadas internas y no hay que recolocarlos.
 * El plot no guarda entidades, así que asientos, marcos, mobs y jugadores se mueven aparte:
 * <ul>
 *   <li>Entidades dentro del plot (asientos de Create, etc.): se guardan en NBT y se vuelven a crear con las mismas coordenadas de plot.</li>
 *   <li>Entidades en el mundo a bordo (jugadores de pie, mobs): se pasan a coordenadas de plot con la pose vieja y vuelven al mundo con la nueva.</li>
 * </ul>
 */
public final class ShipTransfer {
    private ShipTransfer() {}

    /** Jugador que tiene que volver a montar en su asiento unos ticks después de llegar. */
    public record PendingRemount(UUID player, UUID vehicle, ServerLevel level, int ticksLeft) {}

    private static final List<PendingRemount> REMOUNTS = new ArrayList<>();

    /**
     * @param root     la nave que ha cruzado el umbral
     * @param target   dimensión de destino
     * @param newRootPosition posición en el mundo de destino para la pose de la nave raíz
     * @param userData datos de Create Universal a guardar en la nave (puede ser null)
     * @return true si se ha movido
     */
    public static boolean transfer(ServerSubLevel root, ServerLevel target, Vec3 newRootPosition, CompoundTag userData) {
        ServerLevel source = root.getLevel();
        ServerSubLevelContainer sourceContainer = SubLevelContainer.getContainer(source);
        ServerSubLevelContainer targetContainer = SubLevelContainer.getContainer(target);
        if (sourceContainer == null || targetContainer == null) {
            return false;
        }

        List<ServerSubLevel> chain = new ArrayList<>();
        for (SubLevel s : SubLevelHelper.getConnectedChain(root)) {
            if (s instanceof ServerSubLevel ss && !ss.isRemoved()) {
                chain.add(ss);
            }
        }
        if (!chain.contains(root)) {
            chain.add(root);
        }

        Vector3d delta = new Vector3d(newRootPosition.x, newRootPosition.y, newRootPosition.z).sub(root.logicalPose().position());
        List<UUID> chainIds = chain.stream().map(SubLevel::getUniqueId).toList();

        // 1. Recoger a bordo: entidades del plot (coordenadas de plot) y entidades del mundo (pasadas a coordenadas de plot).
        Map<ServerSubLevel, List<Entity>> plotEntities = new HashMap<>();
        List<Entity> worldEntities = new ArrayList<>();
        Map<Entity, Vec3> worldEntityPlotPos = new HashMap<>();
        Map<Entity, ServerSubLevel> worldEntityShip = new HashMap<>();
        Map<UUID, UUID> riding = new HashMap<>();

        for (ServerSubLevel ship : chain) {
            BoundingBox3ic plotBox = ship.getPlot().getBoundingBox();
            AABB plotAabb = new AABB(plotBox.minX(), plotBox.minY(), plotBox.minZ(),
                    plotBox.maxX() + 1, plotBox.maxY() + 1, plotBox.maxZ() + 1).inflate(1.0);
            plotEntities.put(ship, new ArrayList<>(source.getEntities((Entity) null, plotAabb, e -> !(e instanceof ServerPlayer))));

            BoundingBox3dc world = ship.boundingBox();
            AABB worldAabb = new AABB(world.minX(), world.minY(), world.minZ(), world.maxX(), world.maxY(), world.maxZ()).inflate(2.0);
            for (Entity e : source.getEntities((Entity) null, worldAabb, e -> true)) {
                if (worldEntityShip.containsKey(e) || plotEntities.get(ship).contains(e)) {
                    continue;
                }
                worldEntities.add(e);
                worldEntityShip.put(e, ship);
                worldEntityPlotPos.put(e, ship.logicalPose().transformPositionInverse(e.position()));
            }
        }
        // Pasajeros de asientos del plot. Sable coloca a quien va sentado en coordenadas del plot, no del mundo, así que
        // no aparece en la búsqueda por el volumen de la nave: se asigna a la nave de su asiento y su posición de plot es
        // la del asiento.
        for (Map.Entry<ServerSubLevel, List<Entity>> entry : plotEntities.entrySet()) {
            for (Entity vehicle : entry.getValue()) {
                for (Entity passenger : vehicle.getPassengers()) {
                    riding.put(passenger.getUUID(), vehicle.getUUID());
                    worldEntityShip.put(passenger, entry.getKey());
                    worldEntityPlotPos.put(passenger, vehicle.position().add(0.0, 0.5, 0.0));
                    if (!worldEntities.contains(passenger)) {
                        worldEntities.add(passenger);
                    }
                }
            }
        }
        for (Entity e : worldEntities) {
            if (e.getVehicle() != null) {
                riding.put(e.getUUID(), e.getVehicle().getUUID());
            }
            e.stopRiding();
            e.ejectPassengers();
        }

        // 2. Guardar las naves y las entidades que no son jugadores.
        //    Cada nave intenta volver a su mismo índice de plot. Si en el destino está ocupado (también por naves
        //    descargadas, que siguen marcadas en el registro de ocupación de Sable), se pasa a uno libre y se desplaza
        //    todo lo que guarda coordenadas absolutas del plot.
        List<SubLevelData> datas = new ArrayList<>();
        Map<UUID, int[]> plotShift = new HashMap<>();
        Set<Integer> reserved = new HashSet<>();
        for (ServerSubLevel ship : chain) {
            SubLevelData data = SubLevelSerializer.toData(ship, new ArrayList<>(chainIds));
            CompoundTag tag = data.fullTag();
            int[] shift = assignFreePlot(targetContainer, tag.getCompound("plot"), reserved);
            if (shift == null) {
                CreateUniversal.LOGGER.error("No quedan plots libres en {}: la nave {} no se mueve", target.dimension().location(), ship.getUniqueId());
                return false;
            }
            plotShift.put(ship.getUniqueId(), shift);
            Pose3d pose = SableNBTUtils.readPose3d(tag.getCompound("pose"));
            pose.position().add(delta);
            pose.rotationPoint().add(shift[0], 0.0, shift[1]);
            tag.put("pose", SableNBTUtils.writePose3d(pose));
            if (userData != null) {
                CompoundTag ud = tag.contains("user_data") ? tag.getCompound("user_data") : new CompoundTag();
                ud.put(CreateUniversal.MOD_ID, userData.copy());
                tag.put("user_data", ud);
            }
            datas.add(data);
        }
        Map<ServerSubLevel, List<CompoundTag>> savedPlotEntities = new HashMap<>();
        for (Map.Entry<ServerSubLevel, List<Entity>> entry : plotEntities.entrySet()) {
            int[] shift = plotShift.get(entry.getKey().getUniqueId());
            List<CompoundTag> saved = new ArrayList<>();
            for (Entity e : entry.getValue()) {
                CompoundTag t = new CompoundTag();
                if (e.saveAsPassenger(t)) {
                    shiftEntityPos(t, shift);
                    saved.add(t);
                }
                e.remove(Entity.RemovalReason.CHANGED_DIMENSION);
            }
            savedPlotEntities.put(entry.getKey(), saved);
        }
        List<CompoundTag> savedWorldMobs = new ArrayList<>();
        List<Vec3> savedWorldMobPlotPos = new ArrayList<>();
        List<ServerSubLevel> savedWorldMobShip = new ArrayList<>();
        List<ServerPlayer> players = new ArrayList<>();
        for (Entity e : worldEntities) {
            if (e instanceof ServerPlayer p) {
                players.add(p);
                continue;
            }
            ServerSubLevel ship = worldEntityShip.get(e);
            CompoundTag t = new CompoundTag();
            if (ship != null && e.saveAsPassenger(t)) {
                savedWorldMobs.add(t);
                savedWorldMobPlotPos.add(shifted(worldEntityPlotPos.get(e), plotShift.get(ship.getUniqueId())));
                savedWorldMobShip.add(ship);
                e.remove(Entity.RemovalReason.CHANGED_DIMENSION);
            }
        }

        // Velocidad de la nave raíz (bloques/segundo), para que los jugadores de pie viajen con ella y para el log.
        Vector3d shipVelocity = new Vector3d();
        CompoundTag rootTag = datas.isEmpty() ? new CompoundTag() : datas.get(chain.indexOf(root)).fullTag();
        if (rootTag.contains("linear_velocity")) {
            shipVelocity.set(SableNBTUtils.readVector3d(rootTag.getCompound("linear_velocity")));
        }

        // 3. Quitar la nave original (REMOVED: sin soltar objetos y sin dejar copia guardada) y crearla en el destino.
        for (ServerSubLevel ship : chain) {
            sourceContainer.removeSubLevel(ship, SubLevelRemovalReason.REMOVED);
        }
        Map<UUID, ServerSubLevel> loaded = new HashMap<>();
        for (SubLevelData data : datas) {
            Pose3d pose = SableNBTUtils.readPose3d(data.fullTag().getCompound("pose"));
            target.getChunk((int) Math.floor(pose.position().x) >> 4, (int) Math.floor(pose.position().z) >> 4);
            ServerSubLevel ship = SubLevelSerializer.fullyLoad(target, data);
            if (ship == null) {
                CreateUniversal.LOGGER.error("Sable no pudo volver a crear la nave {} en {}", data.uuid(), target.dimension().location());
                continue;
            }
            loaded.put(data.uuid(), ship);
        }

        // 4. Volver a crear las entidades del plot (mismas coordenadas de plot) y las del mundo (con la pose nueva).
        Map<UUID, Entity> recreated = new HashMap<>();
        for (Map.Entry<ServerSubLevel, List<CompoundTag>> entry : savedPlotEntities.entrySet()) {
            for (CompoundTag t : entry.getValue()) {
                Entity e = EntityType.loadEntityRecursive(t, target, x -> x);
                if (e != null) {
                    target.addFreshEntity(e);
                    recreated.put(e.getUUID(), e);
                }
            }
        }
        for (int i = 0; i < savedWorldMobs.size(); i++) {
            ServerSubLevel newShip = loaded.get(savedWorldMobShip.get(i).getUniqueId());
            if (newShip == null) {
                continue;
            }
            Vec3 pos = newShip.logicalPose().transformPosition(savedWorldMobPlotPos.get(i));
            Entity created = EntityType.loadEntityRecursive(savedWorldMobs.get(i), target, e -> {
                e.moveTo(pos.x, pos.y, pos.z, e.getYRot(), e.getXRot());
                return e;
            });
            if (created != null) {
                target.addFreshEntity(created);
            }
        }
        for (ServerPlayer p : players) {
            try {
                ServerSubLevel oldShip = worldEntityShip.get(p);
                ServerSubLevel newShip = oldShip != null ? loaded.get(oldShip.getUniqueId()) : null;
                Vec3 pos = newShip != null && worldEntityPlotPos.containsKey(p)
                        ? newShip.logicalPose().transformPosition(shifted(worldEntityPlotPos.get(p), plotShift.get(oldShip.getUniqueId())))
                        : newRootPosition;
                Vec3 tickVelocity = new Vec3(shipVelocity.x, shipVelocity.y, shipVelocity.z).scale(1.0 / 20.0);
                p.changeDimension(new DimensionTransition(target, pos, tickVelocity, p.getYRot(), p.getXRot(), DimensionTransition.DO_NOTHING));
                UUID vehicleId = riding.get(p.getUUID());
                if (vehicleId == null && newShip != null && worldEntityPlotPos.containsKey(p)) {
                    // De pie: anclado a la nave 1,5 s para no atravesarla mientras el cliente la carga.
                    ShipAnchor.anchor(p, newShip, target, shifted(worldEntityPlotPos.get(p), plotShift.get(oldShip.getUniqueId())));
                }
                if (vehicleId != null) {
                    // Se sienta en el mismo tick: el asiento de Create se borra solo si en su primer tick no tiene pasajero.
                    Entity vehicle = recreated.get(vehicleId);
                    boolean seated = vehicle != null && p.startRiding(vehicle, true);
                    CreateUniversal.LOGGER.info("{} vuelve a su asiento {}: {}", p.getScoreboardName(), vehicleId,
                            seated ? "sí" : (vehicle == null ? "no (el asiento no se ha recreado)" : "no (startRiding rechazado)"));
                    if (!seated) {
                        REMOUNTS.add(new PendingRemount(p.getUUID(), vehicleId, target, 1));
                    }
                }
            } catch (RuntimeException ex) {
                // Nunca dejar a un jugador atrás por un fallo: como último recurso va al punto de llegada de la nave.
                CreateUniversal.LOGGER.error("Fallo al mover a {} con la nave; se le lleva al punto de llegada", p.getScoreboardName(), ex);
                p.changeDimension(new DimensionTransition(target, newRootPosition, Vec3.ZERO, p.getYRot(), p.getXRot(), DimensionTransition.DO_NOTHING));
            }
        }

        CreateUniversal.LOGGER.info("Nave {} ({} piezas, {} jugadores, {} entidades) movida de {} a {} en {} con velocidad {} b/s",
                root.getUniqueId(), chain.size(), players.size(),
                savedWorldMobs.size() + savedPlotEntities.values().stream().mapToInt(List::size).sum(),
                source.dimension().location(), target.dimension().location(), newRootPosition, shipVelocity);
        for (ServerSubLevel ship : loaded.values()) {
            ShipTracker.watch(ship, target);
        }
        return !loaded.isEmpty();
    }

    /**
     * Reserva un índice de plot libre en el destino para la nave guardada en {@code plot} (el tag de {@code ServerLevelPlot.save}).
     * Si su índice original está libre lo mantiene; si no, la pasa al primer índice libre y desplaza en el tag las
     * coordenadas absolutas de block entities y ticks pendientes. Los chunks se guardan en coordenadas locales del plot y
     * no hace falta tocarlos.
     *
     * @return desplazamiento en bloques {dx, dz} aplicado (0, 0 si no se ha movido), o null si no hay sitio
     */
    private static int[] assignFreePlot(ServerSubLevelContainer target, CompoundTag plot, Set<Integer> reserved) {
        int x = plot.getInt("plot_x");
        int z = plot.getInt("plot_z");
        int logSize = plot.getInt("log_size");
        int logSide = target.getLogSideLength();
        int side = 1 << logSide;
        BitSet occupancy = target.getOccupancy();

        int index = target.getIndex(x, z);
        if (x >= 0 && z >= 0 && x < side && z < side && !occupancy.get(index) && !reserved.contains(index)) {
            reserved.add(index);
            return new int[]{0, 0};
        }
        int free = -1;
        do {
            free = occupancy.nextClearBit(free + 1);
        } while (reserved.contains(free));
        if (free >= side * side) {
            return null;
        }
        reserved.add(free);
        int nx = free & (side - 1);
        int nz = free >> logSide;
        int dx = (nx - x) << (logSize + 4);
        int dz = (nz - z) << (logSize + 4);

        CompoundTag chunks = plot.getCompound("chunks");
        for (String key : chunks.getAllKeys()) {
            CompoundTag chunk = chunks.getCompound(key);
            for (String list : new String[]{"block_entities", "block_ticks", "fluid_ticks"}) {
                ListTag entries = chunk.getList(list, Tag.TAG_COMPOUND);
                for (int i = 0; i < entries.size(); i++) {
                    CompoundTag e = entries.getCompound(i);
                    if (e.contains("x") && e.contains("z")) {
                        e.putInt("x", e.getInt("x") + dx);
                        e.putInt("z", e.getInt("z") + dz);
                    }
                }
            }
        }
        plot.putInt("plot_x", nx);
        plot.putInt("plot_z", nz);
        CreateUniversal.LOGGER.info("Plot {},{} ocupado en el destino: la nave pasa al plot {},{} (desplazamiento {}, {})", x, z, nx, nz, dx, dz);
        return new int[]{dx, dz};
    }

    private static void shiftEntityPos(CompoundTag entity, int[] shift) {
        if (shift == null || (shift[0] == 0 && shift[1] == 0)) {
            return;
        }
        ListTag pos = entity.getList("Pos", Tag.TAG_DOUBLE);
        if (pos.size() == 3) {
            ListTag moved = new ListTag();
            moved.add(DoubleTag.valueOf(pos.getDouble(0) + shift[0]));
            moved.add(DoubleTag.valueOf(pos.getDouble(1)));
            moved.add(DoubleTag.valueOf(pos.getDouble(2) + shift[1]));
            entity.put("Pos", moved);
        }
        ListTag passengers = entity.getList("Passengers", Tag.TAG_COMPOUND);
        for (int i = 0; i < passengers.size(); i++) {
            shiftEntityPos(passengers.getCompound(i), shift);
        }
    }

    private static Vec3 shifted(Vec3 plotPos, int[] shift) {
        return shift == null ? plotPos : plotPos.add(shift[0], 0.0, shift[1]);
    }

    /** Se llama cada tick del servidor: vuelve a sentar a los jugadores cuando el cliente ya conoce el asiento. */
    public static void tickRemounts() {
        if (REMOUNTS.isEmpty()) {
            return;
        }
        // ticksLeft cuenta los intentos hechos: se reintenta cada tick hasta 20 veces.
        List<PendingRemount> next = new ArrayList<>();
        for (PendingRemount r : REMOUNTS) {
            Entity player = r.level().getEntity(r.player());
            Entity vehicle = r.level().getEntity(r.vehicle());
            boolean seated = player != null && vehicle != null
                    && (player.getVehicle() == vehicle || player.startRiding(vehicle, true));
            if (seated) {
                CreateUniversal.LOGGER.info("{} vuelve a su asiento al intento {}", player.getScoreboardName(), r.ticksLeft());
            } else if (r.ticksLeft() < 20) {
                next.add(new PendingRemount(r.player(), r.vehicle(), r.level(), r.ticksLeft() + 1));
            } else {
                CreateUniversal.LOGGER.warn("No se pudo volver a sentar a {} (asiento {} {})", r.player(), r.vehicle(),
                        vehicle == null ? "no existe" : "rechaza al pasajero");
            }
        }
        REMOUNTS.clear();
        REMOUNTS.addAll(next);
    }
}
