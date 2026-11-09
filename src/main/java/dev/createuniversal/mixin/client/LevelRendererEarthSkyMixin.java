package dev.createuniversal.mixin.client;

import com.mojang.blaze3d.vertex.VertexBuffer;
import dev.createuniversal.client.sky.EarthSky;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.level.material.FogType;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cielo real en el Overworld ({@link EarthSky}). Se dibuja el cielo entero en lugar del de Minecraft: el mismo color,
 * amaneceres y disco oscuro que Minecraft, pero con el Sol, la Luna, los planetas y las estrellas de verdad.
 *
 * Va al principio de {@code renderSky} con prioridad alta porque Atmospherics también cancela {@code renderSky} para
 * dibujar el suyo (con el Sol cuadrado y la Luna de Minecraft); así el nuestro va primero. Con un paquete de shaders de
 * Iris no se toca nada: el paquete pinta su propio cielo.
 */
@Mixin(value = LevelRenderer.class, priority = 500)
public abstract class LevelRendererEarthSkyMixin {
    @Shadow
    private VertexBuffer skyBuffer;
    @Shadow
    private VertexBuffer darkBuffer;
    @Shadow
    private ClientLevel level;
    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    protected abstract boolean doesMobEffectBlockSky(Camera camera);

    @Inject(method = "renderSky", at = @At("HEAD"), cancellable = true)
    private void create_universal$realEarthSky(Matrix4f frustumMatrix, Matrix4f projectionMatrix, float partialTick, Camera camera,
                                               boolean isFoggy, Runnable skyFogSetup, CallbackInfo ci) {
        if (level == null || level.effects().skyType() != DimensionSpecialEffects.SkyType.NORMAL || !EarthSky.active()) {
            return;
        }
        ci.cancel();
        skyFogSetup.run();
        if (isFoggy) {
            return;
        }
        FogType fluid = camera.getFluidInCamera();
        if (fluid == FogType.POWDER_SNOW || fluid == FogType.LAVA || doesMobEffectBlockSky(camera)) {
            return;
        }
        EarthSky.renderSky(frustumMatrix, projectionMatrix, partialTick, skyBuffer, darkBuffer, skyFogSetup);
    }
}
