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
// Prioridad baja: se aplica antes que Gravity Control, que también fija el resultado al final de getGravity (lo
// multiplica por su fuerza). Con dos enganches al final que fijan el resultado, el primero que lo fija gana y el otro no
// llega a ejecutarse; así manda lo nuestro (espacio, astros y Estabilizador de gravedad) (alpha.9.3.3).
@Mixin(value = Entity.class, priority = 500)
public abstract class EntitySpaceGravityMixin {
    @Shadow
    public abstract Level level();

    @Inject(method = "getGravity", at = @At("RETURN"), cancellable = true)
    private void create_universal$noGravityInSpace(CallbackInfoReturnable<Double> cir) {
        Level level = level();
        if (level == null) {
            return;
        }
        // Dentro del área de un Estabilizador de gravedad manda él: en una nave la gravedad la pone GravityTravelMixin en
        // la dirección de la nave (aquí 0); en el suelo, su fuerza (la dirección la gira Gravity Control).
        if ((Object) this instanceof net.minecraft.world.entity.LivingEntity) {
            dev.createuniversal.space.Gravity.State state = dev.createuniversal.space.Gravity.state((Entity) (Object) this);
            if (state != null) {
                cir.setReturnValue(state.ship() != null ? 0.0 : dev.createuniversal.space.Gravity.EARTH * state.strength());
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
