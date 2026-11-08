package dev.createuniversal.client.sky;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Cielo de la dimensión espacio: fondo negro, estrellas (con la banda de la Vía Láctea) y el sistema solar
 * ({@link PlanetRenderer}). Las estrellas están en el infinito: solo dependen de hacia dónde mira la cámara.
 */
public final class SpaceSkyEffects extends DimensionSpecialEffects {
    private static final int STAR_COUNT = 7000;
    /** Fracción de estrellas que se agrupan en la banda de la Vía Láctea. */
    private static final float MILKY_WAY_SHARE = 0.45F;

    private VertexBuffer stars;

    public SpaceSkyEffects() {
        super(Float.NaN, false, SkyType.NONE, false, false);
    }

    @Override
    public Vec3 getBrightnessDependentFogColor(Vec3 fogColor, float brightness) {
        return Vec3.ZERO;
    }

    @Override
    public boolean isFoggyAt(int x, int y) {
        return false;
    }

    @Override
    public boolean renderClouds(ClientLevel level, int ticks, float partialTick, PoseStack poseStack, double camX, double camY, double camZ,
                                Matrix4f modelViewMatrix, Matrix4f projectionMatrix) {
        return true;
    }

    @Override
    public boolean renderSnowAndRain(ClientLevel level, int ticks, float partialTick, net.minecraft.client.renderer.LightTexture lightTexture,
                                     double camX, double camY, double camZ) {
        return true;
    }

    @Override
    public boolean renderSky(ClientLevel level, int ticks, float partialTick, Matrix4f modelViewMatrix, Camera camera,
                             Matrix4f projectionMatrix, boolean isFoggy, Runnable setupFog) {
        FogRenderer.setupNoFog();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        drawStars(modelViewMatrix, projectionMatrix);

        PlanetRenderer.render(modelViewMatrix, projectionMatrix, camera.getPosition(), SpaceClock.renderTime(partialTick));

        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.disableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(true);
        setupFog.run();
        return true;
    }

    private void drawStars(Matrix4f view, Matrix4f projection) {
        if (stars == null) {
            stars = new VertexBuffer(VertexBuffer.Usage.STATIC);
            stars.bind();
            stars.upload(buildStars());
            VertexBuffer.unbind();
        }
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        stars.bind();
        stars.drawWithShader(view, projection, GameRenderer.getPositionColorShader());
        VertexBuffer.unbind();
    }

    private static MeshData buildStars() {
        RandomSource random = RandomSource.create(20261108L);
        // Plano de la Vía Láctea: inclinado ~60° respecto a la eclíptica, como el real.
        Vector3f galacticNormal = new Vector3f(0.0F, 1.0F, 0.0F).rotateX(Mth.DEG_TO_RAD * 60.2F).rotateY(Mth.DEG_TO_RAD * 25.0F);
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        int made = 0;
        while (made < STAR_COUNT) {
            Vector3f dir = new Vector3f(random.nextFloat() * 2 - 1, random.nextFloat() * 2 - 1, random.nextFloat() * 2 - 1);
            float len2 = dir.lengthSquared();
            if (len2 < 0.01F || len2 > 1.0F) {
                continue;
            }
            dir.normalize();
            boolean band = random.nextFloat() < MILKY_WAY_SHARE;
            if (band) {
                // Se aplasta hacia el plano galáctico con una caída suave.
                float h = dir.dot(galacticNormal);
                float squeeze = 0.12F + 0.1F * random.nextFloat();
                dir.sub(new Vector3f(galacticNormal).mul(h * (1.0F - squeeze))).normalize();
            }
            made++;
            dir.mul(100.0F);
            // Muchas estrellas débiles y pocas brillantes.
            float m = random.nextFloat();
            float bright = band ? 0.18F + 0.35F * m * m : 0.25F + 0.75F * m * m * m;
            float size = 0.07F + 0.28F * m * m * m + (band ? 0.0F : 0.03F);
            // Color por temperatura: azuladas, blancas, amarillas y rojizas.
            float t = random.nextFloat();
            float r = t < 0.12F ? 0.72F : t > 0.88F ? 1.0F : t > 0.7F ? 1.0F : 0.95F;
            float g = t < 0.12F ? 0.82F : t > 0.88F ? 0.72F : t > 0.7F ? 0.93F : 0.97F;
            float b = t < 0.12F ? 1.0F : t > 0.88F ? 0.55F : t > 0.7F ? 0.78F : 1.0F;
            r *= bright;
            g *= bright;
            b *= bright;
            Quaternionf rot = new Quaternionf().rotateTo(new Vector3f(0, 0, -1), new Vector3f(dir).normalize()).rotateZ(random.nextFloat() * Mth.TWO_PI);
            buf.addVertex(new Vector3f(dir).add(new Vector3f(size, -size, 0).rotate(rot))).setColor(r, g, b, 1.0F);
            buf.addVertex(new Vector3f(dir).add(new Vector3f(size, size, 0).rotate(rot))).setColor(r, g, b, 1.0F);
            buf.addVertex(new Vector3f(dir).add(new Vector3f(-size, size, 0).rotate(rot))).setColor(r, g, b, 1.0F);
            buf.addVertex(new Vector3f(dir).add(new Vector3f(-size, -size, 0).rotate(rot))).setColor(r, g, b, 1.0F);
        }
        return buf.buildOrThrow();
    }
}
