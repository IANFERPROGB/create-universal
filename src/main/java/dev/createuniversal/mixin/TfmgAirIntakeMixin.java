package dev.createuniversal.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.createuniversal.space.Breathing;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * La toma de aire de TFMG no saca aire donde no hay aire respirable (D59): sin ella, sus motores de combustión no
 * arrancan en el vacío ni en Marte, Venus o Titán (allí no hay oxígeno), salvo dentro de una sala con oxígeno.
 */
@Pseudo
@Mixin(targets = "com.drmangotea.tfmg.content.machinery.misc.air_intake.AirIntakeBlockEntity", remap = false)
public abstract class TfmgAirIntakeMixin {
    @WrapOperation(method = "tick", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/fluids/capability/templates/FluidTank;setFluid(Lnet/neoforged/neoforge/fluids/FluidStack;)V"))
    private void createUniversal$noAirNoIntake(FluidTank tank, FluidStack stack, Operation<Void> original) {
        BlockEntity self = (BlockEntity) (Object) this;
        if (self.getLevel() == null || Breathing.hasAirAround(self.getLevel(), self.getBlockPos())) {
            original.call(tank, stack);
        }
    }
}
