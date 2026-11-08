package dev.createuniversal.mixin;

import dev.createuniversal.space.CUDimensions;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Sable borra las naves cuya altura sale de [sub_level_remove_min, sub_level_remove_max] (por defecto -10.000 y 100.000).
 * Es el "vacío" de las naves; en un espacio sin bordes (D3) una nave a la deriva acabaría borrada, así que en el espacio
 * los límites son infinitos. En el resto de dimensiones se respeta la configuración de Sable.
 */
@Mixin(value = ServerSubLevel.class, remap = false)
public abstract class ServerSubLevelSpaceBoundsMixin {
    @Redirect(method = "tick",
            at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/common/ModConfigSpec$DoubleValue;getAsDouble()D", ordinal = 0))
    private double create_universal$noMinInSpace(ModConfigSpec.DoubleValue value) {
        return inSpace() ? Double.NEGATIVE_INFINITY : value.getAsDouble();
    }

    @Redirect(method = "tick",
            at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/common/ModConfigSpec$DoubleValue;getAsDouble()D", ordinal = 1))
    private double create_universal$noMaxInSpace(ModConfigSpec.DoubleValue value) {
        return inSpace() ? Double.POSITIVE_INFINITY : value.getAsDouble();
    }

    private boolean inSpace() {
        return ((ServerSubLevel) (Object) this).getLevel().dimension() == CUDimensions.SPACE;
    }
}
