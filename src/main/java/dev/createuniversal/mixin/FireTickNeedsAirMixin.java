package dev.createuniversal.mixin;

import dev.createuniversal.space.Breathing;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** El fuego que ya ardía se apaga si su sala se queda sin oxígeno (una descompresión, un oxigenador apagado). */
@Mixin(FireBlock.class)
public abstract class FireTickNeedsAirMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, require = 0)
    private void createUniversal$goesOut(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
        if (!Breathing.hasAirAround(level, pos)) {
            level.removeBlock(pos, false);
            ci.cancel();
        }
    }
}
