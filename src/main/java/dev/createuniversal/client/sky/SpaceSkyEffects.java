package dev.createuniversal.client.sky;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.createuniversal.space.SpaceLayout;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Cielo de la dimensión espacio (alpha.1): fondo negro, estrellas siempre visibles, el Sol y la Tierra como esfera.
 * La esfera se dibuja "proyectada en el cielo": a una distancia fija, con el mismo tamaño angular que tendría de verdad,
 * así nunca la recorta la distancia de renderizado. En la alpha.4 se cambia por planetas con textura, atmósfera y nubes.
 */
public final class SpaceSkyEffects extends DimensionSpecialEffects {
    private static final ResourceLocation SUN_TEXTURE = ResourceLocation.withDefaultNamespace("textures/environment/sun.png");
    private static final float SKY_DISTANCE = 80.0F;
    private static final Vec3 SUN_POSITION = Vec3.ZERO;

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
        PoseStack pose = new PoseStack();
        pose.mulPose(modelViewMatrix);
        Vec3 cam = camera.getPosition();
        Vec3 sunDirFromEarth = SUN_POSITION.subtract(SpaceLayout.EARTH_CENTER).normalize();

        FogRenderer.setupNoFog();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);

        drawStars(pose, projectionMatrix);
        drawSun(pose, SUN_POSITION.subtract(cam).normalize());

        RenderSystem.defaultBlendFunc();
        drawPlanet(pose, cam, SpaceLayout.EARTH_CENTER, SpaceLayout.EARTH_RADIUS, sunDirFromEarth);

        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.disableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(true);
        setupFog.run();
        return true;
    }

    private void drawStars(PoseStack pose, Matrix4f projection) {
        if (stars == null) {
            stars = new VertexBuffer(VertexBuffer.Usage.STATIC);
            stars.bind();
            stars.upload(buildStars());
            VertexBuffer.unbind();
        }
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        stars.bind();
        stars.drawWithShader(pose.last().pose(), projection, GameRenderer.getPositionColorShader());
        VertexBuffer.unbind();
    }

    private static com.mojang.blaze3d.vertex.MeshData buildStars() {
        RandomSource random = RandomSource.create(20261108L);
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i < 4000; i++) {
            Vector3f dir = new Vector3f(random.nextFloat() * 2 - 1, random.nextFloat() * 2 - 1, random.nextFloat() * 2 - 1);
            float len2 = dir.lengthSquared();
            if (len2 < 0.01F || len2 > 1.0F) {
                continue;
            }
            dir.normalize(100.0F);
            float size = 0.08F + random.nextFloat() * random.nextFloat() * 0.35F;
            float bright = 0.35F + random.nextFloat() * 0.65F;
            float tint = random.nextFloat();
            float r = bright * (tint < 0.15F ? 0.8F : 1.0F);
            float g = bright * (tint > 0.85F ? 0.9F : 1.0F);
            float b = bright * (tint < 0.15F ? 1.0F : tint > 0.85F ? 0.75F : 1.0F);
            Quaternionf rot = new Quaternionf().rotateTo(new Vector3f(0, 0, -1), dir).rotateZ(random.nextFloat() * Mth.TWO_PI);
            buf.addVertex(new Vector3f(dir).add(new Vector3f(size, -size, 0).rotate(rot))).setColor(r, g, b, 1.0F);
            buf.addVertex(new Vector3f(dir).add(new Vector3f(size, size, 0).rotate(rot))).setColor(r, g, b, 1.0F);
            buf.addVertex(new Vector3f(dir).add(new Vector3f(-size, size, 0).rotate(rot))).setColor(r, g, b, 1.0F);
            buf.addVertex(new Vector3f(dir).add(new Vector3f(-size, -size, 0).rotate(rot))).setColor(r, g, b, 1.0F);
        }
        return buf.buildOrThrow();
    }

    private static void drawSun(PoseStack pose, Vec3 dir) {
        Vector3f d = new Vector3f((float) dir.x, (float) dir.y, (float) dir.z);
        Quaternionf rot = new Quaternionf().rotateTo(new Vector3f(0, 0, -1), d);
        Matrix4f m = pose.last().pose();
        float s = 9.0F;
        Vector3f c = new Vector3f(d).mul(100.0F);
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, SUN_TEXTURE);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        Vector3f p0 = new Vector3f(-s, -s, 0).rotate(rot).add(c);
        Vector3f p1 = new Vector3f(s, -s, 0).rotate(rot).add(c);
        Vector3f p2 = new Vector3f(s, s, 0).rotate(rot).add(c);
        Vector3f p3 = new Vector3f(-s, s, 0).rotate(rot).add(c);
        buf.addVertex(m, p0.x, p0.y, p0.z).setUv(0, 0);
        buf.addVertex(m, p1.x, p1.y, p1.z).setUv(1, 0);
        buf.addVertex(m, p2.x, p2.y, p2.z).setUv(1, 1);
        buf.addVertex(m, p3.x, p3.y, p3.z).setUv(0, 1);
        BufferUploader.drawWithShader(buf.buildOrThrow());
    }

    /** Esfera sombreada por el Sol, con un borde de atmósfera. Provisional hasta la alpha.4. */
    private static void drawPlanet(PoseStack pose, Vec3 cam, Vec3 center, double radius, Vec3 sunDir) {
        Vec3 toCenter = center.subtract(cam);
        double dist = toCenter.length();
        if (dist <= radius) {
            return;
        }
        float r = (float) (SKY_DISTANCE * radius / dist);
        Vec3 n = toCenter.scale(1.0 / dist);
        Vector3f c = new Vector3f((float) n.x, (float) n.y, (float) n.z).mul(SKY_DISTANCE);
        Vector3f sun = new Vector3f((float) sunDir.x, (float) sunDir.y, (float) sunDir.z);
        Matrix4f m = pose.last().pose();

        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableCull();
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        int lonSeg = 48;
        int latSeg = 24;
        for (int la = 0; la < latSeg; la++) {
            float t0 = Mth.PI * la / latSeg;
            float t1 = Mth.PI * (la + 1) / latSeg;
            for (int lo = 0; lo < lonSeg; lo++) {
                float p0 = Mth.TWO_PI * lo / lonSeg;
                float p1 = Mth.TWO_PI * (lo + 1) / lonSeg;
                vertex(buf, m, c, r, t0, p0, sun);
                vertex(buf, m, c, r, t1, p0, sun);
                vertex(buf, m, c, r, t1, p1, sun);
                vertex(buf, m, c, r, t0, p1, sun);
            }
        }
        BufferUploader.drawWithShader(buf.buildOrThrow());
        RenderSystem.disableCull();
    }

    private static void vertex(BufferBuilder buf, Matrix4f m, Vector3f c, float r, float theta, float phi, Vector3f sun) {
        Vector3f nrm = new Vector3f(Mth.sin(theta) * Mth.cos(phi), Mth.cos(theta), Mth.sin(theta) * Mth.sin(phi));
        float light = Math.max(0.06F, nrm.dot(sun));
        // Continentes falsos con ruido barato: verde-marrón sobre océano azul. Lo sustituye una textura real en la alpha.4.
        double noise = Math.sin(phi * 3.0 + Math.sin(theta * 5.0) * 2.0) * Math.cos(theta * 4.0 + phi * 2.0);
        boolean land = noise > 0.35 && Math.abs(Math.cos(theta)) < 0.85;
        boolean ice = Math.abs(Math.cos(theta)) > 0.9;
        float cr = ice ? 0.92F : land ? 0.30F : 0.08F;
        float cg = ice ? 0.95F : land ? 0.48F : 0.22F;
        float cb = ice ? 1.00F : land ? 0.20F : 0.55F;
        buf.addVertex(m, c.x + nrm.x * r, c.y + nrm.y * r, c.z + nrm.z * r).setColor(cr * light, cg * light, cb * light, 1.0F);
    }
}
