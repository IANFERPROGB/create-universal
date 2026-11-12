package dev.createuniversal.client.effects;

import dev.createuniversal.network.DebrisMarkersPayload;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Punto de ruta de los restos de una nave destruida (D45): un rombo naranja con la distancia y el tiempo que les queda,
 * en su sitio de la pantalla, o en el borde si quedan detrás.
 */
public final class DebrisMarkers {
    private static final int COLOR = 0xFFFF8A3D;
    private static List<DebrisMarkersPayload.Marker> markers = new ArrayList<>();

    private DebrisMarkers() {}

    public static List<DebrisMarkersPayload.Marker> markers() {
        return markers;
    }

    public static void receive(List<DebrisMarkersPayload.Marker> list) {
        markers = new ArrayList<>(list);
    }

    public static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (markers.isEmpty() || mc.level == null || mc.player == null || mc.options.hideGui) {
            return;
        }
        long now = mc.level.getGameTime();
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 eye = camera.getPosition();
        Vector3f look = camera.getLookVector();
        Vector3f up = camera.getUpVector();
        Vector3f left = camera.getLeftVector();
        int w = g.guiWidth();
        int h = g.guiHeight();
        double focal = (h * 0.5) / Math.tan(Math.toRadians(mc.options.fov().get()) * 0.5);
        Font font = mc.font;
        for (DebrisMarkersPayload.Marker m : markers) {
            if (m.expires() <= now || !m.dimension().equals(mc.level.dimension().location())) {
                continue;
            }
            Vec3 rel = new Vec3(m.x(), m.y(), m.z()).subtract(eye);
            double fwd = rel.x * look.x() + rel.y * look.y() + rel.z * look.z();
            double sx = rel.x * left.x() + rel.y * left.y() + rel.z * left.z();
            double sy = rel.x * up.x() + rel.y * up.y() + rel.z * up.z();
            double px;
            double py;
            if (fwd > 0.1) {
                px = w * 0.5 - sx / fwd * focal;
                py = h * 0.5 - sy / fwd * focal;
            } else {
                // Detrás: en el borde, por el lado en que queda.
                double len = Math.max(Math.hypot(sx, sy), 1.0e-6);
                px = w * 0.5 - sx / len * w;
                py = h * 0.5 - sy / len * h;
            }
            px = Math.max(12, Math.min(w - 12, px));
            py = Math.max(12, Math.min(h - 24, py));
            int x = (int) px;
            int y = (int) py;
            g.drawCenteredString(font, "◆", x, y - 4, COLOR);
            long minutes = Math.max(1, (m.expires() - now) / 1200);
            Component label = Component.translatable("create_universal.hud.debris", Math.round(rel.length()), minutes);
            g.drawCenteredString(font, label, x, y + 6, COLOR);
        }
    }
}
