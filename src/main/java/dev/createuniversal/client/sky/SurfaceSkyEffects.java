package dev.createuniversal.client.sky;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.createuniversal.space.surface.PlanetSurface;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/** Efectos de las dimensiones de la Luna y de Marte: su cielo ({@link SurfaceSky}), sin nubes ni lluvia, y su niebla. */
public final class SurfaceSkyEffects extends DimensionSpecialEffects {
    private final PlanetSurface surface;

    public SurfaceSkyEffects(PlanetSurface surface) {
        super(Float.NaN, false, SkyType.NONE, false, false);
        this.surface = surface;
    }

    /**
     * Color de la niebla del horizonte: negro en la Luna (no hay aire); en Marte, el caramelo del cielo de día, que se
     * apaga de noche.
     */
    @Override
    public Vec3 getBrightnessDependentFogColor(Vec3 fogColor, float brightness) {
        if (surface.pressure() <= 0.0) {
            return Vec3.ZERO;
        }
        Minecraft mc = Minecraft.getInstance();
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        float day = SurfaceSky.daylight(surface, SpaceClock.renderTime(1.0F), cam.x, cam.z);
        return new Vec3(0.72, 0.52, 0.36).scale(0.04 + 0.96 * day);
    }

    @Override
    public boolean isFoggyAt(int x, int y) {
        return false;
    }

    @Override
    public boolean renderSky(ClientLevel level, int ticks, float partialTick, Matrix4f modelViewMatrix, Camera camera,
                             Matrix4f projectionMatrix, boolean isFoggy, Runnable setupFog) {
        FogRenderer.setupNoFog();
        SpaceSky.renderSkyPass(modelViewMatrix, projectionMatrix, camera, partialTick,
                (view, projection, cam, partial, farDepth) -> SurfaceSky.paint(surface, view, projection, cam, partial, farDepth));
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.disableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(true);
        setupFog.run();
        return true;
    }

    @Override
    public boolean renderClouds(ClientLevel level, int ticks, float partialTick, PoseStack poseStack, double camX, double camY, double camZ,
                                Matrix4f modelViewMatrix, Matrix4f projectionMatrix) {
        return true;
    }

    @Override
    public boolean renderSnowAndRain(ClientLevel level, int ticks, float partialTick, LightTexture lightTexture, double camX, double camY, double camZ) {
        return true;
    }
}
