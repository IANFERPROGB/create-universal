package dev.createuniversal.mixin.compat;

import dev.createuniversal.compat.PowerGridSolar;
import dev.createuniversal.space.Sunlight;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DaylightDetectorBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * El sensor de luz del día conectado al convertidor de redstone de Power Grid (con lo que se monta un seguidor solar con
 * un servo): en el espacio y en los astros da la altura del Sol real en ese sitio.
 */
@Pseudo
@Mixin(targets = "org.patryk3211.powergrid.electricity.redstoneconverter.DaylightSensorBehaviour", remap = false)
public abstract class PowerGridDaylightSensorMixin {
    @Inject(method = "getSignal", at = @At("HEAD"), cancellable = true, require = 0)
    private void createUniversal$realSun(Level level, BlockState state, BlockPos pos, Direction face, CallbackInfoReturnable<Float> cir) {
        if (Sunlight.handles(level)) {
            cir.setReturnValue(PowerGridSolar.daylightSignal(level, pos, state.getValue(DaylightDetectorBlock.INVERTED)));
        }
    }
}
