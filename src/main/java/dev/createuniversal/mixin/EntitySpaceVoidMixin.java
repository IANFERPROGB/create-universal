package dev.createuniversal.mixin;

import dev.createuniversal.space.CUDimensions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * En el espacio no existe "caer fuera del mundo": por debajo de la altura mínima no hay daño del vacío ni se borran
 * entidades ni objetos. En el Overworld todo sigue igual.
 */
@Mixin(Entity.class)
public abstract class EntitySpaceVoidMixin {
    @Shadow
    public abstract Level level();

    @Inject(method = "checkBelowWorld", at = @At("HEAD"), cancellable = true)
    private void create_universal$noVoidInSpace(CallbackInfo ci) {
        Level level = level();
        if (level != null && level.dimension() == CUDimensions.SPACE) {
            ci.cancel();
        }
    }
}
