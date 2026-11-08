package dev.createuniversal.space;

import net.minecraft.world.phys.Vec3;

/**
 * Dónde está cada cosa dentro de la dimensión espacio.
 * alpha.1: la Tierra está fija. En la alpha.4 se sustituye por el sistema solar con órbitas (escala B, sección 4.4).
 */
public final class SpaceLayout {
    /** 1 bloque ≈ 19 km: la Tierra mide ~676 bloques de diámetro (sección 4.4). */
    public static final double EARTH_RADIUS = 338.0;
    /** Capa de atmósfera alrededor de la esfera: al tocarla empieza la reentrada. */
    public static final double EARTH_ATMOSPHERE = 40.0;
    /** Altura sobre la superficie a la que apareces al salir (órbita baja). */
    public static final double ARRIVAL_ALTITUDE = 150.0;
    /** Centro de la Tierra. Va bajo el suelo de la dimensión para que la órbita baja quede dentro de la altura construible. */
    public static final Vec3 EARTH_CENTER = new Vec3(100_000.0, -400.0, 0.0);

    private SpaceLayout() {}

    public static Vec3 arrivalPoint() {
        return EARTH_CENTER.add(0.0, EARTH_RADIUS + ARRIVAL_ALTITUDE, 0.0);
    }

    public static boolean insideEarthAtmosphere(Vec3 pos) {
        return pos.distanceTo(EARTH_CENTER) < EARTH_RADIUS + EARTH_ATMOSPHERE;
    }
}
