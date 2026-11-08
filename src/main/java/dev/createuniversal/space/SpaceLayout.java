package dev.createuniversal.space;

import net.minecraft.world.phys.Vec3;

/**
 * Dónde está cada cosa dentro de la dimensión espacio.
 * alpha.2: la Tierra está fija en su punto de la órbita. En la alpha.4 se sustituye por el sistema solar con órbitas
 * (escala B, sección 4.4 y D73: 1 bloque ≈ 6 km, la Luna a 8,5 min yendo a 125 bloques/s).
 */
public final class SpaceLayout {
    /** 1 bloque ≈ 6 km: la Tierra mide ~2.110 bloques de diámetro (D73). */
    public static final double EARTH_RADIUS = 1056.0;
    /** Capa de atmósfera alrededor de la esfera: al tocarla empieza la reentrada. */
    public static final double EARTH_ATMOSPHERE = 125.0;
    /** Altura sobre la superficie a la que apareces al salir (órbita baja). */
    public static final double ARRIVAL_ALTITUDE = 470.0;
    /**
     * Centro de la Tierra: en su radio de órbita alrededor del Sol (312.500, D73) y bajo el suelo de la dimensión, para que
     * la órbita baja de llegada (Y = 56) quede dentro de la altura construible.
     */
    public static final Vec3 EARTH_CENTER = new Vec3(312_500.0, -1470.0, 0.0);

    private SpaceLayout() {}

    public static Vec3 arrivalPoint() {
        return EARTH_CENTER.add(0.0, EARTH_RADIUS + ARRIVAL_ALTITUDE, 0.0);
    }

    public static boolean insideEarthAtmosphere(Vec3 pos) {
        return pos.distanceTo(EARTH_CENTER) < EARTH_RADIUS + EARTH_ATMOSPHERE;
    }
}
