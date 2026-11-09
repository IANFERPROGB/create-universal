package dev.createuniversal.client.sky;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import dev.createuniversal.client.compat.IrisCompat;
import dev.createuniversal.network.SkyShipsPayload;
import dev.createuniversal.space.SolarSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3d;

/**
 * El cielo real en la Tierra (Overworld): el Sol, la Luna con su fase y su tamaño real, los planetas y las estrellas del
 * catálogo de la NASA, todo en su sitio y en tiempo real, vistos desde el punto de la Tierra donde está el Overworld
 * (ecuador, longitud 0: así el día y la noche de Minecraft coinciden con el Sol real, y las estaciones mueven su camino
 * por el cielo). También las naves que estén en órbita cerca de la Tierra: con un catalejo se ve su forma.
 *
 * Sustituye al cielo de Minecraft ({@code LevelRendererEarthSkyMixin}); el color del cielo, los amaneceres y la niebla
 * siguen siendo los de Minecraft (y la niebla de Atmospherics si está).
 */
public final class EarthSky {
    /** Dónde está el Overworld en el globo. */
    private static final double LATITUDE = 0.0;
    private static final double LONGITUDE = 0.0;

    private EarthSky() {}

    public static boolean active() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && mc.level.dimension() == Level.OVERWORLD && PlanetRenderer.shader() != null
                && !IrisCompat.shaderPackActive();
    }

    /**
     * El cielo entero del Overworld: cúpula del color del cielo y abanico del amanecer como Minecraft, luego el cielo real
     * y el disco oscuro bajo el horizonte.
     */
    public static void renderSky(Matrix4f frustumMatrix, Matrix4f projection, float partialTick, VertexBuffer skyBuffer,
                                 VertexBuffer darkBuffer, Runnable skyFogSetup) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || skyBuffer == null) {
            return;
        }
        PoseStack pose = new PoseStack();
        pose.mulPose(frustumMatrix);
        Vec3 color = level.getSkyColor(mc.gameRenderer.getMainCamera().getPosition(), partialTick);
        FogRenderer.levelFogColor();
        RenderSystem.depthMask(false);
        RenderSystem.setShaderColor((float) color.x, (float) color.y, (float) color.z, 1.0F);
        ShaderInstance position = GameRenderer.getPositionShader();
        skyBuffer.bind();
        skyBuffer.drawWithShader(pose.last().pose(), projection, position);
        VertexBuffer.unbind();
        RenderSystem.enableBlend();
        drawSunriseFan(level, pose, partialTick);

        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        FogRenderer.setupNoFog();
        render(frustumMatrix, projection, partialTick);
        skyFogSetup.run();

        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.disableBlend();
        RenderSystem.defaultBlendFunc();
        double belowHorizon = mc.player.getEyePosition(partialTick).y - level.getLevelData().getHorizonHeight(level);
        if (belowHorizon < 0.0 && darkBuffer != null) {
            RenderSystem.setShaderColor(0.0F, 0.0F, 0.0F, 1.0F);
            pose.pushPose();
            pose.translate(0.0F, 12.0F, 0.0F);
            darkBuffer.bind();
            darkBuffer.drawWithShader(pose.last().pose(), projection, position);
            VertexBuffer.unbind();
            pose.popPose();
        }
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.depthMask(true);
    }

    /** El resplandor del amanecer y el atardecer, igual que en Minecraft. */
    private static void drawSunriseFan(ClientLevel level, PoseStack pose, float partialTick) {
        float[] c = level.effects().getSunriseColor(level.getTimeOfDay(partialTick), partialTick);
        if (c == null) {
            return;
        }
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        pose.pushPose();
        pose.mulPose(Axis.XP.rotationDegrees(90.0F));
        pose.mulPose(Axis.ZP.rotationDegrees(Mth.sin(level.getSunAngle(partialTick)) < 0.0F ? 180.0F : 0.0F));
        pose.mulPose(Axis.ZP.rotationDegrees(90.0F));
        Matrix4f m = pose.last().pose();
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);
        buf.addVertex(m, 0.0F, 100.0F, 0.0F).setColor(c[0], c[1], c[2], c[3]);
        for (int j = 0; j <= 16; j++) {
            float a = j * Mth.TWO_PI / 16.0F;
            float sin = Mth.sin(a);
            float cos = Mth.cos(a);
            buf.addVertex(m, sin * 120.0F, cos * 120.0F, -cos * 40.0F * c[3]).setColor(c[0], c[1], c[2], 0.0F);
        }
        BufferUploader.drawWithShader(buf.buildOrThrow());
        pose.popPose();
    }

    /** El Sol, la Luna, los planetas, las estrellas y las naves de verdad. */
    private static void render(Matrix4f frustumMatrix, Matrix4f projection, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return;
        }
        double time = SpaceClock.renderTime(partialTick);
        SolarSystem.Body earth = SolarSystem.get("earth");
        Vector3d[] axes = SolarSystem.horizonAxes(earth, time, LATITUDE, LONGITUDE);
        Vector3d e = axes[0];
        Vector3d u = axes[1];
        Vector3d s = axes[2];
        Vec3 up = new Vec3(u.x, u.y, u.z);
        Vec3 cam = SolarSystem.position(earth, time).add(up.scale(earth.radius() + 0.6));
        // De direcciones del espacio a este, arriba y sur de Minecraft.
        Matrix3f toLocal = new Matrix3f((float) e.x, (float) u.x, (float) s.x, (float) e.y, (float) u.y, (float) s.y,
                (float) e.z, (float) u.z, (float) s.z);
        Matrix4f view = new Matrix4f(frustumMatrix).mul(new Matrix4f().set(toLocal));

        float rain = level.getRainLevel(partialTick);
        float night = Mth.clamp(level.getStarBrightness(partialTick) * 2.0F, 0.0F, 1.0F) * (1.0F - rain);
        float seconds = (float) ((level.getGameTime() + partialTick) / 20.0 % 100000.0);

        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        PlanetRenderer.commonUniforms(seconds, false);
        SpaceSky.drawBackdrop(view, projection, night, false);
        if (rain < 0.95F) {
            PlanetRenderer.setGroundObserver("earth", up, 1.0F - night);
            try {
                PlanetRenderer.render(view, projection, cam, time, seconds, false);
                Vec3 sun = SolarSystem.position(SolarSystem.get("sun"), time);
                double age = SkyShips.age();
                for (SkyShipsPayload.Ship ship : SkyShips.current()) {
                    Vec3 pos = new Vec3(ship.x() + ship.vx() * age, ship.y() + ship.vy() * age, ship.z() + ship.vz() * age);
                    if (pos.subtract(cam).dot(up) > 0.0) {
                        PlanetRenderer.drawShip(view, projection, cam, pos, new Vec3(ship.hx(), ship.hy(), ship.hz()), sun);
                    }
                }
            } finally {
                PlanetRenderer.clearGroundObserver();
            }
        }
        RenderSystem.enableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
    }
}
