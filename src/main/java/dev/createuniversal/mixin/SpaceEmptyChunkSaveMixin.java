package dev.createuniversal.mixin;

import dev.createuniversal.space.CUDimensions;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Chunks vacíos del espacio (beta.2): el espacio no tiene terreno, pero cada chunk por el que se pasaba se guardaba en
 * disco. En un mundo de pruebas eran 14 GB y guardar la dimensión tardaba 10 s. Un chunk del espacio sin
 * ningún bloque ni block entity no se guarda: si vuelve a hacer falta, se genera otra vez igual de vacío. Los que tienen
 * algo (estaciones, la zona interna de las naves) se guardan como siempre. Las entidades van aparte y no cambian.
 */
@Mixin(ChunkMap.class)
public abstract class SpaceEmptyChunkSaveMixin {
    @Shadow
    @Final
    ServerLevel level;

    @Inject(method = "save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z", at = @At("HEAD"), cancellable = true, require = 0)
    private void createUniversal$skipEmptySpaceChunk(ChunkAccess chunk, CallbackInfoReturnable<Boolean> cir) {
        if (level.dimension() != CUDimensions.SPACE || !chunk.getBlockEntitiesPos().isEmpty()) {
            return;
        }
        for (LevelChunkSection section : chunk.getSections()) {
            if (section != null && !section.hasOnlyAir()) {
                return;
            }
        }
        chunk.setUnsaved(false);
        cir.setReturnValue(false);
    }
}
