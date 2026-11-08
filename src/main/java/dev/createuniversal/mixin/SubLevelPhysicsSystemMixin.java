package dev.createuniversal.mixin;

import dev.createuniversal.CreateUniversal;
import dev.createuniversal.space.CUDimensions;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.server.level.ServerLevel;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * El motor de física de Sable recibe la gravedad de su dimensión una sola vez, al arrancar ({@code initialize}).
 * En las pruebas de la alpha.2 las naves seguían cayendo en el espacio aunque {@code dimension_physics/space.json} pone
 * gravedad 0, así que aquí se garantiza: el motor del espacio siempre arranca sin gravedad, y se registra en el log qué
 * valor traía para saber de dónde venía.
 */
@Mixin(value = SubLevelPhysicsSystem.class, remap = false)
public abstract class SubLevelPhysicsSystemMixin {
    @Shadow
    @Final
    private ServerLevel level;

    @ModifyArg(method = "initialize",
            at = @At(value = "INVOKE", target = "Ldev/ryanhcode/sable/api/physics/PhysicsPipeline;init(Lorg/joml/Vector3dc;D)V"),
            index = 0)
    private Vector3dc create_universal$zeroGravityInSpace(Vector3dc gravity) {
        if (level.dimension() != CUDimensions.SPACE) {
            return gravity;
        }
        CreateUniversal.LOGGER.info("Motor de física del espacio: gravedad recibida {} -> se fuerza a 0", gravity);
        return new Vector3d();
    }
}
