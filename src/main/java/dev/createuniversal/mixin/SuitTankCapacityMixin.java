package dev.createuniversal.mixin;

import com.simibubi.create.content.equipment.armor.BacktankUtil;
import dev.createuniversal.space.SuitUpgrades;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Tanque ampliado: la pechera con esa mejora guarda el doble de oxígeno (30 min en vez de 15). */
@Mixin(value = BacktankUtil.class, remap = false)
public abstract class SuitTankCapacityMixin {
    @Inject(method = "maxAir(Lnet/minecraft/world/item/ItemStack;)I", at = @At("RETURN"), cancellable = true, require = 0)
    private static void createUniversal$biggerTank(ItemStack backtank, CallbackInfoReturnable<Integer> cir) {
        if (SuitUpgrades.of(backtank).contains(SuitUpgrades.TANK)) {
            cir.setReturnValue(cir.getReturnValueI() * 2);
        }
    }
}
