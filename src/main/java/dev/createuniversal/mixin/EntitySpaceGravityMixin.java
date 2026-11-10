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
 * En el espacio no hay gravedad para ninguna entidad: jugadores, mobs, objetos tirados, flechas... (D4). En la Luna y en
 * Marte, la suya (1/6 y 0,38 de la de la Tierra).
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
        if (level == null) {
            return;
        }
        // Dentro del área de un Estabilizador de gravedad manda él (alpha.9.3): su fuerza, también en el espacio. La
        // dirección la gira Gravity Control.
        if ((Object) this instanceof net.minecraft.world.entity.LivingEntity) {
            dev.createuniversal.space.Gravity.State state = dev.createuniversal.space.Gravity.state((Entity) (Object) this);
            if (state != null) {
                cir.setReturnValue(dev.createuniversal.space.Gravity.EARTH * state.strength());
                return;
            }
        }
        if (level.dimension() == CUDimensions.SPACE) {
            cir.setReturnValue(0.0);
            return;
        }
        // En la Luna y en Marte, su gravedad (alpha.5).
        dev.createuniversal.space.surface.PlanetSurface surface = dev.createuniversal.space.surface.PlanetSurface.byDimension(level.dimension());
        if (surface != null) {
            cir.setReturnValue(cir.getReturnValue() * surface.gravity());
        }
    }
}
