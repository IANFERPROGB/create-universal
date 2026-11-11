package dev.createuniversal.mixin;

import dev.createuniversal.space.LoginHold;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sable Player Ragdoll Patch, al entrar, saca al punto de aparición a quien está en la zona interna de una nave que aún no
 * ha cargado (cree que la nave ya no existe). Mientras {@link LoginHold} espera a que cargue la nave del jugador, no
 * (alpha.9.8).
 */
@Pseudo
@Mixin(targets = "twtlinmiao.sableplayerragdollpatch.SablePlayerRagdollPatchNeoForge", remap = false)
public abstract class RagdollPatchLoginMixin {
    @Inject(method = "onPlayerLoggedIn", at = @At("HEAD"), cancellable = true, require = 0)
    private static void createUniversal$waitForShip(PlayerEvent.PlayerLoggedInEvent event, CallbackInfo ci) {
        if (event.getEntity() instanceof ServerPlayer player && LoginHold.isHeld(player)) {
            ci.cancel();
        }
    }
}
