package dev.createuniversal.mixin;

import dev.createuniversal.CreateUniversal;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * Simulated Coasters: un remache (rivet) que no encuentra el bloque al que estaba unido destruye la nave entera. Al
 * cambiar de dimensión ese bloque se queda atrás (un raíl del Overworld) o cambia de sitio, y la nave desaparecía nada
 * más llegar (medido el 2026-11-09). Las naves que han pasado por Create Universal llevan nuestra marca en sus datos: a
 * esas no se les deja destruirse así.
 */
@Pseudo
@Mixin(targets = "dev.silvergold.simulatedcoasters.rivet.RivetBlockEntity", remap = false)
public abstract class RivetNoTeardownAfterTransferMixin {
    @Inject(method = "scheduleTeardownIfInvalidHost", at = @At("HEAD"), cancellable = true, require = 0)
    private static void create_universal$keepTransferredShips(ServerLevel level, UUID rivetSubLevelId, CallbackInfo ci) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        SubLevel sub = container != null ? container.getSubLevel(rivetSubLevelId) : null;
        if (sub instanceof ServerSubLevel ship && ship.getUserDataTag() != null && ship.getUserDataTag().contains(CreateUniversal.MOD_ID)) {
            ci.cancel();
        }
    }
}
