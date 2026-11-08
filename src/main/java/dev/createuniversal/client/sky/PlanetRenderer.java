package dev.createuniversal.client.sky;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.createuniversal.CreateUniversal;
import dev.createuniversal.space.SolarSystem;
import dev.createuniversal.space.SolarSystem.Body;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.opengl.EXTTextureFilterAnisotropic;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dibuja el Sol, los planetas y sus lunas en el cielo del espacio (alpha.4), con los mapas de la NASA.
 *
 * <p>Cada astro se dibuja "proyectado": la esfera se encoge hacia la cámara hasta quedar a {@link #SKY_DISTANCE} bloques,
 * con el mismo tamaño aparente que tendría de verdad. Así nunca la corta la distancia de renderizado y no hay problemas de
 * precisión a un millón de bloques. Se pintan del más lejano al más cercano.</p>
 *
 * <p>Para que vaya fluido: una sola esfera en la tarjeta gráfica (dos resoluciones) que se reutiliza para todo, un solo
 * shader, mipmaps en las texturas y los astros que ocupan menos de un píxel se dibujan como un punto de luz.</p>
 */
public final class PlanetRenderer {
    private static final float SKY_DISTANCE = 30.0F;
    /** Por debajo de este radio en píxeles el astro es un punto de luz. */
    private static final double DOT_PIXELS = 1.2;
    /** Radio en píxeles a partir del que se usa la esfera detallada. */
    private static final double DETAIL_PIXELS = 60.0;
    /** Tamaño del punto de luz, en píxeles. */
    private static final double DOT_SIZE_PIXELS = 2.5;

    private static final int MODE_SURFACE = 0;
    private static final int MODE_CLOUDS = 1;
    private static final int MODE_ATMOSPHERE = 2;
    private static final int MODE_SUN = 3;
    private static final int MODE_SUN_GLOW = 4;
    private static final int MODE_RINGS = 5;
    private static final int MODE_DOT = 6;

    /** Cómo se ve cada astro: texturas, atmósfera (radio relativo, 0 = sin), brillo del agua y luz ambiente. */
    private record Look(String surface, String night, String clouds, float atmoRadius, float specular, float ambient) {}

    private static final Look AIRLESS = new Look(null, null, null, 0.0F, 0.0F, 0.008F);
    private static final Map<String, Look> LOOKS = new HashMap<>();

    static {
        LOOKS.put("earth", new Look("earth_day", "earth_night", "earth_clouds", 1.035F, 0.9F, 0.01F));
        LOOKS.put("venus", new Look("venus", null, null, 1.05F, 0.0F, 0.01F));
        LOOKS.put("mars", new Look("mars", null, null, 1.02F, 0.0F, 0.008F));
        LOOKS.put("jupiter", new Look("jupiter", null, null, 1.015F, 0.0F, 0.008F));
        LOOKS.put("saturn", new Look("saturn", null, null, 1.015F, 0.0F, 0.008F));
        LOOKS.put("uranus", new Look("uranus", null, null, 1.03F, 0.0F, 0.008F));
        LOOKS.put("neptune", new Look("neptune", null, null, 1.03F, 0.0F, 0.008F));
        LOOKS.put("titan", new Look("titan", null, null, 1.12F, 0.0F, 0.01F));
    }

    private static ShaderInstance shader;
    private static VertexBuffer sphereDetailed;
    private static VertexBuffer sphereSimple;
    private static VertexBuffer quad;
    private static VertexBuffer saturnRings;
    private static boolean preloaded;
    private static final Set<Integer> PREPARED_TEXTURES = new HashSet<>();
    private static final Map<String, float[]> DOT_COLORS = new HashMap<>();

    private record Item(Body body, Vec3 pos, Vec3 rel, double dist, double pixels) {}

    private PlanetRenderer() {}

    /** Desde {@code RegisterShadersEvent}, que se repite en cada recarga de recursos (las texturas se vuelven a preparar). */
    public static void setShader(ShaderInstance instance) {
        shader = instance;
        PREPARED_TEXTURES.clear();
        DOT_COLORS.clear();
        preloaded = false;
    }

    /**
     * @param view       rotación de la cámara (sin traslación)
     * @param projection proyección
     * @param cam        posición de la cámara en la dimensión espacio
     * @param time       tiempo del sistema solar, con decimales
     */
    public static void render(Matrix4f view, Matrix4f projection, Vec3 cam, double time) {
        if (shader == null) {
            return;
        }
        ensureBuffers();
        preload();

        Minecraft mc = Minecraft.getInstance();
        double tanHalfFov = Math.tan(Math.toRadians(mc.options.fov().get()) * 0.5);
        double halfHeight = mc.getWindow().getHeight() * 0.5;
        Vec3 sunPos = SolarSystem.position(SolarSystem.get("sun"), time);

        List<Item> items = new ArrayList<>();
        for (Body body : SolarSystem.bodies()) {
            Vec3 pos = SolarSystem.position(body, time);
            Vec3 rel = pos.subtract(cam);
            double dist = rel.length();
            if (dist <= body.radius() * 1.0005) {
                continue;
            }
            double angular = Math.asin(Math.min(1.0, body.radius() / dist));
            double pixels = Math.tan(angular) / tanHalfFov * halfHeight;
            items.add(new Item(body, pos, rel, dist, pixels));
        }
        items.sort(Comparator.comparingDouble(Item::dist).reversed());

        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);

        // Primero los puntos de luz: cualquier esfera más cercana los tapa.
        additive();
        RenderSystem.disableCull();
        double dotHalf = SKY_DISTANCE * tanHalfFov * DOT_SIZE_PIXELS / halfHeight;
        for (Item it : items) {
            if (it.pixels() < DOT_PIXELS) {
                drawDot(it, view, projection, sunPos, cam, dotHalf);
            }
        }
        for (Item it : items) {
            if (it.pixels() >= DOT_PIXELS) {
                drawBody(it, view, projection, sunPos, time, tanHalfFov);
            }
        }

        GL11.glCullFace(GL11.GL_BACK);
        RenderSystem.disableCull();
        RenderSystem.disableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
    }

    // ---------------------------------------------------------------- astros

    private static void drawBody(Item it, Matrix4f view, Matrix4f projection, Vec3 sunPos, double time, double tanHalfFov) {
        Body body = it.body();
        double k = SKY_DISTANCE / it.dist();
        Vector3f center = vec(it.rel().scale(k));
        float radius = (float) (body.radius() * k);
        Vector3f camRel = vec(it.rel().scale(-1.0 / body.radius()));
        Vector3f sunDir = vec(sunPos.subtract(it.pos()).normalize());
        VertexBuffer sphere = it.pixels() > DETAIL_PIXELS ? sphereDetailed : sphereSimple;
        Look look = LOOKS.getOrDefault(body.id(), AIRLESS);

        if (body.isSun()) {
            drawSunGlow(it, view, projection, center, tanHalfFov);
            Quaternionf q = orientation(body, time, true, 0.0);
            opaque();
            RenderSystem.enableCull();
            bind(0, body.id() + "_hi", true);
            set(MODE_SUN, 0, 0, 0, new Vector3f(0, 1, 0), toObject(q, camRel), 0, 0, 0, 0, 0, 0, 0, 0);
            draw(sphere, model(view, center, q, radius), projection);
            return;
        }

        Quaternionf tilt = orientation(body, time, false, 0.0);
        boolean rings = body.hasRings() && saturnRings != null;
        if (rings) {
            drawRings(body, view, projection, center, radius, tilt, camRel, sunDir, look, 0);
        }

        // Superficie
        Quaternionf q = orientation(body, time, true, 0.0);
        opaque();
        RenderSystem.enableCull();
        bind(0, (look.surface() != null ? look.surface() : body.id()) + "_hi", true);
        float extra = 0.0F;
        if (look.night() != null) {
            bind(1, look.night() + "_hi", true);
            extra = 1.0F;
        } else if (rings) {
            bind(1, "saturn_rings", false);
            extra = 2.0F;
        }
        float[] atmo = atmoColor(body);
        set(MODE_SURFACE, look.ambient(), look.specular(), extra, toObject(q, sunDir), toObject(q, camRel),
                atmo[0], atmo[1], atmo[2], look.atmoRadius() > 0 ? atmo[3] : 0.0F,
                body.ringInner(), body.ringOuter(), 0, look.atmoRadius());
        draw(sphere, model(view, center, q, radius), projection);

        // Nubes: una capa algo por encima que gira un poco más deprisa que el suelo.
        if (look.clouds() != null) {
            Quaternionf qc = orientation(body, time, true, Math.PI * 2.0 * (time / (24000.0 * 40.0) % 1.0));
            alphaBlend();
            bind(0, look.clouds() + "_hi", true);
            set(MODE_CLOUDS, look.ambient(), 0, 0, toObject(qc, sunDir), toObject(qc, camRel), 0, 0, 0, 0, 0, 0, 0, 0);
            draw(sphere, model(view, center, qc, radius * 1.006F), projection);
        }

        // Atmósfera: halo calculado por píxel (se dibujan las caras de atrás de una esfera algo mayor).
        if (look.atmoRadius() > 0.0F && it.pixels() > 3.0) {
            additive();
            GL11.glCullFace(GL11.GL_FRONT);
            set(MODE_ATMOSPHERE, 0, 0, 0, toObject(q, sunDir), toObject(q, camRel), atmo[0], atmo[1], atmo[2], atmo[3],
                    0, 0, 0, look.atmoRadius());
            draw(sphere, model(view, center, q, radius * look.atmoRadius()), projection);
            GL11.glCullFace(GL11.GL_BACK);
        }

        if (rings) {
            drawRings(body, view, projection, center, radius, tilt, camRel, sunDir, look, 1);
        }
    }

    /** Los anillos en dos mitades: la de detrás antes del planeta y la de delante después. */
    private static void drawRings(Body body, Matrix4f view, Matrix4f projection, Vector3f center, float radius, Quaternionf tilt,
                                  Vector3f camRel, Vector3f sunDir, Look look, int half) {
        alphaBlend();
        RenderSystem.disableCull();
        bind(0, "saturn_rings", false);
        set(MODE_RINGS, look.ambient(), 0, 0, toObject(tilt, sunDir), toObject(tilt, camRel), 0, 0, 0, 0,
                body.ringInner(), body.ringOuter(), half, 0);
        draw(saturnRings, model(view, center, tilt, radius), projection);
    }

    private static void drawSunGlow(Item it, Matrix4f view, Matrix4f projection, Vector3f center, double tanHalfFov) {
        double sunAngular = Math.asin(Math.min(1.0, it.body().radius() / it.dist()));
        double glowAngular = Math.max(sunAngular * 14.0, 0.06);
        float half = (float) (SKY_DISTANCE * Math.tan(Math.min(glowAngular, 1.2)));
        additive();
        RenderSystem.disableCull();
        set(MODE_SUN_GLOW, 0, 0, 0, new Vector3f(0, 1, 0), new Vector3f(0, 0, 1), 0, 0, 0, 1.0F,
                (float) (glowAngular / sunAngular), 0, 0, 0);
        draw(quad, model(view, center, facingCamera(center), half), projection);
    }

    private static void drawDot(Item it, Matrix4f view, Matrix4f projection, Vec3 sunPos, Vec3 cam, double half) {
        double brightness = it.pixels() / DOT_PIXELS;
        Vec3 toSun = sunPos.subtract(it.pos()).normalize();
        Vec3 toCam = cam.subtract(it.pos()).normalize();
        double phase = 0.25 + 0.75 * (1.0 + toSun.dot(toCam)) * 0.5;
        float b = (float) Math.min(1.0, brightness * phase * 1.6);
        if (b < 0.03F) {
            return;
        }
        float[] c = dotColor(it.body());
        Vector3f center = vec(it.rel().scale(SKY_DISTANCE / it.dist()));
        set(MODE_DOT, 0, 0, 0, new Vector3f(0, 1, 0), new Vector3f(0, 0, 1), c[0], c[1], c[2], b, 0, 0, 0, 0);
        draw(quad, model(view, center, facingCamera(center), (float) half), projection);
    }

    // ---------------------------------------------------------------- utilidades de dibujo

    private static Quaternionf orientation(Body body, double time, boolean spin, double extraSpin) {
        Quaternionf q = new Quaternionf().rotationZ((float) Math.toRadians(body.tiltDeg()));
        if (spin) {
            q.rotateY((float) ((SolarSystem.spinAngle(body, time) + extraSpin) % (Math.PI * 2.0)));
        }
        return q;
    }

    /** Rotación que pone la cara +Z del cuadro mirando a la cámara (que está en el origen). */
    private static Quaternionf facingCamera(Vector3f center) {
        return new Quaternionf().rotationTo(new Vector3f(0, 0, 1), new Vector3f(center).negate().normalize());
    }

    private static Vector3f toObject(Quaternionf q, Vector3f world) {
        return new Quaternionf(q).conjugate().transform(new Vector3f(world));
    }

    private static Matrix4f model(Matrix4f view, Vector3f center, Quaternionf q, float scale) {
        return new Matrix4f(view).translate(center).rotate(q).scale(scale);
    }

    private static Vector3f vec(Vec3 v) {
        return new Vector3f((float) v.x, (float) v.y, (float) v.z);
    }

    private static float[] atmoColor(Body body) {
        int c = body.atmoColor();
        return new float[] {(c >> 16 & 255) / 255.0F, (c >> 8 & 255) / 255.0F, (c & 255) / 255.0F, body.atmoStrength()};
    }

    private static void set(int mode, float ambient, float specular, float extra, Vector3f sun, Vector3f cam,
                            float ar, float ag, float ab, float aa, float r0, float r1, float r2, float r3) {
        shader.safeGetUniform("Params").set((float) mode, ambient, specular, extra);
        shader.safeGetUniform("SunDir").set(sun.x, sun.y, sun.z);
        shader.safeGetUniform("CamPos").set(cam.x, cam.y, cam.z);
        shader.safeGetUniform("AtmoColor").set(ar, ag, ab, aa);
        shader.safeGetUniform("Ring").set(r0, r1, r2, r3);
    }

    private static void draw(VertexBuffer buffer, Matrix4f modelView, Matrix4f projection) {
        buffer.bind();
        buffer.drawWithShader(modelView, projection, shader);
        VertexBuffer.unbind();
    }

    private static void opaque() {
        RenderSystem.disableBlend();
    }

    private static void alphaBlend() {
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
    }

    private static void additive() {
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE);
    }

    // ---------------------------------------------------------------- texturas

    private static ResourceLocation texture(String name) {
        return CreateUniversal.id("textures/planet/" + name + ".png");
    }

    /** Enlaza una textura con mipmaps (sin ellos, un mapa de 2048 en un planeta pequeño parpadea). */
    private static void bind(int unit, String name, boolean wrapAround) {
        AbstractTexture tex = Minecraft.getInstance().getTextureManager().getTexture(texture(name));
        int id = tex.getId();
        if (PREPARED_TEXTURES.add(id)) {
            GlStateManager._activeTexture(GL30.GL_TEXTURE0);
            GlStateManager._bindTexture(id);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 1000);
            GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LOD, 1000.0F);
            GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, wrapAround ? GL11.GL_REPEAT : GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            if (GL.getCapabilities().GL_EXT_texture_filter_anisotropic) {
                float max = GL11.glGetFloat(EXTTextureFilterAnisotropic.GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT);
                GL11.glTexParameterf(GL11.GL_TEXTURE_2D, EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT, Math.min(8.0F, max));
            }
        }
        RenderSystem.setShaderTexture(unit, id);
    }

    /** Carga todas las texturas de golpe la primera vez (ocurre durante el fundido de llegada, no en mitad del viaje). */
    private static void preload() {
        if (preloaded) {
            return;
        }
        preloaded = true;
        long start = System.currentTimeMillis();
        for (Body body : SolarSystem.bodies()) {
            Look look = LOOKS.getOrDefault(body.id(), AIRLESS);
            bind(0, (look.surface() != null ? look.surface() : body.id()) + "_hi", true);
            if (look.night() != null) {
                bind(0, look.night() + "_hi", true);
            }
            if (look.clouds() != null) {
                bind(0, look.clouds() + "_hi", true);
            }
            dotColor(body);
        }
        bind(0, "saturn_rings", false);
        CreateUniversal.LOGGER.info("Texturas del sistema solar cargadas en {} ms", System.currentTimeMillis() - start);
    }

    /** Color medio del mapa pequeño: el color del punto de luz cuando el astro está lejos. */
    private static float[] dotColor(Body body) {
        return DOT_COLORS.computeIfAbsent(body.id(), id -> {
            Look look = LOOKS.getOrDefault(id, AIRLESS);
            ResourceLocation loc = texture((look.surface() != null ? look.surface() : id) + "_lo");
            try (InputStream in = Minecraft.getInstance().getResourceManager().open(loc); NativeImage img = NativeImage.read(in)) {
                double r = 0, g = 0, b = 0;
                int n = 0;
                for (int y = 0; y < img.getHeight(); y += 2) {
                    for (int x = 0; x < img.getWidth(); x += 2) {
                        int abgr = img.getPixelRGBA(x, y);
                        r += abgr & 255;
                        g += abgr >> 8 & 255;
                        b += abgr >> 16 & 255;
                        n++;
                    }
                }
                float scale = 1.0F / (255.0F * Math.max(1, n));
                float[] c = {(float) r * scale, (float) g * scale, (float) b * scale};
                float max = Math.max(c[0], Math.max(c[1], c[2]));
                if (max > 0.0F) {
                    for (int i = 0; i < 3; i++) {
                        c[i] /= max; // el tono, a pleno brillo: el brillo lo pone el tamaño
                    }
                }
                return c;
            } catch (IOException | RuntimeException e) {
                return new float[] {0.85F, 0.85F, 0.85F};
            }
        });
    }

    // ---------------------------------------------------------------- mallas

    private static void ensureBuffers() {
        if (sphereDetailed != null) {
            return;
        }
        sphereDetailed = upload(sphere(96, 48));
        sphereSimple = upload(sphere(32, 16));
        quad = upload(quad());
        Body saturn = SolarSystem.get("saturn");
        saturnRings = upload(rings(saturn.ringInner(), saturn.ringOuter(), 160));
    }

    private static VertexBuffer upload(com.mojang.blaze3d.vertex.MeshData mesh) {
        VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        buffer.bind();
        buffer.upload(mesh);
        VertexBuffer.unbind();
        return buffer;
    }

    /** Esfera de radio 1 con las coordenadas del mapa equirectangular: u = longitud (0 en +X, hacia el este), v = latitud. */
    private static com.mojang.blaze3d.vertex.MeshData sphere(int lonSegments, int latSegments) {
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        for (int la = 0; la < latSegments; la++) {
            double t0 = Math.PI * la / latSegments;
            double t1 = Math.PI * (la + 1) / latSegments;
            float v0 = (float) la / latSegments;
            float v1 = (float) (la + 1) / latSegments;
            for (int lo = 0; lo < lonSegments; lo++) {
                double p0 = Math.PI * 2.0 * lo / lonSegments;
                double p1 = Math.PI * 2.0 * (lo + 1) / lonSegments;
                float u0 = (float) lo / lonSegments;
                float u1 = (float) (lo + 1) / lonSegments;
                spherePoint(buf, t0, p0, u0, v0);
                spherePoint(buf, t1, p0, u0, v1);
                spherePoint(buf, t1, p1, u1, v1);
                spherePoint(buf, t0, p1, u1, v0);
            }
        }
        return buf.buildOrThrow();
    }

    private static void spherePoint(BufferBuilder buf, double theta, double phi, float u, float v) {
        double s = Math.sin(theta);
        buf.addVertex((float) (s * Math.cos(phi)), (float) Math.cos(theta), (float) (-s * Math.sin(phi))).setUv(u, v);
    }

    private static com.mojang.blaze3d.vertex.MeshData quad() {
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        buf.addVertex(-1, -1, 0).setUv(0, 0);
        buf.addVertex(1, -1, 0).setUv(1, 0);
        buf.addVertex(1, 1, 0).setUv(1, 1);
        buf.addVertex(-1, 1, 0).setUv(0, 1);
        return buf.buildOrThrow();
    }

    /** Anillo plano en el ecuador (y = 0), radios en radios del planeta. */
    private static com.mojang.blaze3d.vertex.MeshData rings(float inner, float outer, int segments) {
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        for (int i = 0; i < segments; i++) {
            double a0 = Math.PI * 2.0 * i / segments;
            double a1 = Math.PI * 2.0 * (i + 1) / segments;
            // El anillo exterior se agranda un poco para que el polígono no recorte el borde redondo.
            float out = (float) (outer / Math.cos(Math.PI / segments));
            buf.addVertex((float) (Math.cos(a0) * inner), 0, (float) (Math.sin(a0) * inner)).setUv(0, 0.5F);
            buf.addVertex((float) (Math.cos(a0) * out), 0, (float) (Math.sin(a0) * out)).setUv(1, 0.5F);
            buf.addVertex((float) (Math.cos(a1) * out), 0, (float) (Math.sin(a1) * out)).setUv(1, 0.5F);
            buf.addVertex((float) (Math.cos(a1) * inner), 0, (float) (Math.sin(a1) * inner)).setUv(0, 0.5F);
        }
        return buf.buildOrThrow();
    }
}
