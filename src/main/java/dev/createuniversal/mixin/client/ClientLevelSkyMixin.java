package dev.createuniversal.mixin.client;

import dev.createuniversal.client.effects.AltitudeAtmosphere;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Cielo que se oscurece con la altura (idea 1). Prioridad alta para actuar después de otros mods que cambian el color del
 * cielo (Atmospherics lo hace en el mismo punto): el resultado final siempre se lleva hacia el negro del espacio.
 */
@Mixin(value = ClientLevel.class, priority = 1500)
public abstract class ClientLevelSkyMixin {
    @Inject(method = "getSkyColor", at = @At("RETURN"), cancellable = true)
    private void create_universal$darkenSkyWithAltitude(Vec3 pos, float partialTick, CallbackInfoReturnable<Vec3> cir) {
        float f = AltitudeAtmosphere.fade();
        if (f > 0.0F) {
            cir.setReturnValue(cir.getReturnValue().scale(1.0F - f));
        }
    }

    @Inject(method = "getStarBrightness", at = @At("RETURN"), cancellable = true)
    private void create_universal$starsWithAltitude(float partialTick, CallbackInfoReturnable<Float> cir) {
        float f = AltitudeAtmosphere.fade();
        if (f > 0.0F) {
            cir.setReturnValue(Math.max(cir.getReturnValueF(), f));
        }
    }

    /**
     * En la Luna y en Marte la luz del día depende de dónde esté el Sol sobre ese punto del globo, no de la hora de
     * Minecraft: de noche oscurece de verdad (alpha.5).
     */
    @Inject(method = "getSkyDarken", at = @At("RETURN"), cancellable = true)
    private void create_universal$surfaceDaylight(float partialTick, CallbackInfoReturnable<Float> cir) {
        ClientLevel self = (ClientLevel) (Object) this;
        dev.createuniversal.space.surface.PlanetSurface surface = dev.createuniversal.space.surface.PlanetSurface.byDimension(self.dimension());
        if (surface == null) {
            return;
        }
        Vec3 cam = net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        float day = dev.createuniversal.client.sky.SurfaceSky.daylight(surface,
                dev.createuniversal.client.sky.SpaceClock.renderTime(partialTick), cam.x, cam.z);
        cir.setReturnValue(0.2F + 0.8F * day);
    }
}
