package dev.createuniversal.mixin;

import dev.createuniversal.space.Breathing;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Method;

/**
 * Create Propulsion (D58): sus propulsores de combustible líquido (el de aguarrás y el vectorial de líquido) queman con el
 * oxígeno del aire. Donde no lo hay (el espacio y los astros, salvo en una sala con oxígeno) empujan la mitad, salvo que
 * lleven oxidante en su depósito de oxidante (el propulsor normal lo tiene). El iónico, el de combustible sólido y el
 * creativo no cambian. El efecto de la presión del aire ya lo calcula Create Propulsion con la de Sable.
 * Además, con el Motor de crucero encendido en su nave y lejos de todo, todos empujan ×4 (alpha.12.4).
 */
@Pseudo
@Mixin(targets = "dev.propulsionteam.propulsionsimulated.content.thruster.AbstractThrusterBlockEntity", remap = false)
public abstract class PropulsionOxidizerMixin {
    @Unique
    private static final java.util.Map<Class<?>, Method> CREATE_UNIVERSAL$OXIDIZER = new java.util.concurrent.ConcurrentHashMap<>();

    @Shadow
    public abstract boolean isIon();

    @Inject(method = "calculateAtmosphericFactor", at = @At("RETURN"), cancellable = true, require = 0)
    private void createUniversal$needsOxygen(CallbackInfoReturnable<Double> cir) {
        net.minecraft.world.level.block.entity.BlockEntity self = (net.minecraft.world.level.block.entity.BlockEntity) (Object) this;
        Level level = self.getLevel();
        BlockPos pos = self.getBlockPos();
        double factor = 1.0;
        String type = getClass().getName();
        if (!isIon() && !type.contains("solid_fuel") && !type.contains("creative") && level != null
                && !Breathing.hasAirAround(level, pos) && !createUniversal$hasOxidizer()) {
            factor = 0.5;
        }
        // Motor de crucero encendido en su nave, lejos de todo: ×4 (alpha.12.4, D95).
        factor *= dev.createuniversal.space.CruiseDrives.factor(level, pos);
        if (factor != 1.0) {
            cir.setReturnValue(cir.getReturnValue() * factor);
        }
    }

    @Unique
    private boolean createUniversal$hasOxidizer() {
        Method m = CREATE_UNIVERSAL$OXIDIZER.computeIfAbsent(getClass(), c -> {
            for (Class<?> k = c; k != null; k = k.getSuperclass()) {
                try {
                    Method found = k.getDeclaredMethod("validOxidizer");
                    found.setAccessible(true);
                    return found;
                } catch (NoSuchMethodException ignored) {
                }
            }
            return null;
        });
        if (m == null) {
            return false;
        }
        try {
            return (Boolean) m.invoke(this);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }
}
