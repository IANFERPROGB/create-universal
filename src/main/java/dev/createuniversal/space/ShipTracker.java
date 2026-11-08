package dev.createuniversal.space;

import dev.createuniversal.CreateUniversal;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Ayuda a depurar las alphas: durante 30 segundos después de cada traslado escribe en el log, una vez por segundo,
 * dónde está la nave y a qué velocidad se mueve. Sirve para ver si cae, si va a la deriva o si se queda quieta.
 */
public final class ShipTracker {
    private static final int DURATION_TICKS = 30 * 20;

    private record Watched(UUID ship, ServerLevel level, int age, Vec3 lastCenter) {}

    private static final List<Watched> WATCHED = new ArrayList<>();

    private ShipTracker() {}

    public static void watch(SubLevel ship, ServerLevel level) {
        WATCHED.add(new Watched(ship.getUniqueId(), level, 0, center(ship)));
    }

    public static void tick() {
        if (WATCHED.isEmpty()) {
            return;
        }
        List<Watched> next = new ArrayList<>();
        for (Watched w : WATCHED) {
            int age = w.age() + 1;
            ServerSubLevelContainer container = SubLevelContainer.getContainer(w.level());
            SubLevel ship = container != null ? container.getSubLevel(w.ship()) : null;
            if (ship == null || ship.isRemoved()) {
                CreateUniversal.LOGGER.info("[seguimiento] nave {} ya no está en {} ({} s después del traslado)",
                        w.ship(), w.level().dimension().location(), age / 20);
                continue;
            }
            Vec3 now = center(ship);
            if (age % 20 == 0) {
                Vec3 speed = now.subtract(w.lastCenter());
                CreateUniversal.LOGGER.info("[seguimiento] nave {} en {} a {} s: centro ({}, {}, {}), se ha movido ({}, {}, {}) en el último segundo",
                        w.ship(), w.level().dimension().location(), age / 20,
                        fmt(now.x), fmt(now.y), fmt(now.z), fmt(speed.x), fmt(speed.y), fmt(speed.z));
                now = center(ship);
            } else {
                now = w.lastCenter();
            }
            if (age < DURATION_TICKS) {
                next.add(new Watched(w.ship(), w.level(), age, age % 20 == 0 ? center(ship) : now));
            }
        }
        WATCHED.clear();
        WATCHED.addAll(next);
    }

    private static Vec3 center(SubLevel ship) {
        BoundingBox3dc b = ship.boundingBox();
        return new Vec3((b.minX() + b.maxX()) * 0.5, (b.minY() + b.maxY()) * 0.5, (b.minZ() + b.maxZ()) * 0.5);
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }
}
