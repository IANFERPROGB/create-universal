package dev.createuniversal.mixin;

import com.simibubi.create.content.contraptions.AssemblyException;
import dev.createuniversal.space.NoDisassemblyInSpace;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.simulated_team.simulated.content.blocks.physics_assembler.PhysicsAssemblerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * D38: el Ensamblador de física de Simulated/Aeronautics no desensambla en el espacio. Se usa su propio sistema de errores
 * ({@code throwDisassemblyExceptions}), así que el jugador ve el motivo igual que "demasiado rápido" o "fuera del mundo".
 */
@Mixin(value = PhysicsAssemblerBlockEntity.class, remap = false)
public abstract class PhysicsAssemblerNoSpaceDisassemblyMixin {
    @Inject(method = "throwDisassemblyExceptions", at = @At("HEAD"))
    private void create_universal$noDisassemblyInSpace(ServerSubLevel subLevel, CallbackInfo ci) throws AssemblyException {
        if (NoDisassemblyInSpace.blocked(subLevel.getLevel())) {
            throw NoDisassemblyInSpace.exception();
        }
    }
}
