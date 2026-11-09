package dev.createuniversal.mixin;

import dev.createuniversal.space.surface.PlanetSurface;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Con menos gravedad se cae más despacio: el daño por caída se escala con la gravedad del astro (Luna 1/6, Marte 0,38). */
@Mixin(LivingEntity.class)
public abstract class EntityPlanetFallMixin {
    @ModifyVariable(method = "causeFallDamage", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private float create_universal$lowGravityFall(float fallDistance) {
        PlanetSurface surface = PlanetSurface.byDimension(((LivingEntity) (Object) this).level().dimension());
        return surface != null ? (float) (fallDistance * surface.gravity()) : fallDistance;
    }
}
