package dev.createuniversal.client;

import dev.createuniversal.CreateUniversal;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Al entrar en el mundo de pie en una nave que va deprisa (alpha.9.10): el servidor coloca al jugador en su sitio, pero en
 * el espacio no hay gravedad que lo apriete contra el suelo y Sable solo lo lleva con la nave cuando nota que la pisa. A
 * 100 bloques/s la nave se escapaba antes. Aquí, en el propio cliente (que es quien mueve al jugador), se le lleva pegado
 * a su punto de la nave cada tick, con la velocidad de la nave, hasta que Sable lo tiene cogido medio segundo seguido
 * (mínimo {@link #MIN_TICKS}, máximo {@link #MAX_TICKS}).
 */
public final class ShipCarry {
    private static final int MIN_TICKS = 20;
    private static final int MAX_TICKS = 100;
    private static final int TRACKED_TICKS = 10;

    private static UUID ship;
    private static Vec3 local;
    private static Vec3 lastWorld;
    private static int ticks;
    private static int tracked;

    private ShipCarry() {}

    public static void start(UUID shipId, Vec3 localPos) {
        ship = shipId;
        local = localPos;
        lastWorld = null;
        ticks = 0;
        tracked = 0;
    }

    public static void afterTick(LocalPlayer player) {
        if (ship == null) {
            return;
        }
        var container = SubLevelContainer.getContainer(player.level());
        SubLevel sub = container != null ? container.getSubLevel(ship) : null;
        if (sub == null || sub.isRemoved() || player.isPassenger()) {
            if (++ticks > MAX_TICKS || player.isPassenger()) {
                ship = null;
            }
            return;
        }
        ticks++;
        SubLevel standing = Sable.HELPER.getTrackingSubLevel(player);
        tracked = standing != null && standing.getUniqueId().equals(ship) ? tracked + 1 : 0;
        if ((ticks >= MIN_TICKS && tracked >= TRACKED_TICKS) || ticks > MAX_TICKS) {
            CreateUniversal.LOGGER.info("[nave] {} ya va con su nave ({} ticks, cogido por Sable: {})", player.getScoreboardName(),
                    ticks, tracked >= TRACKED_TICKS ? "sí" : "no");
            ship = null;
            return;
        }
        Vec3 world = sub.logicalPose().transformPosition(local);
        Vec3 velocity = lastWorld != null ? world.subtract(lastWorld) : Vec3.ZERO;
        lastWorld = world;
        // Un poco hacia el suelo de la nave: sin gravedad nada le aprieta, y Sable solo lo coge al notar que lo pisa.
        Vec3 down = sub.logicalPose().transformNormal(new Vec3(0.0, -1.0, 0.0)).scale(0.08);
        player.setPos(world.x, world.y, world.z);
        player.setDeltaMovement(velocity.add(down));
        player.resetFallDistance();
    }
}
