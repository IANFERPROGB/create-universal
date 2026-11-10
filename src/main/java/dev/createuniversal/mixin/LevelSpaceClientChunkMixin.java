package dev.createuniversal.mixin;

import dev.createuniversal.space.CUDimensions;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Minecraft congela al jugador mientras su cliente no tiene el chunk donde está. En el espacio los chunks están vacíos
 * (las naves van aparte, en Sable), y yendo deprisa el chunk siguiente llegaba tarde: el jugador se quedaba quieto unos
 * instantes (alpha.6.2). En el cliente, en el espacio, todo chunk cuenta como cargado; si de verdad
 * falta, el cliente da uno vacío, que es lo que hay allí.
 *
 * Va en {@code Level.hasChunk} y no en {@code LocalPlayer.tick} porque Sable ya cambia esa llamada para su propia espera
 * (alpha.6.4: dos cambios en el mismo sitio cerraban el juego al arrancar); su versión acaba llamando aquí.
 */
@Mixin(Level.class)
public abstract class LevelSpaceClientChunkMixin {
    @Shadow
    @Final
    public boolean isClientSide;

    @Shadow
    public abstract ResourceKey<Level> dimension();

    @Inject(method = "hasChunk", at = @At("HEAD"), cancellable = true, require = 0)
    private void createUniversal$spaceChunksAlwaysThere(int chunkX, int chunkZ, CallbackInfoReturnable<Boolean> cir) {
        if (isClientSide && dimension() == CUDimensions.SPACE) {
            cir.setReturnValue(true);
        }
    }
}
