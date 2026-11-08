package dev.createuniversal.mixin.client;

import dev.createuniversal.client.sky.SpaceSky;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Con un paquete de shaders de Iris el cielo del espacio se dibuja aquí, cuando el mundo ya está terminado (ver
 * {@code IrisCompat}): Iris no deja dibujar con shaders de otros mods mientras pinta el mundo y repinta el cielo con el suyo.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererSpaceSkyMixin {
    @Inject(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",
            shift = At.Shift.AFTER))
    private void create_universal$spaceSkyAfterShaders(DeltaTracker deltaTracker, CallbackInfo ci) {
        SpaceSky.renderAfterLevel();
    }
}
