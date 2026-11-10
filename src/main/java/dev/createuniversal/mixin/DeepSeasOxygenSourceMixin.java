package dev.createuniversal.mixin;

import com.maxenonyme.createsubmarine.submarine.block.entity.OxygeneDiffuserBlockEntity;
import dev.createuniversal.space.OxygenZones;
import dev.ryanhcode.sable.Sable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * El difusor de oxígeno de Create Deep Seas llena de aire la sala cerrada donde está ({@link OxygenZones}), con sus mismas
 * condiciones: señal de redstone y oxígeno en el depósito. Deep Seas solo gasta oxígeno en naves; en el suelo (una base
 * en la Luna) lo gasta este mod al mismo ritmo, 50 mB por segundo.
 */
@Mixin(value = OxygeneDiffuserBlockEntity.class, remap = false)
public abstract class DeepSeasOxygenSourceMixin {
    @Inject(method = "tick", at = @At("HEAD"), require = 0)
    private static void createUniversal$fillRoom(Level level, BlockPos pos, BlockState state, OxygeneDiffuserBlockEntity be, CallbackInfo ci) {
        if (level == null || level.isClientSide) {
            return;
        }
        boolean running = level.hasNeighborSignal(pos) && be.oxygenTank.getFluidAmount() > 0;
        if (running && level.getGameTime() % 20 == 0 && Sable.HELPER.getContaining(level, pos) == null) {
            FluidStack inside = be.oxygenTank.getFluid();
            be.oxygenTank.drain(inside.copyWithAmount(50), IFluidHandler.FluidAction.EXECUTE);
        }
        OxygenZones.sourceTick(level, pos, running);
    }
}
