package dev.createuniversal.space;

import dev.createuniversal.CUConfig;
import dev.createuniversal.CreateUniversal;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * alpha.1: paso de un jugador suelto entre el Overworld y el espacio (D1, D3).
 * Las naves de Sable se mueven en la alpha.2; mientras tanto, a quien vaya dentro de una nave no se le mueve solo.
 */
public final class SpaceTransitions {
    private static final String TAG = CreateUniversal.MOD_ID;
    private static final AttributeModifier ZERO_GRAVITY = new AttributeModifier(
            CreateUniversal.id("space_zero_gravity"), -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);

    private SpaceTransitions() {}

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.isSpectator()) {
            return;
        }
        ServerLevel level = player.serverLevel();
        updateZeroGravity(player, level.dimension() == CUDimensions.SPACE);

        if (isAboardSubLevel(player, level)) {
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

    /** ¿Está el jugador dentro del volumen de alguna nave de Sable? Entonces el viaje lo hará la nave entera (alpha.2). */
    private static boolean isAboardSubLevel(ServerPlayer player, ServerLevel level) {
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return false;
        }
        return container.queryIntersecting(new BoundingBox3d(player.getBoundingBox().inflate(1.0))).iterator().hasNext();
    }
}
