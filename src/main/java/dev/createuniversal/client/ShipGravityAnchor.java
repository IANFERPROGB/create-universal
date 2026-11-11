package dev.createuniversal.client;

import dev.createuniversal.CreateUniversal;
import dev.createuniversal.space.Gravity;
import dev.createuniversal.space.OxygenZones;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Ancla del jugador a su nave dentro de la gravedad de un Estabilizador de gravedad (alpha.9.7). A mucha velocidad, Sable
 * a veces no consigue llevar al jugador con la nave en un tick (al levantarse de un asiento, o incluso de pie) y lo deja
 * atravesar el suelo o quedarse atrás.
 *
 * Cada tick se guarda dónde están los ojos del jugador en las coordenadas de la nave. Si respecto a la nave se ha movido
 * más de lo que puede moverse una persona en un tick, o su camino atraviesa un bloque sólido de la nave, vuelve a su
 * última posición buena (llevada con la nave). Solo en el cliente, que es quien mueve al jugador.
 */
public final class ShipGravityAnchor {
    /** Lo más que se mueve una persona respecto a la nave en un tick (andar, saltar, caer dentro de una sala). */
    private static final double MAX_STEP = 2.0;

    private static UUID lastShip;
    private static net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> lastDimension;
    /** Ojos en coordenadas de la nave, el tick anterior. */
    private static Vec3 lastEye;
    /** El tick anterior iba sentado: al levantarse no se mira si atraviesa (el asiento está pegado al suelo). */
    private static boolean wasSeated;
    private static int corrections;

    private ShipGravityAnchor() {}

    public static void afterTick(LocalPlayer player) {
        Gravity.State state = Gravity.state(player);
        SubLevel ship = Gravity.frameShip(player);
        if (ship == null || player.isSpectator()) {
            lastShip = null;
            lastEye = null;
            return;
        }
        double eyes = player.getEyeHeight();
        Vec3 eyeWorld = player.position().add(0.0, eyes, 0.0);
        Vec3 eye = ship.logicalPose().transformPositionInverse(eyeWorld);
        boolean seated = player.isPassenger();
        if (seated || lastEye == null || !ship.getUniqueId().equals(lastShip) || player.level().dimension() != lastDimension) {
            lastDimension = player.level().dimension();
            remember(ship, eye, seated);
            return;
        }
        Direction up = state != null && state.ship() != null ? state.down().getOpposite() : Direction.UP;
        Vec3 upLocal = new Vec3(up.getStepX(), up.getStepY(), up.getStepZ());
        double step = eye.distanceTo(lastEye);
        boolean glitch = step > MAX_STEP || (!wasSeated && crossesSolid(player, lastEye, eye, upLocal, eyes));
        if (glitch) {
            // Vuelve a su sitio en la nave, con la nave donde está ahora.
            Vec3 back = ship.logicalPose().transformPosition(lastEye).subtract(0.0, eyes, 0.0);
            player.setPos(back.x, back.y, back.z);
            player.setDeltaMovement(Vec3.ZERO);
            player.resetFallDistance();
            if (++corrections % 20 == 1) {
                CreateUniversal.LOGGER.info("[gravedad-debug] ancla: {} vuelve a su sitio en la nave (se había movido {} respecto a ella)",
                        player.getScoreboardName(), String.format(java.util.Locale.ROOT, "%.2f", step));
            }
            remember(ship, lastEye, false);
            return;
        }
        remember(ship, eye, false);
    }

    private static void remember(SubLevel ship, Vec3 eye, boolean seated) {
        lastShip = ship.getUniqueId();
        lastEye = eye;
        wasSeated = seated;
    }

    /**
     * ¿Pasa el centro del cuerpo (o los ojos) por un bloque sólido de la nave entre un tick y otro? Coordenadas de la nave.
     * No los pies: al subir un escalón o una escalera rozarían el bloque sin atravesarlo.
     */
    private static boolean crossesSolid(LocalPlayer player, Vec3 fromEye, Vec3 toEye, Vec3 up, double eyes) {
        if (fromEye.distanceToSqr(toEye) < 1.0e-6) {
            return false;
        }
        Vec3 fromBody = fromEye.subtract(up.scale(eyes * 0.55));
        Vec3 toBody = toEye.subtract(up.scale(eyes * 0.55));
        return hits(player, fromBody, toBody) || hits(player, fromEye, toEye);
    }

    private static boolean hits(LocalPlayer player, Vec3 from, Vec3 to) {
        BlockHitResult hit = player.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK && !hit.isInside();
    }
}
