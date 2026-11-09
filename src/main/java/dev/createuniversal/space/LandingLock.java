package dev.createuniversal.space;

import dev.createuniversal.CreateUniversal;
import dev.createuniversal.space.surface.PlanetSurface;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Hasta aterrizar no se vuelve a subir (alpha.5.7).
 *
 * Lo que baja del espacio a la Tierra, la Luna o Marte llega en el aire, a veces por encima de la altura de salida (en
 * pruebas la salida está a Y 400) o sube un poco por la inercia, y volvía al espacio en el acto. Ahora queda marcado:
 * <ul>
 *   <li>Naves: {@code mustLand} en sus datos de Create Universal (se guardan con la nave). Se quita cuando la nave lleva
 *       un segundo casi quieta y con el suelo a pocos bloques bajo alguna de sus piezas.</li>
 *   <li>Jugadores sueltos: la misma marca en sus datos persistentes; se quita al pisar el suelo o entrar en el agua.</li>
 * </ul>
 */
public final class LandingLock {
    public static final String KEY = "mustLand";
    /** Distancia máxima del suelo bajo la nave para contar como aterrizada. */
    private static final double GROUND_GAP = 4.0;
    /** Lo que puede moverse en un segundo una nave posada. */
    private static final double STILL = 1.0;

    private static final Map<UUID, Vec3> LAST_CENTER = new HashMap<>();

    private LandingLock() {}

    // ---------------------------------------------------------------- naves

    public static boolean mustLand(ServerSubLevel ship) {
        CompoundTag ud = ship.getUserDataTag();
        return ud != null && ud.getCompound(CreateUniversal.MOD_ID).getBoolean(KEY);
    }

    public static boolean mustLand(List<ServerSubLevel> group) {
        for (ServerSubLevel ship : group) {
            if (mustLand(ship)) {
                return true;
            }
        }
        return false;
    }

    private static void clear(ServerSubLevel ship) {
        CompoundTag ud = ship.getUserDataTag();
        if (ud == null) {
            return;
        }
        CompoundTag ours = ud.getCompound(CreateUniversal.MOD_ID);
        ours.remove(KEY);
        ud.put(CreateUniversal.MOD_ID, ours);
        ship.setUserDataTag(ud);
    }

    /** Una vez por segundo: ¿ha aterrizado ya alguna nave marcada? */
    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % 20 != 0) {
            return;
        }
        List<ServerLevel> levels = new ArrayList<>();
        levels.add(server.overworld());
        for (PlanetSurface surface : PlanetSurface.ALL) {
            ServerLevel l = server.getLevel(surface.dimension());
            if (l != null) {
                levels.add(l);
            }
        }
        Set<UUID> seen = new HashSet<>();
        for (ServerLevel level : levels) {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) {
                continue;
            }
            Set<UUID> done = new HashSet<>();
            for (ServerSubLevel ship : new ArrayList<>(container.getAllSubLevels())) {
                if (ship.isRemoved() || done.contains(ship.getUniqueId()) || !mustLand(ship)) {
                    continue;
                }
                List<ServerSubLevel> group = ShipGroup.of(ship);
                boolean still = true;
                boolean grounded = false;
                for (ServerSubLevel s : group) {
                    done.add(s.getUniqueId());
                    seen.add(s.getUniqueId());
                    Vec3 c = center(s);
                    Vec3 before = LAST_CENTER.put(s.getUniqueId(), c);
                    if (before == null || before.distanceTo(c) > STILL) {
                        still = false;
                    }
                    grounded |= nearGround(level, s);
                }
                if (still && grounded) {
                    group.forEach(LandingLock::clear);
                    CreateUniversal.LOGGER.info("Nave {} aterrizada en {}: ya puede volver a despegar", ship.getUniqueId(), level.dimension().location());
                }
            }
        }
        LAST_CENTER.keySet().retainAll(seen);
    }

    private static boolean nearGround(ServerLevel level, ServerSubLevel ship) {
        BoundingBox3dc b = ship.boundingBox();
        int[][] points = {
                {(int) Math.floor((b.minX() + b.maxX()) * 0.5), (int) Math.floor((b.minZ() + b.maxZ()) * 0.5)},
                {(int) Math.floor(b.minX()), (int) Math.floor(b.minZ())}, {(int) Math.floor(b.maxX()), (int) Math.floor(b.minZ())},
                {(int) Math.floor(b.minX()), (int) Math.floor(b.maxZ())}, {(int) Math.floor(b.maxX()), (int) Math.floor(b.maxZ())}};
        for (int[] p : points) {
            if (!level.hasChunk(p[0] >> 4, p[1] >> 4)) {
                continue;
            }
            int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, p[0], p[1]);
            if (b.minY() - ground <= GROUND_GAP) {
                return true;
            }
        }
        return false;
    }

    private static Vec3 center(ServerSubLevel ship) {
        BoundingBox3dc b = ship.boundingBox();
        return new Vec3((b.minX() + b.maxX()) * 0.5, (b.minY() + b.maxY()) * 0.5, (b.minZ() + b.maxZ()) * 0.5);
    }

    // ---------------------------------------------------------------- jugadores

    public static void markPlayer(ServerPlayer player) {
        CompoundTag data = player.getPersistentData().getCompound(SpaceTransitions.TAG);
        data.putBoolean(KEY, true);
        player.getPersistentData().put(SpaceTransitions.TAG, data);
    }

    /** ¿Tiene que aterrizar antes de poder subir? Se le quita la marca en cuanto pisa el suelo o el agua. */
    public static boolean playerMustLand(ServerPlayer player) {
        CompoundTag data = player.getPersistentData().getCompound(SpaceTransitions.TAG);
        if (!data.getBoolean(KEY)) {
            return false;
        }
        if (player.level().dimension() == CUDimensions.SPACE || (!player.isPassenger() && (player.onGround() || player.isInWater()))) {
            data.remove(KEY);
            player.getPersistentData().put(SpaceTransitions.TAG, data);
            return false;
        }
        return true;
    }

    /** ¿Baja del espacio a una superficie? */
    public static boolean isDescent(Level source, Level target) {
        return source.dimension() == CUDimensions.SPACE && target.dimension() != CUDimensions.SPACE;
    }
}
