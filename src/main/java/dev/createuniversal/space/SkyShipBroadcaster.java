package dev.createuniversal.space;

import dev.createuniversal.network.SkyShipsPayload;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cada 5 ticks manda a los jugadores del Overworld las naves del espacio que están cerca de la Tierra, para verlas pasar
 * por el cielo. Solo las que están a menos de {@link #RANGE} bloques del centro de la Tierra (órbitas bajas y algo más).
 */
public final class SkyShipBroadcaster {
    private static final double RANGE = 8000.0;
    private static final int MAX_SHIPS = 64;
    private static final int PERIOD_TICKS = 5;

    private static final Map<UUID, Vec3> LAST = new HashMap<>();
    private static int counter;
    private static boolean sentEmpty = true;

    private SkyShipBroadcaster() {}

    public static void tick(MinecraftServer server) {
        if (++counter % PERIOD_TICKS != 0) {
            return;
        }
        List<ServerPlayer> watchers = server.overworld().players();
        ServerLevel space = server.getLevel(CUDimensions.SPACE);
        if (space == null || watchers.isEmpty()) {
            LAST.clear();
            return;
        }
        ServerSubLevelContainer container = SubLevelContainer.getContainer(space);
        Vec3 earth = SpaceLayout.earthCenter(SpaceLayout.time(space));
        List<SkyShipsPayload.Ship> ships = new ArrayList<>();
        Map<UUID, Vec3> now = new HashMap<>();
        if (container != null) {
            for (ServerSubLevel ship : container.getAllSubLevels()) {
                if (ship.isRemoved() || ships.size() >= MAX_SHIPS) {
                    continue;
                }
                BoundingBox3dc b = ship.boundingBox();
                Vec3 c = new Vec3((b.minX() + b.maxX()) * 0.5, (b.minY() + b.maxY()) * 0.5, (b.minZ() + b.maxZ()) * 0.5);
                if (c.distanceTo(earth) > RANGE) {
                    continue;
                }
                now.put(ship.getUniqueId(), c);
                Vec3 prev = LAST.get(ship.getUniqueId());
                Vec3 v = prev == null ? Vec3.ZERO : c.subtract(prev).scale(20.0 / PERIOD_TICKS);
                ships.add(new SkyShipsPayload.Ship(c.x, c.y, c.z, (float) v.x, (float) v.y, (float) v.z,
                        (float) ((b.maxX() - b.minX()) * 0.5), (float) ((b.maxY() - b.minY()) * 0.5), (float) ((b.maxZ() - b.minZ()) * 0.5)));
            }
        }
        LAST.clear();
        LAST.putAll(now);
        if (ships.isEmpty() && sentEmpty) {
            return;
        }
        sentEmpty = ships.isEmpty();
        SkyShipsPayload payload = new SkyShipsPayload(ships);
        for (ServerPlayer p : watchers) {
            PacketDistributor.sendToPlayer(p, payload);
        }
    }
}
