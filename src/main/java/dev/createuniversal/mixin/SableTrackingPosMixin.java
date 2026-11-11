package dev.createuniversal.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.createuniversal.space.Gravity;
import dev.ryanhcode.sable.ActiveSableCompanion;
import dev.ryanhcode.sable.mixinterface.EntityExtension;
import dev.ryanhcode.sable.sublevel.entity_collision.SubLevelEntityCollision;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Arreglos en el choque de Sable con las naves para la gravedad de una nave (orientación propia de la entidad), que en
 * Sable está a medio hacer:
 *
 * <ul>
 *   <li><b>Giro en cada paso:</b> Sable pide la orientación de la entidad en varios momentos del tick (0 a 1) y mueve la
 *       nave entre su pose anterior y la actual. Se le da la de la nave en ese mismo momento (no la dibujada, que va
 *       unos ticks por detrás): si no, al girar rápido el cuerpo con el que se choca no coincidía con la nave y se
 *       atravesaban bloques (alpha.9.4.1).</li>
 *   <li><b>Posición al llevarla la nave:</b> Sable la deja en los pies del cuerpo girado; se pasa a la posición de
 *       Minecraft (ojos − (0, ojos, 0)) (alpha.9.4).</li>
 *   <li><b>Movimiento heredado de la nave:</b> Sable lo mide con los pies; con la orientación propia se mide con la
 *       posición de Minecraft, que va con los ojos (el punto de giro). Medido con los pies, al girar la nave sumaba el
 *       vaivén de los pies y daba tirones de lado a lado (alpha.9.4.1).</li>
 * </ul>
 */
@Mixin(value = SubLevelEntityCollision.class, remap = false)
public abstract class SableTrackingPosMixin {
    @WrapOperation(method = "collide", require = 0, at = @At(value = "INVOKE",
            target = "Ldev/ryanhcode/sable/api/entity/EntitySubLevelUtil;getCustomEntityOrientation(Lnet/minecraft/world/entity/Entity;F)Lorg/joml/Quaterniondc;"))
    private static Quaterniondc createUniversal$physicsOrientation(Entity entity, float partialTicks, Operation<Quaterniondc> original) {
        Quaterniond q = Gravity.physicsOrientation(entity, partialTicks);
        return q != null ? q : original.call(entity, partialTicks);
    }

    @WrapOperation(method = "collide", require = 0, at = @At(value = "INVOKE",
            target = "Ldev/ryanhcode/sable/ActiveSableCompanion;getFeetPos(Lnet/minecraft/world/entity/Entity;FLorg/joml/Quaterniondc;)Lorg/joml/Vector3d;"))
    private static Vector3d createUniversal$anchor(ActiveSableCompanion instance, Entity entity, float distanceDown, Quaterniondc orientation,
                                                   Operation<Vector3d> original) {
        if (orientation != null && Gravity.state(entity) != null) {
            return new Vector3d(entity.getX(), entity.getY() - distanceDown, entity.getZ());
        }
        return original.call(instance, entity, distanceDown, orientation);
    }

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
