package dev.createuniversal.space;

import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * La Tierra dentro de la dimensión espacio: dónde se sale al espacio y dónde empieza la reentrada.
 * Desde la alpha.4 la Tierra se mueve por su órbita ({@link SolarSystem}), así que todo depende del tiempo.
 */
public final class SpaceLayout {
    /** 1 bloque ≈ 6 km: la Tierra mide ~2.110 bloques de diámetro (D73). */
    public static final double EARTH_RADIUS = 1056.0;
    /** Capa de atmósfera alrededor de la esfera: al tocarla empieza la reentrada. */
    public static final double EARTH_ATMOSPHERE = 125.0;
    /** Altura sobre la superficie a la que apareces al salir (órbita baja). */
    public static final double ARRIVAL_ALTITUDE = 470.0;

    private SpaceLayout() {}

    /** Tiempo del sistema solar: dayTime del Overworld (compartido por todas las dimensiones). */
    public static double time(Level level) {
        return level.getDayTime();
    }

    public static Vec3 earthCenter(double time) {
        return SolarSystem.position("earth", time);
    }

    /** Velocidad orbital de la Tierra en bloques/segundo (~16 b/s): lo que llega de la Tierra la lleva consigo. */
    public static Vec3 earthVelocity(double time) {
        return SolarSystem.velocity(SolarSystem.get("earth"), time);
    }

    public static Vec3 arrivalPoint(double time) {
        return earthCenter(time).add(0.0, EARTH_RADIUS + ARRIVAL_ALTITUDE, 0.0);
    }

    public static boolean insideEarthAtmosphere(Vec3 pos, double time) {
        return pos.distanceTo(earthCenter(time)) < EARTH_RADIUS + EARTH_ATMOSPHERE;
    }
}
