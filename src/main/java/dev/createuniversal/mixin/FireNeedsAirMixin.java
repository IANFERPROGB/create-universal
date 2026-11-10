package dev.createuniversal.mixin;

import dev.createuniversal.space.Breathing;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sin oxígeno no hay fuego (D59, alpha.8.0): fuera de la Tierra, el fuego (normal y de almas) solo prende dentro de una
 * sala con oxígeno. Así tampoco se encienden portales del Nether en el vacío, ni calientan las calderas de vapor con
 * fuego; los quemadores de blaze son mágicos y siguen funcionando.
 */
@Mixin(BaseFireBlock.class)
public abstract class FireNeedsAirMixin {
    @Inject(method = "canBePlacedAt", at = @At("HEAD"), cancellable = true, require = 0)
    private static void createUniversal$noFireWithoutAir(Level level, BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        if (!Breathing.hasAirAround(level, pos)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "onPlace", at = @At("HEAD"), cancellable = true, require = 0)
    private void createUniversal$goesOut(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston,
                                         CallbackInfo ci) {
        if (!level.isClientSide && !Breathing.hasAirAround(level, pos)) {
            level.removeBlock(pos, false);
            ci.cancel();
        }
    }
}
