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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

        // Cada nave vuelve a su mismo índice de plot: si en el destino está ocupado, no se puede mover todavía.
        for (ServerSubLevel ship : chain) {
            ChunkPos plot = ship.getPlot().plotPos;
            for (ServerSubLevel other : targetContainer.getAllSubLevels()) {
                if (!other.isRemoved() && other.getPlot().plotPos.equals(plot)) {
                    CreateUniversal.LOGGER.warn("No se puede mover la nave {}: el plot {} está ocupado en {}",
                            ship.getUniqueId(), plot, target.dimension().location());
                    return false;
                }
            }
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
        // Pasajeros de asientos del plot (los jugadores sentados están en el mundo pero montan una entidad del plot).
        for (List<Entity> list : plotEntities.values()) {
            for (Entity vehicle : list) {
                for (Entity passenger : vehicle.getPassengers()) {
                    riding.put(passenger.getUUID(), vehicle.getUUID());
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
        List<SubLevelData> datas = new ArrayList<>();
        for (ServerSubLevel ship : chain) {
            SubLevelData data = SubLevelSerializer.toData(ship, new ArrayList<>(chainIds));
            CompoundTag tag = data.fullTag();
            Pose3d pose = SableNBTUtils.readPose3d(tag.getCompound("pose"));
            pose.position().add(delta);
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
            List<CompoundTag> saved = new ArrayList<>();
            for (Entity e : entry.getValue()) {
                CompoundTag t = new CompoundTag();
                if (e.saveAsPassenger(t)) {
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
            CompoundTag t = new CompoundTag();
            if (e.saveAsPassenger(t)) {
                savedWorldMobs.add(t);
                savedWorldMobPlotPos.add(worldEntityPlotPos.get(e));
                savedWorldMobShip.add(worldEntityShip.get(e));
            }
            e.remove(Entity.RemovalReason.CHANGED_DIMENSION);
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
        for (Map.Entry<ServerSubLevel, List<CompoundTag>> entry : savedPlotEntities.entrySet()) {
            for (CompoundTag t : entry.getValue()) {
                Entity created = EntityType.loadEntityRecursive(t, target, e -> e);
                if (created != null) {
                    target.addFreshEntity(created);
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
            ServerSubLevel newShip = loaded.get(worldEntityShip.get(p).getUniqueId());
            Vec3 pos = newShip != null
                    ? newShip.logicalPose().transformPosition(worldEntityPlotPos.get(p))
                    : newRootPosition;
            p.changeDimension(new DimensionTransition(target, pos, Vec3.ZERO, p.getYRot(), p.getXRot(), DimensionTransition.DO_NOTHING));
            UUID vehicle = riding.get(p.getUUID());
            if (vehicle != null) {
                REMOUNTS.add(new PendingRemount(p.getUUID(), vehicle, target, 5));
            }
        }

        CreateUniversal.LOGGER.info("Nave {} ({} piezas, {} jugadores, {} entidades) movida de {} a {} en {}",
                root.getUniqueId(), chain.size(), players.size(),
                savedWorldMobs.size() + savedPlotEntities.values().stream().mapToInt(List::size).sum(),
                source.dimension().location(), target.dimension().location(), newRootPosition);
        return !loaded.isEmpty();
    }

    /** Se llama cada tick del servidor: vuelve a sentar a los jugadores cuando el cliente ya conoce el asiento. */
    public static void tickRemounts() {
        if (REMOUNTS.isEmpty()) {
            return;
        }
        List<PendingRemount> next = new ArrayList<>();
        for (PendingRemount r : REMOUNTS) {
            if (r.ticksLeft() > 0) {
                next.add(new PendingRemount(r.player(), r.vehicle(), r.level(), r.ticksLeft() - 1));
                continue;
            }
            Entity player = r.level().getEntity(r.player());
            Entity vehicle = r.level().getEntity(r.vehicle());
            if (player != null && vehicle != null) {
                player.startRiding(vehicle, true);
            }
        }
        REMOUNTS.clear();
        REMOUNTS.addAll(next);
    }
}
