package dev.createuniversal.client.nav;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.createuniversal.client.sky.SpaceClock;
import dev.createuniversal.registry.HoloProjectorBlockEntity;
import dev.createuniversal.space.CUDimensions;
import dev.createuniversal.space.Navigation;
import dev.createuniversal.space.SolarSystem;
import dev.createuniversal.space.surface.PlanetSurface;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * El holograma del proyector (D46, alpha.11.2): el sistema solar en 3D encima del bloque, con los astros en su sitio real de
 * ahora (con la inclinación de sus órbitas), las órbitas punteadas, el jugador, sus marcadores y los nombres mirando a la
 * cámara. Tres vistas: la del mapa, el sistema solar entero (distancias comprimidas para que quepa todo) y alrededor del
 * jugador (su astro y sus lunas).
 */
public class HoloProjectorRenderer implements BlockEntityRenderer<HoloProjectorBlockEntity> {
    private static final int CYAN = 0x6058E1FF;
    /** Sistema entero: distancia al Sol → radio del holograma (logarítmico). */
    private static final double R0 = 40_000.0;
    private static final double RMAX = 1_050_000.0;

    public HoloProjectorRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public boolean shouldRenderOffScreen(HoloProjectorBlockEntity be) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 96;
    }

    @Override
    public AABB getRenderBoundingBox(HoloProjectorBlockEntity be) {
        return new AABB(be.getBlockPos()).inflate(be.size() + 1.5);
    }

    /** Cómo pasa un punto del espacio al holograma (coordenadas relativas al centro del holograma). */
    private interface Projection {
        Vec3 map(Vec3 world);
    }

    @Override
    public void render(HoloProjectorBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        int size = be.size();
        double time = SpaceClock.renderTime(partialTick);
        Vec3 player = NavClient.playerPosition(time);
        Minecraft mc = Minecraft.getInstance();

        // Vista elegida.
        int view = be.view();
        SolarSystem.Body home = null;
        if (view == HoloProjectorBlockEntity.VIEW_LOCAL) {
            home = homeBody(mc, player, time);
            if (home == null || home.isSun()) {
                view = HoloProjectorBlockEntity.VIEW_SYSTEM;
            }
        }
        Projection proj;
        double bodyScale;
        if (view == HoloProjectorBlockEntity.VIEW_SYSTEM) {
            Vec3 sun = SolarSystem.position(SolarSystem.get("sun"), time);
            double norm = size / Math.log(1.0 + RMAX / R0);
            proj = w -> {
                Vec3 d = w.subtract(sun);
                double r = d.length();
                return r < 1e-6 ? Vec3.ZERO : d.scale(Math.log(1.0 + r / R0) * norm / r);
            };
            bodyScale = 0.0;
        } else {
            Vec3 c;
            double span;
            if (view == HoloProjectorBlockEntity.VIEW_LOCAL) {
                c = SolarSystem.position(home, time);
                double reach = home.radius() * 6.0;
                for (SolarSystem.Body b : SolarSystem.bodies()) {
                    if (home.id().equals(b.parent())) {
                        reach = Math.max(reach, b.orbitRadius() * 1.15);
                    }
                }
                span = reach * 2.0;
            } else {
                double[] v = NavigationScreen.view(player);
                c = new Vec3(v[0], SolarSystem.ECLIPTIC_Y, v[1]);
                span = v[2];
            }
            double k = 2.0 * size / span;
            proj = w -> w.subtract(c).scale(k);
            bodyScale = k;
        }

        pose.pushPose();
        pose.translate(0.5, 0.75 + size * 0.45, 0.5);
        VertexConsumer vc = buffers.getBuffer(RenderType.debugQuads());
        Matrix4f m = pose.last().pose();
        float flicker = 0.85F + 0.15F * (float) Math.sin(time * 0.37);
        float limit = size * 1.08F;

        // Disco tenue del plano de las órbitas.
        for (int k = 0; k < 96; k++) {
            double a = 2.0 * Math.PI * k / 96;
            flat(vc, m, (float) (Math.cos(a) * size), 0F, (float) (Math.sin(a) * size), 0.01F * size, CYAN);
        }

        for (SolarSystem.Body body : SolarSystem.bodies()) {
            if (body.parent() == null || !NavClient.isDiscovered(body.id())) {
                continue;
            }
            SolarSystem.Body parent = SolarSystem.get(body.parent());
            Vec3 pc = proj.map(SolarSystem.position(parent, time));
            Vec3 bc = proj.map(SolarSystem.position(body, time));
            if (pc.distanceTo(bc) < 0.06 * size || pc.length() > limit) {
                continue;
            }
            double period = Math.abs(body.periodDays()) * 24000.0;
            Vec3 parentNow = SolarSystem.position(parent, time);
            int n = (int) Math.max(32, Math.min(128, pc.distanceTo(bc) * 60));
            for (int k = 0; k < n; k++) {
                double t = time + period * k / n;
                Vec3 rel = SolarSystem.position(body, t).subtract(SolarSystem.position(parent, t));
                Vec3 p = proj.map(parentNow.add(rel));
                if (p.length() <= limit) {
                    flat(vc, m, (float) p.x, (float) p.y, (float) p.z, 0.006F * size, alpha(0x80FFFFFF, flicker));
                }
            }
        }
        for (SolarSystem.Body body : SolarSystem.bodies()) {
            boolean known = NavClient.isDiscovered(body.id());
            Vec3 p = proj.map(SolarSystem.position(body, time));
            if (p.length() > limit) {
                continue;
            }
            if (body.parent() != null && !body.parent().equals("sun")) {
                Vec3 pp = proj.map(SolarSystem.position(SolarSystem.get(body.parent()), time));
                if (pp.distanceTo(p) < 0.06 * size) {
                    continue;
                }
            }
            float s;
            if (bodyScale > 0) {
                s = (float) Math.max(0.018 * size, body.radius() * bodyScale);
            } else {
                s = (float) (size * (body.isSun() ? 0.06 : 0.012 + 0.006 * Math.log10(Math.max(10.0, body.radius()))));
            }
            s = Math.min(s, size * 0.5F);
            int color = known ? NavigationScreen.colorOf(body.id()) : 0xFF707070;
            cube(vc, m, (float) p.x, (float) p.y, (float) p.z, s, alpha(color, flicker));
        }
        for (dev.createuniversal.network.DebrisMarkersPayload.Marker d : dev.createuniversal.client.effects.DebrisMarkers.markers()) {
            if (mc.level != null && d.dimension().equals(CUDimensions.SPACE.location()) && d.expires() > mc.level.getGameTime()) {
                Vec3 p = proj.map(new Vec3(d.x(), d.y(), d.z()));
                if (p.length() <= limit) {
                    cube(vc, m, (float) p.x, (float) p.y, (float) p.z, 0.018F * size, alpha(0xFFFF8A3D, flicker));
                }
            }
        }
        for (Navigation.Marker mk : NavClient.markers()) {
            Vec3 p = proj.map(new Vec3(mk.x(), mk.y(), mk.z()));
            if (p.length() <= limit) {
                cube(vc, m, (float) p.x, (float) p.y, (float) p.z, 0.02F * size, alpha(0xFF58E1FF, flicker));
            }
        }
        Vec3 me = proj.map(player);
        if (me.length() <= limit) {
            cube(vc, m, (float) me.x, (float) me.y, (float) me.z, 0.025F * size, 0xFFFFFFFF);
            cube(vc, m, (float) me.x, (float) me.y + 0.06F * size, (float) me.z, 0.008F * size, 0xFFFFFFFF);
        }

        if (be.names()) {
            labels(pose, buffers, proj, time, limit, size);
        }
        pose.popPose();
    }

    private static SolarSystem.Body homeBody(Minecraft mc, Vec3 player, double time) {
        if (mc.level == null) {
            return null;
        }
        if (mc.level.dimension() == CUDimensions.SPACE) {
            SolarSystem.Body b = SolarSystem.frameBody(player, time);
            // En la zona de una luna, mejor su planeta con todas sus lunas.
            if (b != null && b.parent() != null && !b.parent().equals("sun")) {
                b = SolarSystem.get(b.parent());
            }
            return b;
        }
        PlanetSurface s = PlanetSurface.byDimension(mc.level.dimension());
        SolarSystem.Body b = SolarSystem.get(s != null ? s.bodyId() : "earth");
        if (b != null && b.parent() != null && !b.parent().equals("sun")) {
            b = SolarSystem.get(b.parent());
        }
        return b;
    }

    private static void labels(PoseStack pose, MultiBufferSource buffers, Projection proj, double time, float limit, int size) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        float scale = 0.0045F * (float) Math.sqrt(size);
        for (SolarSystem.Body body : SolarSystem.bodies()) {
            if (!NavClient.isDiscovered(body.id())) {
                continue;
            }
            Vec3 p = proj.map(SolarSystem.position(body, time));
            if (p.length() > limit) {
                continue;
            }
            if (body.parent() != null && !body.parent().equals("sun")) {
                Vec3 pp = proj.map(SolarSystem.position(SolarSystem.get(body.parent()), time));
                if (pp.distanceTo(p) < 0.12 * size) {
                    continue;
                }
            }
            pose.pushPose();
            pose.translate(p.x, p.y + 0.05 * size, p.z);
            pose.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
            pose.scale(scale, -scale, scale);
            Component name = Component.translatable("create_universal.body." + body.id());
            font.drawInBatch(name, -font.width(name) / 2.0F, 0, 0xFFBFF4FF, false, pose.last().pose(), buffers,
                    Font.DisplayMode.SEE_THROUGH, 0, LightTexture.FULL_BRIGHT);
            pose.popPose();
        }
    }

    private static int alpha(int argb, float f) {
        int a = (int) (((argb >>> 24) & 0xFF) * f);
        return (a << 24) | (argb & 0xFFFFFF);
    }

    /** Un cuadradito horizontal (puntos de las órbitas y del disco). */
    private static void flat(VertexConsumer vc, Matrix4f m, float x, float y, float z, float h, int color) {
        vc.addVertex(m, x - h, y, z - h).setColor(color);
        vc.addVertex(m, x - h, y, z + h).setColor(color);
        vc.addVertex(m, x + h, y, z + h).setColor(color);
        vc.addVertex(m, x + h, y, z - h).setColor(color);
    }

    private static void cube(VertexConsumer vc, Matrix4f m, float x, float y, float z, float h, int color) {
        float x0 = x - h, x1 = x + h, y0 = y - h, y1 = y + h, z0 = z - h, z1 = z + h;
        quad(vc, m, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, color);
        quad(vc, m, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, shade(color, 0.55F));
        quad(vc, m, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, shade(color, 0.8F));
        quad(vc, m, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, shade(color, 0.8F));
        quad(vc, m, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, shade(color, 0.7F));
        quad(vc, m, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, shade(color, 0.7F));
    }

    private static int shade(int argb, float f) {
        int r = (int) (((argb >> 16) & 0xFF) * f);
        int g = (int) (((argb >> 8) & 0xFF) * f);
        int b = (int) ((argb & 0xFF) * f);
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    private static void quad(VertexConsumer vc, Matrix4f m, float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz, int color) {
        vc.addVertex(m, ax, ay, az).setColor(color);
        vc.addVertex(m, bx, by, bz).setColor(color);
        vc.addVertex(m, cx, cy, cz).setColor(color);
        vc.addVertex(m, dx, dy, dz).setColor(color);
    }
}
