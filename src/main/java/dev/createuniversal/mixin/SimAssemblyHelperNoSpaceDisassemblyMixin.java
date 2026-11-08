package dev.createuniversal.mixin;

import dev.createuniversal.CreateUniversal;
import dev.createuniversal.space.NoDisassemblyInSpace;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.simulated_team.simulated.util.SimAssemblyHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * D38, red de seguridad: casi todo lo que desensambla una nave (Ensamblador de física, cola de fusión, Assembler Stick…)
 * acaba en {@code SimAssemblyHelper.disassembleSubLevel}. En el espacio no hace nada aunque alguien llegue hasta aquí
 * saltándose las comprobaciones con mensaje.
 */
@Mixin(value = SimAssemblyHelper.class, remap = false)
public abstract class SimAssemblyHelperNoSpaceDisassemblyMixin {
    @Inject(method = "disassembleSubLevel", at = @At("HEAD"), cancellable = true)
    private static void create_universal$noDisassemblyInSpace(Level level, SubLevel subLevel, BlockPos anchor, BlockPos goal,
                                                             Rotation rotation, boolean flag, CallbackInfo ci) {
        if (NoDisassemblyInSpace.blocked(level)) {
            CreateUniversal.LOGGER.info("Desensamblado de la nave {} bloqueado: está en el espacio", subLevel.getUniqueId());
            ci.cancel();
        }
    }
}
