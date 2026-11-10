package dev.createuniversal.space;

import dev.createuniversal.network.GravityStatePayload;
import dev.ryanhcode.sable.sublevel.SubLevel;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Vector3d;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Áreas de los Estabilizadores de gravedad encendidos y a quién afectan (D51, D53, D91). Cada tick se mira qué seres
 * vivos tienen los ojos dentro de cada área; si están en varias, manda el estabilizador más cercano. La fuerza se envía a
 * los clientes que ven a esa entidad (y a ella misma); la dirección se la da Gravity Control (va dentro de nuestro jar),
 * que solo tiene las 6 caras: se elige la más cercana al "abajo" del estabilizador girado con la nave, en tiempo real.
 */
public final class GravityFields {
    /** Ticks que se mantiene la gravedad tras salir del área (para que no parpadee en una puerta). */
    private static final long GRACE = 5;
    /** Ticks sin que el bloque lo renueve para apagar el área (bloque roto o descargado). */
    private static final long FORGET = 30;

    private static final class Field {
        final ResourceKey<Level> dimension;
        final BlockPos source;
        final UUID ship;
        final LongOpenHashSet cells;
        final int minX, minY, minZ, maxX, maxY, maxZ;
        Direction down;
        float strength;
        long lastSeen;

        Field(ServerLevel level, BlockPos source, OxygenZones.Zone zone, Direction down, float strength) {
            this.dimension = level.dimension();
            this.source = source;
            this.ship = zone.ship;
            this.cells = zone.cells;
            this.minX = zone.minX;
            this.minY = zone.minY;
            this.minZ = zone.minZ;
            this.maxX = zone.maxX;
            this.maxY = zone.maxY;
            this.maxZ = zone.maxZ;
            this.down = down;
            this.strength = strength;
            this.lastSeen = level.getGameTime();
        }
    }

    private record Assigned(String field, Gravity.State state, long lastSeen) {}

    private static final Map<String, Field> FIELDS = new HashMap<>();
    private static final Map<UUID, Assigned> ASSIGNED = new HashMap<>();

    private GravityFields() {}

    private static String key(Level level, BlockPos pos) {
        return level.dimension().location() + "@" + pos.asLong();
    }

    /** Vuelve a mirar el área del estabilizador. @return true si está cerrada (o no se ha podido mirar y ya lo estaba) */
    public static boolean update(ServerLevel level, BlockPos pos, Direction down, float strength) {
        String k = key(level, pos);
        OxygenZones.Scan scan = OxygenZones.room(level, pos);
        if (scan.zone() == null) {
            if (scan.unknown() && FIELDS.containsKey(k)) {
                keep(level, pos, down, strength);
                return true;
            }
            FIELDS.remove(k);
            return false;
        }
        FIELDS.put(k, new Field(level, pos, scan.zone(), down, strength));
        return true;
    }

    public static void keep(ServerLevel level, BlockPos pos, Direction down, float strength) {
        Field f = FIELDS.get(key(level, pos));
        if (f != null) {
            f.lastSeen = level.getGameTime();
            f.down = down;
            f.strength = strength;
        }
    }

    public static void remove(ServerLevel level, BlockPos pos) {
        FIELDS.remove(key(level, pos));
    }

    /** Cada tick del servidor. */
    public static void tick(MinecraftServer server) {
        long now = server.overworld().getGameTime();
        FIELDS.values().removeIf(f -> now - f.lastSeen > FORGET);
        Map<UUID, Assigned> next = new HashMap<>();
        Map<UUID, Double> bestDistance = new HashMap<>();
        Map<UUID, Entity> entities = new HashMap<>();
        for (Map.Entry<String, Field> e : FIELDS.entrySet()) {
            Field f = e.getValue();
            ServerLevel level = server.getLevel(f.dimension);
            if (level == null) {
                continue;
            }
            SubLevel ship = f.ship != null ? OxygenZones.ship(level, f.ship) : null;
            if (f.ship != null && ship == null) {
                continue;
            }
            AABB box = worldBox(f, ship);
            Vec3 sourceWorld = toWorld(ship, Vec3.atCenterOf(f.source));
            for (LivingEntity living : level.getEntitiesOfClass(LivingEntity.class, box, le -> le.isAlive() && !le.isSpectator())) {
                BlockPos cell = OxygenZones.toZone(level, f.ship, living.getEyePosition());
                if (cell == null || !f.cells.contains(cell.asLong())) {
                    continue;
                }
                double d = living.getEyePosition().distanceToSqr(sourceWorld);
                Double best = bestDistance.get(living.getUUID());
                if (best != null && best <= d) {
                    continue;
                }
                bestDistance.put(living.getUUID(), d);
                entities.put(living.getUUID(), living);
                next.put(living.getUUID(), new Assigned(e.getKey(), new Gravity.State(f.ship, f.down, f.strength), now));
            }
        }
        // Los que han salido: un momento de gracia y luego sin gravedad propia.
        for (Iterator<Map.Entry<UUID, Assigned>> it = ASSIGNED.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Assigned> e = it.next();
            if (next.containsKey(e.getKey())) {
                continue;
            }
            if (now - e.getValue().lastSeen() <= GRACE && FIELDS.containsKey(e.getValue().field())) {
                next.put(e.getKey(), e.getValue());
                continue;
            }
            Entity entity = find(server, e.getKey());
            Gravity.SERVER.remove(e.getKey());
            if (entity != null) {
                send(entity, null);
                setDirection(entity, Direction.DOWN);
            }
        }
        for (Map.Entry<UUID, Assigned> e : next.entrySet()) {
            Assigned old = ASSIGNED.get(e.getKey());
            Gravity.State state = e.getValue().state();
            Gravity.SERVER.put(e.getKey(), state);
            Entity entity = entities.containsKey(e.getKey()) ? entities.get(e.getKey()) : find(server, e.getKey());
            if (entity == null) {
                continue;
            }
            if (old == null || !Objects.equals(old.state(), state)) {
                send(entity, state);
            }
            // En tiempo real: la cara del mundo más cercana al "abajo" del estabilizador (girado con la nave).
            setDirection(entity, worldDirection(entity, state));
        }
        ASSIGNED.clear();
        ASSIGNED.putAll(next);
    }

    /** Marca en la entidad que su gravedad la hemos girado nosotros (para devolverla al cargar el mundo si hace falta). */
    private static final String TURNED = dev.createuniversal.CreateUniversal.MOD_ID + ":gravity_turned";
    /** Para cambiar de cara hace falta que la nueva esté bastante más cerca que la actual (si no, a 45° parpadearía). */
    private static final double HYSTERESIS = 0.15;

    private static Direction worldDirection(Entity entity, Gravity.State state) {
        if (state.ship() == null) {
            return state.down();
        }
        SubLevel ship = OxygenZones.ship(entity.level(), state.ship());
        if (ship == null) {
            return Direction.DOWN;
        }
        Vector3d d = new Vector3d(state.down().getStepX(), state.down().getStepY(), state.down().getStepZ());
        ship.logicalPose().orientation().transform(d);
        Direction nearest = Direction.getNearest(d.x, d.y, d.z);
        Direction current = gravitychanger.api.GravityChangerAPI.getBaseGravityDirection(entity);
        double currentDot = d.x * current.getStepX() + d.y * current.getStepY() + d.z * current.getStepZ();
        double nearestDot = d.x * nearest.getStepX() + d.y * nearest.getStepY() + d.z * nearest.getStepZ();
        return nearestDot - currentDot > HYSTERESIS ? nearest : current;
    }

    private static void setDirection(Entity entity, Direction direction) {
        if (!gravitychanger.api.GravityChangerAPI.canChangeGravity(entity)) {
            return;
        }
        if (gravitychanger.api.GravityChangerAPI.getBaseGravityDirection(entity) != direction) {
            gravitychanger.api.GravityChangerAPI.setBaseGravityDirection(entity, direction);
        }
        if (direction == Direction.DOWN) {
            entity.getPersistentData().remove(TURNED);
        } else {
            entity.getPersistentData().putBoolean(TURNED, true);
        }
    }

    /**
     * Al cargar una entidad (o entrar un jugador) con la gravedad girada por un estabilizador, vuelve a ser normal: si sigue
     * en el área, el siguiente tick la vuelve a girar. Así nadie se queda de lado tras salir del juego en una sala.
     */
    @SubscribeEvent
    public static void onJoin(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
        Entity entity = event.getEntity();
        if (!event.getLevel().isClientSide() && entity.getPersistentData().getBoolean(TURNED)
                && !Gravity.SERVER.containsKey(entity.getUUID())) {
            setDirection(entity, Direction.DOWN);
        }
    }

    private static Entity find(MinecraftServer server, UUID id) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity e = level.getEntity(id);
            if (e != null) {
                return e;
            }
        }
        return null;
    }

    private static void send(Entity entity, Gravity.State state) {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(entity, new GravityStatePayload(entity.getId(), state));
    }

    /** Al empezar a ver una entidad, su gravedad propia. */
    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        Gravity.State state = Gravity.SERVER.get(event.getTarget().getUUID());
        if (state != null && event.getEntity() instanceof ServerPlayer p) {
            PacketDistributor.sendToPlayer(p, new GravityStatePayload(event.getTarget().getId(), state));
        }
    }

    private static Vec3 toWorld(SubLevel ship, Vec3 local) {
        if (ship == null) {
            return local;
        }
        Vector3d v = new Vector3d(local.x, local.y, local.z);
        ship.logicalPose().transformPosition(v);
        return new Vec3(v.x, v.y, v.z);
    }

    /** Caja del mundo que contiene el área (girada con la nave si va en una). */
    private static AABB worldBox(Field f, SubLevel ship) {
        double[][] corners = {
                {f.minX, f.minY, f.minZ}, {f.maxX + 1, f.minY, f.minZ}, {f.minX, f.maxY + 1, f.minZ}, {f.minX, f.minY, f.maxZ + 1},
                {f.maxX + 1, f.maxY + 1, f.minZ}, {f.maxX + 1, f.minY, f.maxZ + 1}, {f.minX, f.maxY + 1, f.maxZ + 1},
                {f.maxX + 1, f.maxY + 1, f.maxZ + 1}};
        AABB box = null;
        for (double[] c : corners) {
            Vec3 w = toWorld(ship, new Vec3(c[0], c[1], c[2]));
            AABB p = new AABB(w, w);
            box = box == null ? p : box.minmax(p);
        }
        return box.inflate(2.0);
    }
}
