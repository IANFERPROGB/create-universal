package dev.createuniversal.mixin.compat;

import dev.createuniversal.compat.PowerGridSolar;
import dev.createuniversal.space.Sunlight;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.patryk3211.powergrid.electricity.solarpanel.SolarHelper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashSet;
import java.util.Set;

/** Placa solar de Power Grid: en el espacio y en los astros, con el Sol real ({@link PowerGridSolar}). */
@Pseudo
@Mixin(targets = "org.patryk3211.powergrid.electricity.solarpanel.SolarPanelBlockEntity", remap = false)
public abstract class PowerGridSolarPanelMixin {
    @Shadow private int rayCastDelay;
    @Shadow private float sunVisibility;
    @Shadow private boolean skyVisible;
    @Shadow private Vector3d panelNormal;
    @Shadow @Final private Set<BlockPos> connectedPanels;
    @Shadow private BlockPos controller;

    @Inject(method = "getIrradiance", at = @At("HEAD"), cancellable = true, require = 0)
    private void createUniversal$realSun(double airMass, double cloudCover, int y, Level level, CallbackInfoReturnable<Double> cir) {
        if (!Sunlight.handles(level)) {
            return;
        }
        BlockPos pos = ((BlockEntity) (Object) this).getBlockPos();
        Vec3 local = connectedPanels.isEmpty() ? Vec3.atCenterOf(pos) : SolarHelper.getSolarPanelCenter(pos, connectedPanels);
        Vec3 at = PowerGridSolar.world(level, local);
        Sunlight.Sun sun = Sunlight.at(level, at);
        if (rayCastDelay-- <= 0) {
            rayCastDelay = level.random.nextInt(41) + 10;
            if (connectedPanels.isEmpty() || controller == null) {
                Set<BlockPos> own = new HashSet<>(connectedPanels);
                own.add(pos);
                Vector3d d = sun.dir();
                sunVisibility = PowerGridSolar.visibility(level, at.add(d.x * 0.8, d.y * 0.8, d.z * 0.8), d, own, null);
                skyVisible = !sun.ground() || SolarHelper.skyCheck(level, BlockPos.containing(local));
            }
        }
        cir.setReturnValue(PowerGridSolar.irradiance(sun, panelNormal, sunVisibility, skyVisible));
    }
}
