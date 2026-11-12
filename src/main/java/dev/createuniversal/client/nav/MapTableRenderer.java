package dev.createuniversal.client.nav;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.createuniversal.client.sky.SpaceClock;
import dev.createuniversal.network.DebrisMarkersPayload;
import dev.createuniversal.registry.MapTableBlockEntity;
import dev.createuniversal.space.CUDimensions;
import dev.createuniversal.space.Navigation;
import dev.createuniversal.space.SolarSystem;
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
 * La superficie de la mesa de mapa (alpha.11.1): el mapa en directo, con la misma vista que la última vez que se abrió (y
 * siguiendo al jugador si estaba en "seguir"). En mesas de 2×2 o más, también los nombres.
 */
public class MapTableRenderer implements BlockEntityRenderer<MapTableBlockEntity> {
    private static final float TOP = 1.0F;

    public MapTableRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public boolean shouldRenderOffScreen(MapTableBlockEntity be) {
        return true;
    }

    @Override
    public AABB getRenderBoundingBox(MapTableBlockEntity be) {
        return new AABB(be.getBlockPos()).expandTowards(be.size(), 1, be.size());
    }

    @Override
    public void render(MapTableBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        if (!be.isMaster()) {
            return;
        }
        int size = be.size();
        double time = SpaceClock.renderTime(partialTick);
        Vec3 player = NavClient.playerPosition(time);
        double[] view = NavigationScreen.view(player);
        double cx = view[0];
        double cz = view[1];
        double span = view[2];
        double perBlock = span / size;

        VertexConsumer vc = buffers.getBuffer(RenderType.debugQuads());
        Matrix4f m = pose.last().pose();
        quad(vc, m, 0, 0, size, size, TOP + 0.001F, 0xFF0A1020);
        float y = TOP + 0.002F;

        // Coordenadas de la mesa: u hacia el este, v hacia el sur (como el mapa, con el norte arriba).
        java.util.function.DoubleUnaryOperator u = x -> (x - cx) / perBlock + size / 2.0;
        java.util.function.DoubleUnaryOperator v = z -> (z - cz) / perBlock + size / 2.0;
        float dot = (float) Math.max(0.012, 0.012 * size);

        for (SolarSystem.Body body : SolarSystem.bodies()) {
            if (body.parent() == null || !NavClient.isDiscovered(body.id())) {
                continue;
            }
            double orbitBlocks = body.orbitRadius() / perBlock;
            if (orbitBlocks < 0.08) {
                continue;
            }
            SolarSystem.Body parent = SolarSystem.get(body.parent());
            Vec3 parentNow = SolarSystem.position(parent, time);
            double period = Math.abs(body.periodDays()) * 24000.0;
            int n = (int) Math.max(24, Math.min(160, orbitBlocks * 40));
            for (int k = 0; k < n; k++) {
                double t = time + period * k / n;
                Vec3 rel = SolarSystem.position(body, t).subtract(SolarSystem.position(parent, t));
                square(vc, m, u.applyAsDouble(parentNow.x + rel.x), v.applyAsDouble(parentNow.z + rel.z), dot * 0.5, y, size, 0x66FFFFFF);
            }
        }
        for (SolarSystem.Body body : SolarSystem.bodies()) {
            if (body.parent() != null && !body.parent().equals("sun") && body.orbitRadius() / perBlock < 0.08) {
                continue;
            }
            boolean known = NavClient.isDiscovered(body.id());
            Vec3 p = SolarSystem.position(body, time);
            double r = Math.max(dot * 1.6, Math.min(size, body.radius() / perBlock));
            int color = known ? NavigationScreen.colorOf(body.id()) : 0xFF707070;
            square(vc, m, u.applyAsDouble(p.x), v.applyAsDouble(p.z), r, y + 0.0005F, size, color);
        }
        Minecraft mc = Minecraft.getInstance();
        for (DebrisMarkersPayload.Marker d : dev.createuniversal.client.effects.DebrisMarkers.markers()) {
            if (mc.level != null && d.dimension().equals(CUDimensions.SPACE.location()) && d.expires() > mc.level.getGameTime()) {
                square(vc, m, u.applyAsDouble(d.x()), v.applyAsDouble(d.z()), dot * 2.0, y + 0.0008F, size, 0xFFFF8A3D);
            }
        }
        for (dev.createuniversal.network.ParkedShipsPayload.Ship s : NavClient.spaceShips()) {
            square(vc, m, u.applyAsDouble(s.x()), v.applyAsDouble(s.z()), dot * 2.2, y + 0.0008F, size, 0xFF7CFF8A);
        }
        for (Navigation.Marker mk : NavClient.markers()) {
            square(vc, m, u.applyAsDouble(mk.x()), v.applyAsDouble(mk.z()), dot * 2.0, y + 0.0008F, size, 0xFF58E1FF);
        }
        double pu = u.applyAsDouble(player.x);
        double pv = v.applyAsDouble(player.z);
        rect(vc, m, pu - dot * 3, pv - dot * 0.5, pu + dot * 3, pv + dot * 0.5, y + 0.001F, size, 0xFFFFFFFF);
        rect(vc, m, pu - dot * 0.5, pv - dot * 3, pu + dot * 0.5, pv + dot * 3, y + 0.001F, size, 0xFFFFFFFF);

        if (size >= 2) {
            labels(pose, buffers, time, u, v, size, perBlock);
        }
    }

    private static void labels(PoseStack pose, MultiBufferSource buffers, double time, java.util.function.DoubleUnaryOperator u,
                               java.util.function.DoubleUnaryOperator v, int size, double perBlock) {
        Font font = Minecraft.getInstance().font;
        float scale = 0.0035F * size;
        for (SolarSystem.Body body : SolarSystem.bodies()) {
            if (!NavClient.isDiscovered(body.id())) {
                continue;
            }
            if (body.parent() != null && !body.parent().equals("sun") && body.orbitRadius() / perBlock < 0.25) {
                continue;
            }
            Vec3 p = SolarSystem.position(body, time);
            double x = u.applyAsDouble(p.x);
            double z = v.applyAsDouble(p.z);
            if (x < 0 || z < 0 || x > size || z > size) {
                continue;
            }
            pose.pushPose();
            pose.translate(x + 0.05, TOP + 0.004, z);
            // Tumbado sobre la mesa, leyéndose desde arriba (el norte del mapa hacia el norte del mundo).
            pose.mulPose(Axis.XP.rotationDegrees(90));
            pose.scale(scale, scale, -scale);
            Component name = Component.translatable("create_universal.body." + body.id());
            font.drawInBatch(name, 0, -4, 0xFFDDDDDD, false, pose.last().pose(), buffers, Font.DisplayMode.NORMAL, 0,
                    LightTexture.FULL_BRIGHT);
            pose.popPose();
        }
    }

    /** Un cuadradito centrado en (cu, cv) de medio lado {@code half}, recortado a la mesa. */
    private static void square(VertexConsumer vc, Matrix4f m, double cu, double cv, double half, float y, int size, int color) {
        rect(vc, m, cu - half, cv - half, cu + half, cv + half, y, size, color);
    }

    private static void rect(VertexConsumer vc, Matrix4f m, double u0, double v0, double u1, double v1, float y, int size, int color) {
        float a = (float) Math.max(0, u0);
        float b = (float) Math.max(0, v0);
        float c = (float) Math.min(size, u1);
        float d = (float) Math.min(size, v1);
        if (c <= a || d <= b) {
            return;
        }
        quad(vc, m, a, b, c, d, y, color);
    }

    private static void quad(VertexConsumer vc, Matrix4f m, float u0, float v0, float u1, float v1, float y, int color) {
        vc.addVertex(m, u0, y, v0).setColor(color);
        vc.addVertex(m, u0, y, v1).setColor(color);
        vc.addVertex(m, u1, y, v1).setColor(color);
        vc.addVertex(m, u1, y, v0).setColor(color);
    }
}
