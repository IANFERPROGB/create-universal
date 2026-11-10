package dev.createuniversal.mixin;

import dev.createuniversal.space.Breathing;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Las hogueras se apagan sin oxígeno (D59), y con ellas las calderas de vapor que calentaban. */
@Mixin(CampfireBlockEntity.class)
public abstract class CombustionNeedsAirMixin {
    @Inject(method = "cookTick", at = @At("HEAD"), cancellable = true, require = 0)
    private static void createUniversal$goesOut(Level level, BlockPos pos, BlockState state, CampfireBlockEntity be, CallbackInfo ci) {
        if (state.getValue(CampfireBlock.LIT) && !Breathing.hasAirAround(level, pos)) {
            level.setBlock(pos, state.setValue(CampfireBlock.LIT, false), 3);
            level.playSound(null, pos, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.6F, 1.2F);
            ci.cancel();
        }
    }
}
