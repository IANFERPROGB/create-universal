package dev.createuniversal.mixin;

import dev.ryanhcode.sable.ActiveSableCompanion;
import net.minecraft.world.entity.Entity;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Corrige un fallo de Sable en el camino de la orientación propia (que ningún mod usaba hasta el Estabilizador de
 * gravedad). Sable gira el cuerpo de una entidad con orientación propia alrededor de sus ojos (cámara, dibujo, choques),
 * pero aquí calcula los pies como {@code posición − giro·(0, ojos, 0)}, como si la posición fuera la de los ojos. La
 * posición de Minecraft es la de los pies "de pie", así que los ojos están en {@code posición + (0, ojos, 0)} y los pies
 * girados en {@code ojos − giro·(0, ojos + bajada, 0)}. Con el fallo, Sable miraba 1,6 bloques por debajo (bloque que se
 * pisa, rozamiento, sonidos) y el movimiento heredado de la nave se descuadraba.
 *
 * Sin orientación propia queda igual que el de Sable.
 */
@Mixin(value = ActiveSableCompanion.class, remap = false)
public abstract class SableFeetPosMixin {
    /** Sustituye al método por defecto de la interfaz de Sable (misma firma). */
    public Vector3d getFeetPos(Entity entity, float distanceDown, Quaterniondc orientation) {
        Vector3d pos = new Vector3d(entity.getX(), entity.getY(), entity.getZ());
        if (orientation == null) {
            return pos.sub(0.0, distanceDown, 0.0);
        }
        double eyes = entity.getEyeHeight();
        return pos.add(0.0, eyes, 0.0).sub(orientation.transform(new Vector3d(0.0, distanceDown + eyes, 0.0)));
    }
}
