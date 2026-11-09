package dev.createuniversal.mixin;

import dev.createuniversal.CreateUniversal;
import dev.createuniversal.space.ShipTracker;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Diagnóstico (alpha.5.4): algunas naves desaparecen justo después de cambiar de dimensión. Si se borra una nave que se
 * ha movido hace poco, se escribe en el log quién la borra y su masa (Sable destruye las naves con masa 0).
 */
@Mixin(value = SubLevel.class, remap = false)
public abstract class SubLevelRemovalDiagnosticMixin {
    @Inject(method = "markRemoved", at = @At("HEAD"))
    private void create_universal$logRemoval(CallbackInfo ci) {
        SubLevel self = (SubLevel) (Object) this;
        if (!self.isRemoved() && ShipTracker.isWatched(self.getUniqueId())) {
            double mass = self instanceof ServerSubLevel s ? s.getMassTracker().getMass() : -1.0;
            CreateUniversal.LOGGER.warn("La nave {} recién movida se borra (masa {})", self.getUniqueId(), mass, new Throwable("quién la borra"));
        }
    }
}
