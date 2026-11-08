package dev.createuniversal.mixin;

import dev.createuniversal.space.CUDimensions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * En el espacio no hay gravedad para ninguna entidad: jugadores, mobs, objetos tirados, flechas... (D4).
 * Se hace en {@code Entity.getGravity()} y no con el atributo de gravedad del jugador porque otros mods del pack
 * (Sable, Immersive Portals) también calculan la gravedad de las entidades y el atributo no bastaba.
 * Se aplica en el servidor y en el cliente, que es quien mueve al jugador.
 */
@Mixin(Entity.class)
public abstract class EntitySpaceGravityMixin {
    @Shadow
    public abstract Level level();

    @Inject(method = "getGravity", at = @At("RETURN"), cancellable = true)
    private void create_universal$noGravityInSpace(CallbackInfoReturnable<Double> cir) {
        Level level = level();
        if (level != null && level.dimension() == CUDimensions.SPACE) {
            cir.setReturnValue(0.0);
        }
    }
}
