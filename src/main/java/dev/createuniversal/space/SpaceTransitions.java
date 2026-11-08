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
    private static final String TAG = CreateUniversal.MOD_ID;
    private static final AttributeModifier ZERO_GRAVITY = new AttributeModifier(
            CreateUniversal.id("space_zero_gravity"), -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
    /** Separación entre naves que llegan al mismo punto, para que no aparezcan unas dentro de otras. */
    private static final double ARRIVAL_SPACING = 96.0;

    private SpaceTransitions() {}

    // ---------------------------------------------------------------- naves

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        ServerLevel overworld = server.overworld();
        ServerLevel space = server.getLevel(CUDimensions.SPACE);
        if (space == null) {
            return;
        }
        int exit = CUConfig.EXIT_ALTITUDE.get();

        for (ServerSubLevel ship : shipsToMove(overworld, s -> centerOf(s).y >= exit)) {
            Vec3 from = centerOf(ship);
            CompoundTag data = new CompoundTag();
            data.putDouble("earthX", from.x);
            data.putDouble("earthZ", from.z);
            Vec3 target = freeArrivalPoint(space, SpaceLayout.arrivalPoint());
            safeTransfer(ship, space, target, data);
        }
        for (ServerSubLevel ship : shipsToMove(space, s -> SpaceLayout.insideEarthAtmosphere(centerOf(s)))) {
            CompoundTag ours = ship.getUserDataTag() != null ? ship.getUserDataTag().getCompound(CreateUniversal.MOD_ID) : new CompoundTag();
            double x = ours.contains("earthX") ? ours.getDouble("earthX") : overworld.getSharedSpawnPos().getX() + 0.5;
            double z = ours.contains("earthZ") ? ours.getDouble("earthZ") : overworld.getSharedSpawnPos().getZ() + 0.5;
            safeTransfer(ship, overworld, new Vec3(x, exit - 150.0, z), ours);
        }
        ShipTransfer.tickRemounts();
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
            if (ship.isRemoved() || taken.contains(ship.getUniqueId()) || !test.test(ship)) {
                continue;
            }
            for (var linked : dev.ryanhcode.sable.api.SubLevelHelper.getConnectedChain(ship)) {
                taken.add(linked.getUniqueId());
            }
            taken.add(ship.getUniqueId());
            result.add(ship);
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
        updateZeroGravity(player, level.dimension() == CUDimensions.SPACE);

        if (player.isPassenger() || isAboardShip(player, level)) {
            return;
        }
        if (level.dimension() == Level.OVERWORLD && player.getY() >= CUConfig.EXIT_ALTITUDE.get()) {
            leaveForSpace(player);
        } else if (level.dimension() == CUDimensions.SPACE && SpaceLayout.insideEarthAtmosphere(player.position())) {
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

        Vec3 target = SpaceLayout.arrivalPoint();
        CreateUniversal.LOGGER.info("{} sale al espacio desde {} -> {}", player.getScoreboardName(), player.position(), target);
        player.changeDimension(new DimensionTransition(space, target, Vec3.ZERO, player.getYRot(), player.getXRot(), DimensionTransition.DO_NOTHING));
    }

    private static void returnToEarth(ServerPlayer player) {
        ServerLevel overworld = player.server.overworld();
        CompoundTag data = player.getPersistentData().getCompound(TAG);
        double x = data.contains("earthX") ? data.getDouble("earthX") : overworld.getSharedSpawnPos().getX() + 0.5;
        double z = data.contains("earthZ") ? data.getDouble("earthZ") : overworld.getSharedSpawnPos().getZ() + 0.5;
        Vec3 target = new Vec3(x, CUConfig.EXIT_ALTITUDE.get() - 100.0, z);
        CreateUniversal.LOGGER.info("{} vuelve a la Tierra -> {}", player.getScoreboardName(), target);
        player.changeDimension(new DimensionTransition(overworld, target, Vec3.ZERO, player.getYRot(), player.getXRot(), DimensionTransition.DO_NOTHING));
    }

    /** En el espacio no hay gravedad para el jugador (D4). Las naves ya la pierden por la física de Sable (dimension_physics/space.json). */
    private static void updateZeroGravity(ServerPlayer player, boolean inSpace) {
        AttributeInstance gravity = player.getAttribute(Attributes.GRAVITY);
        if (gravity == null) {
            return;
        }
        boolean has = gravity.hasModifier(ZERO_GRAVITY.id());
        if (inSpace && !has) {
            gravity.addTransientModifier(ZERO_GRAVITY);
        } else if (!inSpace && has) {
            gravity.removeModifier(ZERO_GRAVITY.id());
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
