package dev.createuniversal.mixin;

import dev.createuniversal.space.Breathing;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * La cámara de combustión de los reactores de AeroEngine quema queroseno con el oxígeno del aire (D23): donde no hay aire
 * respirable (el espacio, y Marte, Venus o Titán, que no tienen oxígeno) se apaga. El empuje de sus hélices y ventiladores
 * ya depende de la presión del aire de Sable.
 */
@Pseudo
@Mixin(targets = "com.cxw.aeroengineering.content.blockentity.EngineCombustorBlockEntity", remap = false)
public abstract class AeroEngineCombustorMixin {
    @Shadow
    private boolean startModeActive;

    @Shadow
    private void setStartModeActive(boolean active) {
        throw new AssertionError();
    }

    @Inject(method = "tick", at = @At("HEAD"), require = 0)
    private void createUniversal$needsOxygen(CallbackInfo ci) {
        BlockEntity self = (BlockEntity) (Object) this;
        if (startModeActive && self.getLevel() != null && !self.getLevel().isClientSide
                && !Breathing.hasAirAround(self.getLevel(), self.getBlockPos())) {
            setStartModeActive(false);
        }
    }
}
