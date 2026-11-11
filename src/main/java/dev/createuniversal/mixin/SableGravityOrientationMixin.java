package dev.createuniversal.mixin;

import dev.createuniversal.space.Gravity;
import dev.ryanhcode.sable.api.entity.EntitySubLevelUtil;
import net.minecraft.world.entity.Entity;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sable deja un hueco para que una entidad tenga su propia orientación ("arriba" que no es el del mundo) y con ella gira
 * la cámara, el cuerpo, el movimiento, el salto y el choque con las naves. Aquí se rellena con la gravedad del
 * Estabilizador de gravedad cuando la entidad está en una nave ({@link Gravity#orientation}).
 */
@Mixin(value = EntitySubLevelUtil.class, remap = false)
public abstract class SableGravityOrientationMixin {
    @Inject(method = "getCustomEntityOrientation", at = @At("HEAD"), cancellable = true, require = 0)
    private static void createUniversal$gravity(Entity entity, float partialTicks, CallbackInfoReturnable<Quaterniondc> cir) {
        if (entity == null) {
            return;
        }
        Quaterniond q = Gravity.orientation(entity, partialTicks);
        if (q != null) {
            cir.setReturnValue(q);
        }
    }

    @Inject(method = "hasCustomEntityOrientation", at = @At("HEAD"), cancellable = true, require = 0)
    private static void createUniversal$hasGravity(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (entity != null) {
            Gravity.State s = Gravity.state(entity);
            if (s != null && s.ship() != null) {
                cir.setReturnValue(true);
            }
        }
    }
}
