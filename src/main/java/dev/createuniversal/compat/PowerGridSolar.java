package dev.createuniversal.compat;

import dev.createuniversal.space.Sunlight;
import dev.ryanhcode.sable.companion.SableCompanion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.patryk3211.powergrid.collections.ModdedTags;
import org.patryk3211.powergrid.electricity.solarpanel.SolarHelper;

import java.util.Collection;
import java.util.List;

/**
 * Placas solares de Create Power Grid en el espacio y en los astros: la misma cuenta que hace
 * Power Grid (luz directa según el ángulo con la placa, luz difusa del cielo y reflejada del suelo), pero con el Sol
 * real de {@link Sunlight}: su dirección en 3D, su fuerza según la distancia, el aire de cada astro y los eclipses.
 * Así una placa en una nave en el espacio produce si mira al Sol, y un seguidor solar lo sigue de verdad.
 *
 * Solo lo llaman los mixins de Power Grid (que no se aplican si Power Grid no está).
 */
public final class PowerGridSolar {
    private PowerGridSolar() {}

    /** Punto del mundo (fuera de la nave si la placa va en una) de una posición de bloque o de la zona interna. */
    public static Vec3 world(Level level, Vec3 local) {
        Vector3d w = SableCompanion.INSTANCE.projectOutOfSubLevel(level, new Vector3d(local.x, local.y, local.z));
        return new Vec3(w.x, w.y, w.z);
    }

    /**
     * Luz total que recibe una placa (W/m², sin contar sombras de bloques: eso es {@code visibility}).
     *
     * @param normal     hacia dónde mira la placa, en el mundo
     * @param visibility qué parte del Sol no tapan los bloques (0..1), la de la última comprobación
     * @param skyVisible si ve el cielo (para la luz difusa)
     */
    public static double irradiance(Sunlight.Sun sun, Vector3dc normal, float visibility, boolean skyVisible) {
        if (sun == null || sun.watts() <= 0.0 || normal == null) {
            return 0.0;
        }
        double cosIncidence = Math.max(0.0, sun.dir().dot(normal));
        double up = Math.max(0.0, sun.dir().y);
        double diffuse = sun.atmosphere() && skyVisible ? 0.12 * up * sun.watts() * (1.0 + normal.y()) / 2.0 : 0.0;
        double reflected = sun.ground() && skyVisible ? 0.08 * up * sun.watts() * (1.0 - normal.y()) / 2.0 : 0.0;
        return sun.watts() * visibility * cosIncidence + diffuse + reflected;
    }

    /**
     * Qué parte del Sol llega a la placa sin que la tapen bloques (de la nave, de otras naves o del suelo), con las
     * mismas reglas de Power Grid: los bloques de sus etiquetas de luz dejan pasar una parte; el resto, nada.
     *
     * @param from  punto de salida (en el mundo), ya un poco por delante de la placa
     * @param own   posiciones (de bloque o de zona interna) de la propia placa y de las conectadas, que no la tapan
     * @param ownContraption el artefacto que mueve el cojinete solar (sus propias placas no lo tapan), o null
     */
    public static float visibility(Level level, Vec3 from, Vector3d dir, Collection<BlockPos> own, Object ownContraption) {
        Vec3 end = from.add(dir.x * 64.0, dir.y * 64.0, dir.z * 64.0);
        List<SolarHelper.DDAHit> hits = SolarHelper.DDA(level, from, end);
        float light = 1.0F;
        for (SolarHelper.DDAHit hit : hits) {
            if (hit.contraption() == null ? own.contains(hit.worldOrLocalPos()) : hit.contraption() == ownContraption) {
                continue;
            }
            BlockState state;
            if (hit.contraption() != null) {
                var info = hit.contraption().getContraption().getBlocks().get(hit.worldOrLocalPos());
                if (info == null) {
                    continue;
                }
                state = info.state();
            } else {
                state = level.getBlockState(hit.worldOrLocalPos());
            }
            if (state.isAir()) {
                continue;
            }
            if (state.is(ModdedTags.Block.SOLAR_FULL_LIGHT.tag)) {
                continue;
            }
            if (state.is(ModdedTags.Block.SOLAR_3QUARTER_LIGHT.tag)) {
                light *= 0.75F;
            } else if (state.is(ModdedTags.Block.SOLAR_HALF_LIGHT.tag)) {
                light *= 0.5F;
            } else if (state.is(ModdedTags.Block.SOLAR_QUARTER_LIGHT.tag)) {
                light *= 0.25F;
            } else {
                return 0.0F;
            }
        }
        return light;
    }

    /** Señal del sensor de luz del día de Power Grid (−1..1, 1 con el Sol en lo alto), con el Sol real. */
    public static float daylightSignal(Level level, BlockPos pos, boolean inverted) {
        Sunlight.Sun sun = Sunlight.at(level, world(level, Vec3.atCenterOf(pos)));
        float signal;
        if (!sun.ground()) {
            // En el espacio no hay "arriba": de día siempre, salvo en un eclipse.
            signal = sun.watts() > 0.0 ? 1.0F : -1.0F;
        } else {
            signal = (float) (1.0 - Math.abs(Sunlight.vanillaAngle(sun)) / (Math.PI / 2.0));
        }
        return inverted ? -signal : signal;
    }
}
