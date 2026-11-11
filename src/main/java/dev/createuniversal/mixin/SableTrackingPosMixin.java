package dev.createuniversal.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.createuniversal.space.Gravity;
import dev.ryanhcode.sable.mixinterface.EntityExtension;
import dev.ryanhcode.sable.sublevel.entity_collision.SubLevelEntityCollision;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Corrige el otro fallo de Sable con la orientación propia: al llevar con la nave a quien la pisa, coloca la entidad en los
 * pies de su cuerpo girado, pero la posición de Minecraft es la de los pies "de pie" (ojos − (0, ojos, 0)). La diferencia,
 * (1 − giro)·(0, ojos, 0), acababa sumada como movimiento heredado de la nave en cada tick: hasta 3 bloques por tick con la
 * nave del revés. Era lo que disparaba al jugador en la alpha.9.2. Aquí se pasa a la posición de Minecraft.
 */
@Mixin(value = SubLevelEntityCollision.class, remap = false)
public abstract class SableTrackingPosMixin {
    @WrapOperation(method = "collide", require = 0, at = @At(value = "INVOKE", ordinal = 0,
            target = "Ldev/ryanhcode/sable/mixinterface/EntityExtension;sable$setPosSuperRaw(Lnet/minecraft/world/phys/Vec3;)V"))
    private static void createUniversal$vanillaPosition(EntityExtension instance, Vec3 rotatedFeet, Operation<Void> original,
                                                        @Local(argsOnly = true) Entity entity) {
        Vector3d up = Gravity.up(entity);
        if (up != null) {
            double eyes = entity.getEyeHeight();
            // ojos = pies girados + arriba·ojos; posición de Minecraft = ojos − (0, ojos, 0)
            rotatedFeet = rotatedFeet.add(up.x * eyes, up.y * eyes - eyes, up.z * eyes);
        }
        original.call(instance, rotatedFeet);
    }
}
