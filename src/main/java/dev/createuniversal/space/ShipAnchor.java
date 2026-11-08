package dev.createuniversal.space;

import dev.createuniversal.CreateUniversal;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Ancla a la nave, durante un momento después del cambio de dimensión, a los jugadores que viajan de pie.
 * Al llegar, la nave ya va a toda velocidad y el cliente todavía no la tiene cargada: sin ancla el jugador la atraviesa y
 * se queda atrás. Mientras dura el ancla, cada tick se coloca al jugador en su mismo punto de la nave, como si fuera un
 * bloque más; al soltarlo se le da la velocidad de la nave para que sigan juntos.
 */
public final class ShipAnchor {
    /** 1,5 segundos. */
    public static final int ANCHOR_TICKS = 30;

    private record Anchored(UUID player, UUID ship, ServerLevel level, Vec3 plotPos, int ticksLeft, Vec3 lastWorldPos) {}

    private static final List<Anchored> ANCHORED = new ArrayList<>();

    private ShipAnchor() {}

    /** @param plotPos posición del jugador en coordenadas del plot de la nave (ya desplazada si la nave cambió de plot) */
    public static void anchor(ServerPlayer player, SubLevel ship, ServerLevel level, Vec3 plotPos) {
        ANCHORED.removeIf(a -> a.player().equals(player.getUUID()));
        ANCHORED.add(new Anchored(player.getUUID(), ship.getUniqueId(), level, plotPos, ANCHOR_TICKS,
                ship.logicalPose().transformPosition(plotPos)));
    }

    public static void tick() {
        if (ANCHORED.isEmpty()) {
            return;
        }
        List<Anchored> next = new ArrayList<>();
        for (Anchored a : ANCHORED) {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(a.level());
            SubLevel ship = container != null ? container.getSubLevel(a.ship()) : null;
            if (!(a.level().getPlayerByUUID(a.player()) instanceof ServerPlayer player) || ship == null || ship.isRemoved()
                    || player.level() != a.level()) {
                continue;
            }
            Vec3 pos = ship.logicalPose().transformPosition(a.plotPos());
            Vec3 velocity = pos.subtract(a.lastWorldPos());
            player.connection.teleport(pos.x, pos.y, pos.z, player.getYRot(), player.getXRot());
            player.setDeltaMovement(velocity);
            player.resetFallDistance();
            if (a.ticksLeft() > 1) {
                next.add(new Anchored(a.player(), a.ship(), a.level(), a.plotPos(), a.ticksLeft() - 1, pos));
            } else {
                // Soltar con la velocidad de la nave para seguir juntos.
                player.connection.send(new ClientboundSetEntityMotionPacket(player));
                CreateUniversal.LOGGER.info("{} se suelta del ancla de la nave {} con velocidad {} b/tick",
                        player.getScoreboardName(), a.ship(), velocity);
            }
        }
        ANCHORED.clear();
        ANCHORED.addAll(next);
    }
}
