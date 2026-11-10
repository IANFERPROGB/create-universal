package dev.createuniversal.client.sky;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.createuniversal.space.SolarSystem;
import dev.createuniversal.space.surface.PlanetSurface;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3d;

/**
 * El cielo visto desde la superficie de la Luna o de Marte, desde el punto del globo donde se está (D74): el Sol, la
 * Tierra (en la Luna, quieta en el cielo y con sus fases), los demás astros, las estrellas y, en Marte, su atmósfera
 * calculada desde dentro (cielo color caramelo de día, atardecer azul).
 */
public final class SurfaceSky {
    private SurfaceSky() {}

    /** Ejes del horizonte (este, arriba, sur) en el espacio para la posición de la cámara en el mapa de la superficie. */
    static Vector3d[] axes(PlanetSurface surface, double time, double x, double z) {
        return SolarSystem.horizonAxes(surface.body(), time, surface.latitude(z), surface.longitude(x));
    }

    /** Cuánto de día es donde está la cámara: 0 de noche, 1 con el Sol alto. */
    public static float daylight(PlanetSurface surface, double time, double x, double z) {
        Vector3d up = axes(surface, time, x, z)[1];
        Vec3 sun = SolarSystem.position(SolarSystem.get("sun"), time).subtract(SolarSystem.position(surface.body(), time)).normalize();
        double altitude = sun.x * up.x + sun.y * up.y + sun.z * up.z;
        return (float) Mth.clamp((altitude + 0.06) / 0.2, 0.0, 1.0);
    }

    public static void paint(PlanetSurface surface, Matrix4f frustumMatrix, Matrix4f projection, Vec3 camPos, float partialTick,
                             boolean farDepth) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || PlanetRenderer.shader() == null) {
            return;
        }
        double time = SpaceClock.renderTime(partialTick);
        SolarSystem.Body body = surface.body();
        Vector3d[] axes = axes(surface, time, camPos.x, camPos.z);
        Vector3d e = axes[0];
        Vector3d u = axes[1];
        Vector3d s = axes[2];
        Vec3 up = new Vec3(u.x, u.y, u.z);
        Vec3 cam = SolarSystem.position(body, time).add(up.scale(body.radius() * 1.0002 + 0.3));
        Matrix3f toLocal = new Matrix3f((float) e.x, (float) u.x, (float) s.x, (float) e.y, (float) u.y, (float) s.y,
                (float) e.z, (float) u.z, (float) s.z);
        Matrix4f view = new Matrix4f(frustumMatrix).mul(new Matrix4f().set(toLocal));

        boolean airless = surface.airless();
        float day = daylight(surface, time, camPos.x, camPos.z);
        // Sin aire las estrellas se ven también de día (algo menos, deslumbra el suelo); con aire, solo de noche, y bajo
        // las nubes de Venus o la bruma de Titán casi nada.
        float stars = airless ? 1.0F - 0.35F * day : (1.0F - day) * (float) (1.0 - surface.haze());
        float seconds = (float) ((level.getGameTime() + partialTick) / 20.0 % 100000.0);

        PlanetRenderer.commonUniforms(seconds, farDepth);
        if (farDepth) {
            SpaceSky.paintBlack();
        }
        SpaceSky.drawBackdrop(view, projection, stars, farDepth);
        PlanetRenderer.setGroundObserver(body.id(), up, airless ? 0.0F : day, !airless);
        try {
            PlanetRenderer.render(view, projection, cam, time, seconds, farDepth);
            if (!airless) {
                PlanetRenderer.drawHomeAtmosphere(view, projection, body, cam, time, seconds);
            }
        } finally {
            PlanetRenderer.clearGroundObserver();
        }
        RenderSystem.disableBlend();
        RenderSystem.defaultBlendFunc();
    }
}
