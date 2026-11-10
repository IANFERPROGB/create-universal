package dev.createuniversal.mixin.compat;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import dev.createuniversal.compat.PowerGridSolar;
import dev.createuniversal.space.Sunlight;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.patryk3211.powergrid.electricity.solarpanel.SolarHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

/**
 * Cojinete de placas solares de Power Grid (el que gira las placas, a mano o con un servo): en el espacio y en los astros,
 * con el Sol real. Así un seguidor solar que gire hacia el Sol de verdad produce lo que debe.
 */
@Pseudo
@Mixin(targets = "org.patryk3211.powergrid.electricity.solarpanel.SolarPanelBearingBlockEntity", remap = false)
public abstract class PowerGridSolarBearingMixin {
    @Shadow private int rayCastDelay;
    @Shadow private float sunVisibility;
    @Shadow private boolean skyVisible;
    @Shadow private Vector3d panelNormal;
    @Shadow protected ControlledContraptionEntity movedContraption;

    @Shadow
    private Vector3d getContraptionCenter(AbstractContraptionEntity entity) {
        throw new AssertionError();
    }

    @Inject(method = "getIrradiance", at = @At("HEAD"), cancellable = true, require = 0)
    private void createUniversal$realSun(double airMass, double cloudCover, int y, Level level, CallbackInfoReturnable<Double> cir) {
        if (!Sunlight.handles(level)) {
            return;
        }
        if (movedContraption == null || panelNormal == null) {
            cir.setReturnValue(0.0);
            return;
        }
        Vector3d c = getContraptionCenter(movedContraption);
        Vec3 local = new Vec3(c.x, c.y, c.z);
        Vec3 at = PowerGridSolar.world(level, local);
        Sunlight.Sun sun = Sunlight.at(level, at);
        if (rayCastDelay-- <= 0) {
            rayCastDelay = level.random.nextInt(41) + 10;
            Vector3d d = sun.dir();
            Vec3 from = at.add(panelNormal.x, panelNormal.y, panelNormal.z);
            sunVisibility = PowerGridSolar.visibility(level, from, d, Set.of(), movedContraption);
            skyVisible = !sun.ground() || SolarHelper.skyCheck(level, BlockPos.containing(local.add(panelNormal.x, panelNormal.y, panelNormal.z)));
        }
        cir.setReturnValue(PowerGridSolar.irradiance(sun, panelNormal, sunVisibility, skyVisible));
    }
}
