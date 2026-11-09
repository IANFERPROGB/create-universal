package dev.createuniversal.client.sky;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
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
 * Dibuja el Sol, los planetas y sus lunas con los mapas de la NASA.
 *
 * <p>Cada astro se dibuja "proyectado": la esfera se encoge hacia la cámara hasta quedar a {@link #SKY_DISTANCE} bloques,
 * con el mismo tamaño aparente que tendría de verdad. Así nunca la corta la distancia de renderizado y no hay problemas de
 * precisión a un millón de bloques. Se pintan del más lejano al más cercano.</p>
 *
 * <p>Por astro: superficie (relieve, mar con reflejos, luces de ciudades, sombras de nubes y de anillos, eclipses), capa de
 * nubes que cambia con el tiempo, atmósfera con dispersión de la luz calculada por píxel (Rayleigh y Mie, como la real:
 * borde azul, atardeceres rojos, auroras), anillos y, si está lejos, un punto de luz. Rendimiento: una malla de esfera en
 * la tarjeta gráfica que sirve para todo, un shader, texturas con mipmaps y carga única.</p>
 */
public final class PlanetRenderer {
    static final float SKY_DISTANCE = 30.0F;
    private static final double DOT_PIXELS = 1.2;
    private static final double DETAIL_PIXELS = 50.0;
    private static final double DOT_SIZE_PIXELS = 2.5;
    /** Los astros más pequeños que esto en pantalla llevan un halo de luz. */
    private static final double HALO_MAX_PIXELS = 20.0;

    static final int MODE_SURFACE = 0;
    static final int MODE_CLOUDS = 1;
    static final int MODE_ATMOSPHERE = 2;
    static final int MODE_SUN = 3;
    static final int MODE_CORONA = 4;
    static final int MODE_RINGS = 5;
    static final int MODE_DOT = 6;
    static final int MODE_BLACK = 7;
    static final int MODE_MILKY_WAY = 8;
    static final int MODE_FLARE = 9;
    static final int MODE_BOX = 10;

    private static final int F_NIGHT = 1;
    private static final int F_RING_SHADOW = 2;
    private static final int F_CLOUDS = 4;
    private static final int F_NORMAL = 8;
    private static final int F_BUMP = 16;
    private static final int F_BANDS = 32;
    private static final int F_AURORA = 64;
    private static final int F_RED_ECLIPSE = 128;

    /**
     * Atmósfera, en radios del astro: Rayleigh RGB y su altura de escala, Mie y su altura, asimetría g, borde y exposición.
     * Tierra: el espesor óptico real (azul 0,27 en el cénit), con la capa algo más gruesa para que se vea desde lejos.
     */
    private record Atmosphere(float rayleighR, float rayleighG, float rayleighB, float rayleighHeight,
                              float mie, float mieHeight, float g, float top, float exposure) {}

    private record Look(String surface, String night, String clouds, String normal, int flags, float ambient, float specular,
                        Atmosphere atmosphere) {}

    private static final Look AIRLESS = new Look(null, null, null, null, F_BUMP, 0.006F, 0.0F, null);
    private static final Map<String, Look> LOOKS = new HashMap<>();

    static {
        LOOKS.put("earth", new Look("earth_day", "earth_night", "earth_clouds", null, F_NIGHT | F_CLOUDS | F_AURORA, 0.008F, 1.0F,
                new Atmosphere(10.4F, 24.0F, 59.0F, 0.0045F, 16.0F, 0.0016F, 0.76F, 1.036F, 1.0F)));
        LOOKS.put("moon", new Look("moon", null, null, "moon_normal", F_NORMAL | F_RED_ECLIPSE, 0.012F, 0.0F, null));
        LOOKS.put("venus", new Look("venus", null, null, null, 0, 0.01F, 0.0F,
                new Atmosphere(9.0F, 8.0F, 4.5F, 0.012F, 25.0F, 0.006F, 0.7F, 1.09F, 1.0F)));
        LOOKS.put("mars", new Look("mars", null, null, null, F_BUMP, 0.008F, 0.0F,
                new Atmosphere(7.0F, 4.5F, 2.6F, 0.008F, 6.0F, 0.006F, 0.65F, 1.05F, 1.0F)));
        LOOKS.put("jupiter", new Look("jupiter", null, null, null, F_BANDS, 0.006F, 0.0F,
                new Atmosphere(2.0F, 2.5F, 4.0F, 0.004F, 2.0F, 0.003F, 0.7F, 1.025F, 1.0F)));
        LOOKS.put("saturn", new Look("saturn", null, null, null, F_BANDS | F_RING_SHADOW, 0.006F, 0.0F,
                new Atmosphere(2.0F, 2.3F, 3.0F, 0.004F, 3.0F, 0.003F, 0.7F, 1.025F, 1.0F)));
        LOOKS.put("uranus", new Look("uranus", null, null, null, F_BANDS, 0.006F, 0.0F,
                new Atmosphere(2.0F, 6.0F, 10.0F, 0.006F, 2.0F, 0.004F, 0.7F, 1.035F, 1.0F)));
        LOOKS.put("neptune", new Look("neptune", null, null, null, F_BANDS, 0.006F, 0.0F,
                new Atmosphere(2.0F, 5.0F, 12.0F, 0.006F, 2.0F, 0.004F, 0.7F, 1.035F, 1.0F)));
        LOOKS.put("titan", new Look("titan", null, null, null, 0, 0.01F, 0.0F,
                new Atmosphere(14.0F, 7.0F, 2.0F, 0.03F, 10.0F, 0.02F, 0.7F, 1.2F, 1.0F)));
        LOOKS.put("sun", new Look("sun", null, null, null, 0, 0.0F, 0.0F, null));
    }

    private static ShaderInstance shader;
    private static VertexBuffer sphereDetailed;
    private static VertexBuffer sphereSimple;
    private static VertexBuffer quad;
    private static VertexBuffer saturnRings;
    private static VertexBuffer cube;
    private static boolean preloaded;
    private static final Set<Integer> PREPARED_TEXTURES = new HashSet<>();
    private static final Map<String, float[]> DOT_COLORS = new HashMap<>();

    private record Item(Body body, Vec3 pos, Vec3 rel, double dist, double pixels) {}

    // Observador en la superficie de un astro (cielo de la Tierra, de la Luna...). Se fija antes de cada render.
    private static String homeBody;
    private static Vec3 groundUp;
    private static float daylight;
    private static boolean additiveBodies;

    /**
     * Para dibujar desde la superficie de {@code home}: ese astro no se dibuja, lo que está bajo el horizonte tampoco, los
     * astros se suman al color del cielo (de día la cara oscura de la Luna no tapa el azul) y los puntos de luz se
     * apagan con la luz del día ({@code daylight} de 0 a 1).
     */
    public static void setGroundObserver(String home, Vec3 up, float daylightAmount, boolean addToSky) {
        homeBody = home;
        groundUp = up;
        daylight = daylightAmount;
        additiveBodies = addToSky;
    }

    public static void clearGroundObserver() {
        homeBody = null;
        groundUp = null;
        daylight = 0.0F;
        additiveBodies = false;
    }

    private static float nightVisibility() {
        return 1.0F - 0.92F * daylight;
    }

    private PlanetRenderer() {}

    /** Desde {@code RegisterShadersEvent}, que se repite en cada recarga de recursos (las texturas se vuelven a preparar). */
    public static void setShader(ShaderInstance instance) {
        shader = instance;
        PREPARED_TEXTURES.clear();
        DOT_COLORS.clear();
        preloaded = false;
    }

    static ShaderInstance shader() {
        return shader;
    }

    /**
     * @param view       rotación de la cámara (sin traslación)
     * @param projection proyección
     * @param cam        posición de la cámara en la dimensión espacio
     * @param time       tiempo del sistema solar, con decimales
     * @param seconds    segundos de juego, para las animaciones
     * @param farDepth   dibujar en el plano lejano con prueba de profundidad (después de Iris)
     */
    public static void render(Matrix4f view, Matrix4f projection, Vec3 cam, double time, float seconds, boolean farDepth) {
        if (shader == null) {
            return;
        }
        ensureBuffers();
        preload();

        Minecraft mc = Minecraft.getInstance();
        double tanHalfFov = 1.0 / Math.max(1e-4, projection.m11());
        double halfHeight = mc.getWindow().getHeight() * 0.5;
        Vec3 sunPos = SolarSystem.position(SolarSystem.get("sun"), time);

        List<Item> items = new ArrayList<>();
        for (Body body : SolarSystem.bodies()) {
            if (body.id().equals(homeBody)) {
                continue;
            }
            Vec3 pos = SolarSystem.position(body, time);
            Vec3 rel = pos.subtract(cam);
            double dist = rel.length();
            if (groundUp != null && rel.dot(groundUp) < -body.radius()) {
                continue; // bajo el horizonte
            }
            if (dist <= body.radius() * 1.0005) {
                continue;
            }
            double angular = Math.asin(Math.min(1.0, body.radius() / dist));
            double pixels = Math.tan(angular) / tanHalfFov * halfHeight;
            items.add(new Item(body, pos, rel, dist, pixels));
        }
        items.sort(Comparator.comparingDouble(Item::dist).reversed());

        commonUniforms(seconds, farDepth);
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
                drawBody(it, view, projection, sunPos, time, seconds);
                if (it.pixels() < HALO_MAX_PIXELS && !it.body().isSun()) {
                    drawHalo(it, view, projection, sunPos, cam, tanHalfFov, halfHeight);
                }
            }
        }

        GL11.glCullFace(GL11.GL_BACK);
        RenderSystem.disableCull();
        RenderSystem.disableBlend();
        RenderSystem.defaultBlendFunc();
    }

    /** Uniformes que no cambian en todo el fotograma. */
    static void commonUniforms(float seconds, boolean farDepth) {
        shader.safeGetUniform("FarDepth").set(farDepth ? 1.0F : 0.0F);
        shader.safeGetUniform("Occluder").set(0.0F, 0.0F, 0.0F, 0.0F);
        shader.safeGetUniform("ScatterR").set(0.0F, 0.0F, 0.0F, 0.01F);
        shader.safeGetUniform("ScatterM").set(0.0F, 0.01F, 0.7F, 0.0F);
        shader.safeGetUniform("Tint").set(1.0F, 1.0F, 1.0F, 1.0F);
        shader.safeGetUniform("Anim").set(seconds, 0.0F, 0.0F, 0.005F);
    }

    // ---------------------------------------------------------------- astros

    private static void drawBody(Item it, Matrix4f view, Matrix4f projection, Vec3 sunPos, double time, float seconds) {
        Body body = it.body();
        double k = SKY_DISTANCE / it.dist();
        Vector3f center = vec(it.rel().scale(k));
        float radius = (float) (body.radius() * k);
        Vector3f camRel = vec(it.rel().scale(-1.0 / body.radius()));
        Vector3f sunDir = vec(sunPos.subtract(it.pos()).normalize());
        VertexBuffer sphere = it.pixels() > DETAIL_PIXELS ? sphereDetailed : sphereSimple;
        Look look = LOOKS.getOrDefault(body.id(), AIRLESS);
        Atmosphere atmo = look.atmosphere();

        if (body.isSun()) {
            drawCorona(it, view, projection, center);
            Quaternionf q = orientation(body, time, true, 0.0);
            opaque();
            RenderSystem.enableCull();
            bind(0, "sun_hi", true);
            set(MODE_SUN, 0, 0, 0, new Vector3f(0, 1, 0), toObject(q, camRel));
            draw(sphere, model(view, center, q, radius), projection);
            return;
        }

        float sunAngular = (float) (SolarSystem.get("sun").radius() / sunPos.distanceTo(it.pos()));
        float bandShear = (float) ((time / 24000.0) * 0.015 % 1.0);
        Quaternionf tilt = orientation(body, time, false, 0.0);
        boolean rings = body.hasRings() && saturnRings != null;
        if (rings) {
            drawRings(body, view, projection, center, radius, tilt, camRel, sunDir, look, 0);
        }

        // Superficie
        Quaternionf q = orientation(body, time, true, 0.0);
        double cloudExtra = Math.PI * 2.0 * (time / (24000.0 * 30.0) % 1.0);
        opaque();
        RenderSystem.enableCull();
        bind(0, (look.surface() != null ? look.surface() : body.id()) + "_hi", true);
        int flags = look.flags();
        if (look.night() != null) {
            bind(1, look.night() + "_hi", true);
        } else if (rings) {
            bind(1, "saturn_rings", false);
        } else {
            flags &= ~F_RING_SHADOW;
        }
        if (look.clouds() != null) {
            bind(2, look.clouds() + "_hi", true);
        } else if (look.normal() != null) {
            bind(2, look.normal() + "_hi", true);
        }
        occluder(it, q, time);
        scatter(atmo);
        shader.safeGetUniform("Anim").set(seconds, (float) (-cloudExtra / (Math.PI * 2.0)), bandShear, sunAngular);
        shader.safeGetUniform("Ring").set(body.ringInner(), body.ringOuter(), 0.0F, 0.0F);
        shader.safeGetUniform("Tint").set(1.0F, 1.0F, 1.0F, atmo != null ? atmo.exposure() : 1.0F);
        set(MODE_SURFACE, look.ambient(), look.specular(), flags, toObject(q, sunDir), toObject(q, camRel));
        draw(sphere, model(view, center, q, radius), projection);

        // Nubes: capa algo por encima que gira un poco más deprisa que el suelo y cambia con el tiempo.
        if (look.clouds() != null) {
            Quaternionf qc = orientation(body, time, true, cloudExtra);
            alphaBlend();
            bind(0, look.clouds() + "_hi", true);
            occluder(it, qc, time);
            set(MODE_CLOUDS, look.ambient(), 0, 0, toObject(qc, sunDir), toObject(qc, camRel));
            draw(sphere, model(view, center, qc, radius * 1.004F), projection);
        }

        // Atmósfera: se dibujan las caras de atrás de una esfera algo mayor y el shader calcula la luz dispersada.
        if (atmo != null && it.pixels() > 2.5) {
            RenderSystem.enableBlend();
            RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.SRC_ALPHA);
            GL11.glCullFace(GL11.GL_FRONT);
            occluder(it, q, time);
            set(MODE_ATMOSPHERE, 0, 0, look.flags(), toObject(q, sunDir), toObject(q, camRel));
            draw(sphere, model(view, center, q, radius * atmo.top()), projection);
            GL11.glCullFace(GL11.GL_BACK);
        }
        scatter(null);
        shader.safeGetUniform("Occluder").set(0.0F, 0.0F, 0.0F, 0.0F);

        if (rings) {
            drawRings(body, view, projection, center, radius, tilt, camRel, sunDir, look, 1);
        }
    }

    /**
     * La atmósfera del astro donde se está de pie, vista desde dentro (cielo de Marte): el mismo cálculo de dispersión
     * que desde el espacio, con la cámara dentro de la capa. Da el color del cielo de día, el atardecer azul de Marte y el
     * Sol enrojecido cerca del horizonte.
     */
    public static void drawHomeAtmosphere(Matrix4f view, Matrix4f projection, Body body, Vec3 cam, double time, float seconds) {
        Look look = LOOKS.getOrDefault(body.id(), AIRLESS);
        Atmosphere atmo = look.atmosphere();
        if (shader == null || atmo == null) {
            return;
        }
        ensureBuffers();
        Vec3 pos = SolarSystem.position(body, time);
        Vec3 rel = pos.subtract(cam);
        double dist = rel.length();
        double k = SKY_DISTANCE / dist;
        Vector3f center = vec(rel.scale(k));
        float radius = (float) (body.radius() * k);
        Vec3 sunPos = SolarSystem.position(SolarSystem.get("sun"), time);
        Vector3f sunDir = vec(sunPos.subtract(pos).normalize());
        Vector3f camRel = vec(rel.scale(-1.0 / body.radius()));
        Quaternionf q = orientation(body, time, true, 0.0);
        float sunAngular = (float) (SolarSystem.get("sun").radius() / sunPos.distanceTo(pos));
        scatter(atmo);
        shader.safeGetUniform("Occluder").set(0.0F, 0.0F, 0.0F, 0.0F);
        shader.safeGetUniform("Anim").set(seconds, 0.0F, 0.0F, sunAngular);
        shader.safeGetUniform("Tint").set(1.0F, 1.0F, 1.0F, atmo.exposure());
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.SRC_ALPHA);
        RenderSystem.enableCull();
        GL11.glCullFace(GL11.GL_FRONT);
        set(MODE_ATMOSPHERE, 0, 0, look.flags(), toObject(q, sunDir), toObject(q, camRel));
        draw(sphereDetailed, model(view, center, q, radius * atmo.top()), projection);
        GL11.glCullFace(GL11.GL_BACK);
        RenderSystem.disableCull();
        scatter(null);
        shader.safeGetUniform("Tint").set(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /** El astro que puede hacer sombra sobre este (eclipses): las lunas, su planeta; los planetas, su luna mejor alineada. */
    private static void occluder(Item it, Quaternionf q, double time) {
        Body body = it.body();
        Body best = null;
        if (body.parent() != null && !"sun".equals(body.parent())) {
            best = SolarSystem.get(body.parent());
        } else {
            Vec3 toSun = SolarSystem.position(SolarSystem.get("sun"), time).subtract(it.pos()).normalize();
            double bestScore = Double.MAX_VALUE;
            for (Body moon : SolarSystem.bodies()) {
                if (!body.id().equals(moon.parent())) {
                    continue;
                }
                Vec3 rel = SolarSystem.position(moon, time).subtract(it.pos());
                double along = rel.dot(toSun);
                if (along <= 0.0) {
                    continue;
                }
                double off = rel.subtract(toSun.scale(along)).length() - moon.radius() - body.radius();
                if (off < bestScore) {
                    bestScore = off;
                    best = moon;
                }
            }
        }
        if (best == null) {
            shader.safeGetUniform("Occluder").set(0.0F, 0.0F, 0.0F, 0.0F);
            return;
        }
        Vector3f rel = toObject(q, vec(SolarSystem.position(best, time).subtract(it.pos()).scale(1.0 / body.radius())));
        shader.safeGetUniform("Occluder").set(rel.x, rel.y, rel.z, (float) (best.radius() / body.radius()));
    }

    private static void scatter(Atmosphere a) {
        if (a == null) {
            shader.safeGetUniform("ScatterR").set(0.0F, 0.0F, 0.0F, 0.01F);
            shader.safeGetUniform("ScatterM").set(0.0F, 0.01F, 0.7F, 0.0F);
            return;
        }
        shader.safeGetUniform("ScatterR").set(a.rayleighR(), a.rayleighG(), a.rayleighB(), a.rayleighHeight());
        shader.safeGetUniform("ScatterM").set(a.mie(), a.mieHeight(), a.g(), a.top());
    }

    /** Los anillos en dos mitades: la de detrás antes del planeta y la de delante después. */
    private static void drawRings(Body body, Matrix4f view, Matrix4f projection, Vector3f center, float radius, Quaternionf tilt,
                                  Vector3f camRel, Vector3f sunDir, Look look, int half) {
        alphaBlend();
        RenderSystem.disableCull();
        bind(0, "saturn_rings", false);
        shader.safeGetUniform("Ring").set(body.ringInner(), body.ringOuter(), (float) half, 0.0F);
        set(MODE_RINGS, look.ambient(), 0, 0, toObject(tilt, sunDir), toObject(tilt, camRel));
        draw(saturnRings, model(view, center, tilt, radius), projection);
    }

    private static void drawCorona(Item it, Matrix4f view, Matrix4f projection, Vector3f center) {
        double sunAngular = Math.asin(Math.min(1.0, it.body().radius() / it.dist()));
        double glowAngular = Math.max(sunAngular * 16.0, 0.07);
        float half = (float) (SKY_DISTANCE * Math.tan(Math.min(glowAngular, 1.2)));
        additive();
        RenderSystem.disableCull();
        shader.safeGetUniform("Ring").set((float) (glowAngular / sunAngular), 0.0F, 0.0F, 0.0F);
        shader.safeGetUniform("Tint").set(1.0F, 1.0F, 1.0F, 1.0F);
        set(MODE_CORONA, 0, 0, 0, new Vector3f(0, 1, 0), new Vector3f(0, 0, 1));
        draw(quad, model(view, center, facingCamera(center), half), projection);
    }

    /**
     * Halo de los astros pequeños y brillantes. La Luna real mide medio grado, como aquí (unos 7 píxeles), pero brilla
     * tanto más que las estrellas que el ojo la ve al instante; una pantalla no tiene ese brillo, así que se imita como lo
     * hace una cámara: un resplandor alrededor que se apaga cuando el astro ya se ve grande.
     */
    private static void drawHalo(Item it, Matrix4f view, Matrix4f projection, Vec3 sunPos, Vec3 cam, double tanHalfFov,
                                 double halfHeight) {
        Vec3 toSun = sunPos.subtract(it.pos()).normalize();
        Vec3 toCam = cam.subtract(it.pos()).normalize();
        double lit = (1.0 + toSun.dot(toCam)) * 0.5;
        double fade = 1.0 - smooth(3.0, HALO_MAX_PIXELS, it.pixels());
        float strength = (float) (0.4 * Math.pow(lit, 0.6) * fade) * nightVisibility();
        if (strength < 0.01F) {
            return;
        }
        double haloPixels = Math.max(7.0, it.pixels() * 2.5);
        double half = SKY_DISTANCE * tanHalfFov * haloPixels / halfHeight;
        float[] c = dotColor(it.body());
        float grey = (c[0] + c[1] + c[2]) / 3.0F;
        Vector3f center = vec(it.rel().scale(SKY_DISTANCE / it.dist()));
        additive();
        RenderSystem.disableCull();
        // Color del astro aclarado: el halo de algo muy brillante tira a blanco.
        shader.safeGetUniform("Tint").set((c[0] + grey) * 0.5F, (c[1] + grey) * 0.5F, (c[2] + grey) * 0.5F, strength);
        set(MODE_DOT, 0, 0, 0, new Vector3f(0, 1, 0), new Vector3f(0, 0, 1));
        draw(quad, model(view, center, facingCamera(center), (float) half), projection);
        shader.safeGetUniform("Tint").set(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static double smooth(double e0, double e1, double x) {
        double t = Math.max(0.0, Math.min(1.0, (x - e0) / (e1 - e0)));
        return t * t * (3.0 - 2.0 * t);
    }

    private static void drawDot(Item it, Matrix4f view, Matrix4f projection, Vec3 sunPos, Vec3 cam, double half) {
        double brightness = it.pixels() / DOT_PIXELS;
        Vec3 toSun = sunPos.subtract(it.pos()).normalize();
        Vec3 toCam = cam.subtract(it.pos()).normalize();
        double phase = 0.25 + 0.75 * (1.0 + toSun.dot(toCam)) * 0.5;
        float b = (float) Math.min(1.0, Math.sqrt(brightness) * phase * 1.6) * nightVisibility();
        if (b < 0.03F) {
            return;
        }
        float[] c = dotColor(it.body());
        Vector3f center = vec(it.rel().scale(SKY_DISTANCE / it.dist()));
        shader.safeGetUniform("Tint").set(c[0], c[1], c[2], b);
        set(MODE_DOT, 0, 0, 0, new Vector3f(0, 1, 0), new Vector3f(0, 0, 1));
        draw(quad, model(view, center, facingCamera(center), (float) half), projection);
    }

    // ---------------------------------------------------------------- utilidades de dibujo

    /** Eje inclinado hacia −Z, como el de la Tierra hacia la estrella Polar en este cielo, y giro sobre ese eje. */
    private static Quaternionf orientation(Body body, double time, boolean spin, double extraSpin) {
        org.joml.Quaterniond q = SolarSystem.orientation(body, time, spin, extraSpin);
        return new Quaternionf((float) q.x, (float) q.y, (float) q.z, (float) q.w);
    }

    static Quaternionf facingCamera(Vector3f center) {
        return new Quaternionf().rotationTo(new Vector3f(0, 0, 1), new Vector3f(center).negate().normalize());
    }

    private static Vector3f toObject(Quaternionf q, Vector3f world) {
        return new Quaternionf(q).conjugate().transform(new Vector3f(world));
    }

    static Matrix4f model(Matrix4f view, Vector3f center, Quaternionf q, float scale) {
        return new Matrix4f(view).translate(center).rotate(q).scale(scale);
    }

    static Vector3f vec(Vec3 v) {
        return new Vector3f((float) v.x, (float) v.y, (float) v.z);
    }

    static void set(int mode, float ambient, float specular, int flags, Vector3f sun, Vector3f cam) {
        shader.safeGetUniform("Params").set((float) mode, ambient, specular, (float) flags);
        shader.safeGetUniform("SunDir").set(sun.x, sun.y, sun.z);
        shader.safeGetUniform("CamPos").set(cam.x, cam.y, cam.z);
    }

    static void draw(VertexBuffer buffer, Matrix4f modelView, Matrix4f projection) {
        buffer.bind();
        buffer.drawWithShader(modelView, projection, shader);
        VertexBuffer.unbind();
    }

    static VertexBuffer quad() {
        ensureBuffers();
        return quad;
    }

    static VertexBuffer sphere() {
        ensureBuffers();
        return sphereSimple;
    }

    private static void opaque() {
        if (groundUp != null && additiveBodies) {
            additive();
        } else {
            RenderSystem.disableBlend();
        }
    }

    private static void alphaBlend() {
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
    }

    static void additive() {
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE);
    }

    // ---------------------------------------------------------------- texturas

    static ResourceLocation texture(String name) {
        return CreateUniversal.id("textures/planet/" + name + ".png");
    }

    /** Enlaza una textura con mipmaps y filtrado anisótropo (sin ellos, un mapa de 4096 en un planeta pequeño parpadea). */
    static void bind(int unit, String name, boolean wrapAround) {
        bind(unit, texture(name), wrapAround);
    }

    static void bind(int unit, ResourceLocation location, boolean wrapAround) {
        AbstractTexture tex = Minecraft.getInstance().getTextureManager().getTexture(location);
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
            for (String extra : new String[] {look.night(), look.clouds(), look.normal()}) {
                if (extra != null) {
                    bind(0, extra + "_hi", true);
                }
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
        sphereDetailed = upload(sphereMesh(128, 64));
        sphereSimple = upload(sphereMesh(40, 20));
        quad = upload(quadMesh());
        Body saturn = SolarSystem.get("saturn");
        saturnRings = upload(ringMesh(saturn.ringInner(), saturn.ringOuter(), 192));
        cube = upload(cubeMesh());
    }

    private static VertexBuffer upload(MeshData mesh) {
        VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        buffer.bind();
        buffer.upload(mesh);
        VertexBuffer.unbind();
        return buffer;
    }

    /** Esfera de radio 1 con las coordenadas del mapa equirectangular: u = longitud (0 en +X, hacia el este), v = latitud. */
    private static MeshData sphereMesh(int lonSegments, int latSegments) {
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

    private static MeshData quadMesh() {
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        buf.addVertex(-1, -1, 0).setUv(0, 0);
        buf.addVertex(1, -1, 0).setUv(1, 0);
        buf.addVertex(1, 1, 0).setUv(1, 1);
        buf.addVertex(-1, 1, 0).setUv(0, 1);
        return buf.buildOrThrow();
    }

    private static MeshData cubeMesh() {
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        float[][] faces = {
                {1, -1, -1, 1, 1, -1, 1, 1, 1, 1, -1, 1}, {-1, -1, 1, -1, 1, 1, -1, 1, -1, -1, -1, -1},
                {-1, 1, -1, -1, 1, 1, 1, 1, 1, 1, 1, -1}, {-1, -1, 1, -1, -1, -1, 1, -1, -1, 1, -1, 1},
                {-1, -1, 1, 1, -1, 1, 1, 1, 1, -1, 1, 1}, {1, -1, -1, -1, -1, -1, -1, 1, -1, 1, 1, -1}};
        for (float[] f : faces) {
            for (int i = 0; i < 4; i++) {
                buf.addVertex(f[i * 3], f[i * 3 + 1], f[i * 3 + 2]).setUv(0, 0);
            }
        }
        return buf.buildOrThrow();
    }

    /**
     * Una nave del espacio vista desde la superficie de un planeta (cielo de la Tierra): caja iluminada por el Sol con su
     * tamaño aparente real, o un punto de luz si mide menos de un píxel (como la Estación Espacial vista de noche).
     */
    public static void drawShip(Matrix4f view, Matrix4f projection, Vec3 cam, Vec3 center, Vec3 halfExtents, Vec3 sunPos) {
        if (shader == null) {
            return;
        }
        ensureBuffers();
        Vec3 rel = center.subtract(cam);
        double dist = rel.length();
        if (dist < 1.0) {
            return;
        }
        double tanHalfFov = 1.0 / Math.max(1e-4, projection.m11());
        double halfHeight = Minecraft.getInstance().getWindow().getHeight() * 0.5;
        double size = Math.max(halfExtents.x, Math.max(halfExtents.y, halfExtents.z));
        double pixels = size / dist / tanHalfFov * halfHeight;
        double k = SKY_DISTANCE / dist;
        Vector3f c = vec(rel.scale(k));
        Vector3f sun = vec(sunPos.subtract(center).normalize());
        additive();
        RenderSystem.disableCull();
        if (pixels < 1.0) {
            float b = (float) Math.min(1.0, 0.25 + pixels) * (0.35F + 0.65F * nightVisibility());
            double half = SKY_DISTANCE * tanHalfFov * DOT_SIZE_PIXELS / halfHeight;
            shader.safeGetUniform("Tint").set(0.95F, 0.95F, 1.0F, b);
            set(MODE_DOT, 0, 0, 0, new Vector3f(0, 1, 0), new Vector3f(0, 0, 1));
            draw(quad, model(view, c, facingCamera(c), (float) half), projection);
        } else {
            shader.safeGetUniform("Tint").set(0.82F, 0.82F, 0.86F, 1.1F);
            set(MODE_BOX, 0, 0, 0, sun, new Vector3f());
            Matrix4f m = new Matrix4f(view).translate(c).scale((float) (halfExtents.x * k), (float) (halfExtents.y * k), (float) (halfExtents.z * k));
            draw(cube, m, projection);
        }
        shader.safeGetUniform("Tint").set(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /** Anillo plano en el ecuador (y = 0), radios en radios del planeta. */
    private static MeshData ringMesh(float inner, float outer, int segments) {
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        float in = (float) (inner * Math.cos(Math.PI / segments));
        float out = (float) (outer / Math.cos(Math.PI / segments));
        for (int i = 0; i < segments; i++) {
            double a0 = Math.PI * 2.0 * i / segments;
            double a1 = Math.PI * 2.0 * (i + 1) / segments;
            buf.addVertex((float) (Math.cos(a0) * in), 0, (float) (Math.sin(a0) * in)).setUv(0, 0.5F);
            buf.addVertex((float) (Math.cos(a0) * out), 0, (float) (Math.sin(a0) * out)).setUv(1, 0.5F);
            buf.addVertex((float) (Math.cos(a1) * out), 0, (float) (Math.sin(a1) * out)).setUv(1, 0.5F);
            buf.addVertex((float) (Math.cos(a1) * in), 0, (float) (Math.sin(a1) * in)).setUv(0, 0.5F);
        }
        return buf.buildOrThrow();
    }
}
