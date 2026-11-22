package dev.createuniversal.mixin;

import dev.createuniversal.space.CUDimensions;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * En el espacio, un chunk que no está cargado es vacío (beta.3). Volando a más de 99 b/s lejos de los astros ya no se
 * cargan los chunks de alrededor (D94), pero Minecraft mira cada tick los bloques que tocan a cada entidad (por ejemplo,
 * si tiene la cabeza dentro de un bloque) y, si el chunk no estaba, el servidor se paraba a cargarlo: en una prueba del
 * 2026-11-21 iba 5–7 s por detrás cada 20 s. Ahí no hay nada, así que se contesta "aire" sin esperar. Las
 * naves van en su zona interna, que siempre está cargada.
 */
@Mixin(Level.class)
public abstract class LevelSpaceUnloadedIsAirMixin {
    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true, require = 0)
    private void createUniversal$airIfUnloaded(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        if (createUniversal$unloadedInSpace(pos)) {
            cir.setReturnValue(Blocks.AIR.defaultBlockState());
        }
    }

    @Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true, require = 0)
    private void createUniversal$noFluidIfUnloaded(BlockPos pos, CallbackInfoReturnable<FluidState> cir) {
        if (createUniversal$unloadedInSpace(pos)) {
            cir.setReturnValue(Fluids.EMPTY.defaultFluidState());
        }
    }

    private boolean createUniversal$unloadedInSpace(BlockPos pos) {
        Level self = (Level) (Object) this;
        return self instanceof ServerLevel server && server.dimension() == CUDimensions.SPACE
                && server.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null;
    }
}
