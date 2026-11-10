package dev.createuniversal.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.createuniversal.client.effects.AltitudeAtmosphere;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Niebla y color de fondo que se oscurecen y se alejan con la altura (idea 1).
 *
 * Va al final de {@code setupColor} y {@code setupFog} con prioridad alta porque Atmospherics vuelve a calcular el color
 * y la distancia de la niebla en ese mismo punto (y su niebla del atardecer no respeta su interruptor general); con el
 * evento de NeoForge el resultado quedaba tapado y el fondo salía celeste. Así este es siempre el último cambio.
 */
@Mixin(value = FogRenderer.class, priority = 2000)
public abstract class FogRendererAltitudeMixin {
    @Shadow
    private static float fogRed;
    @Shadow
    private static float fogGreen;
    @Shadow
    private static float fogBlue;

    @Inject(method = "setupColor", at = @At("TAIL"))
    private static void create_universal$darkenFogWithAltitude(Camera camera, float partialTick, ClientLevel level,
                                                               int renderDistanceChunks, float darkenWorldAmount, CallbackInfo ci) {
        // En el espacio y donde no hay aire, fondo negro siempre. Si el cliente aún no tiene el chunk de la cámara (yendo
        // deprisa), Minecraft toma el bioma de llanura y su cielo azul teñía todo un instante.
        if (level.dimension() == dev.createuniversal.space.CUDimensions.SPACE) {
            create_universal$black();
            return;
        }
        dev.createuniversal.space.surface.PlanetSurface surface = dev.createuniversal.space.surface.PlanetSurface.byDimension(level.dimension());
        if (surface != null && surface.airless()) {
            create_universal$black();
            return;
        }
        float f = AltitudeAtmosphere.fade();
        if (f <= 0.0F) {
            return;
        }
        float keep = 1.0F - f;
        fogRed *= keep;
        fogGreen *= keep;
        fogBlue *= keep;
        RenderSystem.clearColor(fogRed, fogGreen, fogBlue, 0.0F);
    }

    private static void create_universal$black() {
        fogRed = 0.0F;
        fogGreen = 0.0F;
        fogBlue = 0.0F;
        RenderSystem.clearColor(0.0F, 0.0F, 0.0F, 0.0F);
    }

    @Inject(method = "setupFog", at = @At("TAIL"))
    private static void create_universal$pushFogAwayWithAltitude(Camera camera, FogRenderer.FogMode fogMode, float farPlaneDistance,
                                                                boolean shouldCreateFog, float partialTick, CallbackInfo ci) {
        float f = AltitudeAtmosphere.fade();
        if (f <= 0.0F) {
            return;
        }
        float scale = 1.0F + f * 4.0F;
        RenderSystem.setShaderFogStart(RenderSystem.getShaderFogStart() * scale);
        RenderSystem.setShaderFogEnd(RenderSystem.getShaderFogEnd() * scale);
    }
}
