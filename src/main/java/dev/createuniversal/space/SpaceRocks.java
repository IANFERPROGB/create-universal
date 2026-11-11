package dev.createuniversal.space;

import dev.createuniversal.CUConfig;
import dev.createuniversal.CreateUniversal;
import dev.createuniversal.registry.CUBlocks;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.platform.SableAssemblyPlatform;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Rocas espaciales (D14–D16, alpha.10): el cinturón de asteroides y los anillos de Saturno.
 *
 * <ul>
 *   <li><b>Siempre las mismas:</b> no se guardan. El campo se reparte en celdas (anillos de {@link #CELL} bloques de
 *       ancho, cortados en trozos de unos {@link #CELL} bloques) y cada celda saca sus rocas de una semilla fija. Servidor
 *       y cliente calculan lo mismo.</li>
 *   <li><b>Se mueven:</b> cada franja del campo gira alrededor de su centro (el Sol, Saturno) a su ritmo: los anillos a la
 *       velocidad de una órbita en la zona de Saturno ({@link SatelliteOrbits#defaultSpeed}), así una nave que orbita
 *       Saturno va casi con ellas; el cinturón, muy despacio. Y cada roca da vueltas sobre sí misma.</li>
 *   <li><b>De lejos solo se dibujan</b> (cliente, {@code RockRenderer}). <b>De cerca</b> (a {@code rockCollisionChunks}
 *       chunks de un jugador, 6 por defecto) se vuelven una pieza de Sable de bloques que no se rompen, con colisión, que
 *       sigue su camino en cada paso de física (como Fobos y Deimos). Se puede aterrizar encima y te lleva con ella
 *       ({@link MoonletGrip}). Al alejarse todos, se quitan.</li>
 * </ul>
 */
public final class SpaceRocks {
    public static final String KEY = "space_rock";
    /** Tamaño de las celdas en las que se reparte el campo. */
    static final double CELL = 128.0;
    /** Lo más grande que puede ser una roca (radio). */
    static final double MAX_RADIUS = 14.0;

    /**
     * Un campo de rocas: un anillo plano alrededor de un centro.
     *
     * @param id        nombre (también el del fragmento que suelta, alpha.10.1)
     * @param center    astro en el centro
     * @param rMin      radio interior (bloques)
     * @param rMax      radio exterior
     * @param half      medio grosor (bloques, a cada lado del plano)
     * @param density   rocas por celda de 128 × 128
     * @param maxRadius radio de la roca más grande
     */
    public record Field(String id, String center, double rMin, double rMax, double half, double density, double maxRadius,
                        boolean ice) {}

    /** Una roca: su franja (para el giro), dónde está en el campo al principio y cómo es. */
    public record Rock(String id, Field field, int ring, double r, double phi0, double h, double a, double b, double c,
                       long seed, Vector3d spinAxis, double spinRate) {}

    private static List<Field> fields;

    private SpaceRocks() {}

    public static List<Field> fields() {
        if (fields == null) {
            List<Field> list = new ArrayList<>();
            // Cinturón de asteroides, entre Marte y Júpiter (sección 4.4). Disperso, con rocas grandes donde posarse.
            list.add(new Field("asteroid_belt", "sun", 546_875, 640_625, 400, 0.55, MAX_RADIUS, false));
            SolarSystem.Body saturn = SolarSystem.get("saturn");
            if (saturn != null && saturn.hasRings()) {
                // Anillos de Saturno: muchos trozos de hielo, casi planos (como en el dibujo de los anillos).
                list.add(new Field("saturn_rings", "saturn", saturn.radius() * saturn.ringInner(),
                        saturn.radius() * saturn.ringOuter(), 18, 2.2, 7.0, true));
            }
            fields = list;
        }
        return fields;
    }

    // ---------------------------------------------------------------- geometría (servidor y cliente)

    /** Centro del campo en el espacio. */
    static Vec3 center(Field f, double time) {
        return SolarSystem.position(SolarSystem.get(f.center()), time);
    }

    /** Orientación del plano del campo: el ecuador del astro (el Sol: el plano de las órbitas). */
    static Quaterniond frame(Field f, double time) {
        SolarSystem.Body body = SolarSystem.get(f.center());
        return body.isSun() ? new Quaterniond() : SolarSystem.orientation(body, time, false, 0.0);
    }

    /** Radio del centro de la franja {@code ring}. */
    static double ringRadius(Field f, int ring) {
        return f.rMin() + (ring + 0.5) * CELL;
    }

    /** Velocidad angular (rad/tick) de la franja: lo que gira el campo con el tiempo. */
    static double omega(Field f, int ring) {
        SolarSystem.Body body = SolarSystem.get(f.center());
        double r = ringRadius(f, ring);
        if (body.isSun()) {
            // El cinturón: una vuelta en ~1.000 días de Minecraft (casi quieto a la vista).
            return 2.0 * Math.PI / (1000.0 * 24000.0);
        }
        double scale = CUConfig.RING_SPEED.get();
        return SatelliteOrbits.defaultSpeed(body, r) / r / 20.0 * scale;
    }

    /** Número de trozos en que se corta la franja {@code ring}. */
    static int sectors(Field f, int ring) {
        return Math.max(8, (int) Math.floor(2.0 * Math.PI * ringRadius(f, ring) / CELL));
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, List<Rock>> CELLS = new java.util.concurrent.ConcurrentHashMap<>();

    /** Las rocas de una celda. Siempre las mismas para la misma celda (se guardan para no repetir la cuenta). */
    static List<Rock> cell(Field f, int ring, int sector) {
        int n = sectors(f, ring);
        int s = Math.floorMod(sector, n);
        String key = f.id() + ':' + ring + ':' + s;
        List<Rock> cached = CELLS.get(key);
        if (cached != null) {
            return cached;
        }
        if (CELLS.size() > 20000) {
            CELLS.clear();
        }
        List<Rock> made = makeCell(f, ring, s, n);
        CELLS.put(key, made);
        return made;
    }

    private static List<Rock> makeCell(Field f, int ring, int s, int n) {
        long seed = (f.id().hashCode() * 0x9E3779B97F4A7C15L) ^ (ring * 0xC2B2AE3D27D4EB4FL) ^ (s * 0x165667B19E3779F9L);
        Random rng = new Random(seed);
        double density = f.density() * CUConfig.ROCK_DENSITY.get();
        int count = (int) Math.floor(density) + (rng.nextDouble() < density - Math.floor(density) ? 1 : 0);
        List<Rock> rocks = new ArrayList<>(count);
        double dphi = 2.0 * Math.PI / n;
        for (int k = 0; k < count; k++) {
            double r = f.rMin() + (ring + rng.nextDouble()) * CELL;
            if (r > f.rMax()) {
                continue;
            }
            double phi = (s + rng.nextDouble()) * dphi;
            double h = Math.max(-f.half(), Math.min(f.half(), rng.nextGaussian() * f.half() * 0.45));
            // La mayoría pequeñas; unas pocas grandes.
            double u = rng.nextDouble();
            double size = 1.4 + (f.maxRadius() - 1.4) * u * u * u * u;
            double a = size * (0.75 + rng.nextDouble() * 0.5);
            double b = size * (0.6 + rng.nextDouble() * 0.5);
            double c = size * (0.55 + rng.nextDouble() * 0.45);
            Vector3d axis = new Vector3d(rng.nextGaussian(), rng.nextGaussian(), rng.nextGaussian());
            if (axis.lengthSquared() < 1.0e-6) {
                axis.set(0, 1, 0);
            }
            axis.normalize();
            double spin = (0.05 + rng.nextDouble() * 0.25) / 20.0 / Math.max(1.0, size * 0.5);
            rocks.add(new Rock(f.id() + ":" + ring + ":" + s + ":" + k, f, ring, r, phi, h, a, b, c, rng.nextLong(), axis, spin));
        }
        return rocks;
    }

    /** Posición de la roca en el espacio. */
    public static Vec3 position(Rock rock, double time) {
        double phi = rock.phi0() + omega(rock.field(), rock.ring()) * time;
        Vector3d local = new Vector3d(Math.cos(phi) * rock.r(), rock.h(), -Math.sin(phi) * rock.r());
        frame(rock.field(), time).transform(local);
        return center(rock.field(), time).add(local.x, local.y, local.z);
    }

    /** Orientación de la roca (su giro sobre sí misma). */
    public static Quaterniond orientation(Rock rock, double time) {
        double angle = (rock.spinRate() * time) % (Math.PI * 2.0);
        Vector3d ax = rock.spinAxis();
        double s = Math.sin(angle * 0.5);
        return new Quaterniond(ax.x * s, ax.y * s, ax.z * s, Math.cos(angle * 0.5));
    }

    /** Velocidad (bloques/s) y giro (rad/s, en el espacio) de la roca. */
    static Vec3 velocity(Rock rock, double time) {
        return position(rock, time + 0.5).subtract(position(rock, time - 0.5)).scale(20.0);
    }

    public static Vec3 velocityOf(Rock rock, double time) {
        return velocity(rock, time);
    }

    /** Un punto en mitad del campo, en el lado del centro que mira a la Tierra (para el comando de prueba). */
    public static Vec3 probePoint(Field f, double time) {
        Vec3 c = center(f, time);
        Vec3 earth = SolarSystem.position(SolarSystem.get("earth"), time).subtract(c);
        Vector3d local = new Quaterniond(frame(f, time)).conjugate().transform(new Vector3d(earth.x, earth.y, earth.z));
        double angle = Math.atan2(-local.z, local.x);
        double r = (f.rMin() + f.rMax()) * 0.5;
        Vector3d p = new Vector3d(Math.cos(angle) * r, 0.0, -Math.sin(angle) * r);
        frame(f, time).transform(p);
        return c.add(p.x, p.y, p.z);
    }

    static Vector3d spin(Rock rock) {
        return new Vector3d(rock.spinAxis()).mul(rock.spinRate() * 20.0);
    }

    /** Todas las rocas a menos de {@code range} bloques de {@code pos} (más su tamaño). */
    public static void near(Vec3 pos, double range, double time, Consumer<Rock> out) {
        for (Field f : fields()) {
            Vec3 rel = pos.subtract(center(f, time));
            Vector3d local = new Quaterniond(frame(f, time)).conjugate().transform(new Vector3d(rel.x, rel.y, rel.z));
            double reach = range + f.maxRadius();
            double r = Math.sqrt(local.x * local.x + local.z * local.z);
            if (r < f.rMin() - reach || r > f.rMax() + reach || Math.abs(local.y) > f.half() + reach) {
                continue;
            }
            int ring0 = (int) Math.floor((r - reach - f.rMin()) / CELL);
            int ring1 = (int) Math.floor((r + reach - f.rMin()) / CELL);
            int rings = (int) Math.ceil((f.rMax() - f.rMin()) / CELL);
            double angle = Math.atan2(-local.z, local.x);
            for (int ring = Math.max(0, ring0); ring <= Math.min(rings - 1, ring1); ring++) {
                int n = sectors(f, ring);
                double dphi = 2.0 * Math.PI / n;
                // Ángulo de la posición en el campo que gira: el de ahora menos lo que ha girado la franja.
                double a = angle - omega(f, ring) * time;
                double span = (reach + CELL) / Math.max(ringRadius(f, ring) - CELL, CELL);
                int s0 = (int) Math.floor((a - span) / dphi);
                int s1 = (int) Math.floor((a + span) / dphi);
                if (s1 - s0 >= n) {
                    s0 = 0;
                    s1 = n - 1;
                }
                for (int s = s0; s <= s1; s++) {
                    for (Rock rock : cell(f, ring, s)) {
                        if (position(rock, time).distanceTo(pos) < range + Math.max(rock.a(), Math.max(rock.b(), rock.c()))) {
                            out.accept(rock);
                        }
                    }
                }
            }
        }
    }

    /** Bloques de la roca (coordenadas relativas a su centro): un elipsoide abollado. */
    public static List<BlockPos> shape(Rock rock) {
        Random rng = new Random(rock.seed());
        double[][] bumps = new double[6][4];
        for (double[] bump : bumps) {
            Vector3d d = new Vector3d(rng.nextGaussian(), rng.nextGaussian(), rng.nextGaussian()).normalize();
            bump[0] = d.x;
            bump[1] = d.y;
            bump[2] = d.z;
            bump[3] = (rng.nextDouble() - 0.5) * 0.5;
        }
        List<BlockPos> blocks = new ArrayList<>();
        int ra = (int) Math.ceil(rock.a());
        int rb = (int) Math.ceil(rock.b());
        int rc = (int) Math.ceil(rock.c());
        for (int x = -ra; x <= ra; x++) {
            for (int y = -rc; y <= rc; y++) {
                for (int z = -rb; z <= rb; z++) {
                    double e = (x * x) / (rock.a() * rock.a()) + (y * y) / (rock.c() * rock.c()) + (z * z) / (rock.b() * rock.b());
                    double len = Math.sqrt(x * x + y * y + z * z);
                    if (len > 0) {
                        for (double[] bump : bumps) {
                            double dot = (x * bump[0] + y * bump[1] + z * bump[2]) / len;
                            if (dot > 0.6) {
                                e -= bump[3] * (dot - 0.6) * 2.5;
                            }
                        }
                    }
                    if (e <= 1.0) {
                        blocks.add(new BlockPos(x, y, z));
                    }
                }
            }
        }
        if (blocks.isEmpty()) {
            blocks.add(BlockPos.ZERO);
        }
        return blocks;
    }

    // ---------------------------------------------------------------- servidor: rocas sólidas cerca de los jugadores

    private static final TicketType<ChunkPos> TICKET = TicketType.create(CreateUniversal.MOD_ID + ":space_rock",
            Comparator.comparingLong(ChunkPos::toLong), 40);

    private record Active(Rock rock, ServerSubLevel body) {}

    private static final Map<String, Active> ACTIVE = new HashMap<>();
    private static double lastTick = Double.NaN;
    private static double subTicks;

    public static boolean isRock(ServerSubLevel ship) {
        CompoundTag tag = ship.getUserDataTag();
        return tag != null && tag.getCompound(CreateUniversal.MOD_ID).contains(KEY);
    }

    /** Nombre del origen de la roca de esta pieza (para los fragmentos), o null. */
    public static String origin(ServerSubLevel body) {
        for (Active a : ACTIVE.values()) {
            if (a.body() == body) {
                return a.rock().field().id();
            }
        }
        return null;
    }

    /** Id de la roca de esta pieza, o null. */
    public static String rockId(ServerSubLevel body) {
        for (Map.Entry<String, Active> e : ACTIVE.entrySet()) {
            if (e.getValue().body() == body) {
                return e.getKey();
            }
        }
        return null;
    }

    public static void tick(ServerLevel space) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(space);
        if (container == null) {
            return;
        }
        if (space.getGameTime() % 10 != 0) {
            return;
        }
        double time = SpaceLayout.time(space);
        double spawn = CUConfig.ROCK_COLLISION_CHUNKS.get() * 16.0;
        double despawn = spawn + 64.0;
        // Las que se dejaron al cerrar (no deberían quedar) o que nadie conoce: fuera.
        for (ServerSubLevel s : new ArrayList<>(container.getAllSubLevels())) {
            if (!s.isRemoved() && isRock(s) && ACTIVE.values().stream().noneMatch(a -> a.body() == s)) {
                container.removeSubLevel(s, SubLevelRemovalReason.REMOVED);
            }
        }
        Map<String, Rock> wanted = new HashMap<>();
        for (ServerPlayer p : space.players()) {
            if (p.isSpectator()) {
                continue;
            }
            near(p.position(), spawn, time, rock -> wanted.putIfAbsent(rock.id(), rock));
        }
        // Quitar las que ya no tiene nadie cerca.
        Set<String> keep = new HashSet<>();
        for (Map.Entry<String, Active> e : new ArrayList<>(ACTIVE.entrySet())) {
            Active a = e.getValue();
            Vec3 pos = position(a.rock(), time);
            boolean someone = false;
            for (ServerPlayer p : space.players()) {
                if (!p.isSpectator() && p.position().distanceTo(pos) < despawn) {
                    someone = true;
                    break;
                }
            }
            if (!someone || a.body().isRemoved()) {
                MoonletGrip.releaseAllFrom(a.body().getUniqueId(), space.getGameTime());
                if (!a.body().isRemoved()) {
                    container.removeSubLevel(a.body(), SubLevelRemovalReason.REMOVED);
                }
                ACTIVE.remove(e.getKey());
            } else {
                keep.add(e.getKey());
                if (space.getGameTime() % 20 == 0) {
                    ChunkPos chunk = new ChunkPos(BlockPos.containing(pos));
                    space.getChunkSource().addRegionTicket(TICKET, chunk, 2, chunk);
                }
            }
        }
        // Crear las nuevas (las más cercanas primero), hasta el máximo.
        int max = CUConfig.MAX_ACTIVE_ROCKS.get();
        List<Rock> pending = new ArrayList<>();
        for (Rock rock : wanted.values()) {
            if (!keep.contains(rock.id())) {
                pending.add(rock);
            }
        }
        pending.sort(Comparator.comparingDouble(r -> nearestPlayer(space, position(r, time))));
        for (Rock rock : pending) {
            if (ACTIVE.size() >= max) {
                break;
            }
            try {
                ACTIVE.put(rock.id(), new Active(rock, create(space, container, rock, time)));
            } catch (RuntimeException ex) {
                CreateUniversal.LOGGER.error("No se pudo crear la roca {}", rock.id(), ex);
            }
        }
    }

    private static double nearestPlayer(ServerLevel level, Vec3 pos) {
        double best = Double.POSITIVE_INFINITY;
        for (ServerPlayer p : level.players()) {
            best = Math.min(best, p.position().distanceTo(pos));
        }
        return best;
    }

    private static ServerSubLevel create(ServerLevel level, ServerSubLevelContainer container, Rock rock, double time) {
        Vec3 pos = position(rock, time);
        Pose3d pose = new Pose3d();
        pose.position().set(pos.x, pos.y, pos.z);
        ServerSubLevel body = (ServerSubLevel) container.allocateNewSubLevel(pose);
        CompoundTag ours = new CompoundTag();
        ours.putString(KEY, rock.id());
        CompoundTag user = new CompoundTag();
        user.put(CreateUniversal.MOD_ID, ours);
        body.setUserDataTag(user);
        body.setName(rock.field().id());

        LevelPlot plot = body.getPlot();
        plot.newEmptyChunk(plot.getCenterChunk());
        BlockPos anchor = plot.getCenterBlock();
        List<BlockPos> blocks = new ArrayList<>();
        for (BlockPos off : shape(rock)) {
            BlockPos p = anchor.offset(off);
            blocks.add(p);
            ChunkPos cp = new ChunkPos(p);
            if (plot.getChunkHolder(plot.toLocal(cp)) == null) {
                plot.newEmptyChunk(cp);
            }
        }
        BlockState stone = (rock.field().ice() ? CUBlocks.RING_ICE : CUBlocks.ASTEROID_ROCK).get().defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        SableAssemblyPlatform.INSTANCE.setIgnoreOnPlace(level, true);
        try {
            for (BlockPos p : blocks) {
                LevelChunk chunk = level.getChunk(p.getX() >> 4, p.getZ() >> 4);
                chunk.setBlockState(p, stone, false);
                level.onBlockStateChange(p, air, stone);
            }
        } finally {
            SableAssemblyPlatform.INSTANCE.setIgnoreOnPlace(level, false);
        }
        for (BlockPos p : blocks) {
            level.sendBlockUpdated(p, air, stone, 3);
        }
        body.logicalPose().rotationPoint().set(anchor.getX() + 0.5, anchor.getY() + 0.5, anchor.getZ() + 0.5);
        body.logicalPose().position().set(pos.x, pos.y, pos.z);
        Quaterniond q = orientation(rock, time);
        body.logicalPose().orientation().set(q);
        container.physicsSystem().getPipeline().teleport(body, body.logicalPose().position(), q);
        body.updateLastPose();
        return body;
    }

    /** Paso de física: cada roca sólida en su sitio, con su velocidad y su giro (lo llama {@link Moonlets}). */
    static List<MoonletGrip.MoonState> physicsStep(SubLevelPhysicsSystem system, double timeStep) {
        List<MoonletGrip.MoonState> states = new ArrayList<>();
        ServerLevel level = system.getLevel();
        if (ACTIVE.isEmpty() || level.dimension() != CUDimensions.SPACE) {
            return states;
        }
        double tick = SpaceLayout.time(level);
        if (tick != lastTick) {
            lastTick = tick;
            subTicks = 0.0;
        } else {
            subTicks = Math.min(1.0, subTicks + timeStep * 20.0);
        }
        double time = tick + subTicks;
        for (Active a : ACTIVE.values()) {
            if (a.body().isRemoved()) {
                continue;
            }
            RigidBodyHandle handle = system.getPhysicsHandle(a.body());
            if (handle == null || !handle.isValid()) {
                continue;
            }
            Vec3 pos = position(a.rock(), time);
            Quaterniond rot = orientation(a.rock(), time);
            handle.teleport(new Vector3d(pos.x, pos.y, pos.z), rot);
            Vec3 v = velocity(a.rock(), time);
            Vector3d spin = spin(a.rock());
            handle.addLinearAndAngularVelocity(new Vector3d(v.x, v.y, v.z).sub(handle.getLinearVelocity(new Vector3d())),
                    new Vector3d(spin).sub(handle.getAngularVelocity(new Vector3d())));
            states.add(new MoonletGrip.MoonState(a.body().getUniqueId(), a.rock().field().id(), new Vector3d(pos.x, pos.y, pos.z),
                    rot, new Vector3d(v.x, v.y, v.z), spin));
        }
        return states;
    }

    /** Al cerrar el servidor se quitan todas: se vuelven a crear al acercarse. */
    public static void removeAll(ServerLevel space) {
        ServerSubLevelContainer container = space != null ? SubLevelContainer.getContainer(space) : null;
        if (container != null) {
            for (Active a : ACTIVE.values()) {
                if (!a.body().isRemoved()) {
                    container.removeSubLevel(a.body(), SubLevelRemovalReason.REMOVED);
                }
            }
        }
        ACTIVE.clear();
        lastTick = Double.NaN;
    }
}
