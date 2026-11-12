package dev.createuniversal.client.compat;

import dev.createuniversal.CreateUniversal;
import dev.createuniversal.client.effects.DebrisMarkers;
import dev.createuniversal.client.nav.NavClient;
import dev.createuniversal.network.DebrisMarkersPayload;
import dev.createuniversal.network.NavEditPayload;
import dev.createuniversal.network.ParkedShipsPayload;
import dev.createuniversal.space.CUDimensions;
import dev.createuniversal.space.Navigation;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Compatibilidad con el minimapa y el mapa del mundo de Xaero (alpha.11.3, idea 23).
 *
 * <ul>
 *   <li>Nuestros marcadores, los restos de naves y las naves aparcadas salen como puntos de ruta de Xaero (los "de otros
 *       mods" de su lista: no se guardan en sus archivos y se ven en el minimapa y en el mapa del mundo), en la dimensión
 *       que toca.</li>
 *   <li>Si se borra uno de ellos en Xaero, también se quita de nuestro mapa.</li>
 *   <li>Los puntos de ruta propios de Xaero en el espacio salen en nuestro mapa y se pueden elegir como destino.</li>
 * </ul>
 *
 * Todo lo de Xaero está en {@link Bridge}, que solo se carga si el minimapa está instalado. Si algo falla (otra versión de
 * Xaero), se apaga sin romper el juego.
 */
public final class XaeroCompat {
    private static final boolean LOADED = ModList.get().isLoaded("xaerominimap");
    private static boolean broken;
    private static int ticks;

    private XaeroCompat() {}

    public static void tick() {
        if (!LOADED || broken || ++ticks % 20 != 0) {
            return;
        }
        try {
            Bridge.sync();
        } catch (Throwable t) {
            broken = true;
            CreateUniversal.LOGGER.warn("Create Universal: no se pudo enlazar con los puntos de ruta de Xaero; se desactiva", t);
        }
    }

    /** Lo que se quiere tener en Xaero: id → datos. */
    private record Point(int x, int y, int z, String name, String initials, int color) {}

    private static final class Bridge {
        private static final ResourceLocation ORIGIN = CreateUniversal.id("navigation");
        private static xaero.hud.minimap.waypoint.thirdparty.ThirdPartyWaypointManager lastManager;
        private static String lastSignature = "";
        /** Ids que ya se avisó al servidor de que se borraron en Xaero. */
        private static final Set<String> REPORTED = new HashSet<>();

        static void sync() {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) {
                return;
            }
            xaero.hud.minimap.module.MinimapSession session = xaero.hud.minimap.BuiltInHudModules.MINIMAP.getCurrentSession();
            if (session == null) {
                return;
            }
            xaero.hud.minimap.world.MinimapWorld world = session.getWorldManager().getCurrentWorld();
            if (world == null || world.getContainer() == null) {
                return;
            }
            var manager = world.getContainer().getThirdPartyWaypointManager();
            if (manager == null) {
                return;
            }
            if (lastManager != null && lastManager != manager) {
                lastManager.clearOrigin(ORIGIN);
                lastSignature = "";
            }
            lastManager = manager;
            var ours = manager.get(ORIGIN);

            // 1) Lo que el jugador borró en Xaero se quita también de nuestro mapa.
            for (String id : new ArrayList<>(ours.getWaypoints().keySet())) {
                xaero.common.minimap.waypoints.Waypoint w = ours.get(id);
                if (w == null || !w.isThirdPartyDeleted() || !REPORTED.add(id)) {
                    continue;
                }
                if (id.startsWith("marker:")) {
                    PacketDistributor.sendToServer(new NavEditPayload(NavEditPayload.REMOVE, id.substring(7), "", 0, 0, 0));
                } else if (id.startsWith("debris:")) {
                    DebrisMarkersPayload.Marker d = DebrisMarkers.byKey(id);
                    if (d != null) {
                        PacketDistributor.sendToServer(new NavEditPayload(NavEditPayload.REMOVE_DEBRIS, "", "", d.x(), d.y(), d.z()));
                        DebrisMarkers.forget(d);
                    }
                } else if (id.startsWith("ship:")) {
                    PacketDistributor.sendToServer(new NavEditPayload(NavEditPayload.REMOVE_SHIP, id.substring(5), "", 0, 0, 0));
                }
            }

            // 2) Lo que debe estar en Xaero en esta dimensión.
            ResourceLocation dim = mc.level.dimension().location();
            boolean space = mc.level.dimension() == CUDimensions.SPACE;
            Map<String, Point> wanted = new LinkedHashMap<>();
            if (space) {
                for (Navigation.Marker m : NavClient.markers()) {
                    wanted.put("marker:" + m.id(), new Point((int) Math.floor(m.x()), (int) Math.floor(m.y()), (int) Math.floor(m.z()),
                            m.name(), initials(m.name(), "M"), xaero.hud.minimap.waypoint.WaypointColor.AQUA.ordinal()));
                }
            }
            long now = mc.level.getGameTime();
            String debrisName = Component.translatable("create_universal.nav.debris").getString();
            for (DebrisMarkersPayload.Marker d : DebrisMarkers.markers()) {
                if (d.dimension().equals(dim) && d.expires() > now) {
                    wanted.put(DebrisMarkers.key(d), new Point((int) Math.floor(d.x()), (int) Math.floor(d.y()), (int) Math.floor(d.z()),
                            debrisName, "X", xaero.hud.minimap.waypoint.WaypointColor.GOLD.ordinal()));
                }
            }
            for (ParkedShipsPayload.Ship s : NavClient.ships()) {
                if (s.dimension().equals(dim)) {
                    String name = NavClient.shipName(s).getString();
                    wanted.put("ship:" + s.id(), new Point((int) Math.floor(s.x()), (int) Math.floor(s.y()), (int) Math.floor(s.z()),
                            name, initials(s.name(), "N"), xaero.hud.minimap.waypoint.WaypointColor.GREEN.ordinal()));
                }
            }
            String signature = dim + "|" + wanted;
            if (!signature.equals(lastSignature)) {
                lastSignature = signature;
                ours.clear();
                for (Map.Entry<String, Point> e : wanted.entrySet()) {
                    Point p = e.getValue();
                    xaero.common.minimap.waypoints.Waypoint w = new xaero.common.minimap.waypoints.Waypoint(p.x(), p.y(), p.z(), p.name(),
                            p.initials(), xaero.hud.minimap.waypoint.WaypointColor.fromIndex(p.color()),
                            xaero.hud.minimap.waypoint.WaypointPurpose.NORMAL, false, true);
                    ours.add(e.getKey(), w);
                }
            }

            // 3) Los puntos de ruta propios de Xaero en el espacio, para nuestro mapa.
            if (space) {
                List<NavClient.XaeroPoint> mine = new ArrayList<>();
                for (xaero.hud.minimap.waypoint.set.WaypointSet set : world.getIterableWaypointSets()) {
                    for (xaero.common.minimap.waypoints.Waypoint w : set.getWaypoints()) {
                        if (Boolean.TRUE.equals(w.getDisabled()) || w.isTemporary()) {
                            continue;
                        }
                        mine.add(new NavClient.XaeroPoint(w.getName(), w.getX() + 0.5, w.getY(), w.getZ() + 0.5,
                                w.getWaypointColor().getHex() | 0xFF000000));
                    }
                }
                NavClient.receiveXaero(mine);
            }
        }

        private static String initials(String name, String fallback) {
            String t = name == null ? "" : name.trim();
            return t.isEmpty() ? fallback : t.substring(0, Math.min(t.length(), 1)).toUpperCase(Locale.ROOT);
        }
    }
}
