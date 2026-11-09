package dev.createuniversal.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.createuniversal.CreateUniversal;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.UUID;

/**
 * Al colocar una nave guardada, la Toolgun la frena durante 22 ticks pidiendo al motor de física la velocidad de cada
 * pieza. Si en ese tiempo Sable borra una de las piezas (al montarla, fusionarla o vaciarla), la pieza sigue en el
 * contenedor hasta el final del tick pero su cuerpo físico ya no existe, y la Toolgun tumba el servidor con "Body has
 * been removed". Se le da la pieza borrada como si no existiera, y la Toolgun ya se la salta.
 * {@code @Pseudo} y {@code require = 0}: si la Toolgun no está instalada o cambia, no se aplica.
 */
@Pseudo
@Mixin(targets = "com.enxv.aeronauticsstructuretool.blueprint.runtime.RuntimeContraptionRestoreCoordinator", remap = false)
public abstract class ToolgunStabilizationRemovedShipMixin {
    @WrapOperation(method = "applySpawnStabilization",
            at = @At(value = "INVOKE", target = "Ldev/ryanhcode/sable/api/sublevel/ServerSubLevelContainer;getSubLevel(Ljava/util/UUID;)Ldev/ryanhcode/sable/sublevel/SubLevel;"),
            require = 0)
    private static SubLevel create_universal$skipRemovedShip(ServerSubLevelContainer container, UUID id, Operation<SubLevel> original) {
        SubLevel ship = original.call(container, id);
        if (ship != null && ship.isRemoved()) {
            double mass = ship instanceof dev.ryanhcode.sable.sublevel.ServerSubLevel s ? s.getMassTracker().getMass() : -1.0;
            CreateUniversal.LOGGER.info("La Toolgun intentaba frenar la pieza {} (masa {}), que ya se ha borrado: se salta", id, mass);
            return null;
        }
        return ship;
    }
}
