package dev.createuniversal.space;

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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Fobos y Deimos como cuerpos sólidos (D35): son tan pequeños (12 y 8 bloques de radio en el espacio) que no tienen
 * dimensión propia, pero se puede chocar con ellos y posarse encima.
 *
 * <ul>
 *   <li>Cuando un jugador se acerca a menos de {@link #SPAWN} bloques, la luna aparece como una pieza de Sable con su
 *       forma real (un elipsoide con cráteres, Stickney en Fobos) de un bloque que no se puede romper; a más de
 *       {@link #DESPAWN} de todos los jugadores se quita. No se guarda nada: se crea igual cada vez.</li>
 *   <li>En cada paso de física se la coloca en su órbita, girando con la cara larga hacia Marte, con su velocidad real,
 *       para que lo que se apoye en ella viaje con ella.</li>
 *   <li>No es una nave: no cambia de dimensión, no la aplasta nada y no se puede construir en ella ni picarla.</li>
 *   <li>De cerca el cliente deja de dibujar la esfera de la luna ({@link #HIDE_SPHERE_WITHIN}): se ven los bloques.</li>
 * </ul>
 */
public final class Moonlets {
    public static final String KEY = "moonlet";
    /** Distancia a la que aparece el cuerpo sólido. */
    private static final double SPAWN = 200.0;
    /** Distancia a la que se quita (más que {@link #SPAWN}, para que no aparezca y desaparezca en el borde). */
    private static final double DESPAWN = 320.0;
    /** El cliente deja de dibujar la esfera de Fobos o Deimos a menos de esta distancia. */
    public static final double HIDE_SPHERE_WITHIN = 180.0;

    /** Semiejes (hacia Marte, a lo largo de la órbita, polar), con las proporciones reales y el radio medio del mod. */
    private record Shape(String id, double a, double b, double c, double[][] craters) {}

    private static final Shape[] SHAPES = {
            // Fobos: 27 × 22 × 18 km. Stickney (9 km) en la cara que mira hacia delante en la órbita.
            new Shape("phobos", 14.7, 12.0, 9.8, new double[][] {{-0.35, 0.05, 1.0, 4.6}}),
            // Deimos: 15 × 12 × 11 km, liso; solo cráteres pequeños.
            new Shape("deimos", 9.7, 7.9, 6.7, new double[0][])};

    private static final TicketType<ChunkPos> TICKET = TicketType.create(CreateUniversal.MOD_ID + ":moonlet",
            Comparator.comparingLong(ChunkPos::toLong), 40);

    /** Pieza de Sable de cada luna mientras existe. */
    private static final Map<String, ServerSubLevel> ACTIVE = new HashMap<>();
    /** Para repartir la posición entre los pasos de física de un mismo tick (las lunas van a 60–90 bloques/s). */
    private static double lastTick = Double.NaN;
    private static double subTicks;

    private Moonlets() {}

    public static boolean isMoonlet(ServerSubLevel ship) {
        CompoundTag tag = ship.getUserDataTag();
        return tag != null && tag.getCompound(CreateUniversal.MOD_ID).contains(KEY);
    }

    // ---------------------------------------------------------------- cada tick del servidor

    public static void tick(ServerLevel space) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(space);
        if (container == null) {
            return;
        }
        double time = SpaceLayout.time(space);
        adopt(container);
        for (Shape shape : SHAPES) {
            SolarSystem.Body body = SolarSystem.get(shape.id());
            if (body == null) {
                continue;
            }
            Vec3 pos = SolarSystem.position(body, time);
            double nearest = Double.POSITIVE_INFINITY;
            for (ServerPlayer p : space.players()) {
                if (!p.isSpectator()) {
                    nearest = Math.min(nearest, p.position().distanceTo(pos));
                }
            }
            ServerSubLevel moon = ACTIVE.get(shape.id());
            if (moon != null && moon.isRemoved()) {
                ACTIVE.remove(shape.id());
                moon = null;
            }
            if (moon == null && nearest < SPAWN) {
                try {
                    ACTIVE.put(shape.id(), create(space, container, shape, body, time));
                } catch (RuntimeException e) {
                    CreateUniversal.LOGGER.error("No se pudo crear {} como cuerpo sólido", shape.id(), e);
                }
            } else if (moon != null && nearest > DESPAWN) {
                MoonletGrip.releaseAllFrom(moon.getUniqueId(), space.getGameTime());
                container.removeSubLevel(moon, SubLevelRemovalReason.REMOVED);
                ACTIVE.remove(shape.id());
                CreateUniversal.LOGGER.info("{} deja de ser un cuerpo sólido (nadie cerca)", shape.id());
            }
            if (ACTIVE.containsKey(shape.id()) && space.getGameTime() % 5 == 0) {
                // Que la zona donde está no se descargue aunque la luna vaya más deprisa de lo que carga el jugador.
                ChunkPos chunk = new ChunkPos(BlockPos.containing(pos));
                space.getChunkSource().addRegionTicket(TICKET, chunk, 3, chunk);
            }
        }
    }

    /** Recoge las lunas que ya existen (al volver a cargar el mundo) y quita las que sobren. */
    private static void adopt(ServerSubLevelContainer container) {
        for (ServerSubLevel ship : new ArrayList<>(container.getAllSubLevels())) {
            if (ship.isRemoved() || !isMoonlet(ship)) {
                continue;
            }
            String id = ship.getUserDataTag().getCompound(CreateUniversal.MOD_ID).getString(KEY);
            ServerSubLevel current = ACTIVE.get(id);
            if (current == null || current.isRemoved()) {
                ACTIVE.put(id, ship);
            } else if (current != ship) {
                container.removeSubLevel(ship, SubLevelRemovalReason.REMOVED);
            }
        }
    }

    private static ServerSubLevel create(ServerLevel level, ServerSubLevelContainer container, Shape shape, SolarSystem.Body body,
                                         double time) {
        Vec3 pos = SolarSystem.position(body, time);
        Pose3d pose = new Pose3d();
        pose.position().set(pos.x, pos.y, pos.z);
        ServerSubLevel moon = (ServerSubLevel) container.allocateNewSubLevel(pose);
        CompoundTag ours = new CompoundTag();
        ours.putString(KEY, shape.id());
        CompoundTag user = new CompoundTag();
        user.put(CreateUniversal.MOD_ID, ours);
        moon.setUserDataTag(user);
        moon.setName(shape.id());

        LevelPlot plot = moon.getPlot();
        plot.newEmptyChunk(plot.getCenterChunk());
        BlockPos anchor = plot.getCenterBlock();
        List<BlockPos> blocks = shapeBlocks(shape, anchor);
        for (BlockPos p : blocks) {
            ChunkPos cp = new ChunkPos(p);
            if (plot.getChunkHolder(plot.toLocal(cp)) == null) {
                plot.newEmptyChunk(cp);
            }
        }
        BlockState rock = CUBlocks.CARBONACEOUS_REGOLITH.get().defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        SableAssemblyPlatform.INSTANCE.setIgnoreOnPlace(level, true);
        try {
            for (BlockPos p : blocks) {
                LevelChunk chunk = level.getChunk(p.getX() >> 4, p.getZ() >> 4);
                chunk.setBlockState(p, rock, false);
                level.onBlockStateChange(p, air, rock);
            }
        } finally {
            SableAssemblyPlatform.INSTANCE.setIgnoreOnPlace(level, false);
        }
        for (BlockPos p : blocks) {
            level.sendBlockUpdated(p, air, rock, 3);
        }
        moon.logicalPose().rotationPoint().set(anchor.getX() + 0.5, anchor.getY() + 0.5, anchor.getZ() + 0.5);
        moon.logicalPose().position().set(pos.x, pos.y, pos.z);
        Quaterniond q = orientation(body, time);
        moon.logicalPose().orientation().set(q);
        container.physicsSystem().getPipeline().teleport(moon, moon.logicalPose().position(), q);
        moon.updateLastPose();
        CreateUniversal.LOGGER.info("{} es ahora un cuerpo sólido: {} bloques", shape.id(), blocks.size());
        return moon;
    }

    /** Los bloques de la luna, en coordenadas de su zona interna, alrededor de {@code anchor}. */
    private static List<BlockPos> shapeBlocks(Shape shape, BlockPos anchor) {
        List<double[]> craters = new ArrayList<>();
        for (double[] c : shape.craters()) {
            craters.add(crater(shape, c[0], c[1], c[2], c[3]));
        }
        Random rng = new Random(shape.id().hashCode());
        for (int i = 0; i < 14; i++) {
            double[] d = {rng.nextGaussian(), rng.nextGaussian(), rng.nextGaussian()};
            craters.add(crater(shape, d[0], d[1], d[2], 1.2 + rng.nextDouble() * 1.8));
        }
        List<BlockPos> blocks = new ArrayList<>();
        int ra = (int) Math.ceil(shape.a());
        int rb = (int) Math.ceil(shape.b());
        int rc = (int) Math.ceil(shape.c());
        // Eje X local = hacia Marte (a), Z = a lo largo de la órbita (b), Y = polar (c).
        for (int x = -ra; x <= ra; x++) {
            for (int y = -rc; y <= rc; y++) {
                for (int z = -rb; z <= rb; z++) {
                    double e = (x * x) / (shape.a() * shape.a()) + (y * y) / (shape.c() * shape.c()) + (z * z) / (shape.b() * shape.b());
                    if (e > 1.0 || inCrater(craters, x, y, z)) {
                        continue;
                    }
                    blocks.add(anchor.offset(x, y, z));
                }
            }
        }
        return blocks;
    }

    /** Esfera que se resta: centrada algo por fuera de la superficie en esa dirección, para que quede un cuenco. */
    private static double[] crater(Shape shape, double dx, double dy, double dz, double radius) {
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        dx /= len;
        dy /= len;
        dz /= len;
        // Punto de la superficie del elipsoide en esa dirección.
        double t = 1.0 / Math.sqrt(dx * dx / (shape.a() * shape.a()) + dy * dy / (shape.c() * shape.c()) + dz * dz / (shape.b() * shape.b()));
        double out = radius * 0.55;
        return new double[] {dx * (t + out), dy * (t + out), dz * (t + out), radius};
    }

    private static boolean inCrater(List<double[]> craters, int x, int y, int z) {
        for (double[] c : craters) {
            double ex = x - c[0];
            double ey = y - c[1];
            double ez = z - c[2];
            if (ex * ex + ey * ey + ez * ez < c[3] * c[3]) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- órbita en cada paso de física

    public static void prePhysicsTick(SubLevelPhysicsSystem system, double timeStep) {
        ServerLevel level = system.getLevel();
        if (level.dimension() != CUDimensions.SPACE || ACTIVE.isEmpty()) {
            return;
        }
        double tick = SpaceLayout.time(level);
        if (tick != lastTick) {
            lastTick = tick;
            subTicks = 0.0;
        } else {
            subTicks = Math.min(1.0, subTicks + timeStep * 20.0);
        }
        double time = tick + subTicks;
        List<MoonletGrip.MoonState> states = new ArrayList<>();
        for (Map.Entry<String, ServerSubLevel> e : ACTIVE.entrySet()) {
            ServerSubLevel moon = e.getValue();
            SolarSystem.Body body = SolarSystem.get(e.getKey());
            if (moon.isRemoved() || body == null) {
                continue;
            }
            RigidBodyHandle handle = system.getPhysicsHandle(moon);
            if (handle == null || !handle.isValid()) {
                continue;
            }
            Vec3 pos = SolarSystem.position(body, time);
            Quaterniond rot = orientation(body, time);
            handle.teleport(new Vector3d(pos.x, pos.y, pos.z), rot);
            Vec3 v = SolarSystem.velocity(body, time);
            Vector3d spin = orbitNormal(body, time).mul(2.0 * Math.PI / (body.periodDays() * 24000.0 / 20.0));
            Vector3d lin = new Vector3d(v.x, v.y, v.z).sub(handle.getLinearVelocity(new Vector3d()));
            Vector3d ang = new Vector3d(spin).sub(handle.getAngularVelocity(new Vector3d()));
            handle.addLinearAndAngularVelocity(lin, ang);
            states.add(new MoonletGrip.MoonState(moon.getUniqueId(), e.getKey(), new Vector3d(pos.x, pos.y, pos.z), rot,
                    new Vector3d(v.x, v.y, v.z), spin));
        }
        MoonletGrip.step(system, states);
    }

    private static Vector3d orbitNormal(SolarSystem.Body body, double time) {
        Vec3 r = SolarSystem.position(body, time).subtract(SolarSystem.position(body.parent(), time));
        Vec3 v = SolarSystem.velocity(body, time).subtract(SolarSystem.velocity(SolarSystem.get(body.parent()), time));
        Vec3 n = r.cross(v).normalize();
        return new Vector3d(n.x, n.y, n.z);
    }

    /**
     * Acoplamiento de marea: eje X local hacia Marte, Y local = normal de la órbita, Z local hacia delante en la órbita.
     * El cuaternión se calcula a mano desde la matriz (la JOML de Minecraft tiene fallos con los cuaterniones, ver MODLOG).
     */
    private static Quaterniond orientation(SolarSystem.Body body, double time) {
        Vec3 toParent = SolarSystem.position(body.parent(), time).subtract(SolarSystem.position(body, time)).normalize();
        Vector3d y = orbitNormal(body, time);
        Vector3d x = new Vector3d(toParent.x, toParent.y, toParent.z);
        x.sub(new Vector3d(y).mul(x.dot(y))).normalize();
        Vector3d z = new Vector3d(x).cross(y).normalize();
        // Matriz de columnas x, y, z (local → mundo).
        double m00 = x.x, m01 = y.x, m02 = z.x;
        double m10 = x.y, m11 = y.y, m12 = z.y;
        double m20 = x.z, m21 = y.z, m22 = z.z;
        double trace = m00 + m11 + m22;
        double qw, qx, qy, qz;
        if (trace > 0) {
            double s = Math.sqrt(trace + 1.0) * 2.0;
            qw = 0.25 * s;
            qx = (m21 - m12) / s;
            qy = (m02 - m20) / s;
            qz = (m10 - m01) / s;
        } else if (m00 > m11 && m00 > m22) {
            double s = Math.sqrt(1.0 + m00 - m11 - m22) * 2.0;
            qw = (m21 - m12) / s;
            qx = 0.25 * s;
            qy = (m01 + m10) / s;
            qz = (m02 + m20) / s;
        } else if (m11 > m22) {
            double s = Math.sqrt(1.0 + m11 - m00 - m22) * 2.0;
            qw = (m02 - m20) / s;
            qx = (m01 + m10) / s;
            qy = 0.25 * s;
            qz = (m12 + m21) / s;
        } else {
            double s = Math.sqrt(1.0 + m22 - m00 - m11) * 2.0;
            qw = (m10 - m01) / s;
            qx = (m02 + m20) / s;
            qy = (m12 + m21) / s;
            qz = 0.25 * s;
        }
        return new Quaterniond(qx, qy, qz, qw).normalize();
    }

    // ---------------------------------------------------------------- protección

    private static boolean onMoonlet(Level level, BlockPos pos) {
        if (level.isClientSide() || level.dimension() != CUDimensions.SPACE) {
            return false;
        }
        Vec3 c = Vec3.atCenterOf(pos);
        for (ServerSubLevel moon : ACTIVE.values()) {
            if (!moon.isRemoved() && moon.getPlot().contains(c)) {
                return true;
            }
        }
        return false;
    }

    public static void onBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof Level level && onMoonlet(level, event.getPos())) {
            event.setCanceled(true);
        }
    }

    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel() instanceof Level level && onMoonlet(level, event.getPos())) {
            event.setCanceled(true);
        }
    }

    /** Al cerrar el servidor se quitan: se vuelven a crear al acercarse, y así no quedan copias guardadas. */
    public static void onServerStopping(ServerStoppingEvent event) {
        ServerLevel space = event.getServer().getLevel(CUDimensions.SPACE);
        ServerSubLevelContainer container = space != null ? SubLevelContainer.getContainer(space) : null;
        if (container != null) {
            for (ServerSubLevel moon : ACTIVE.values()) {
                if (!moon.isRemoved()) {
                    container.removeSubLevel(moon, SubLevelRemovalReason.REMOVED);
                }
            }
        }
        ACTIVE.clear();
        MoonletGrip.clear();
        lastTick = Double.NaN;
    }
}
