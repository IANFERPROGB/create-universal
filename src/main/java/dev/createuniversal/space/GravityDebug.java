package dev.createuniversal.space;

import dev.createuniversal.CreateUniversal;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Diagnóstico temporal del Estabilizador de gravedad (alpha.9.3.2): el jugador sale disparado al encenderlo en el suelo de
 * una nave. Mientras un jugador tiene gravedad de estabilizador se escribe en el log, una vez por segundo, dónde está y
 * cómo se mueve; y en cuanto se mueve más de {@link #JUMP} bloques en un tick, todo el detalle (en el servidor y en el
 * cliente). Se quitará cuando se encuentre la causa.
 */
public final class GravityDebug {
    /** Movimiento respecto a la nave en un tick que cuenta como "salto" raro. */
    private static final double JUMP = 0.8;
    private static final Map<UUID, Vec3> LAST = new HashMap<>();
    private static Vec3 lastClient;

    /** Posición en las coordenadas de la nave de la gravedad (si la hay): así se mide el movimiento respecto a ella. */
    private static Vec3 local(Player p, Gravity.State state) {
        if (state.ship() == null) {
            return p.position();
        }
        SubLevel ship = OxygenZones.ship(p.level(), state.ship());
        if (ship == null) {
            return p.position();
        }
        return ship.logicalPose().transformPositionInverse(p.position());
    }

    private GravityDebug() {}

    static void serverTick(MinecraftServer server) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            Gravity.State state = Gravity.state(p);
            Vec3 last = LAST.get(p.getUUID());
            if (state == null) {
                if (last != null) {
                    CreateUniversal.LOGGER.info("[gravedad-debug] servidor: {} ya no tiene gravedad de estabilizador", p.getScoreboardName());
                    LAST.remove(p.getUUID());
                }
                continue;
            }
            LAST.put(p.getUUID(), local(p, state));
            if (last == null) {
                CreateUniversal.LOGGER.info("[gravedad-debug] servidor: {} entra en la gravedad de un estabilizador {}", p.getScoreboardName(), state);
            }
            double moved = last != null ? local(p, state).distanceTo(last) : 0.0;
            if (moved > JUMP || p.tickCount % 20 == 0) {
                CreateUniversal.LOGGER.info("[gravedad-debug] servidor{}: {}", moved > JUMP ? " SALTO" : "", describe(p, moved));
            }
        }
    }

    /** Lo llama el cliente en cada tick (ver {@code CreateUniversalClient}). */
    public static void clientTick(Player p) {
        Gravity.State state = Gravity.state(p);
        if (state == null) {
            if (lastClient != null) {
                CreateUniversal.LOGGER.info("[gravedad-debug] cliente: sin gravedad de estabilizador");
                lastClient = null;
            }
            return;
        }
        double moved = lastClient != null ? local(p, state).distanceTo(lastClient) : 0.0;
        if (lastClient == null) {
            CreateUniversal.LOGGER.info("[gravedad-debug] cliente: entra en la gravedad de un estabilizador {}", state);
        }
        lastClient = local(p, state);
        if (moved > JUMP || p.tickCount % 20 == 0) {
            CreateUniversal.LOGGER.info("[gravedad-debug] cliente{}: {}", moved > JUMP ? " SALTO" : "", describe(p, moved));
        }
    }

    private static String describe(Player p, double moved) {
        SubLevel tracking = Sable.HELPER.getTrackingSubLevel(p);
        SubLevel containing = Sable.HELPER.getContaining(p);
        Vec3 v = p.getDeltaMovement();
        return String.format(Locale.ROOT,
                "%s pos (%.2f, %.2f, %.2f) movido %.2f vel (%.3f, %.3f, %.3f) suelo=%s gravedad=%.4f dirGC=%s vuela=%s montado=%s nave-pisada=%s nave-dentro=%s",
                p.getScoreboardName(), p.getX(), p.getY(), p.getZ(), moved, v.x, v.y, v.z, p.onGround(), p.getGravity(),
                gravitychanger.api.GravityChangerAPI.getGravityDirection(p), p.getAbilities().flying,
                p.getVehicle() != null ? p.getVehicle().getType().toShortString() : "no",
                tracking != null ? tracking.getUniqueId() : "ninguna", containing != null ? containing.getUniqueId() : "ninguna");
    }
}
