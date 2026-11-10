package dev.createuniversal.mixin;

import dev.createuniversal.space.Breathing;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hornos, ahumadores y altos hornos queman combustible: fuera de la Tierra solo funcionan dentro de una sala con oxígeno
 * (D59). Sin aire se quedan parados (sin gastar combustible) hasta que lo haya.
 */
@Mixin(AbstractFurnaceBlockEntity.class)
public abstract class FurnaceNeedsAirMixin {
    @Inject(method = "serverTick", at = @At("HEAD"), cancellable = true, require = 0)
    private static void createUniversal$noAir(Level level, BlockPos pos, BlockState state, AbstractFurnaceBlockEntity be, CallbackInfo ci) {
        if (!Breathing.hasAirAround(level, pos)) {
            if (state.getValue(AbstractFurnaceBlock.LIT)) {
                level.setBlock(pos, state.setValue(AbstractFurnaceBlock.LIT, false), 3);
            }
            ci.cancel();
        }
    }
}
