package dev.createuniversal.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.createuniversal.space.CUDimensions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * NeoForge hace que el servidor cargue en el acto el chunk al que se mueve cualquier entidad ({@code Entity.setPosRaw}),
 * esperando a que esté generado. Volando muy deprisa por el espacio (en espectador o con una nave) se entra en chunks
 * nuevos sin parar y el servidor se quedaba parado hasta 8 s esperándolos: el jugador se quedaba medio congelado
 * (alpha.6.6). En el espacio los chunks están vacíos: no se espera; se cargan a su ritmo.
 */
@Mixin(Entity.class)
public abstract class EntitySpaceNoForcedChunkMixin {
    @WrapOperation(method = "setPosRaw", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getChunk(II)Lnet/minecraft/world/level/chunk/LevelChunk;"))
    private LevelChunk createUniversal$noWaitInSpace(Level level, int chunkX, int chunkZ, Operation<LevelChunk> original) {
        if (level.dimension() == CUDimensions.SPACE) {
            return null;
        }
        return original.call(level, chunkX, chunkZ);
    }
}
