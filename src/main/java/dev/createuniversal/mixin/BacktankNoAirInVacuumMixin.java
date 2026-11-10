package dev.createuniversal.mixin;

import com.simibubi.create.content.equipment.armor.BacktankBlockEntity;
import dev.createuniversal.space.Breathing;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Un backtank (o la pechera del traje) colocado solo se llena donde hay aire que comprimir (D31): en la Tierra, o en el
 * espacio y los astros dentro de un compartimento sellado con oxígeno de Create Deep Seas. En el vacío no se llena.
 */
@Mixin(value = BacktankBlockEntity.class, remap = false)
public abstract class BacktankNoAirInVacuumMixin {
    @Inject(method = "tick", require = 0, cancellable = true,
            at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/equipment/armor/BacktankUtil;maxAir(I)I"))
    private void createUniversal$onlyWhereThereIsAir(CallbackInfo ci) {
        BlockEntity self = (BlockEntity) (Object) this;
        if (self.getLevel() != null && !Breathing.hasAirAround(self.getLevel(), self.getBlockPos())) {
            ci.cancel();
        }
    }
}
