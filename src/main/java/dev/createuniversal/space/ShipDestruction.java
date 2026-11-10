package dev.createuniversal.space;

import dev.createuniversal.CUConfig;
import dev.createuniversal.CreateUniversal;
import dev.createuniversal.registry.CUSuit;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Choques destructivos (D39, D40, D43–D45): una nave fijada a la que llega un astro, cualquier nave que toca el Sol o que
 * se mete en las nubes de un gigante gaseoso.
 *
 * <ul>
 *   <li><b>Explosión:</b> una grande en el centro y varias más repartidas por la nave. Las explosiones del mundo rompen
 *       los bloques de las naves (Sable) y Sable separa sola en pedazos lo que queda partido.</li>
 *   <li><b>Pedazos:</b> durante 10 s, todo trozo de nave que aparece en la zona se marca como resto y sale despedido hacia
 *       fuera. Los restos duran 1 hora (configurable) desde la explosión, aunque la zona no esté cargada, y estallan si
 *       chocan con un astro o con el Sol.</li>
 *   <li><b>Gente a bordo:</b> muere, salvo quien lleva el traje espacial completo, que sale despedido con algo de daño.
 *       Sus objetos y los de la nave flotan entre los restos y también duran 1 hora.</li>
 *   <li><b>Punto de ruta:</b> en la pantalla de quien iba a bordo o estaba cerca ({@link DebrisSites}).</li>
 * </ul>
 */
public final class ShipDestruction {
    public static final ResourceKey<DamageType> IMPACT = ResourceKey.create(Registries.DAMAGE_TYPE, CreateUniversal.id("impact"));
    public static final ResourceKey<DamageType> SUN = ResourceKey.create(Registries.DAMAGE_TYPE, CreateUniversal.id("sun"));

    private static final String DEBRIS = "debris_expires";
    private static final String ITEM_EXPIRES = CreateUniversal.MOD_ID + ":expires";
    /** Durante cuánto se recogen pedazos nuevos (Sable tarda unos ticks en separarlos). */
    private static final int COLLECT_TICKS = 200;

    private record Site(ServerLevel level, Vec3 center, double radius, long start, long expires) {}

    private static final List<Site> ACTIVE = new ArrayList<>();
    /** Jugadores con traje durante la explosión: el traje los protege. */
    private static final Set<UUID> SHIELDED = new HashSet<>();

    private ShipDestruction() {}

    public static long lifetimeTicks() {
        return CUConfig.DEBRIS_MINUTES.get() * 60L * 20L;
    }

    public static DamageSource damage(ServerLevel level, ResourceKey<DamageType> type) {
        return new DamageSource(level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(type));
    }

    // ---------------------------------------------------------------- restos

    public static boolean isDebris(ServerSubLevel ship) {
        CompoundTag ud = ship.getUserDataTag();
        return ud != null && ud.getCompound(CreateUniversal.MOD_ID).contains(DEBRIS);
    }

    public static boolean isDebris(List<ServerSubLevel> group) {
        for (ServerSubLevel s : group) {
            if (isDebris(s)) {
                return true;
            }
        }
        return false;
    }

    private static void markDebris(ServerSubLevel ship, long expires) {
        CompoundTag ud = ship.getUserDataTag() != null ? ship.getUserDataTag() : new CompoundTag();
        CompoundTag ours = ud.getCompound(CreateUniversal.MOD_ID);
        ours.putLong(DEBRIS, expires);
        ud.put(CreateUniversal.MOD_ID, ours);
        ship.setUserDataTag(ud);
    }

    private static long debrisExpiry(ServerSubLevel ship) {
        CompoundTag ud = ship.getUserDataTag();
        return ud != null ? ud.getCompound(CreateUniversal.MOD_ID).getLong(DEBRIS) : Long.MAX_VALUE;
    }

    // ---------------------------------------------------------------- destrucción

    /**
     * Destruye una nave entera (todas sus piezas unidas).
     *
     * @param cause   tipo de daño para quien va a bordo
     * @param message aviso para quien va a bordo
     */
    public static void destroy(ServerLevel level, List<ServerSubLevel> group, ResourceKey<DamageType> cause, Component message) {
        if (group.isEmpty()) {
            return;
        }
        Set<Entity> aboard = aboard(level, group);
        Vec3 c = centerOf(group);
        double size = 0.0;
        AABB box = null;
        for (ServerSubLevel s : group) {
            BoundingBox3dc b = s.boundingBox();
            AABB a = new AABB(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ());
            box = box == null ? a : box.minmax(a);
        }
        size = Math.max(box.getXsize(), Math.max(box.getYsize(), box.getZsize()));
        long now = level.getGameTime();
        long expires = now + lifetimeTicks();

        // Ya no está fijada ni en órbita: es chatarra desde ya.
        for (ServerSubLevel s : group) {
            markDebris(s, expires);
        }
        ACTIVE.add(new Site(level, c, size * 0.5 + 48.0, now, expires));

        int players = 0;
        List<ServerPlayer> suited = new ArrayList<>();
        for (Entity e : aboard) {
            if (e instanceof ServerPlayer p) {
                players++;
                p.displayClientMessage(message.copy().withStyle(ChatFormatting.RED), false);
                if (CUSuit.wearsFullSuit(p)) {
                    suited.add(p);
                    SHIELDED.add(p.getUUID());
                }
            }
        }

        DamageSource source = damage(level, cause);
        RandomSource random = level.getRandom();
        float power = (float) Math.max(6.0, Math.min(20.0, size * 0.5));
        try {
            level.explode(null, source, null, c.x, c.y, c.z, power, false, Level.ExplosionInteraction.TNT);
            int extra = (int) Math.min(6, 2 + box.getXsize() * box.getYsize() * box.getZsize() / 2000.0);
            for (int i = 0; i < extra; i++) {
                double x = box.minX + random.nextDouble() * box.getXsize();
                double y = box.minY + random.nextDouble() * box.getYsize();
                double z = box.minZ + random.nextDouble() * box.getZsize();
                level.explode(null, source, null, x, y, z, 5.0F + random.nextFloat() * 3.0F, false, Level.ExplosionInteraction.TNT);
            }
        } finally {
            SHIELDED.clear();
        }
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, c.x, c.y, c.z, 6, size * 0.3, size * 0.3, size * 0.3, 0.0);
        level.sendParticles(ParticleTypes.FLAME, c.x, c.y, c.z, 200, size * 0.3, size * 0.3, size * 0.3, 0.3);
        level.playSound(null, c.x, c.y, c.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 16.0F, 0.5F);

        for (Entity e : aboard) {
            if (!e.isAlive()) {
                continue;
            }
            if (e instanceof ServerPlayer p) {
                p.stopRiding();
                if (suited.contains(p)) {
                    Vec3 out = p.position().subtract(c).normalize().scale(1.2);
                    p.setDeltaMovement(out);
                    p.hurtMarked = true;
                } else {
                    p.hurt(source, Float.MAX_VALUE);
                }
            } else if (e instanceof LivingEntity living) {
                living.hurt(source, Float.MAX_VALUE);
            }
        }

        List<UUID> witnesses = new ArrayList<>();
        for (ServerPlayer p : level.players()) {
            if (aboard.contains(p) || p.position().distanceToSqr(c) < 512.0 * 512.0) {
                witnesses.add(p.getUUID());
            }
        }
        DebrisSites.add(level, c, expires, witnesses);
        CreateUniversal.LOGGER.info("Nave {} ({} piezas, {} jugadores) destruida ({}) en {} ({}, {}, {})", group.get(0).getUniqueId(),
                group.size(), players, cause.location(), level.dimension().location(), Math.round(c.x), Math.round(c.y), Math.round(c.z));
    }

    /** Lo que va a bordo: lo de la zona interna de cada pieza (asientos, objetos) y lo que está en su volumen. */
    static Set<Entity> aboard(ServerLevel level, List<ServerSubLevel> group) {
        Set<Entity> aboard = new LinkedHashSet<>();
        for (ServerSubLevel ship : group) {
            var plot = ship.getPlot();
            AABB plotAabb = new AABB(plot.getChunkMin().getMinBlockX(), level.getMinBuildHeight(), plot.getChunkMin().getMinBlockZ(),
                    plot.getChunkMax().getMaxBlockX() + 1, level.getMaxBuildHeight(), plot.getChunkMax().getMaxBlockZ() + 1);
            for (Entity e : level.getEntities((Entity) null, plotAabb, e -> plot.contains(e.position()))) {
                aboard.add(e);
                aboard.addAll(e.getPassengers());
            }
            BoundingBox3dc b = ship.boundingBox();
            aboard.addAll(level.getEntities((Entity) null, new AABB(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()).inflate(2.0),
                    e -> !(e instanceof ItemEntity)));
        }
        return aboard;
    }

    // ---------------------------------------------------------------- cada tick

    /** Cada tick del servidor: pedazos nuevos, restos caducados y choques con astros y el Sol. */
    public static void tick(MinecraftServer server, ServerLevel space) {
        collectPieces();
        if (server.getTickCount() % 100 == 0) {
            expireDebris(server);
            DebrisSites.prune(server);
        }
        impacts(space);
    }

    private static void collectPieces() {
        if (ACTIVE.isEmpty()) {
            return;
        }
        List<Site> done = new ArrayList<>();
        for (Site site : ACTIVE) {
            long age = site.level().getGameTime() - site.start();
            if (age > COLLECT_TICKS) {
                done.add(site);
                continue;
            }
            if (age % 5 != 0) {
                continue;
            }
            ServerSubLevelContainer container = SubLevelContainer.getContainer(site.level());
            if (container == null) {
                continue;
            }
            RandomSource random = site.level().getRandom();
            for (ServerSubLevel s : new ArrayList<>(container.getAllSubLevels())) {
                if (s.isRemoved() || Moonlets.isMoonlet(s) || centerOf(List.of(s)).distanceTo(site.center()) > site.radius()) {
                    continue;
                }
                CompoundTag ud = s.getUserDataTag();
                boolean pushed = ud != null && ud.getCompound(CreateUniversal.MOD_ID).getBoolean("debris_pushed");
                if (pushed) {
                    continue;
                }
                markDebris(s, site.expires());
                CompoundTag tag = s.getUserDataTag();
                tag.getCompound(CreateUniversal.MOD_ID).putBoolean("debris_pushed", true);
                s.setUserDataTag(tag);
                RigidBodyHandle h = container.physicsSystem().getPhysicsHandle(s);
                if (h != null && h.isValid()) {
                    Vec3 out = centerOf(List.of(s)).subtract(site.center());
                    out = out.lengthSqr() < 1.0e-4 ? new Vec3(random.nextGaussian(), random.nextGaussian(), random.nextGaussian()) : out;
                    out = out.normalize().scale(4.0 + random.nextDouble() * 8.0);
                    h.addLinearAndAngularVelocity(new Vector3d(out.x, out.y, out.z),
                            new Vector3d(random.nextGaussian(), random.nextGaussian(), random.nextGaussian()).mul(0.4));
                }
            }
        }
        ACTIVE.removeAll(done);
    }

    private static void expireDebris(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) {
                continue;
            }
            long now = level.getGameTime();
            for (ServerSubLevel s : new ArrayList<>(container.getAllSubLevels())) {
                if (!s.isRemoved() && isDebris(s) && debrisExpiry(s) <= now) {
                    container.removeSubLevel(s, SubLevelRemovalReason.REMOVED);
                }
            }
        }
    }

    /** Restos que tocan un astro (o la atmósfera de la Tierra) o el Sol: estallan y desaparecen. */
    public static void burnUp(ServerLevel level, ServerSubLevel piece) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null || piece.isRemoved()) {
            return;
        }
        Vec3 c = centerOf(List.of(piece));
        level.sendParticles(ParticleTypes.EXPLOSION, c.x, c.y, c.z, 4, 1.0, 1.0, 1.0, 0.0);
        level.sendParticles(ParticleTypes.FLAME, c.x, c.y, c.z, 30, 1.0, 1.0, 1.0, 0.1);
        level.playSound(null, c.x, c.y, c.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 4.0F, 1.0F);
        container.removeSubLevel(piece, SubLevelRemovalReason.REMOVED);
    }

    private static void impacts(ServerLevel space) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(space);
        if (container == null) {
            return;
        }
        double time = SpaceLayout.time(space);
        SolarSystem.Body sun = SolarSystem.get("sun");
        Vec3 sunPos = SolarSystem.position(sun, time);
        boolean warnNow = space.getGameTime() % 20 == 0;
        Set<UUID> seen = new HashSet<>();
        for (ServerSubLevel ship : new ArrayList<>(container.getAllSubLevels())) {
            if (ship.isRemoved() || seen.contains(ship.getUniqueId()) || Moonlets.isMoonlet(ship)) {
                continue;
            }
            if (isDebris(ship)) {
                seen.add(ship.getUniqueId());
                Vec3 c = centerOf(List.of(ship));
                if (c.distanceTo(sunPos) < sun.radius() || hitsBody(c, time, 70.0) != null
                        || SpaceLayout.insideEarthAtmosphere(c, time)) {
                    burnUp(space, ship);
                }
                continue;
            }
            List<ServerSubLevel> group = ShipGroup.of(ship);
            group.forEach(s -> seen.add(s.getUniqueId()));
            Vec3 c = centerOf(group);
            if (c.distanceTo(sunPos) < sun.radius() + 10.0) {
                destroy(space, group, SUN, Component.translatable("create_universal.message.ship_burned"));
                continue;
            }
            if (!OrbitalStabilizers.isPinned(group)) {
                continue;
            }
            SolarSystem.Body hit = hitsBody(c, time, 4.0);
            if (hit != null) {
                destroy(space, group, IMPACT, Component.translatable("create_universal.message.ship_hit",
                        Component.translatable("create_universal.body." + hit.id())));
            } else if (warnNow) {
                warnApproach(space, group, c, time);
            }
        }
        sunHeat(space, sunPos, sun.radius(), warnNow);
    }

    /** El astro (no el Sol) cuya superficie está a menos de {@code margin} bloques de {@code pos}, o null. */
    private static SolarSystem.Body hitsBody(Vec3 pos, double time, double margin) {
        for (SolarSystem.Body body : SolarSystem.bodies()) {
            if (body.isSun()) {
                continue;
            }
            if (pos.distanceTo(SolarSystem.position(body, time)) < body.radius() + margin) {
                return body;
            }
        }
        return null;
    }

    /** Aviso a bordo de una nave fijada: un astro viene hacia ella. */
    private static void warnApproach(ServerLevel level, List<ServerSubLevel> group, Vec3 c, double time) {
        for (SolarSystem.Body body : SolarSystem.bodies()) {
            if (body.isSun()) {
                continue;
            }
            Vec3 bp = SolarSystem.position(body, time);
            double gap = c.distanceTo(bp) - body.radius();
            if (gap > 1500.0) {
                continue;
            }
            double closing = SolarSystem.velocity(body, time).dot(c.subtract(bp).normalize());
            if (closing <= 0.05) {
                continue;
            }
            int seconds = (int) Math.ceil(gap / closing);
            if (seconds > 120) {
                continue;
            }
            Component msg = Component.translatable("create_universal.message.body_approaching",
                    Component.translatable("create_universal.body." + body.id()), seconds).withStyle(ChatFormatting.RED);
            for (Entity e : aboard(level, group)) {
                if (e instanceof ServerPlayer p) {
                    p.displayClientMessage(msg, true);
                }
            }
            return;
        }
    }

    /** Cerca del Sol: aviso, y más cerca, calor y fuego. Quien lo toca sin nave, muere. */
    private static void sunHeat(ServerLevel space, Vec3 sunPos, double radius, boolean warnNow) {
        for (ServerPlayer p : space.players()) {
            if (p.isSpectator() || p.isCreative()) {
                continue;
            }
            double d = p.position().distanceTo(sunPos);
            if (d < radius) {
                p.hurt(damage(space, SUN), Float.MAX_VALUE);
            } else if (d < radius * 1.3) {
                if (!SuitUpgrades.protects(p, SuitUpgrades.COOLING)) {
                    p.setRemainingFireTicks(Math.max(p.getRemainingFireTicks(), 60));
                    if (warnNow) {
                        p.hurt(damage(space, Hazards.HEAT), 2.0F);
                    }
                }
                if (warnNow) {
                    p.displayClientMessage(Component.translatable("create_universal.message.sun_close").withStyle(ChatFormatting.RED), true);
                }
            } else if (d < radius * 1.6 && warnNow) {
                p.displayClientMessage(Component.translatable("create_universal.message.sun_warning").withStyle(ChatFormatting.GOLD), true);
            }
        }
    }

    // ---------------------------------------------------------------- eventos

    /** El traje completo aguanta la explosión de su nave (D43). */
    @SubscribeEvent
    public static void onDamage(LivingIncomingDamageEvent event) {
        if (!SHIELDED.isEmpty() && SHIELDED.contains(event.getEntity().getUUID())) {
            event.setAmount(Math.min(event.getAmount(), 6.0F));
        }
    }

    /** Objetos entre los restos: duran lo mismo que los restos, aunque la zona se descargue. */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof ItemEntity item) || event.getLevel().isClientSide()) {
            return;
        }
        long now = event.getLevel().getGameTime();
        long expires = item.getPersistentData().getLong(ITEM_EXPIRES);
        if (expires == 0L) {
            for (Site site : ACTIVE) {
                if (site.level() == event.getLevel() && item.position().distanceTo(site.center()) < site.radius() + 32.0) {
                    expires = site.expires();
                    item.getPersistentData().putLong(ITEM_EXPIRES, expires);
                    break;
                }
            }
        }
        if (expires == 0L) {
            return;
        }
        long remaining = expires - now;
        if (remaining <= 0L) {
            event.setCanceled(true);
            return;
        }
        item.lifespan = (int) Math.min(Integer.MAX_VALUE, item.getAge() + remaining);
    }

    static Vec3 centerOf(List<ServerSubLevel> group) {
        double x = 0, y = 0, z = 0;
        for (ServerSubLevel s : group) {
            BoundingBox3dc b = s.boundingBox();
            x += (b.minX() + b.maxX()) * 0.5;
            y += (b.minY() + b.maxY()) * 0.5;
            z += (b.minZ() + b.maxZ()) * 0.5;
        }
        return new Vec3(x / group.size(), y / group.size(), z / group.size());
    }
}
