package dev.createuniversal.client.sky;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.createuniversal.CUConfig;
import dev.createuniversal.CreateUniversal;
import dev.createuniversal.space.CUDimensions;
import dev.createuniversal.space.SolarSystem;
import dev.createuniversal.space.SpaceRocks;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Las rocas del espacio de lejos (alpha.10, D14): cada una es una piedra de 80 caras (20 de lejos) con la textura de su
 * bloque, iluminada desde el Sol. Las que están cerca de un jugador ya son piezas de Sable con sus bloques (las dibuja
 * Sable); esas no se dibujan aquí.
 */
public final class RockRenderer {
    private static final ResourceLocation STONE = CreateUniversal.id("textures/block/asteroid_rock.png");
    private static final ResourceLocation ICE = CreateUniversal.id("textures/block/ring_ice.png");
    /** A partir de aquí, la piedra de 20 caras. */
    private static final double LOW_DETAIL = 250.0;

    private static final List<SpaceRocks.Rock> VISIBLE = new ArrayList<>();
    private static long lastRefresh = Long.MIN_VALUE;
    private static Vec3 lastRefreshPos = Vec3.ZERO;

    private static final Map<String, float[][]> MESHES = new LinkedHashMap<>(256, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, float[][]> eldest) {
            return size() > 3000;
        }
    };
    private static float[][] ico0;
    private static float[][] ico1;

    private RockRenderer() {}

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.level.dimension() != CUDimensions.SPACE) {
            return;
        }
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        double time = SpaceClock.renderTime(partial);
        Vec3 cam = event.getCamera().getPosition();
        double range = CUConfig.ROCK_RENDER_DISTANCE.get();

        // La lista de rocas cercanas se rehace cada medio segundo (o si la cámara se ha movido mucho).
        long now = mc.level.getGameTime();
        if (now - lastRefresh >= 10 || now < lastRefresh || cam.distanceTo(lastRefreshPos) > 200.0) {
            lastRefresh = now;
            lastRefreshPos = cam;
            VISIBLE.clear();
            SpaceRocks.near(cam, range + 250.0, time, VISIBLE::add);
        }
        if (VISIBLE.isEmpty()) {
            return;
        }
        double solidRange = CUConfig.ROCK_COLLISION_CHUNKS.get() * 16.0 + 90.0;
        Vec3 sun = SolarSystem.position(SolarSystem.get("sun"), time);
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        for (boolean ice : new boolean[] {false, true}) {
            RenderType type = RenderType.entitySolid(ice ? ICE : STONE);
            VertexConsumer vc = null;
            for (SpaceRocks.Rock rock : VISIBLE) {
                if (rock.field().ice() != ice) {
                    continue;
                }
                Vec3 pos = SpaceRocks.position(rock, time);
                double dist = pos.distanceTo(cam);
                double size = Math.max(rock.a(), Math.max(rock.b(), rock.c()));
                if (dist > range || size / Math.max(dist, 1.0) < 0.0015) {
                    continue;
                }
                if (dist < solidRange && isSolid(mc, pos, size)) {
                    continue;
                }
                if (vc == null) {
                    vc = buffers.getBuffer(type);
                }
                Vec3 toSun = sun.subtract(pos).normalize();
                draw(vc, pose, rock, pos.subtract(cam), SpaceRocks.orientation(rock, time), toSun, dist > LOW_DETAIL);
            }
            if (vc != null) {
                buffers.endBatch(type);
            }
        }
    }

    /** ¿Ya hay ahí una pieza de Sable (la roca sólida)? */
    private static boolean isSolid(Minecraft mc, Vec3 pos, double size) {
        BoundingBox3d box = new BoundingBox3d(pos.x - 0.5, pos.y - 0.5, pos.z - 0.5, pos.x + 0.5, pos.y + 0.5, pos.z + 0.5);
        return Sable.HELPER.getAllIntersecting(mc.level, box).iterator().hasNext();
    }

    private static void draw(VertexConsumer vc, PoseStack pose, SpaceRocks.Rock rock, Vec3 rel, Quaterniond rot, Vec3 toSun,
                             boolean low) {
        float[][] mesh = low ? icosphere(0) : MESHES.computeIfAbsent(rock.id(), id -> bumpy(rock));
        pose.pushPose();
        pose.translate(rel.x, rel.y, rel.z);
        Quaternionf q = new Quaternionf((float) rot.x, (float) rot.y, (float) rot.z, (float) rot.w);
        pose.mulPose(q);
        Matrix4f m = pose.last().pose();
        // Dirección al Sol en el espacio propio de la roca, para sombrear cada cara.
        Vector3f sunLocal = q.conjugate(new Quaternionf()).transform(new Vector3f((float) toSun.x, (float) toSun.y, (float) toSun.z));
        float sx = (float) rock.a();
        float sy = (float) rock.c();
        float sz = (float) rock.b();
        for (float[] tri : mesh) {
            // tri: 3 vértices (x, y, z) en la esfera unidad (con bultos), y la normal de la cara.
            float nx = tri[9] / sx;
            float ny = tri[10] / sy;
            float nz = tri[11] / sz;
            float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            nx /= nl;
            ny /= nl;
            nz /= nl;
            float light = Math.max(0.12F, nx * sunLocal.x + ny * sunLocal.y + nz * sunLocal.z);
            int c = (int) (255 * light);
            for (int v = 0; v < 4; v++) {
                int i = Math.min(v, 2) * 3;
                float x = tri[i] * sx;
                float y = tri[i + 1] * sy;
                float z = tri[i + 2] * sz;
                float u = (float) (Math.atan2(tri[i + 2], tri[i]) / (Math.PI * 2.0) + 0.5) * 2.0F;
                float w = tri[i + 1] * 0.5F + 0.5F;
                vc.addVertex(m, x, y, z).setColor(c, c, c, 255).setUv(u, w).setOverlay(OverlayTexture.NO_OVERLAY)
                        .setLight(LightTexture.FULL_BRIGHT).setNormal(pose.last(), nx, ny, nz);
            }
        }
        pose.popPose();
    }

    /** La piedra de cada roca: una esfera de 80 caras con bultos que salen de su semilla. */
    private static float[][] bumpy(SpaceRocks.Rock rock) {
        float[][] base = icosphere(1);
        Random rng = new Random(rock.seed());
        Map<Long, Float> offsets = new java.util.HashMap<>();
        float[][] out = new float[base.length][];
        for (int t = 0; t < base.length; t++) {
            float[] tri = new float[12];
            for (int v = 0; v < 3; v++) {
                float x = base[t][v * 3];
                float y = base[t][v * 3 + 1];
                float z = base[t][v * 3 + 2];
                long key = ((long) Math.round(x * 1000) * 31L + Math.round(y * 1000)) * 31L + Math.round(z * 1000);
                float k = offsets.computeIfAbsent(key, kk -> 0.82F + rng.nextFloat() * 0.3F);
                tri[v * 3] = x * k;
                tri[v * 3 + 1] = y * k;
                tri[v * 3 + 2] = z * k;
            }
            setNormal(tri);
            out[t] = tri;
        }
        return out;
    }

    private static void setNormal(float[] tri) {
        Vector3f a = new Vector3f(tri[3] - tri[0], tri[4] - tri[1], tri[5] - tri[2]);
        Vector3f b = new Vector3f(tri[6] - tri[0], tri[7] - tri[1], tri[8] - tri[2]);
        Vector3f n = a.cross(b);
        float cx = (tri[0] + tri[3] + tri[6]) / 3F;
        float cy = (tri[1] + tri[4] + tri[7]) / 3F;
        float cz = (tri[2] + tri[5] + tri[8]) / 3F;
        if (n.x * cx + n.y * cy + n.z * cz < 0) {
            n.negate();
            // Al revés: se cambia el orden para que mire hacia fuera.
            float[] tmp = {tri[3], tri[4], tri[5]};
            tri[3] = tri[6];
            tri[4] = tri[7];
            tri[5] = tri[8];
            tri[6] = tmp[0];
            tri[7] = tmp[1];
            tri[8] = tmp[2];
        }
        n.normalize();
        tri[9] = n.x;
        tri[10] = n.y;
        tri[11] = n.z;
    }

    /** Icosaedro (0) o icosaedro subdividido una vez (1), en la esfera unidad. */
    private static float[][] icosphere(int level) {
        if (level == 0 && ico0 != null) {
            return ico0;
        }
        if (level == 1 && ico1 != null) {
            return ico1;
        }
        float t = (float) ((1.0 + Math.sqrt(5.0)) / 2.0);
        float[][] v = {{-1, t, 0}, {1, t, 0}, {-1, -t, 0}, {1, -t, 0}, {0, -1, t}, {0, 1, t}, {0, -1, -t}, {0, 1, -t},
                {t, 0, -1}, {t, 0, 1}, {-t, 0, -1}, {-t, 0, 1}};
        int[][] f = {{0, 11, 5}, {0, 5, 1}, {0, 1, 7}, {0, 7, 10}, {0, 10, 11}, {1, 5, 9}, {5, 11, 4}, {11, 10, 2}, {10, 7, 6},
                {7, 1, 8}, {3, 9, 4}, {3, 4, 2}, {3, 2, 6}, {3, 6, 8}, {3, 8, 9}, {4, 9, 5}, {2, 4, 11}, {6, 2, 10}, {8, 6, 7}, {9, 8, 1}};
        List<float[][]> tris = new ArrayList<>();
        for (int[] face : f) {
            tris.add(new float[][] {norm(v[face[0]]), norm(v[face[1]]), norm(v[face[2]])});
        }
        for (int i = 0; i < level; i++) {
            List<float[][]> next = new ArrayList<>();
            for (float[][] tri : tris) {
                float[] ab = norm(mid(tri[0], tri[1]));
                float[] bc = norm(mid(tri[1], tri[2]));
                float[] ca = norm(mid(tri[2], tri[0]));
                next.add(new float[][] {tri[0], ab, ca});
                next.add(new float[][] {tri[1], bc, ab});
                next.add(new float[][] {tri[2], ca, bc});
                next.add(new float[][] {ab, bc, ca});
            }
            tris = next;
        }
        float[][] out = new float[tris.size()][];
        for (int i = 0; i < tris.size(); i++) {
            float[][] tri = tris.get(i);
            float[] flat = new float[12];
            for (int k = 0; k < 3; k++) {
                flat[k * 3] = tri[k][0];
                flat[k * 3 + 1] = tri[k][1];
                flat[k * 3 + 2] = tri[k][2];
            }
            setNormal(flat);
            out[i] = flat;
        }
        if (level == 0) {
            ico0 = out;
        } else {
            ico1 = out;
        }
        return out;
    }

    private static float[] mid(float[] a, float[] b) {
        return new float[] {(a[0] + b[0]) * 0.5F, (a[1] + b[1]) * 0.5F, (a[2] + b[2]) * 0.5F};
    }

    private static float[] norm(float[] a) {
        float l = (float) Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
        return new float[] {a[0] / l, a[1] / l, a[2] / l};
    }
}
