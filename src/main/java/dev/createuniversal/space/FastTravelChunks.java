package dev.createuniversal.space;

import dev.ryanhcode.sable.companion.SableCompanion;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Carga de terreno al ir muy deprisa (ideas 1 y 2): quien carga los chunks alrededor de los
 * jugadores en el pack es Immersive Portals, que ya pide como mucho 4 a la vez, de los más cercanos a los más lejanos.
 *
 * <ol>
 *   <li><b>Límite según cómo va el servidor:</b> si el servidor ya va con retraso, pide menos chunks nuevos por tick
 *       (2, o 1 si va muy mal), para que pueda ponerse al día en vez de amontonar trabajo.</li>
 *   <li><b>Primero lo que hay delante:</b> a quien va deprisa se le adelanta el terreno en su dirección (hasta donde
 *       llegará en ~1,5 s); lo que deja atrás va al final de la cola.</li>
 * </ol>
 */
public final class FastTravelChunks {
    /** Por debajo de esta velocidad (bloques/s) se carga como siempre, por cercanía. */
    private static final double FAST = 20.0;
    /** Cuánto adelante se mira (ticks). */
    private static final double LOOKAHEAD_TICKS = 30.0;

    private record Mover(double fromX, double fromZ, double toX, double toZ) {}

    private static final class Track {
        Vec3 last;
        Vec3 velocity = Vec3.ZERO;
    }

    private static final Map<UUID, Track> TRACKS = new HashMap<>();
    /** En el espacio, por encima de esta velocidad (respecto al astro de la zona) y lejos de todo, no se cargan chunks. */
    private static final double SPACE_SKIP_ABOVE = 99.0;
    /** ...y se vuelven a cargar al bajar de esta (un poco menos, para que no se encienda y apague sin parar). */
    private static final double SPACE_SKIP_BELOW = 95.0;
    /** Jugadores en el espacio a los que ahora mismo no se les cargan chunks (salvo el suyo). */
    private static final java.util.Set<UUID> SPACE_SKIP = new java.util.HashSet<>();
    private static final Map<ServerLevel, List<Mover>> MOVERS = new HashMap<>();
    private static int budget = 4;

    private FastTravelChunks() {}

    /** Cada tick del servidor: velocidad suavizada de cada jugador (en el mundo, también si va sentado en una nave). */
    public static void tick(MinecraftServer server) {
        float ms = server.getCurrentSmoothedTickTime();
        budget = ms > 60.0F ? 1 : ms > 45.0F ? 2 : 4;
        MOVERS.clear();
        Map<UUID, Track> seen = new HashMap<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerLevel level = player.serverLevel();
            Vec3 pos = SableCompanion.INSTANCE.projectOutOfSubLevel(level, player.position());
            Track t = TRACKS.getOrDefault(player.getUUID(), new Track());
            if (t.last != null) {
                Vec3 step = pos.subtract(t.last);
                // Un salto enorme es un teletransporte o un cambio de dimensión, no velocidad.
                t.velocity = step.lengthSqr() > 200.0 * 200.0 ? Vec3.ZERO : t.velocity.scale(0.8).add(step.scale(0.2));
            }
            t.last = pos;
            seen.put(player.getUUID(), t);
            spaceSkip(player, level, pos, t.velocity.scale(20.0));
            if (t.velocity.length() * 20.0 >= FAST) {
                int radius = server.getPlayerList().getViewDistance() * 16;
                Vec3 ahead = t.velocity.scale(LOOKAHEAD_TICKS);
                double len = ahead.horizontalDistance();
                if (len > radius) {
                    ahead = ahead.scale(radius / len);
                }
                MOVERS.computeIfAbsent(level, k -> new ArrayList<>())
                        .add(new Mover(pos.x / 16.0, pos.z / 16.0, (pos.x + ahead.x) / 16.0, (pos.z + ahead.z) / 16.0));
            }
        }
        TRACKS.clear();
        TRACKS.putAll(seen);
        SPACE_SKIP.retainAll(seen.keySet());
    }

    /**
     * Espacio (alpha.12.3): entre astros no hay nada, así que a más de 99 b/s (respecto al astro en
     * cuya zona estás) no se cargan los chunks de alrededor, solo el tuyo. Cerca de un astro, de un campo de rocas o
     * yendo despacio, se cargan como siempre.
     */
    private static void spaceSkip(ServerPlayer player, ServerLevel level, Vec3 pos, Vec3 velocity) {
        UUID id = player.getUUID();
        if (level.dimension() != CUDimensions.SPACE) {
            SPACE_SKIP.remove(id);
            return;
        }
        double time = SpaceLayout.time(level);
        SolarSystem.Body frame = SolarSystem.frameBody(pos, time);
        Vec3 rel = frame != null ? velocity.subtract(SolarSystem.velocity(frame, time)) : velocity;
        double speed = rel.length();
        boolean skipping = SPACE_SKIP.contains(id);
        boolean fast = skipping ? speed > SPACE_SKIP_BELOW : speed > SPACE_SKIP_ABOVE;
        boolean skip = fast && !nearSomething(pos, time);
        if (skip != skipping) {
            if (skip) {
                SPACE_SKIP.add(id);
            } else {
                SPACE_SKIP.remove(id);
            }
            dev.createuniversal.CreateUniversal.LOGGER.debug("Espacio: {} a {} b/s: {}", player.getScoreboardName(), Math.round(speed),
                    skip ? "sin cargar chunks" : "carga normal");
        }
    }

    /** Cerca de un astro (a menos de su radio de la superficie, 3.000 bloques como poco) o de un campo de rocas. */
    private static boolean nearSomething(Vec3 pos, double time) {
        for (SolarSystem.Body b : SolarSystem.bodies()) {
            if (pos.distanceTo(SolarSystem.position(b, time)) - b.radius() < Math.max(3_000.0, b.radius())) {
                return true;
            }
        }
        for (SpaceRocks.Field f : SpaceRocks.fields()) {
            Vec3 c = SpaceRocks.center(f, time);
            org.joml.Vector3d l = SpaceRocks.frame(f, time).conjugate(new org.joml.Quaterniond())
                    .transform(new org.joml.Vector3d(pos.x - c.x, pos.y - c.y, pos.z - c.z));
            double r = Math.hypot(l.x, l.z);
            if (r > f.rMin() - 1_500.0 && r < f.rMax() + 1_500.0 && Math.abs(l.y) < f.half() + 1_500.0) {
                return true;
            }
        }
        return false;
    }

    /** Si a este jugador, en el espacio, no hay que cargarle chunks alrededor ahora mismo. */
    public static boolean skipSpaceChunks(ServerPlayer player) {
        return SPACE_SKIP.contains(player.getUUID());
    }

    /** Chunks nuevos que se pueden pedir en este tick (Immersive Portals pide como mucho 4 a la vez). */
    public static int ticketBudget() {
        return budget;
    }

    /**
     * Prioridad de un chunk para Immersive Portals (menor = antes): su distancia de siempre, salvo si está en el camino
     * de alguien que va deprisa; entonces cuenta su distancia a ese camino, y lo de atrás pasa al final.
     */
    public static int priority(ServerLevel level, long chunkPos, int distance) {
        List<Mover> movers = MOVERS.get(level);
        if (movers == null || distance <= 0) {
            return distance;
        }
        double cx = ChunkPos.getX(chunkPos) + 0.5;
        double cz = ChunkPos.getZ(chunkPos) + 0.5;
        int best = Integer.MAX_VALUE;
        for (Mover m : movers) {
            double dx = m.toX - m.fromX;
            double dz = m.toZ - m.fromZ;
            double len2 = dx * dx + dz * dz;
            double t = len2 > 0.0 ? ((cx - m.fromX) * dx + (cz - m.fromZ) * dz) / len2 : 0.0;
            if (t < 0.0) {
                // Detrás: al final, en el mismo orden de cercanía.
                best = Math.min(best, distance * 2 + 2);
                continue;
            }
            t = Math.min(1.0, t);
            double px = m.fromX + dx * t;
            double pz = m.fromZ + dz * t;
            int toPath = (int) Math.max(Math.abs(cx - px), Math.abs(cz - pz));
            // Lo de delante, de lo más cerca a lo más lejos; los lados del camino, un poco después.
            int along = (int) (t * Math.sqrt(len2) / 3.0);
            best = Math.min(best, Math.max(1, toPath + 1 + along));
        }
        return best == Integer.MAX_VALUE ? distance : best;
    }
}
