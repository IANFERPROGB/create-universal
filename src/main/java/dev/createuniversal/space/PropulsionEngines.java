package dev.createuniversal.space;

import dev.propulsionteam.propulsionsimulated.content.thruster.AbstractThrusterBlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.joml.Vector3d;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Los propulsores de Create Propulsion para el Asistente de nave (alpha.12): qué son, hacia dónde empujan, si funcionan
 * donde están y cómo se les da potencia (el mismo control "digital" que usa ComputerCraft). Solo se carga si Create
 * Propulsion está instalado (ver {@link ShipAssistant}).
 */
final class PropulsionEngines {
    private static final Map<Class<?>, Method> OXIDIZER = new ConcurrentHashMap<>();

    private PropulsionEngines() {}

    /** El propulsor (solo el bloque principal de cada uno), o null si no es de Create Propulsion. */
    static ShipAssistant.Engine engine(BlockEntity be) {
        if (!(be instanceof AbstractThrusterBlockEntity t) || !t.isController()) {
            return null;
        }
        String type = t.getClass().getSimpleName();
        String kind;
        if (t.isCreative()) {
            kind = "creative";
        } else if (t.isIon()) {
            kind = "ion";
        } else if (type.contains("SolidFuel")) {
            kind = "solid";
        } else if (type.contains("Vector")) {
            kind = "vector";
        } else {
            kind = "liquid";
        }
        Vector3d dir = t.getThrustDirectionLocal();
        Direction push = Direction.getNearest(dir.x, dir.y, dir.z);
        int efficiency = 100;
        if (kind.equals("liquid") || kind.equals("vector")) {
            Level level = t.getLevel();
            if (level != null && !Breathing.hasAirAround(level, t.getBlockPos()) && !hasOxidizer(t)) {
                efficiency = 50;
            }
        }
        int items = 0;
        if (t instanceof dev.propulsionteam.propulsionsimulated.content.thruster.solid_fuel_thruster.SolidFuelThrusterBlockEntity s) {
            items = s.inventory.getStackInSlot(dev.propulsionteam.propulsionsimulated.content.thruster.solid_fuel_thruster.SolidFuelThrusterItemHandler.FUEL_SLOT)
                    .getCount() + (s.getDisplayBurnTimeRemaining() > 0 ? 1 : 0);
        }
        return new ShipAssistant.Engine(t.getBlockPos(), kind, push, efficiency, t.isActive(), t.getFuelAmountMb(), t.getFuelCapacityMb(), items);
    }

    /** Si este líquido le sirve de combustible a los propulsores de Create Propulsion. */
    static boolean isFuel(net.minecraft.world.level.material.Fluid fluid) {
        return dev.propulsionteam.propulsionsimulated.content.thruster.ThrusterFuelManager.getFuelPropertiesMap().containsKey(fluid);
    }

    /** Potencia de 0 a 1 que pone el asistente; con un valor negativo se le devuelve el control a la redstone. */
    static void setPower(BlockEntity be, float power) {
        if (!(be instanceof AbstractThrusterBlockEntity t)) {
            return;
        }
        if (power < 0) {
            t.setDigitalInput(0.0F);
            t.setControlMode(AbstractThrusterBlockEntity.ControlMode.NORMAL);
            if (t.getLevel() != null) {
                t.setRedstoneInput(t.getLevel().getBestNeighborSignal(t.getBlockPos()));
            }
            return;
        }
        t.setControlMode(AbstractThrusterBlockEntity.ControlMode.PERIPHERAL);
        t.setDigitalInput(power);
    }

    private static boolean hasOxidizer(AbstractThrusterBlockEntity t) {
        Method m = OXIDIZER.computeIfAbsent(t.getClass(), c -> {
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
        try {
            return m != null && Boolean.TRUE.equals(m.invoke(t));
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }
}
