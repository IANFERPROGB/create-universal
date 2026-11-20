package dev.createuniversal.space;

import dev.createuniversal.CreateUniversal;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.CanContinueSleepingEvent;
import net.neoforged.neoforge.event.entity.player.CanPlayerSleepEvent;
import net.neoforged.neoforge.event.level.SleepFinishedTimeEvent;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dormir en el espacio (alpha.13.0, D54, D55).
 *
 * <ul>
 *   <li>En el espacio se puede dormir a cualquier hora (allí siempre es de día) y la cama fija el punto de reaparición.</li>
 *   <li>Cuando duermen todos los jugadores que están en el espacio, se salta media hora real (36.000 ticks): la hora del
 *       mundo avanza (los astros siguen sus órbitas) y cada nave se pone donde estaría. No se simula tick a tick: se
 *       calcula. Fijada con el Estabilizador orbital, se queda; en órbita, sigue dando vueltas a su astro; si no, sigue
 *       recta a su velocidad (dentro de la zona de un astro, con el astro).</li>
 *   <li>Si en ese tiempo una nave con alguien durmiendo va a chocar con un astro, entrar en la zona de calor del Sol o en
 *       un campo de rocas, llegar al destino del piloto automático o quedarse sin combustible o sin oxígeno, el salto se
 *       corta 5 minutos antes y se avisa.</li>
 *   <li>Lo que la nave estaba gastando ({@link ShipSupplies}: combustible y oxígeno) se quita de sus depósitos, y si iba
 *       empujando sigue acelerando igual durante el salto (alpha.13.1).</li>
 *   <li>Después hay que esperar 30 minutos reales para volver a dormir en el espacio.</li>
 * </ul>
 */
public final class SpaceSleep {
    /** Lo que se salta (media hora real por defecto, configurable). */
    private static double jumpSeconds() {
        return dev.createuniversal.CUConfig.SLEEP_MINUTES.get() * 60.0;
    }
    /** Se despierta 5 minutos antes de lo que vaya a pasar. */
    private static final double WAKE_BEFORE = 300.0;
    /** Cooldown: 30 minutos reales (ticks del Overworld, que el salto no cambia). */
    private static long cooldownTicks() {
        return dev.createuniversal.CUConfig.SLEEP_COOLDOWN_MINUTES.get() * 1200L;
    }
    private static final double STEP = 5.0;

    /** Llegada prevista del piloto automático de cada nave (s desde ahora, al apuntarla) y cuándo se apuntó. */
    private record Arrival(double seconds, long at, String target) {}

    private static final Map<UUID, Arrival> ARRIVALS = new ConcurrentHashMap<>();

    private SpaceSleep() {}

    /** El asistente apunta cuándo llegará su nave (para despertar antes). */
    public static void arrival(ServerSubLevel ship, double seconds, String target) {
        ARRIVALS.put(ship.getUniqueId(), new Arrival(seconds, ship.getLevel().getGameTime(), target));
    }

    // ---------------------------------------------------------------- dormir a cualquier hora

    @SubscribeEvent
    public static void onCanSleep(CanPlayerSleepEvent event) {
        if (event.getLevel().dimension() != CUDimensions.SPACE) {
            return;
        }
        long left = cooldownLeft(event.getEntity().server);
        if (left > 0) {
            event.setProblem(Player.BedSleepingProblem.OTHER_PROBLEM);
            event.getEntity().displayClientMessage(Component.translatable("create_universal.sleep.cooldown",
                    Math.max(1, (left + 1199) / 1200)).withStyle(ChatFormatting.GOLD), true);
            return;
        }
        if (event.getProblem() == Player.BedSleepingProblem.NOT_POSSIBLE_NOW) {
            event.setProblem(null);
        }
    }

    @SubscribeEvent
    public static void onContinueSleeping(CanContinueSleepingEvent event) {
        if (event.getEntity().level().dimension() == CUDimensions.SPACE && event.getProblem() == Player.BedSleepingProblem.NOT_POSSIBLE_NOW) {
            event.setContinueSleeping(true);
        }
    }

    /** Las medidas de gasto de las naves con gente (cada 2 s, en el espacio). */
    @SubscribeEvent
    public static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        ServerLevel space = event.getServer().getLevel(CUDimensions.SPACE);
        if (space != null) {
            ShipSupplies.tick(space);
        }
    }

    /** Minecraft ya ha visto que duermen todos los del espacio: en vez de pasar a la mañana, el salto. */
    @SubscribeEvent
    public static void onSleepFinished(SleepFinishedTimeEvent event) {
        if (event.getLevel() instanceof ServerLevel space && space.dimension() == CUDimensions.SPACE) {
            try {
                jump(space);
            } catch (RuntimeException e) {
                CreateUniversal.LOGGER.error("Create Universal: falló el salto de tiempo al dormir en el espacio", e);
            }
        }
    }

    // ---------------------------------------------------------------- el salto

    /** Cómo se moverá una nave: posición y velocidad del centro dentro de {@code t} segundos. */
    private interface Path {
        Vec3 pos(double t);

        Vec3 vel(double t);
    }

    private record Plan(List<ServerSubLevel> group, Vec3 start, Path path, SolarSystem.Body orbiting, boolean sleepersAboard,
                        Arrival arrival, ShipSupplies.Rates rates) {}

    private record Event(double t, Component what) {}

    private static void jump(ServerLevel space) {
        MinecraftServer server = space.getServer();
        ServerSubLevelContainer container = SubLevelContainer.getContainer(space);
        if (container == null) {
            return;
        }
        double time = SpaceLayout.time(space);
        List<ServerPlayer> sleepers = new ArrayList<>();
        for (ServerPlayer p : space.players()) {
            if (p.isSleeping()) {
                sleepers.add(p);
            }
        }

        // Naves (cada grupo una vez), sin rocas, lunas diminutas ni restos.
        List<Plan> plans = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (ServerSubLevel s : new ArrayList<>(container.getAllSubLevels())) {
            if (s.isRemoved() || seen.contains(s.getUniqueId()) || Moonlets.isMoonlet(s)) {
                continue;
            }
            List<ServerSubLevel> group = ShipGroup.of(s);
            group.forEach(g -> seen.add(g.getUniqueId()));
            Set<Entity> aboard = ShipDestruction.aboard(space, group);
            boolean withSleepers = sleepers.stream().anyMatch(aboard::contains);
            Arrival arrival = null;
            for (ServerSubLevel g : group) {
                Arrival a = ARRIVALS.get(g.getUniqueId());
                if (a != null && space.getGameTime() - a.at() < 100) {
                    arrival = a;
                }
            }
            plans.add(plan(space, group, time, withSleepers, arrival));
        }

        // Lo primero que vaya a pasar a una nave con alguien durmiendo.
        Event first = null;
        for (Plan plan : plans) {
            if (!plan.sleepersAboard()) {
                continue;
            }
            Event e = firstEvent(plan, time, jumpSeconds() + WAKE_BEFORE, true);
            if (e != null && (first == null || e.t() < first.t())) {
                first = e;
            }
        }
        double seconds = jumpSeconds();
        if (first != null) {
            seconds = Math.min(seconds, first.t() - WAKE_BEFORE);
        }
        if (seconds < 60.0) {
            for (ServerPlayer p : sleepers) {
                p.stopSleepInBed(false, true);
                p.displayClientMessage(Component.translatable("create_universal.sleep.no_time", first != null ? first.what() : Component.empty())
                        .withStyle(ChatFormatting.GOLD), false);
            }
            return;
        }

        // La hora del mundo (los astros) y cada nave.
        ServerLevel overworld = server.overworld();
        overworld.setDayTime(overworld.getDayTime() + Math.round(seconds * 20.0));
        for (ServerPlayer p : sleepers) {
            p.stopSleepInBed(false, true);
        }
        for (Plan plan : plans) {
            move(space, container, plan, seconds);
        }
        Data.get(server).lastWake = overworld.getGameTime();
        Data.get(server).setDirty();
        ARRIVALS.clear();

        int minutes = (int) Math.round(seconds / 60.0);
        for (ServerPlayer p : space.players()) {
            p.displayClientMessage(Component.translatable("create_universal.sleep.slept", minutes).withStyle(ChatFormatting.AQUA), false);
            if (first != null && seconds < jumpSeconds()) {
                Component alarm = Component.translatable("create_universal.sleep.alarm").withStyle(ChatFormatting.RED);
                p.connection.send(new ClientboundSetTitleTextPacket(alarm));
                p.connection.send(new ClientboundSetSubtitleTextPacket(first.what().copy().withStyle(ChatFormatting.GOLD)));
                p.displayClientMessage(Component.translatable("create_universal.sleep.woken", first.what()).withStyle(ChatFormatting.GOLD), false);
            }
        }
        CreateUniversal.LOGGER.info("Dormir en el espacio: salto de {} s ({} naves){}", Math.round(seconds), plans.size(),
                first != null ? ", cortado por un aviso" : "");
    }

    private static Plan plan(ServerLevel space, List<ServerSubLevel> group, double time, boolean sleepers, Arrival arrival) {
        ServerSubLevel root = group.get(0);
        Vec3 c0 = OrbitalFrame.centerOf(root);
        Vec3 v0 = Autopilot.velocity(space, root);
        ShipSupplies.Rates rates = ShipSupplies.rates(group);
        Vec3 acc = rates.accel();
        if (OrbitalStabilizers.isPinned(group)) {
            return new Plan(group, c0, fixed(c0), null, sleepers, arrival, rates);
        }
        SolarSystem.Body orbitBody = SatelliteOrbits.orbitBodyOf(root);
        if (orbitBody != null) {
            Vec3 b0 = SolarSystem.position(orbitBody, time);
            Vec3 rel0 = c0.subtract(b0);
            Vec3 vrel = v0.subtract(SolarSystem.velocity(orbitBody, time));
            Vec3 n = rel0.cross(vrel);
            double r = rel0.length();
            if (n.lengthSqr() > 1.0e-6 && r > 1.0) {
                Vector3d axis = new Vector3d(n.x, n.y, n.z).normalize();
                Vec3 radial = rel0.normalize();
                Vec3 tangential = vrel.subtract(radial.scale(vrel.dot(radial)));
                double omega = tangential.length() / r;
                return new Plan(group, c0, new Path() {
                    @Override
                    public Vec3 pos(double t) {
                        Vector3d p = new Quaterniond().rotateAxis(omega * t, axis).transform(new Vector3d(rel0.x, rel0.y, rel0.z));
                        return SolarSystem.position(orbitBody, time + t * 20.0).add(p.x, p.y, p.z);
                    }

                    @Override
                    public Vec3 vel(double t) {
                        Vector3d v = new Quaterniond().rotateAxis(omega * t, axis).transform(new Vector3d(tangential.x, tangential.y, tangential.z));
                        return SolarSystem.velocity(orbitBody, time + t * 20.0).add(v.x, v.y, v.z);
                    }
                }, orbitBody, sleepers, arrival, rates);
            }
        }
        // Recta (acelerando si iba empujando); dentro de la zona de un astro (no el Sol), con el astro.
        SolarSystem.Body frame = SolarSystem.frameBody(c0, time);
        if (frame != null && !frame.isSun()) {
            Vec3 rel0 = c0.subtract(SolarSystem.position(frame, time));
            Vec3 vrel = v0.subtract(SolarSystem.velocity(frame, time));
            return new Plan(group, c0, new Path() {
                @Override
                public Vec3 pos(double t) {
                    return SolarSystem.position(frame, time + t * 20.0).add(rel0).add(vrel.scale(t)).add(acc.scale(0.5 * t * t));
                }

                @Override
                public Vec3 vel(double t) {
                    return SolarSystem.velocity(frame, time + t * 20.0).add(vrel).add(acc.scale(t));
                }
            }, null, sleepers, arrival, rates);
        }
        return new Plan(group, c0, new Path() {
            @Override
            public Vec3 pos(double t) {
                return c0.add(v0.scale(t)).add(acc.scale(0.5 * t * t));
            }

            @Override
            public Vec3 vel(double t) {
                return v0.add(acc.scale(t));
            }
        }, null, sleepers, arrival, rates);
    }

    private static Path fixed(Vec3 c0) {
        return new Path() {
            @Override
            public Vec3 pos(double t) {
                return c0;
            }

            @Override
            public Vec3 vel(double t) {
                return Vec3.ZERO;
            }
        };
    }

    /** Lo primero que le pasará a la nave en la próxima media hora (más 5 min), o null. */
    /** Un peligro en el camino de una nave: cuándo (s) y qué. */
    public record Danger(double t, Component what) {}

    /** Si la nave va a chocar con un astro, entrar en la zona de calor del Sol o en un campo de rocas en {@code seconds}. */
    public static Danger danger(ServerLevel space, List<ServerSubLevel> group, double seconds) {
        Plan plan = plan(space, group, SpaceLayout.time(space), false, null);
        Event e = firstEvent(plan, SpaceLayout.time(space), seconds, false);
        return e != null ? new Danger(e.t(), e.what()) : null;
    }

    private static Event firstEvent(Plan plan, double time, double horizon, boolean withSupplies) {
        if (withSupplies && plan.arrival() != null && plan.arrival().seconds() >= 0 && plan.arrival().seconds() < horizon) {
            // El piloto automático llega al destino.
            return new Event(plan.arrival().seconds(), Component.translatable("create_universal.sleep.event.arrival",
                    Math.round(plan.arrival().seconds() / 60.0)));
        }
        // Combustible u oxígeno que se acaban.
        Event supplies = null;
        ShipSupplies.Rates r = withSupplies ? plan.rates() : ShipSupplies.Rates.NONE;
        if (r.oxygenRate() > 0 && r.oxygen() / r.oxygenRate() < horizon) {
            double t = r.oxygen() / r.oxygenRate();
            supplies = new Event(t, Component.translatable("create_universal.sleep.event.oxygen", Math.round(t / 60.0)));
        }
        if (r.fuelRate() > 0 && r.fuel() / r.fuelRate() < horizon && (supplies == null || r.fuel() / r.fuelRate() < supplies.t())) {
            double t = r.fuel() / r.fuelRate();
            supplies = new Event(t, Component.translatable("create_universal.sleep.event.fuel", Math.round(t / 60.0)));
        }
        double limit = supplies != null ? supplies.t() : horizon;
        Vec3 start = plan.path().pos(0);
        List<SolarSystem.Body> bodies = SolarSystem.bodies();
        boolean[] near0 = new boolean[bodies.size()];
        for (int i = 0; i < bodies.size(); i++) {
            SolarSystem.Body b = bodies.get(i);
            near0[i] = start.distanceTo(SolarSystem.position(b, time)) < danger(b);
        }
        boolean[] inRocks0 = new boolean[SpaceRocks.fields().size()];
        for (int i = 0; i < inRocks0.length; i++) {
            inRocks0[i] = inField(SpaceRocks.fields().get(i), start, time);
        }
        double step = Math.min(STEP, horizon / 24.0);
        for (double t = step; t <= limit; t += step) {
            Vec3 p = plan.path().pos(t);
            double tt = time + t * 20.0;
            for (int i = 0; i < bodies.size(); i++) {
                SolarSystem.Body b = bodies.get(i);
                if (near0[i] || b == plan.orbiting()) {
                    continue;
                }
                if (p.distanceTo(SolarSystem.position(b, tt)) < danger(b)) {
                    String key = b.isSun() ? "create_universal.sleep.event.sun" : "create_universal.sleep.event.body";
                    return new Event(t, Component.translatable(key, Component.translatable("create_universal.body." + b.id()),
                            Math.round(t / 60.0)));
                }
            }
            for (int i = 0; i < inRocks0.length; i++) {
                SpaceRocks.Field f = SpaceRocks.fields().get(i);
                if (!inRocks0[i] && inField(f, p, tt)) {
                    return new Event(t, Component.translatable("create_universal.sleep.event.rocks",
                            Component.translatable("create_universal.rocks." + f.id()), Math.round(t / 60.0)));
                }
            }
        }
        return supplies;
    }

    /** A qué distancia del centro de un astro hay peligro: el Sol, su zona de calor; los demás, a 300 bloques del suelo. */
    private static double danger(SolarSystem.Body b) {
        return b.isSun() ? b.radius() * 1.6 : b.radius() + 300.0;
    }

    private static boolean inField(SpaceRocks.Field f, Vec3 p, double time) {
        Vec3 c = SpaceRocks.center(f, time);
        Vector3d l = SpaceRocks.frame(f, time).conjugate(new Quaterniond()).transform(new Vector3d(p.x - c.x, p.y - c.y, p.z - c.z));
        double r = Math.hypot(l.x, l.z);
        return r > f.rMin() && r < f.rMax() && Math.abs(l.y) < f.half() + 50.0;
    }

    /** Pone la nave (todas sus piezas, con lo que va a bordo en el mundo) donde estaría y con la velocidad que tendría. */
    private static void move(ServerLevel space, ServerSubLevelContainer container, Plan plan, double seconds) {
        ShipSupplies.consume(space, plan.group(), plan.rates(), seconds);
        Vec3 delta = plan.path().pos(seconds).subtract(plan.start());
        Vec3 newVel = plan.path().vel(seconds);
        if (delta.lengthSqr() < 1.0e-6) {
            return;
        }
        // Lo que va a bordo fuera de la zona interna (de pie, flotando): se mueve igual.
        List<Entity> carried = new ArrayList<>();
        for (Entity e : ShipDestruction.aboard(space, plan.group())) {
            boolean inPlot = false;
            for (ServerSubLevel s : plan.group()) {
                inPlot |= s.getPlot().contains(e.position());
            }
            if (!inPlot && e.getVehicle() == null) {
                carried.add(e);
            }
        }
        for (ServerSubLevel s : plan.group()) {
            RigidBodyHandle h = container.physicsSystem().getPhysicsHandle(s);
            if (h == null || !h.isValid()) {
                continue;
            }
            Vector3d pos = new Vector3d(s.logicalPose().position()).add(delta.x, delta.y, delta.z);
            h.teleport(pos, new Quaterniond(s.logicalPose().orientation()));
            Vector3d v = h.getLinearVelocity(new Vector3d());
            h.addLinearAndAngularVelocity(new Vector3d(newVel.x - v.x, newVel.y - v.y, newVel.z - v.z), new Vector3d());
        }
        for (Entity e : carried) {
            Vec3 to = e.position().add(delta);
            if (e instanceof ServerPlayer p) {
                p.connection.teleport(to.x, to.y, to.z, p.getYRot(), p.getXRot());
            } else {
                e.teleportTo(to.x, to.y, to.z);
            }
            e.setDeltaMovement(newVel.scale(0.05));
        }
    }

    // ---------------------------------------------------------------- cooldown

    private static long cooldownLeft(MinecraftServer server) {
        long last = Data.get(server).lastWake;
        if (last <= 0) {
            return 0;
        }
        return Math.max(0, cooldownTicks() - (server.overworld().getGameTime() - last));
    }

    private static final class Data extends SavedData {
        private static final String NAME = CreateUniversal.MOD_ID + "_space_sleep";
        long lastWake;

        static Data get(MinecraftServer server) {
            return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(Data::new, Data::load, null), NAME);
        }

        static Data load(CompoundTag tag, HolderLookup.Provider registries) {
            Data d = new Data();
            d.lastWake = tag.getLong("lastWake");
            return d;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            tag.putLong("lastWake", lastWake);
            return tag;
        }
    }
}
