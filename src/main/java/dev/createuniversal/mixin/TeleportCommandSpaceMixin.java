package dev.createuniversal.mixin;

import dev.createuniversal.space.SpaceGate;
import net.minecraft.server.commands.TeleportCommand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** El comando /tp sigue llevando al espacio y sacando de él (D71): es cosa de administradores. */
@Mixin(TeleportCommand.class)
public abstract class TeleportCommandSpaceMixin {
    @Inject(method = "performTeleport", at = @At("HEAD"), require = 0)
    private static void createUniversal$allow(CallbackInfo ci) {
        SpaceGate.allowBegin();
    }

    @Inject(method = "performTeleport", at = @At("RETURN"), require = 0)
    private static void createUniversal$done(CallbackInfo ci) {
        SpaceGate.allowEnd();
    }
}
