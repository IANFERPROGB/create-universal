package dev.createuniversal.mixin;

import com.simibubi.create.content.contraptions.AssemblyException;
import dev.createuniversal.space.NoDisassemblyInSpace;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * D38: el Assembler Stick de Create Propulsion tampoco desensambla en el espacio. {@code @Pseudo}: si Create Propulsion no
 * está instalado, el mixin simplemente no se aplica.
 */
@Pseudo
@Mixin(targets = "dev.propulsionteam.propulsionsimulated.assemblerstick.interaction.AssemblerStickInteractionHandler", remap = false)
public abstract class AssemblerStickNoSpaceDisassemblyMixin {
    @Inject(method = "validateDisassembly", at = @At("HEAD"), require = 0)
    private static void create_universal$noDisassemblyInSpace(ServerLevel level, ServerSubLevel subLevel, CallbackInfo ci) throws AssemblyException {
        if (NoDisassemblyInSpace.blocked(level)) {
            throw NoDisassemblyInSpace.exception();
        }
    }
}
