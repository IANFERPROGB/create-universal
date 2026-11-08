package dev.createuniversal.mixin;

import dev.createuniversal.space.CUDimensions;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * En el espacio no hay aire que frene: jugadores y mobs que no pisan suelo conservan todo su impulso.
 * Minecraft ya trae {@code shouldDiscardFriction()}: si devuelve true, {@code LivingEntity.travel} aplica el movimiento
 * sin el 0,91 horizontal ni el 0,98 vertical de cada tick. Sobre el suelo de una nave el rozamiento sigue igual.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntitySpaceFrictionMixin {
    @Inject(method = "shouldDiscardFriction", at = @At("RETURN"), cancellable = true)
    private void create_universal$noAirFrictionInSpace(CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!cir.getReturnValueZ() && !self.onGround() && self.level().dimension() == CUDimensions.SPACE) {
            cir.setReturnValue(true);
        }
    }
}
