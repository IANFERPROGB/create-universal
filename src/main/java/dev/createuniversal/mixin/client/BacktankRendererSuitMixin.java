package dev.createuniversal.mixin.client;

import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.equipment.armor.BacktankRenderer;
import dev.createuniversal.registry.CUSuit;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** La pechera del traje colocada es de netherita: engranajes y eje de netherita, no los de cobre. */
@Mixin(value = BacktankRenderer.class, remap = false)
public abstract class BacktankRendererSuitMixin {
    @Inject(method = "getCogsModel", at = @At("HEAD"), cancellable = true, require = 0)
    private static void createUniversal$suitCogs(BlockState state, CallbackInfoReturnable<PartialModel> cir) {
        if (state.is(CUSuit.TANK_BLOCK.get())) {
            cir.setReturnValue(AllPartialModels.NETHERITE_BACKTANK_COGS);
        }
    }

    @Inject(method = "getShaftModel", at = @At("HEAD"), cancellable = true, require = 0)
    private static void createUniversal$suitShaft(BlockState state, CallbackInfoReturnable<PartialModel> cir) {
        if (state.is(CUSuit.TANK_BLOCK.get())) {
            cir.setReturnValue(AllPartialModels.NETHERITE_BACKTANK_SHAFT);
        }
    }
}
