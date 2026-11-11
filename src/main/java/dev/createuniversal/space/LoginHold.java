package dev.createuniversal.space;

import dev.createuniversal.CreateUniversal;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityMountEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Volver a la nave al entrar en el mundo (alpha.9.8). Problema: al guardar sentado, Sable guarda la posición real del
 * jugador (junto a la nave), pero al entrar Minecraft lo vuelve a sentar en su asiento, que está en la zona interna de
 * Sable (a ~20 millones de bloques). Si la nave aún no ha cargado, el jugador se queda allí, la nave (que solo carga con
 * alguien cerca de su sitio real) no llega a cargar, y Sable Player Ragdoll Patch o {@code SpaceTransitions} lo sacan al
 * punto de aparición: "la nave desaparece".
 *
 * <ol>
 *   <li>Al salir del juego se apunta en los datos del jugador en qué nave estaba y dónde: el bloque del asiento, o su
 *       posición en coordenadas de la nave si iba de pie.</li>
 *   <li>Al entrar no se le deja sentarse en una nave sin cargar: se queda en su posición real, quieto y sin recibir daño,
 *       hasta que la nave carga y su cliente la tiene ({@link #SETTLE_MS} de margen).</li>
 *   <li>Entonces se le pone en su sitio de la nave (aunque esta se haya movido) y, si iba sentado, se le vuelve a
 *       sentar. Si la nave no aparece en {@link #TIMEOUT_MS}, se queda donde está y se le avisa.</li>
 * </ol>
 */
public final class LoginHold {
    private static final String TAG = CreateUniversal.MOD_ID + ":aboard";
    private static final long SETTLE_MS = 750;
    private static final long TIMEOUT_MS = 20_000;
    /** De pie, tras colocarle: tiempo que se le mantiene en su punto de la nave hasta que Sable lo coge con el suelo. */
    private static final long FOLLOW_MS = 1500;

    private static final class Hold {
        final UUID ship;
        final Vec3 local;
        final BlockPos seat;
        final Vec3 position;
        final long startMs = System.currentTimeMillis();
        long readySinceMs = -1;
        /** De pie: cuándo se le colocó; durante {@link #FOLLOW_MS} se le sigue llevando con la nave. */
        long placedMs = -1;

        Hold(UUID ship, Vec3 local, BlockPos seat, Vec3 position) {
            this.ship = ship;
            this.local = local;
            this.seat = seat;
            this.position = position;
        }
    }

    private static final Map<UUID, Hold> HOLDS = new HashMap<>();

    private LoginHold() {}

    public static boolean isHeld(ServerPlayer player) {
        return HOLDS.containsKey(player.getUUID());
    }

    // ---------------------------------------------------------------- al salir

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        CompoundTag data = player.getPersistentData();
        data.remove(TAG);
        if (HOLDS.remove(player.getUUID()) != null) {
            return;
        }
        ServerLevel level = player.serverLevel();
        CompoundTag tag = new CompoundTag();
        Entity vehicle = player.getVehicle();
        SubLevel seatShip = vehicle != null ? Sable.HELPER.getContaining(level, vehicle.blockPosition()) : null;
        if (seatShip != null) {
            tag.putUUID("ship", seatShip.getUniqueId());
            tag.putLong("seat", vehicle.blockPosition().asLong());
        } else {
            SubLevel ship = shipAround(level, player);
            if (ship == null) {
                return;
            }
            Vec3 local = ship.logicalPose().transformPositionInverse(player.position());
            tag.putUUID("ship", ship.getUniqueId());
            tag.putDouble("x", local.x);
            tag.putDouble("y", local.y);
            tag.putDouble("z", local.z);
        }
        data.put(TAG, tag);
        CreateUniversal.LOGGER.info("{} sale del juego a bordo de la nave {}{}", player.getScoreboardName(), tag.getUUID("ship"),
                tag.contains("seat") ? " (sentado)" : "");
    }

    /** La nave que pisa o en cuyo volumen está el jugador. */
    private static SubLevel shipAround(ServerLevel level, ServerPlayer player) {
        SubLevel tracking = Sable.HELPER.getTrackingSubLevel(player);
        if (tracking != null) {
            return tracking;
        }
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return null;
        }
        for (ServerSubLevel ship : container.getAllSubLevels()) {
            BoundingBox3dc b = ship.boundingBox();
            if (!ship.isRemoved() && new AABB(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()).inflate(1.0).contains(player.position())) {
                return ship;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- al entrar

    /** Al entrar, Minecraft lo vuelve a sentar: si el asiento está en una nave sin cargar, no (se le sienta luego). */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onMount(EntityMountEvent event) {
        if (!event.isMounting() || !(event.getEntityMounting() instanceof ServerPlayer player)
                || !(event.getLevel() instanceof ServerLevel level) || player.tickCount > 5 && !isHeld(player)) {
            return;
        }
        Entity vehicle = event.getEntityBeingMounted();
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (vehicle == null || container == null) {
            return;
        }
        BlockPos pos = vehicle.blockPosition();
        if (container.inBounds(pos) && container.getPlot(new ChunkPos(pos)) == null) {
            event.setCanceled(true);
            vehicle.discard();
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        CompoundTag data = player.getPersistentData();
        if (!data.contains(TAG)) {
            return;
        }
        CompoundTag tag = data.getCompound(TAG);
        data.remove(TAG);
        if (!tag.hasUUID("ship")) {
            return;
        }
        // Si aun así está en la zona interna (asiento ya puesto), se le saca a su sitio real primero.
        ServerLevel level = player.serverLevel();
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (player.isPassenger() && container != null && container.inBounds(player.blockPosition())
                && container.getPlot(new ChunkPos(player.blockPosition())) == null) {
            player.stopRiding();
        }
        Hold hold = new Hold(tag.getUUID("ship"),
                tag.contains("x") ? new Vec3(tag.getDouble("x"), tag.getDouble("y"), tag.getDouble("z")) : null,
                tag.contains("seat") ? BlockPos.of(tag.getLong("seat")) : null, player.position());
        HOLDS.put(player.getUUID(), hold);
        player.displayClientMessage(Component.translatable("create_universal.message.loading_ship").withStyle(ChatFormatting.GRAY), true);
        CreateUniversal.LOGGER.info("{} entra a bordo de la nave {}: esperando a que cargue", player.getScoreboardName(), hold.ship);
    }

    /** Mientras espera, nada le hace daño (ni el vacío: su sala de aire aún no ha cargado). */
    @SubscribeEvent
    public static void onDamage(LivingIncomingDamageEvent event) {
        if (event.getEntity() instanceof ServerPlayer p && isHeld(p)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (HOLDS.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<UUID, Hold>> it = HOLDS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Hold> e = it.next();
            Hold hold = e.getValue();
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(e.getKey());
            if (player == null) {
                it.remove();
                continue;
            }
            ServerLevel level = player.serverLevel();
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            SubLevel s = container != null ? container.getSubLevel(hold.ship) : null;
            ServerSubLevel ship = s instanceof ServerSubLevel sl && !sl.isRemoved() ? sl : null;
            if (hold.placedMs >= 0) {
                // Ya colocado de pie: se le lleva con la nave un momento más.
                if (ship == null || now - hold.placedMs > FOLLOW_MS || player.isPassenger()) {
                    it.remove();
                } else {
                    follow(player, level, ship, hold.local);
                }
                continue;
            }
            // Quieto en su sitio real (así se cargan los chunks de alrededor y con ellos la nave).
            if (!player.isPassenger()) {
                player.connection.teleport(hold.position.x, hold.position.y, hold.position.z, player.getYRot(), player.getXRot());
                player.setDeltaMovement(Vec3.ZERO);
                player.resetFallDistance();
            }
            if (ship != null && ship.getTrackingPlayers().contains(player.getUUID())) {
                if (hold.readySinceMs < 0) {
                    hold.readySinceMs = now;
                }
                if (now - hold.readySinceMs >= SETTLE_MS) {
                    place(player, level, ship, hold);
                    if (hold.local != null && !player.isPassenger()) {
                        hold.placedMs = now;
                    } else {
                        it.remove();
                    }
                }
            } else if (now - hold.startMs > TIMEOUT_MS) {
                player.displayClientMessage(Component.translatable("create_universal.message.ship_not_found").withStyle(ChatFormatting.GOLD), false);
                CreateUniversal.LOGGER.warn("La nave {} de {} no ha cargado en {} s: se queda donde está", hold.ship,
                        player.getScoreboardName(), TIMEOUT_MS / 1000);
                it.remove();
            }
        }
    }

    /** En su punto de la nave (donde esté ahora la nave) y con la velocidad de la nave en ese punto. */
    private static void follow(ServerPlayer player, ServerLevel level, ServerSubLevel ship, Vec3 local) {
        Vec3 pos = ship.logicalPose().transformPosition(local);
        player.connection.teleport(pos.x, pos.y, pos.z, player.getYRot(), player.getXRot());
        var velocity = Sable.HELPER.getVelocity(level, ship, new org.joml.Vector3d(local.x, local.y, local.z),
                new org.joml.Vector3d()).mul(0.05);
        player.setDeltaMovement(velocity.x, velocity.y, velocity.z);
        player.resetFallDistance();
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(player));
    }

    private static void place(ServerPlayer player, ServerLevel level, ServerSubLevel ship, Hold hold) {
        if (player.isPassenger() && player.getVehicle() != null
                && Sable.HELPER.getContaining(level, player.getVehicle().blockPosition()) == ship) {
            // Minecraft ya lo sentó bien (la nave cargó a tiempo).
            CreateUniversal.LOGGER.info("{} ya estaba sentado en su nave {}", player.getScoreboardName(), ship.getUniqueId());
            return;
        }
        if (hold.seat != null) {
            Vec3 seatWorld = ship.logicalPose().transformPosition(Vec3.atCenterOf(hold.seat).add(0.0, 0.5, 0.0));
            player.connection.teleport(seatWorld.x, seatWorld.y, seatWorld.z, player.getYRot(), player.getXRot());
            if (level.getBlockState(hold.seat).getBlock() instanceof com.simibubi.create.content.contraptions.actors.seat.SeatBlock
                    && !com.simibubi.create.content.contraptions.actors.seat.SeatBlock.isSeatOccupied(level, hold.seat)) {
                com.simibubi.create.content.contraptions.actors.seat.SeatBlock.sitDown(level, hold.seat, player);
            }
        } else if (hold.local != null) {
            follow(player, level, ship, hold.local);
        }
        player.displayClientMessage(Component.translatable("create_universal.message.ship_loaded").withStyle(ChatFormatting.AQUA), true);
        CreateUniversal.LOGGER.info("{} vuelve a su sitio en la nave {}{}", player.getScoreboardName(), ship.getUniqueId(),
                player.isPassenger() ? " (sentado)" : "");
    }
}
