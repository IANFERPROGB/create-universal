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
    /** Destino: "body:<id>" o "marker:<id>", o null. */
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

    static String formatTime(double seconds) {
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
