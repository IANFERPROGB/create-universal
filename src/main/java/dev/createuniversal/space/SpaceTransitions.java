package dev.createuniversal.space;

import dev.createuniversal.CUConfig;
import dev.createuniversal.CreateUniversal;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Paso entre el Overworld y el espacio (D1, D3, D10).
 * <ul>
 *   <li>Naves de Sable: se mueven enteras, con todo lo que llevan, desde el tick del servidor ({@link ShipTransfer}).</li>
 *   <li>Jugadores sueltos (jetpack, elytra, creativo): se mueven solos. Quien va dentro de una nave o montado nunca se mueve solo.</li>
 * </ul>
 */
public final class SpaceTransitions {
    static final String TAG = CreateUniversal.MOD_ID;
    private static final AttributeModifier ZERO_GRAVITY = new AttributeModifier(
            CreateUniversal.id("space_zero_gravity"), -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
    /** Separación entre naves que llegan al mismo punto, para que no aparezcan unas dentro de otras. */
    private static final double ARRIVAL_SPACING = 96.0;
    /** Radio de chunks del espacio (4 = 64 bloques: hasta ahí se ven jugadores y objetos sueltos). */
    private static final int SPACE_VIEW_DISTANCE = 4;

    private SpaceTransitions() {}

    // ---------------------------------------------------------------- naves

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        FastTravelChunks.tick(server);
        OxygenZones.tick(server.overworld());
        Decompression.tick(server);
        GravityFields.tick(server);
        ServerLevel overworld = server.overworld();
        ServerLevel space = server.getLevel(CUDimensions.SPACE);
        if (space == null) {
            return;
        }
        int exit = CUConfig.EXIT_ALTITUDE.get();
        double time = SpaceLayout.time(space);
        if (server.getTickCount() % 20 == 0) {
            // En el espacio los chunks están vacíos (las naves van en Sable, con su propia distancia de visión): basta un
            // radio corto, y así ir muy deprisa no obliga a cargar cientos de chunks por segundo. Si el jugador cambia su
            // distancia de renderizado, Minecraft la vuelve a poner en todas las dimensiones: aquí se corrige cada segundo.
            space.getChunkSource().setViewDistance(Math.min(SPACE_VIEW_DISTANCE, server.getPlayerList().getViewDistance()));
        }

        for (ServerSubLevel ship : shipsToMove(overworld, s -> centerOf(s).y >= exit && !OrbitalStabilizers.isPinned(ShipGroup.of(s)))) {
            Vec3 from = centerOf(ship);
            CompoundTag data = new CompoundTag();
            data.putDouble("earthX", from.x);
            data.putDouble("earthZ", from.z);
            Vec3 target = freeArrivalPoint(space, SpaceLayout.arrivalPoint(time));
            safeTransfer(ship, space, target, data);
        }
        for (ServerSubLevel ship : shipsToMove(space, s -> SpaceLayout.insideEarthAtmosphere(centerOf(s), time) && !OrbitalStabilizers.isPinned(ShipGroup.of(s))
                && !ShipDestruction.isDebris(ShipGroup.of(s)))) {
            CompoundTag ours = ship.getUserDataTag() != null ? ship.getUserDataTag().getCompound(CreateUniversal.MOD_ID) : new CompoundTag();
            double x = ours.contains("earthX") ? ours.getDouble("earthX") : overworld.getSharedSpawnPos().getX() + 0.5;
            double z = ours.contains("earthZ") ? ours.getDouble("earthZ") : overworld.getSharedSpawnPos().getZ() + 0.5;
            safeTransfer(ship, overworld, new Vec3(x, exit - 150.0, z), ours);
        }
        dev.createuniversal.space.surface.SurfaceTransitions.tickShips(server, space);
        Moonlets.tick(space);
        GasGiants.tick(space, time);
        SatelliteOrbits.tick(space);
        ShipDestruction.tick(server, space);
        dev.createuniversal.space.surface.SurfaceTransitions.tickWrap(server);
        SkyShipBroadcaster.tick(server);
        ShipTransfer.tickRemounts();
        ShipTracker.tick();
        ShipAnchor.tick();
        OrbitalStabilizers.tick();
        LandingLock.tick(server);
    }

    /** Un fallo al mover una nave se registra en el log, pero nunca debe cerrar el servidor. */
    private static void safeTransfer(ServerSubLevel ship, ServerLevel target, Vec3 position, CompoundTag data) {
        try {
            ShipTransfer.transfer(ship, target, position, data);
        } catch (RuntimeException e) {
            CreateUniversal.LOGGER.error("Fallo al mover la nave {} a {}", ship.getUniqueId(), target.dimension().location(), e);
        }
    }

    private interface ShipTest {
        boolean test(ServerSubLevel ship);
    }

    /** Naves raíz que cumplen la condición. De cada grupo unido se devuelve solo una: el traslado mueve el grupo entero. */
    private static List<ServerSubLevel> shipsToMove(ServerLevel level, ShipTest test) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        List<ServerSubLevel> result = new ArrayList<>();
        if (container == null) {
            return result;
        }
        Set<UUID> taken = new HashSet<>();
        for (ServerSubLevel ship : new ArrayList<>(container.getAllSubLevels())) {
            if (ship.isRemoved() || taken.contains(ship.getUniqueId()) || Moonlets.isMoonlet(ship) || !test.test(ship)) {
                continue;
            }
            // Basta con que una pieza cruce para mover toda la nave; ninguna pieza se mueve dos veces.
            List<ServerSubLevel> group = ShipGroup.of(ship);
            group.forEach(s -> taken.add(s.getUniqueId()));
            // Lo que acaba de bajar del espacio no vuelve a subir hasta aterrizar (LandingLock).
            if (ShipGroup.settled(group, level) && !LandingLock.mustLand(group)) {
                result.add(ship);
            }
        }
        return result;
    }

    private static Vec3 centerOf(ServerSubLevel ship) {
        BoundingBox3dc b = ship.boundingBox();
        return new Vec3((b.minX() + b.maxX()) * 0.5, (b.minY() + b.maxY()) * 0.5, (b.minZ() + b.maxZ()) * 0.5);
    }

    private static Vec3 freeArrivalPoint(ServerLevel space, Vec3 wanted) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(space);
        Vec3 candidate = wanted;
        for (int i = 0; i < 64 && container != null; i++) {
            boolean busy = false;
            for (ServerSubLevel other : container.getAllSubLevels()) {
                if (!other.isRemoved() && centerOf(other).distanceTo(candidate) < ARRIVAL_SPACING) {
                    busy = true;
                    break;
                }
            }
            if (!busy) {
                return candidate;
            }
            candidate = candidate.add(ARRIVAL_SPACING, 0.0, 0.0);
        }
        return candidate;
    }

    // ---------------------------------------------------------------- jugadores sueltos

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.isSpectator()) {
            return;
        }
        ServerLevel level = player.serverLevel();
        updateGravity(player, level);
        rescueFromEmptyPlot(player, level);
        boolean mustLand = LandingLock.playerMustLand(player);

        if (player.isPassenger() || isAboardShip(player, level)) {
            return;
        }
        if (dev.createuniversal.space.surface.SurfaceTransitions.tickPlayer(player)) {
            return;
        }
        if (level.dimension() == Level.OVERWORLD && player.getY() >= CUConfig.EXIT_ALTITUDE.get() && !mustLand) {
            leaveForSpace(player);
        } else if (level.dimension() == CUDimensions.SPACE && SpaceLayout.insideEarthAtmosphere(player.position(), SpaceLayout.time(level))) {
            returnToEarth(player);
        }
    }

    private static void leaveForSpace(ServerPlayer player) {
        ServerLevel space = player.server.getLevel(CUDimensions.SPACE);
        if (space == null) {
            CreateUniversal.LOGGER.error("La dimensión {} no está cargada", CUDimensions.SPACE.location());
            return;
        }
        CompoundTag data = player.getPersistentData().getCompound(TAG);
        data.putDouble("earthX", player.getX());
        data.putDouble("earthZ", player.getZ());
        player.getPersistentData().put(TAG, data);

        double time = SpaceLayout.time(space);
        Vec3 target = SpaceLayout.arrivalPoint(time);
        // Conserva su impulso y suma el de la Tierra, que se mueve por su órbita: así no se queda atrás al llegar.
        Vec3 motion = player.getDeltaMovement().add(SpaceLayout.earthVelocity(time).scale(1.0 / 20.0));
        CreateUniversal.LOGGER.info("{} sale al espacio desde {} -> {}", player.getScoreboardName(), player.position(), target);
        SpaceGate.travel(() -> player.changeDimension(new DimensionTransition(space, target, motion, player.getYRot(), player.getXRot(), DimensionTransition.DO_NOTHING)));
        player.setDeltaMovement(motion);
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(player));
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                new dev.createuniversal.network.TransitionEffectPayload(dev.createuniversal.network.TransitionEffectPayload.EXIT));
    }

    private static void returnToEarth(ServerPlayer player) {
        ServerLevel overworld = player.server.overworld();
        CompoundTag data = player.getPersistentData().getCompound(TAG);
        double x = data.contains("earthX") ? data.getDouble("earthX") : overworld.getSharedSpawnPos().getX() + 0.5;
        double z = data.contains("earthZ") ? data.getDouble("earthZ") : overworld.getSharedSpawnPos().getZ() + 0.5;
        Vec3 target = new Vec3(x, CUConfig.EXIT_ALTITUDE.get() - 100.0, z);
        CreateUniversal.LOGGER.info("{} vuelve a la Tierra -> {}", player.getScoreboardName(), target);
        SpaceGate.travel(() -> player.changeDimension(new DimensionTransition(overworld, target, Vec3.ZERO, player.getYRot(), player.getXRot(), DimensionTransition.DO_NOTHING)));
        LandingLock.markPlayer(player);
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                new dev.createuniversal.network.TransitionEffectPayload(dev.createuniversal.network.TransitionEffectPayload.REENTRY));
    }

    /**
     * Si la nave de un jugador desaparece, el jugador puede quedarse en la zona interna donde Sable guarda los bloques de
     * las naves (a unos 20 millones de bloques), sentado en un asiento que ya no existe. Se le saca al sitio normal.
     */
    private static void rescueFromEmptyPlot(ServerPlayer player, ServerLevel level) {
        // Recién entrado, su nave puede estar cargando todavía (LoginHold): no se le saca.
        if (player.tickCount % 20 != 0 || player.tickCount < 400 || LoginHold.isHeld(player)) {
            return;
        }
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        net.minecraft.core.BlockPos pos = player.blockPosition();
        if (container == null || !container.inBounds(pos) || container.getPlot(new net.minecraft.world.level.ChunkPos(pos)) != null) {
            return;
        }
        player.stopRiding();
        Vec3 target;
        if (level.dimension() == CUDimensions.SPACE) {
            target = SpaceLayout.arrivalPoint(SpaceLayout.time(level));
        } else {
            dev.createuniversal.space.surface.PlanetSurface surface = dev.createuniversal.space.surface.PlanetSurface.byDimension(level.dimension());
            if (surface != null) {
                target = new Vec3(0.5, dev.createuniversal.space.surface.PlanetTerrain.get(surface).surfaceY(0, 0) + 3, 0.5);
            } else {
                net.minecraft.core.BlockPos spawn = level.getSharedSpawnPos();
                target = new Vec3(spawn.getX() + 0.5, level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, spawn.getX(), spawn.getZ()) + 1, spawn.getZ() + 0.5);
            }
        }
        CreateUniversal.LOGGER.warn("{} estaba en la zona interna de Sable sin nave ({}): se le lleva a {}", player.getScoreboardName(), player.position(), target);
        SpaceGate.travel(() -> player.teleportTo(level, target.x, target.y, target.z, player.getYRot(), player.getXRot()));
        player.setDeltaMovement(Vec3.ZERO);
    }

    /** Sin gravedad para el jugador en el espacio (D4). */
    private static void updateGravity(ServerPlayer player, ServerLevel level) {
        AttributeInstance gravity = player.getAttribute(Attributes.GRAVITY);
        if (gravity == null) {
            return;
        }
        // En la Luna y en Marte la gravedad la escala EntitySpaceGravityMixin (para todas las entidades); aquí solo el espacio.
        double wanted = level.dimension() == CUDimensions.SPACE ? -1.0 : 0.0;
        AttributeModifier current = gravity.getModifier(ZERO_GRAVITY.id());
        if (current != null && current.amount() == wanted) {
            return;
        }
        if (current != null) {
            gravity.removeModifier(ZERO_GRAVITY.id());
        }
        if (wanted != 0.0) {
            gravity.addTransientModifier(new AttributeModifier(ZERO_GRAVITY.id(), wanted, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
    }

    /** ¿Está el jugador dentro del volumen de alguna nave? Entonces viaja con la nave entera. */
    private static boolean isAboardShip(ServerPlayer player, ServerLevel level) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return false;
        }
        AABB box = player.getBoundingBox().inflate(2.0);
        for (ServerSubLevel ship : container.getAllSubLevels()) {
            if (!ship.isRemoved() && ship.boundingBox().intersects(box)) {
                return true;
            }
        }
        return false;
    }
}
