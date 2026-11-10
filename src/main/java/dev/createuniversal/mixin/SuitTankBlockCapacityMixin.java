package dev.createuniversal.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.equipment.armor.BacktankBlockEntity;
import dev.createuniversal.registry.CUMinerals;
import dev.createuniversal.space.SuitUpgrades;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/**
 * La pechera colocada con el tanque ampliado se llena hasta el doble (y por eso tarda el doble), y el comparador lo cuenta
 * sobre ese máximo. La mejora va guardada en el bloque con los demás datos de la pechera.
 */
@Mixin(value = BacktankBlockEntity.class, remap = false)
public abstract class SuitTankBlockCapacityMixin {
    @WrapOperation(method = {"tick", "getComparatorOutput"}, require = 0,
            at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/equipment/armor/BacktankUtil;maxAir(I)I"))
    private int createUniversal$biggerTank(int enchantLevel, Operation<Integer> original) {
        int max = original.call(enchantLevel);
        List<String> upgrades = ((BlockEntity) (Object) this).components().get(CUMinerals.SUIT_UPGRADES.get());
        return upgrades != null && upgrades.contains(SuitUpgrades.TANK) ? max * 2 : max;
    }
}
