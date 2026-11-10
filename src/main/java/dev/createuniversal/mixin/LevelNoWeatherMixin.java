package dev.createuniversal.mixin;

import dev.createuniversal.space.CUDimensions;
import dev.createuniversal.space.surface.PlanetSurface;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * El espacio y los astros no comparten el tiempo del Overworld. Minecraft hace que todas las dimensiones con cielo
 * lluevan cuando llueve en el Overworld: en la Luna se oscurecía el cielo y en las lunas heladas se acumulaba nieve
 * (alpha.6, prueba en Europa).
 */
@Mixin(Level.class)
public abstract class LevelNoWeatherMixin {
    @Shadow
    public abstract ResourceKey<Level> dimension();

    private boolean createUniversal$noWeather() {
        ResourceKey<Level> dim = dimension();
        return dim == CUDimensions.SPACE || PlanetSurface.byDimension(dim) != null;
    }

    @Inject(method = "getRainLevel", at = @At("HEAD"), cancellable = true)
    private void createUniversal$noRain(float partialTick, CallbackInfoReturnable<Float> cir) {
        if (createUniversal$noWeather()) {
            cir.setReturnValue(0.0F);
        }
    }

    @Inject(method = "getThunderLevel", at = @At("HEAD"), cancellable = true)
    private void createUniversal$noThunder(float partialTick, CallbackInfoReturnable<Float> cir) {
        if (createUniversal$noWeather()) {
            cir.setReturnValue(0.0F);
        }
    }
}
