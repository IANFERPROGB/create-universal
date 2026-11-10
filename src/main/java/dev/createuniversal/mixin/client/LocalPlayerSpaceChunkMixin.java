package dev.createuniversal.mixin.client;

import dev.createuniversal.space.CUDimensions;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Minecraft congela al jugador mientras su cliente no tiene el chunk donde está. En el espacio no hay nada en los chunks
 * (las naves van aparte, en Sable), y yendo deprisa el chunk siguiente llegaba tarde: el jugador se quedaba quieto unos
 * instantes y la nave se le iba (alpha.6.2). En el espacio se sigue moviendo aunque falte el chunk.
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerSpaceChunkMixin {
    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;hasChunkAt(II)Z"))
    private boolean createUniversal$spaceNeverWaitsForChunks(Level level, int x, int z) {
        return level.dimension() == CUDimensions.SPACE || level.hasChunkAt(x, z);
    }
}
