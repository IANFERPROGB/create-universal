package dev.createuniversal.mixin;

import com.maxenonyme.createsubmarine.submarine.block.entity.HullControllerBlockEntity;
import dev.createuniversal.space.OxygenZones;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** El oxigenador creativo de Create Deep Seas llena de aire la sala cerrada donde está, siempre ({@link OxygenZones}). */
@Mixin(value = HullControllerBlockEntity.class, remap = false)
public abstract class DeepSeasCreativeOxygenMixin {
    @Inject(method = "tick", at = @At("HEAD"), require = 0)
    private void createUniversal$fillRoom(CallbackInfo ci) {
        BlockEntity self = (BlockEntity) (Object) this;
        if (self.getLevel() != null) {
            OxygenZones.sourceTick(self.getLevel(), self.getBlockPos(), true);
        }
    }
}
