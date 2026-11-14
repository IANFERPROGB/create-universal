package dev.createuniversal.space;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Motor de crucero (alpha.12.4, D26, D95): mientras una nave lleva uno encendido y está lejos de los astros y de los
 * campos de rocas (la misma zona en la que ya no se cargan chunks, D94), sus propulsores de Create Propulsion empujan
 * {@link #FACTOR} veces más. Al acercarse a algo se apaga solo.
 */
public final class CruiseDrives {
    public static final double FACTOR = 4.0;
    /** Ticks que dura el impulso desde la última vez que el bloque dijo que sigue encendido. */
    private static final long HOLD = 20;

    private static final Map<UUID, Long> ACTIVE = new ConcurrentHashMap<>();

    private CruiseDrives() {}

    /** El bloque, encendido y lejos de todo, lo pide (cada pocos ticks). */
    public static void mark(SubLevel ship, long gameTime) {
        ACTIVE.put(ship.getUniqueId(), gameTime);
    }

    public static boolean boosting(SubLevel ship) {
        Long at = ACTIVE.get(ship.getUniqueId());
        return at != null && ship.getLevel().getGameTime() - at <= HOLD;
    }

    /** Multiplicador para el empuje de un propulsor que está en {@code pos} (1 si su nave no va en crucero). */
    public static double factor(Level level, BlockPos pos) {
        if (level == null || level.isClientSide || ACTIVE.isEmpty()) {
            return 1.0;
        }
        SubLevel ship = Sable.HELPER.getContaining(level, pos);
        return ship != null && boosting(ship) ? FACTOR : 1.0;
    }
}
