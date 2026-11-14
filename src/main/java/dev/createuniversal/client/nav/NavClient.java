package dev.createuniversal.client.nav;

import dev.createuniversal.client.sky.SpaceClock;
import dev.createuniversal.space.CUDimensions;
import dev.createuniversal.space.Navigation;
import dev.createuniversal.space.SolarSystem;
import dev.createuniversal.space.surface.PlanetSurface;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Lo que sabe el cliente para el mapa (alpha.11): astros descubiertos, marcadores, el destino elegido y la velocidad del
 * jugador en el espacio (medida de un tick a otro, así cuenta también la de la nave). Dibuja en pantalla el destino con la
 * distancia y el tiempo de llegada.
 */
public final class NavClient {
    private static final Set<String> DISCOVERED = new HashSet<>();
    private static final List<Navigation.Marker> MARKERS = new ArrayList<>();
    private static final List<dev.createuniversal.network.ParkedShipsPayload.Ship> SHIPS = new ArrayList<>();
    private static final List<XaeroPoint> XAERO = new ArrayList<>();
    /** Destino: "body:<id>", "marker:<id>", "debris:x,y,z", "ship:<uuid>" o "xaero:nombre@x,z", o null. */
    private static String destination;
    private static Vec3 lastPos;
    private static Vec3 velocity = Vec3.ZERO;

    private NavClient() {}

    public static void receive(List<String> discovered, List<Navigation.Marker> markers) {
        DISCOVERED.clear();
        DISCOVERED.addAll(discovered);
        MARKERS.clear();
        MARKERS.addAll(markers);
        if (destination != null && destination.startsWith("marker:") && marker(destination.substring(7)) == null) {
            destination = null;
        }
    }

    /** Un punto de ruta propio de Xaero en el espacio (alpha.11.3). */
    public record XaeroPoint(String name, double x, double y, double z, int color) {
        public String key() {
            return "xaero:" + name + "@" + Math.round(x) + "," + Math.round(z);
        }
    }

    public static void receiveShips(List<dev.createuniversal.network.ParkedShipsPayload.Ship> ships) {
        SHIPS.clear();
        SHIPS.addAll(ships);
        if (destination != null && destination.startsWith("ship:") && ship(destination.substring(5)) == null) {
            destination = null;
        }
    }

    public static List<dev.createuniversal.network.ParkedShipsPayload.Ship> ships() {
        return SHIPS;
    }

    /** Las aparcadas en el espacio (las que salen en el mapa, la mesa y el proyector). */
    public static List<dev.createuniversal.network.ParkedShipsPayload.Ship> spaceShips() {
        List<dev.createuniversal.network.ParkedShipsPayload.Ship> list = new ArrayList<>();
        for (dev.createuniversal.network.ParkedShipsPayload.Ship s : SHIPS) {
            if (s.dimension().equals(CUDimensions.SPACE.location())) {
                list.add(s);
            }
        }
        return list;
    }

    public static dev.createuniversal.network.ParkedShipsPayload.Ship ship(String id) {
        for (dev.createuniversal.network.ParkedShipsPayload.Ship s : SHIPS) {
            if (s.id().equals(id)) {
                return s;
            }
        }
        return null;
    }

    public static Component shipName(dev.createuniversal.network.ParkedShipsPayload.Ship s) {
        return s.name().isBlank() ? Component.translatable("create_universal.nav.ship") : Component.literal(s.name());
    }

    public static void receiveXaero(List<XaeroPoint> points) {
        XAERO.clear();
        XAERO.addAll(points);
    }

    public static List<XaeroPoint> xaeroPoints() {
        return XAERO;
    }

    private static XaeroPoint xaero(String key) {
        for (XaeroPoint p : XAERO) {
            if (p.key().equals(key)) {
                return p;
            }
        }
        return null;
    }

    public static boolean isDiscovered(String body) {
        return DISCOVERED.contains(body) || Navigation.isKnownFromStart(body);
    }

    public static List<Navigation.Marker> markers() {
        return MARKERS;
    }

    public static Navigation.Marker marker(String id) {
        for (Navigation.Marker m : MARKERS) {
            if (m.id().equals(id)) {
                return m;
            }
        }
        return null;
    }

    public static String destination() {
        return destination;
    }

    public static void setDestination(String target) {
        destination = target;
    }

    /** Velocidad del jugador (bloques/s) en coordenadas del espacio. */
    public static Vec3 velocity() {
        return velocity;
    }

    /**
     * Dónde está el jugador en el mapa (coordenadas del espacio): en el espacio, su posición; en la Tierra, la Tierra; en un
     * astro, ese astro.
     */
    public static Vec3 playerPosition(double time) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return SolarSystem.position(SolarSystem.get("earth"), time);
        }
        return positionIn(mc.level, mc.player.position(), time);
    }

    static Vec3 positionIn(Level level, Vec3 pos, double time) {
        if (level.dimension() == CUDimensions.SPACE) {
            return pos;
        }
        PlanetSurface surface = PlanetSurface.byDimension(level.dimension());
        String body = surface != null ? surface.bodyId() : "earth";
        return SolarSystem.position(SolarSystem.get(body), time);
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            lastPos = null;
            return;
        }
        Vec3 pos = mc.level.dimension() == CUDimensions.SPACE ? mc.player.position() : null;
        if (pos != null && lastPos != null) {
            Vec3 v = pos.subtract(lastPos).scale(20.0);
            // Suavizado: los tirones de un tick no cambian la estimación de llegada.
            velocity = velocity.scale(0.8).add(v.scale(0.2));
        } else if (pos == null) {
            velocity = Vec3.ZERO;
        }
        lastPos = pos;
    }

    /** Dónde está el destino ahora (coordenadas del espacio) y su radio (0 los marcadores); null si no hay. */
    static double[] target(String target, double time) {
        if (target == null) {
            return null;
        }
        if (target.startsWith("body:")) {
            SolarSystem.Body body = SolarSystem.get(target.substring(5));
            if (body == null) {
                return null;
            }
            Vec3 p = SolarSystem.position(body, time);
            Vec3 v = SolarSystem.velocity(body, time);
            return new double[] {p.x, p.y, p.z, body.radius(), v.x, v.y, v.z};
        }
        if (target.startsWith("marker:")) {
            Navigation.Marker m = marker(target.substring(7));
            return m == null ? null : new double[] {m.x(), m.y(), m.z(), 0, 0, 0, 0};
        }
        if (target.startsWith("debris:")) {
            dev.createuniversal.network.DebrisMarkersPayload.Marker d = dev.createuniversal.client.effects.DebrisMarkers.byKey(target);
            return d == null ? null : new double[] {d.x(), d.y(), d.z(), 0, 0, 0, 0};
        }
        if (target.startsWith("ship:")) {
            dev.createuniversal.network.ParkedShipsPayload.Ship s = ship(target.substring(5));
            return s == null ? null : new double[] {s.x(), s.y(), s.z(), 0, 0, 0, 0};
        }
        if (target.startsWith("xaero:")) {
            XaeroPoint p = xaero(target);
            return p == null ? null : new double[] {p.x(), p.y(), p.z(), 0, 0, 0, 0};
        }
        return null;
    }

    static Component targetName(String target) {
        if (target == null) {
            return Component.empty();
        }
        if (target.startsWith("body:")) {
            String id = target.substring(5);
            return isDiscovered(id) ? Component.translatable("create_universal.body." + id) : Component.literal("?");
        }
        if (target.startsWith("debris:")) {
            return Component.translatable("create_universal.nav.debris");
        }
        if (target.equals("explore")) {
            return Component.translatable("create_universal.assistant.explore_target");
        }
        if (target.startsWith("ship:")) {
            dev.createuniversal.network.ParkedShipsPayload.Ship s = ship(target.substring(5));
            return s != null ? shipName(s) : Component.literal("?");
        }
        if (target.startsWith("xaero:")) {
            XaeroPoint p = xaero(target);
            return Component.literal(p != null ? p.name() : "?");
        }
        Navigation.Marker m = marker(target.substring(7));
        return Component.literal(m != null ? m.name() : "?");
    }

    /** Distancia a la superficie (o al punto), velocidad de acercamiento (b/s) y segundos de llegada (−1 si no se acerca). */
    static double[] approach(Vec3 from, Vec3 fromVelocity, double[] t) {
        Vec3 to = new Vec3(t[0], t[1], t[2]);
        Vec3 d = to.subtract(from);
        double dist = Math.max(0.0, d.length() - t[3]);
        Vec3 dir = d.lengthSqr() > 1.0e-9 ? d.normalize() : Vec3.ZERO;
        double closing = fromVelocity.subtract(t[4], t[5], t[6]).dot(dir);
        double eta = closing > 0.5 ? dist / closing : -1.0;
        return new double[] {dist, closing, eta};
    }

    static String formatDistance(double blocks) {
        if (blocks >= 10_000) {
            return String.format(Locale.ROOT, "%.1f k", blocks / 1000.0);
        }
        return String.format(Locale.ROOT, "%d", Math.round(blocks));
    }

    /** Dónde está ahora un destino (x, y, z), o null. */
    public static net.minecraft.world.phys.Vec3 targetPosition(String target) {
        double[] t = target(target, SpaceClock.renderTime(0.0F));
        return t == null ? null : new net.minecraft.world.phys.Vec3(t[0], t[1], t[2]);
    }

    /** El nombre de un destino ("body:mars", "marker:..."), para otras pantallas. */
    public static Component targetNameOf(String target) {
        return targetName(target);
    }

    public static String formatTime(double seconds) {
        if (seconds < 0) {
            return "—";
        }
        long s = Math.round(seconds);
        if (s >= 3600) {
            return String.format(Locale.ROOT, "%d h %02d min", s / 3600, (s % 3600) / 60);
        }
        if (s >= 60) {
            return String.format(Locale.ROOT, "%d min %02d s", s / 60, s % 60);
        }
        return s + " s";
    }

    /** En pantalla: destino, distancia y llegada (arriba, en el centro). */
    public static void renderHud(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (destination == null || mc.level == null || mc.player == null || mc.options.hideGui) {
            return;
        }
        double time = SpaceClock.renderTime(delta.getGameTimeDeltaPartialTick(false));
        double[] t = target(destination, time);
        if (t == null) {
            return;
        }
        double[] a = approach(playerPosition(time), velocity, t);
        Component line = Component.translatable("create_universal.nav.hud", targetName(destination), formatDistance(a[0]),
                formatTime(a[2]));
        g.drawCenteredString(mc.font, line, g.guiWidth() / 2, 4, 0xFF8FD6FF);
    }
}
