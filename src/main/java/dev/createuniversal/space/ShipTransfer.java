package dev.createuniversal.space;

import dev.createuniversal.CreateUniversal;
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

        // Toda la nave: cadena de Sable y piezas pegadas (remaches...). Si no, cada pieza se iría por su cuenta.
        List<ServerSubLevel> chain = ShipGroup.of(root);

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
                // Sentado en un asiento de otra nave (los asientos están en la zona interna de su nave, lejísimos): viaja
                // con la nave de su asiento. Si el asiento va en esta nave se recoge más abajo con sus pasajeros.
                if (e.getVehicle() != null && !worldAabb.contains(e.getVehicle().position())) {
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
        List<PlotArea> areas = new ArrayList<>();
        for (ServerSubLevel ship : chain) {
            SubLevelData data = SubLevelSerializer.toData(ship, new ArrayList<>(chainIds));
            CompoundTag tag = data.fullTag();
            int plotBlocks = 1 << (tag.getCompound("plot").getInt("log_size") + 4);
            int oldMinX = ship.getPlot().plotPos.x * plotBlocks;
            int oldMinZ = ship.getPlot().plotPos.z * plotBlocks;
            int[] xz = assignFreePlot(targetContainer, tag.getCompound("plot"), reserved);
            if (xz == null) {
                CreateUniversal.LOGGER.error("No quedan plots libres en {}: la nave {} no se mueve", target.dimension().location(), ship.getUniqueId());
                return false;
            }
            int dy = remapSections(tag.getCompound("plot"), source, target);
            int[] shift = {xz[0], dy, xz[1]};
            plotShift.put(ship.getUniqueId(), shift);
            areas.add(new PlotArea(oldMinX, oldMinZ, plotBlocks, shift));
            Pose3d pose = SableNBTUtils.readPose3d(tag.getCompound("pose"));
            pose.position().add(delta);
            pose.rotationPoint().add(shift[0], shift[1], shift[2]);
            tag.put("pose", SableNBTUtils.writePose3d(pose));
            if (userData != null) {
                CompoundTag ud = tag.contains("user_data") ? tag.getCompound("user_data") : new CompoundTag();
                ud.put(CreateUniversal.MOD_ID, userData.copy());
                tag.put("user_data", ud);
            }
            datas.add(data);
        }
        shiftPlotReferences(datas, areas);
        Map<ServerSubLevel, List<CompoundTag>> savedPlotEntities = new HashMap<>();
        for (Map.Entry<ServerSubLevel, List<Entity>> entry : plotEntities.entrySet()) {
            int[] shift = plotShift.get(entry.getKey().getUniqueId());
            List<CompoundTag> saved = new ArrayList<>();
            for (Entity e : entry.getValue()) {
                CompoundTag t = new CompoundTag();
                if (e.saveAsPassenger(t)) {
                    shiftEntityPos(t, shift);
                    shiftReferencesIn(t, areas, true);
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
            CreateUniversal.LOGGER.info("Nave {} cargada en {}: masa {}, plot {}", data.uuid(), target.dimension().location(),
                    ship.getMassTracker().getMass(), ship.getPlot().plotPos);
            // Congelar la nave hasta que los clientes la tengan cargada (ShipAnchor). Se le devolverá esta velocidad.
            CompoundTag tag = data.fullTag();
            Vector3d lin = tag.contains("linear_velocity") ? SableNBTUtils.readVector3d(tag.getCompound("linear_velocity")) : new Vector3d();
            Vector3d ang = tag.contains("angular_velocity") ? SableNBTUtils.readVector3d(tag.getCompound("angular_velocity")) : new Vector3d();
            // La Tierra recorre su órbita (~16 b/s): lo que sale de ella la lleva consigo y lo que vuelve la pierde.
            Vector3d earth = earthVelocity(source, target);
            ShipAnchor.holdShip(ship, target, lin.add(earth), ang);
        }
        ShipAnchor.group(new ArrayList<>(loaded.values()));

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
                // Llega parado: la nave también está congelada y los dos recuperan la velocidad a la vez al soltarla.
                p.changeDimension(new DimensionTransition(target, pos, Vec3.ZERO, p.getYRot(), p.getXRot(), DimensionTransition.DO_NOTHING));
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, new dev.createuniversal.network.TransitionEffectPayload(
                        target.dimension() == CUDimensions.SPACE ? dev.createuniversal.network.TransitionEffectPayload.EXIT
                                : dev.createuniversal.network.TransitionEffectPayload.REENTRY));
                UUID vehicleId = riding.get(p.getUUID());
                if (vehicleId == null && newShip != null && worldEntityPlotPos.containsKey(p)) {
                    // De pie: anclado a la nave 1,5 s para no atravesarla mientras el cliente la carga.
                    ShipAnchor.anchorStanding(p, newShip, shifted(worldEntityPlotPos.get(p), plotShift.get(oldShip.getUniqueId())));
                }
                if (vehicleId != null) {
                    // Se sienta en el mismo tick: el asiento de Create se borra solo si en su primer tick no tiene pasajero.
                    Entity vehicle = recreated.get(vehicleId);
                    boolean seated = vehicle != null && p.startRiding(vehicle, true);
                    if (newShip != null) {
                        ShipAnchor.addPassenger(p, newShip);
                    }
                    CreateUniversal.LOGGER.info("{} vuelve a su asiento {}: {}", p.getScoreboardName(), vehicleId,
                            seated ? "sí" : (vehicle == null ? "no (el asiento no se ha recreado)" : "no (startRiding rechazado)"));
                    if (!seated) {
                        REMOUNTS.add(new PendingRemount(p.getUUID(), vehicleId, target, 1));
                        // Mientras tanto, anclado donde estaba su asiento: si no se puede volver a sentar, al menos
                        // viaja con la nave en vez de quedarse flotando mientras ella se va.
                        if (newShip != null && worldEntityPlotPos.containsKey(p)) {
                            ShipAnchor.anchorStanding(p, newShip, shifted(worldEntityPlotPos.get(p), plotShift.get(oldShip.getUniqueId())));
                        }
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
        // Desde el final de la cuadrícula: los primeros plots suelen estar ocupados por naves guardadas que aún no se han
        // cargado y que el registro de ocupación no siempre marca; una nave nueva ahí desaparecía nada más llegar.
        int free = side * side;
        do {
            free = occupancy.previousClearBit(free - 1);
        } while (free >= 0 && reserved.contains(free));
        if (free < 0) {
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

    /**
     * Las secciones de cada chunk del plot se guardan con un índice que cuenta desde el fondo de la dimensión, pero las
     * alturas de los bloques son absolutas. Entre dimensiones de distinta altura (la Luna empieza en Y −1.408 y el espacio
     * en −64) hay que recalcular los índices; si la nave no cabe en la altura del destino se baja o se sube entera, en
     * múltiplos de 16, y se devuelve ese desplazamiento para mover también lo que guarda la altura absoluta.
     */
    private static int remapSections(CompoundTag plot, ServerLevel source, ServerLevel target) {
        int srcMin = source.getMinSection();
        int tgtMin = target.getMinSection();
        int tgtMax = target.getMaxSection() - 1;
        CompoundTag chunks = plot.getCompound("chunks");
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;
        for (String key : chunks.getAllKeys()) {
            for (String idx : chunks.getCompound(key).getCompound("sections").getAllKeys()) {
                int sy = Integer.parseInt(idx) + srcMin;
                lowest = Math.min(lowest, sy);
                highest = Math.max(highest, sy);
            }
        }
        if (lowest == Integer.MAX_VALUE) {
            return 0;
        }
        int dySections = 0;
        if (lowest < tgtMin || highest > tgtMax) {
            dySections = (tgtMin + tgtMax) / 2 - (lowest + highest) / 2;
        }
        if (srcMin == tgtMin && dySections == 0) {
            return 0;
        }
        int dy = dySections * 16;
        for (String key : chunks.getAllKeys()) {
            CompoundTag chunk = chunks.getCompound(key);
            CompoundTag sections = chunk.getCompound("sections");
            CompoundTag moved = new CompoundTag();
            for (String idx : sections.getAllKeys()) {
                int newIndex = Integer.parseInt(idx) + srcMin + dySections - tgtMin;
                if (newIndex >= 0 && newIndex <= tgtMax - tgtMin) {
                    moved.put(String.valueOf(newIndex), sections.getCompound(idx));
                }
            }
            chunk.put("sections", moved);
            if (dy != 0) {
                for (String list : new String[]{"block_entities", "block_ticks", "fluid_ticks"}) {
                    ListTag entries = chunk.getList(list, Tag.TAG_COMPOUND);
                    for (int i = 0; i < entries.size(); i++) {
                        CompoundTag e = entries.getCompound(i);
                        if (e.contains("y")) {
                            e.putInt("y", e.getInt("y") + dy);
                        }
                    }
                }
                chunk.remove("heightmaps");
            }
        }
        if (dy != 0) {
            CreateUniversal.LOGGER.info("La nave no cabe en la altura de {}: se mueve {} bloques en vertical dentro de su plot",
                    target.dimension().location(), dy);
        }
        return dy;
    }

    /** Zona interna (plot) que ocupaba una nave antes de moverla, y cuánto se ha desplazado. */
    private record PlotArea(int minX, int minZ, int size, int[] shift) {
        boolean contains(int x, int z) {
            return x >= minX && x < minX + size && z >= minZ && z < minZ + size;
        }

        boolean moved() {
            return shift[0] != 0 || shift[1] != 0 || shift[2] != 0;
        }
    }

    /**
     * Bloques que enlazan naves entre sí (rodamientos y pistones de físicas, cuerdas, remaches de Simulated Coasters...)
     * guardan posiciones absolutas dentro de la zona interna de su nave o de la nave a la que se unen. Si esa nave cambia
     * de plot, esas posiciones apuntarían a la nada y el bloque rompería o soltaría la unión. Se recorren los datos de
     * todos los bloques con datos de las naves que viajan y se desplaza cualquier posición que caiga dentro del plot
     * antiguo de una de ellas: como número largo (BlockPos), como tres enteros o como un compuesto con X, Y, Z.
     */
    private static void shiftPlotReferences(List<SubLevelData> datas, List<PlotArea> areas) {
        if (areas.stream().noneMatch(PlotArea::moved)) {
            return;
        }
        int count = 0;
        for (SubLevelData data : datas) {
            CompoundTag chunks = data.fullTag().getCompound("plot").getCompound("chunks");
            for (String key : chunks.getAllKeys()) {
                ListTag entries = chunks.getCompound(key).getList("block_entities", Tag.TAG_COMPOUND);
                for (int i = 0; i < entries.size(); i++) {
                    count += shiftReferencesIn(entries.getCompound(i), areas, true);
                }
            }
        }
        if (count > 0) {
            CreateUniversal.LOGGER.info("{} referencias a posiciones dentro de las naves movidas con su plot", count);
        }
    }

    /** @param root true en el compuesto raíz de un bloque o entidad: sus propias x, y, z (o Pos) ya se han movido */
    private static int shiftReferencesIn(CompoundTag tag, List<PlotArea> areas, boolean root) {
        int changed = 0;
        for (String key : new ArrayList<>(tag.getAllKeys())) {
            if (root && (key.equals("x") || key.equals("y") || key.equals("z") || key.equals("Pos") || key.equals("id"))) {
                continue;
            }
            Tag value = tag.get(key);
            if (value instanceof net.minecraft.nbt.LongTag l) {
                net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.of(l.getAsLong());
                PlotArea a = find(areas, pos.getX(), pos.getY(), pos.getZ());
                if (a != null) {
                    tag.putLong(key, pos.offset(a.shift()[0], a.shift()[1], a.shift()[2]).asLong());
                    changed++;
                }
            } else if (value instanceof net.minecraft.nbt.IntArrayTag arr && arr.size() == 3) {
                int[] v = arr.getAsIntArray();
                PlotArea a = find(areas, v[0], v[1], v[2]);
                if (a != null) {
                    tag.putIntArray(key, new int[] {v[0] + a.shift()[0], v[1] + a.shift()[1], v[2] + a.shift()[2]});
                    changed++;
                }
            } else if (value instanceof CompoundTag c) {
                changed += shiftXyz(c, areas, "X", "Y", "Z") + shiftXyz(c, areas, "x", "y", "z");
                changed += shiftReferencesIn(c, areas, false);
            } else if (value instanceof ListTag list) {
                changed += shiftList(list, areas);
            }
        }
        return changed;
    }

    private static int shiftList(ListTag list, List<PlotArea> areas) {
        int changed = 0;
        for (int i = 0; i < list.size(); i++) {
            Tag e = list.get(i);
            if (e instanceof CompoundTag c) {
                changed += shiftXyz(c, areas, "X", "Y", "Z") + shiftXyz(c, areas, "x", "y", "z");
                changed += shiftReferencesIn(c, areas, false);
            } else if (e instanceof net.minecraft.nbt.LongTag l) {
                net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.of(l.getAsLong());
                PlotArea a = find(areas, pos.getX(), pos.getY(), pos.getZ());
                if (a != null) {
                    list.set(i, net.minecraft.nbt.LongTag.valueOf(pos.offset(a.shift()[0], a.shift()[1], a.shift()[2]).asLong()));
                    changed++;
                }
            } else if (e instanceof net.minecraft.nbt.IntArrayTag arr && arr.size() == 3) {
                int[] v = arr.getAsIntArray();
                PlotArea a = find(areas, v[0], v[1], v[2]);
                if (a != null) {
                    list.set(i, new net.minecraft.nbt.IntArrayTag(new int[] {v[0] + a.shift()[0], v[1] + a.shift()[1], v[2] + a.shift()[2]}));
                    changed++;
                }
            } else if (e instanceof ListTag inner) {
                changed += shiftList(inner, areas);
            }
        }
        return changed;
    }

    private static int shiftXyz(CompoundTag c, List<PlotArea> areas, String kx, String ky, String kz) {
        if (!c.contains(kx, Tag.TAG_INT) || !c.contains(ky, Tag.TAG_INT) || !c.contains(kz, Tag.TAG_INT)) {
            return 0;
        }
        PlotArea a = find(areas, c.getInt(kx), c.getInt(ky), c.getInt(kz));
        if (a == null) {
            return 0;
        }
        c.putInt(kx, c.getInt(kx) + a.shift()[0]);
        c.putInt(ky, c.getInt(ky) + a.shift()[1]);
        c.putInt(kz, c.getInt(kz) + a.shift()[2]);
        return 1;
    }

    /** Plot movido que contiene la posición; las zonas internas están a millones de bloques, así que no hay falsos aciertos. */
    private static PlotArea find(List<PlotArea> areas, int x, int y, int z) {
        if (y < -2048 || y > 2048) {
            return null;
        }
        for (PlotArea a : areas) {
            if (a.moved() && a.contains(x, z)) {
                return a;
            }
        }
        return null;
    }

    private static void shiftEntityPos(CompoundTag entity, int[] shift) {
        if (shift == null || (shift[0] == 0 && shift[1] == 0 && shift[2] == 0)) {
            return;
        }
        ListTag pos = entity.getList("Pos", Tag.TAG_DOUBLE);
        if (pos.size() == 3) {
            ListTag moved = new ListTag();
            moved.add(DoubleTag.valueOf(pos.getDouble(0) + shift[0]));
            moved.add(DoubleTag.valueOf(pos.getDouble(1) + shift[1]));
            moved.add(DoubleTag.valueOf(pos.getDouble(2) + shift[2]));
            entity.put("Pos", moved);
        }
        ListTag passengers = entity.getList("Passengers", Tag.TAG_COMPOUND);
        for (int i = 0; i < passengers.size(); i++) {
            shiftEntityPos(passengers.getCompound(i), shift);
        }
    }

    private static Vec3 shifted(Vec3 plotPos, int[] shift) {
        return shift == null ? plotPos : plotPos.add(shift[0], shift[1], shift[2]);
    }

    /** Se llama cada tick del servidor: vuelve a sentar a los jugadores cuando el cliente ya conoce el asiento. */
    /**
     * Velocidad que se suma al pasar de {@code source} a {@code target}: cada sitio se mueve con su astro (la Tierra, la
     * Luna, Marte) y el espacio no; lo que sube hereda la velocidad de su astro y lo que baja la pierde.
     */
    private static Vector3d earthVelocity(ServerLevel source, ServerLevel target) {
        double time = SpaceLayout.time(target);
        Vec3 v = dev.createuniversal.space.surface.SurfaceTransitions.frameVelocity(source, time)
                .subtract(dev.createuniversal.space.surface.SurfaceTransitions.frameVelocity(target, time));
        return new Vector3d(v.x, v.y, v.z);
    }

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
