package dev.createuniversal.mixin;

import com.simibubi.create.foundation.recipe.RecipeApplier;
import dev.createuniversal.registry.CUMinerals;
import dev.createuniversal.registry.CUSuit;
import dev.createuniversal.space.SuitUpgrades;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Poner una mejora en la pechera del traje con el Deployer: las recetas de Create crean el objeto de cero (sin el oxígeno
 * ni los encantamientos de la pechera). Si sale la misma pechera, se devuelve la que entró con la mejora nueva añadida.
 */
@Mixin(value = RecipeApplier.class, remap = false)
public abstract class SuitUpgradeRecipeMixin {
    @Inject(method = "applyRecipeOn(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/crafting/Recipe;Z)Ljava/util/List;",
            at = @At("RETURN"), cancellable = true, require = 0)
    private static void createUniversal$keepSuit(Level level, ItemStack stackIn, Recipe<?> recipe, boolean remainder,
                                                CallbackInfoReturnable<List<ItemStack>> cir) {
        List<ItemStack> out = cir.getReturnValue();
        if (out == null || out.size() != 1 || !stackIn.is(CUSuit.CHESTPLATE.get()) || !out.get(0).is(CUSuit.CHESTPLATE.get())) {
            return;
        }
        ItemStack result = stackIn.copyWithCount(1);
        result.set(CUMinerals.SUIT_UPGRADES.get(), SuitUpgrades.merge(SuitUpgrades.of(stackIn), SuitUpgrades.of(out.get(0))));
        List<ItemStack> fixed = new ArrayList<>();
        fixed.add(result);
        cir.setReturnValue(fixed);
    }
}
