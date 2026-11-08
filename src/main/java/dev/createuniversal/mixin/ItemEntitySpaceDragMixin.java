package dev.createuniversal.mixin;

import dev.createuniversal.space.CUDimensions;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Un objeto tirado en el espacio sigue moviéndose para siempre en la misma dirección.
 * {@code ItemEntity.tick} multiplica su velocidad por 0,98 cada tick (rozamiento con el aire); en el espacio, mientras
 * no esté apoyado, se deja sin tocar. La gravedad ya la quita {@link EntitySpaceGravityMixin}.
 */
@Mixin(ItemEntity.class)
public abstract class ItemEntitySpaceDragMixin {
    @Redirect(method = "tick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/Vec3;multiply(DDD)Lnet/minecraft/world/phys/Vec3;", ordinal = 0))
    private Vec3 create_universal$noDragInSpace(Vec3 velocity, double x, double y, double z) {
        ItemEntity self = (ItemEntity) (Object) this;
        if (!self.onGround() && self.level().dimension() == CUDimensions.SPACE) {
            return velocity;
        }
        return velocity.multiply(x, y, z);
    }
}
