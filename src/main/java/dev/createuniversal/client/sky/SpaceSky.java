package dev.createuniversal.client.sky;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.createuniversal.CreateUniversal;
import dev.createuniversal.client.compat.IrisCompat;
import dev.createuniversal.space.CUDimensions;
import dev.createuniversal.space.SolarSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Todo el cielo del espacio: la Vía Láctea y las estrellas del catálogo de la NASA (Deep Star Maps 2020, en su posición
 * real), el sistema solar ({@link PlanetRenderer}) y el destello de lente del Sol.
 *
 * <p>Sin shaders se dibuja en el paso del cielo de Minecraft. Con un paquete de shaders de Iris se dibuja después de que
 * Iris termine el mundo, en el plano lejano y con prueba de profundidad: solo aparece donde no hay nada delante.</p>
 */
public final class SpaceSky {
    private static final float STAR_DISTANCE = 100.0F;
    private static final float MILKY_WAY_BRIGHTNESS = 0.55F;

    private static ShaderInstance starShader;
    private static VertexBuffer stars;
    private static boolean starsFailed;
    private static int depthCopyFramebuffer = -1;

    // Lo que se usó en el paso del cielo, para repetirlo después de Iris.
    private static final Matrix4f lastView = new Matrix4f();
    private static final Matrix4f lastProjection = new Matrix4f();
    private static float lastPartialTick;
    private static boolean pendingAfterIris;
    private static SkyPainter pendingPainter;

    /** Algo que dibuja un cielo completo; con {@code farDepth} va después de Iris, en el plano lejano. */
    public interface SkyPainter {
        void paint(Matrix4f view, Matrix4f projection, Vec3 cam, float partialTick, boolean farDepth);
    }

    private SpaceSky() {}

    public static void setStarShader(ShaderInstance shader) {
        starShader = shader;
    }

    /** Desde el paso del cielo de Minecraft, en la dimensión espacio. */
    public static void renderSkyPass(Matrix4f view, Matrix4f projection, Camera camera, float partialTick) {
        renderSkyPass(view, projection, camera, partialTick, SpaceSky::render);
    }

    /** Dibuja el cielo ahora o, con shaders de Iris, lo deja para después de que Iris termine el mundo. */
    public static void renderSkyPass(Matrix4f view, Matrix4f projection, Camera camera, float partialTick, SkyPainter painter) {
        if (IrisCompat.shaderPackActive()) {
            lastView.set(view);
            lastProjection.set(projection);
            lastPartialTick = partialTick;
            pendingPainter = painter;
            pendingAfterIris = true;
            return;
        }
        pendingAfterIris = false;
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        painter.paint(view, projection, camera.getPosition(), partialTick, false);
        RenderSystem.enableDepthTest();
    }

    /** Desde {@code GameRendererSpaceSkyMixin}, justo después de que el mundo (e Iris) terminen de dibujarse. */
    public static void renderAfterLevel() {
        Minecraft mc = Minecraft.getInstance();
        if (!pendingAfterIris || mc.level == null || pendingPainter == null) {
            pendingAfterIris = false;
            return;
        }
        pendingAfterIris = false;
        RenderTarget main = mc.getMainRenderTarget();
        main.bindWrite(true);
        copyIrisDepth(main);
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(false);
        pendingPainter.paint(lastView, lastProjection, mc.gameRenderer.getMainCamera().getPosition(), lastPartialTick, true);
        RenderSystem.depthMask(true);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }

    /** Pasa la profundidad sin translúcidos de Iris al búfer principal: los planetas se ven a través de los cristales. */
    private static void copyIrisDepth(RenderTarget main) {
        int depth = IrisCompat.depthWithoutTranslucents();
        if (depth < 0) {
            return;
        }
        if (depthCopyFramebuffer < 0) {
            depthCopyFramebuffer = GlStateManager.glGenFramebuffers();
        }
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, depthCopyFramebuffer);
        GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, depth, 0);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, main.frameBufferId);
        GL30.glBlitFramebuffer(0, 0, main.width, main.height, 0, 0, main.width, main.height, GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
        main.bindWrite(true);
    }

    static void render(Matrix4f view, Matrix4f projection, Vec3 cam, float partialTick, boolean farDepth) {
        ShaderInstance shader = PlanetRenderer.shader();
        if (shader == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        double time = SpaceClock.renderTime(partialTick);
        float seconds = (float) (((mc.level != null ? mc.level.getGameTime() : 0) + partialTick) / 20.0 % 100000.0);
        PlanetRenderer.commonUniforms(seconds, farDepth);

        if (farDepth) {
            paintBlack();
        }

        drawBackdrop(view, projection, 1.0F, farDepth);

        PlanetRenderer.render(view, projection, cam, time, seconds, farDepth);

        drawLensFlare(view, projection, cam, time);
        RenderSystem.disableBlend();
        RenderSystem.defaultBlendFunc();
    }

    /** Con shaders: el cielo que había pintado el paquete se tapa de negro. */
    static void paintBlack() {
        RenderSystem.disableBlend();
        PlanetRenderer.set(PlanetRenderer.MODE_BLACK, 0, 0, 0, new Vector3f(0, 1, 0), new Vector3f());
        PlanetRenderer.draw(PlanetRenderer.quad(), new Matrix4f(), new Matrix4f());
    }

    /** Vía Láctea y estrellas, con un brillo de 0 (de día no se ven) a 1. */
    static void drawBackdrop(Matrix4f view, Matrix4f projection, float brightness, boolean farDepth) {
        ShaderInstance shader = PlanetRenderer.shader();
        if (shader == null || brightness <= 0.001F) {
            return;
        }
        // Vía Láctea: una esfera alrededor de la cámara, vista desde dentro.
        PlanetRenderer.additive();
        RenderSystem.disableCull();
        PlanetRenderer.bind(0, CreateUniversal.id("textures/sky/milkyway.png"), true);
        shader.safeGetUniform("Tint").set(1.0F, 1.0F, 1.0F, MILKY_WAY_BRIGHTNESS * brightness);
        PlanetRenderer.set(PlanetRenderer.MODE_MILKY_WAY, 0, 0, 0, new Vector3f(0, 1, 0), new Vector3f());
        PlanetRenderer.draw(PlanetRenderer.sphere(), new Matrix4f(view).scale(STAR_DISTANCE * 0.9F), projection);
        shader.safeGetUniform("Tint").set(1.0F, 1.0F, 1.0F, 1.0F);
        drawStars(view, projection, farDepth, brightness);
    }

    // ---------------------------------------------------------------- estrellas

    private static void drawStars(Matrix4f view, Matrix4f projection, boolean farDepth, float brightness) {
        if (starShader == null || starsFailed) {
            return;
        }
        if (stars == null) {
            MeshData mesh = buildStars();
            if (mesh == null) {
                starsFailed = true;
                return;
            }
            stars = new VertexBuffer(VertexBuffer.Usage.STATIC);
            stars.bind();
            stars.upload(mesh);
            VertexBuffer.unbind();
        }
        PlanetRenderer.additive();
        starShader.safeGetUniform("FarDepth").set(farDepth ? 1.0F : 0.0F);
        starShader.safeGetUniform("Brightness").set(brightness);
        stars.bind();
        stars.drawWithShader(view, projection, starShader);
        VertexBuffer.unbind();
    }

    /**
     * Estrellas de {@code textures/sky/stars.bin} (generado con {@code tools/TexturePipeline.java} a partir de las Deep Star
     * Maps 2020 de la NASA): ascensión recta y declinación, color y brillo. Se pasan de coordenadas ecuatoriales a las de
     * este mundo (+Y = norte de la eclíptica, +X = punto vernal).
     */
    private static MeshData buildStars() {
        try (InputStream raw = Minecraft.getInstance().getResourceManager().open(CreateUniversal.id("textures/sky/stars.bin"));
             DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(raw))) {
            int count = in.readInt();
            float[] data = new float[count * 6];
            float maxFlux = 0.0F;
            float minFlux = Float.MAX_VALUE;
            for (int i = 0; i < count; i++) {
                for (int k = 0; k < 6; k++) {
                    data[i * 6 + k] = in.readFloat();
                }
                maxFlux = Math.max(maxFlux, data[i * 6 + 5]);
                minFlux = Math.min(minFlux, data[i * 6 + 5]);
            }
            double eps = Math.toRadians(23.4393);
            double range = Math.log(maxFlux / Math.max(1e-6F, minFlux));
            BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            for (int i = 0; i < count; i++) {
                double ra = data[i * 6];
                double dec = data[i * 6 + 1];
                double xq = Math.cos(dec) * Math.cos(ra), yq = Math.cos(dec) * Math.sin(ra), zq = Math.sin(dec);
                double ye = yq * Math.cos(eps) + zq * Math.sin(eps);
                double ze = -yq * Math.sin(eps) + zq * Math.cos(eps);
                Vector3f dir = new Vector3f((float) xq, (float) ze, (float) -ye).normalize();
                float m = (float) (Math.log(data[i * 6 + 5] / minFlux) / range); // 0 = la más débil, 1 = la más brillante
                float bright = 0.2F + 0.8F * (float) Math.pow(m, 1.3);
                float size = (0.11F + 0.42F * m * m) * STAR_DISTANCE / 100.0F;
                float r = data[i * 6 + 2] * bright, g = data[i * 6 + 3] * bright, b = data[i * 6 + 4] * bright;
                Vector3f c = new Vector3f(dir).mul(STAR_DISTANCE);
                Quaternionf rot = new Quaternionf().rotateTo(new Vector3f(0, 0, -1), dir);
                Vector3f p0 = new Vector3f(-size, -size, 0).rotate(rot).add(c);
                Vector3f p1 = new Vector3f(size, -size, 0).rotate(rot).add(c);
                Vector3f p2 = new Vector3f(size, size, 0).rotate(rot).add(c);
                Vector3f p3 = new Vector3f(-size, size, 0).rotate(rot).add(c);
                buf.addVertex(p0.x, p0.y, p0.z).setUv(0, 0).setColor(r, g, b, 1.0F);
                buf.addVertex(p1.x, p1.y, p1.z).setUv(1, 0).setColor(r, g, b, 1.0F);
                buf.addVertex(p2.x, p2.y, p2.z).setUv(1, 1).setColor(r, g, b, 1.0F);
                buf.addVertex(p3.x, p3.y, p3.z).setUv(0, 1).setColor(r, g, b, 1.0F);
            }
            CreateUniversal.LOGGER.info("Cielo: {} estrellas del catálogo", count);
            return buf.buildOrThrow();
        } catch (IOException | RuntimeException e) {
            CreateUniversal.LOGGER.error("No se pudieron cargar las estrellas", e);
            return null;
        }
    }

    // ---------------------------------------------------------------- destello de lente

    /** Destellos en la línea entre el Sol y el centro de la pantalla, si el Sol se ve y no lo tapa ningún astro. */
    private static void drawLensFlare(Matrix4f view, Matrix4f projection, Vec3 cam, double time) {
        SolarSystem.Body sun = SolarSystem.get("sun");
        Vec3 sunPos = SolarSystem.position(sun, time);
        Vec3 toSun = sunPos.subtract(cam);
        double sunDist = toSun.length();
        Vec3 dir = toSun.scale(1.0 / sunDist);
        for (SolarSystem.Body body : SolarSystem.bodies()) {
            if (body.isSun()) {
                continue;
            }
            Vec3 rel = SolarSystem.position(body, time).subtract(cam);
            double along = rel.dot(dir);
            if (along > 0.0 && along < sunDist && rel.subtract(dir.scale(along)).length() < body.radius()) {
                return; // eclipse: el Sol está tapado
            }
        }
        Vector4f clip = new Vector4f((float) dir.x, (float) dir.y, (float) dir.z, 0.0F).mul(view);
        clip.w = 1.0F;
        clip.mul(projection);
        if (clip.w <= 0.0F) {
            return;
        }
        float sx = clip.x / clip.w;
        float sy = clip.y / clip.w;
        float edge = Math.max(Math.abs(sx), Math.abs(sy));
        if (edge > 1.3F) {
            return;
        }
        float strength = (float) (1.0 - Math.min(1.0, edge / 1.3)) * 0.5F;
        Minecraft mc = Minecraft.getInstance();
        float aspect = (float) mc.getWindow().getHeight() / Math.max(1, mc.getWindow().getWidth());
        ShaderInstance shader = PlanetRenderer.shader();
        PlanetRenderer.additive();
        RenderSystem.disableCull();
        // posición en la línea Sol-centro, tamaño, color
        float[][] ghosts = {
                {1.0F, 0.55F, 1.0F, 0.9F, 0.7F, 0.10F},
                {0.55F, 0.06F, 0.6F, 0.8F, 1.0F, 0.25F},
                {0.25F, 0.10F, 1.0F, 0.6F, 0.3F, 0.18F},
                {-0.15F, 0.04F, 0.5F, 1.0F, 0.6F, 0.30F},
                {-0.45F, 0.14F, 0.4F, 0.6F, 1.0F, 0.15F},
                {-0.8F, 0.08F, 1.0F, 0.5F, 0.8F, 0.20F},
        };
        for (float[] g : ghosts) {
            shader.safeGetUniform("Ring").set(g[1] * aspect, g[1], sx * g[0], sy * g[0]);
            shader.safeGetUniform("Tint").set(g[2], g[3], g[4], g[5] * strength);
            PlanetRenderer.set(PlanetRenderer.MODE_FLARE, 0, 0, 0, new Vector3f(0, 1, 0), new Vector3f());
            PlanetRenderer.draw(PlanetRenderer.quad(), new Matrix4f(), new Matrix4f());
        }
        shader.safeGetUniform("Tint").set(1.0F, 1.0F, 1.0F, 1.0F);
    }
}
