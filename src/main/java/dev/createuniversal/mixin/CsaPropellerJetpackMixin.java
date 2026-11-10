package dev.createuniversal.mixin;

import dev.createuniversal.space.CUDimensions;
import dev.createuniversal.space.surface.PlanetSurface;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Los jetpacks de hélice de Create Stuff 'N Additions (andesita, latón y cobre) empujan aire con una hélice: en el espacio
 * y en los astros sin aire (o casi, como Marte) no vuelan. En Venus y Titán sí. El de netherita es de vapor (agua y lava)
 * y funciona en todas partes.
 */
@Pseudo
@Mixin(targets = {
        "net.mcreator.createstuffadditions.procedures.AndesitePropelerBodyTickEventProcedure",
        "net.mcreator.createstuffadditions.procedures.BrassEncasedPropelerBodyTickEventProcedure",
        "net.mcreator.createstuffadditions.procedures.CopperPropelerBodyTickEventProcedure"}, remap = false)
public abstract class CsaPropellerJetpackMixin {
    @Inject(method = "execute", at = @At("HEAD"), cancellable = true, require = 0)
    private static void createUniversal$needsAir(LevelAccessor world, double x, double y, double z, Entity entity,
                                                 ItemStack stack, CallbackInfo ci) {
        if (!(world instanceof Level level)) {
            return;
        }
        if (level.dimension() == CUDimensions.SPACE) {
            ci.cancel();
            return;
        }
        PlanetSurface surface = PlanetSurface.byDimension(level.dimension());
        if (surface != null && surface.pressure() < 0.1) {
            ci.cancel();
        }
    }
}
