package dev.createuniversal.space.surface;

import dev.createuniversal.CreateUniversal;
import dev.createuniversal.space.SolarSystem;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * Un astro con superficie donde aterrizar (D22, D74–D76): su dimensión, su tamaño real y cómo se pasa del globo al mapa.
 *
 * <ul>
 *   <li>Escala 1:1 en horizontal (1 bloque = 1 m): x = longitud × radio, z = −latitud × radio (norte = −Z).</li>
 *   <li>Relieve real de la NASA ×0,15 en altura; por encima de {@link #shoulderMeters} se aprieta más
 *       ({@link #shoulderScale}) para que el Monte Olimpo quepa en la altura de Minecraft.</li>
 *   <li>Al subir por encima de {@link #exitY} vuelves al espacio; desde el espacio se entra al tocar
 *       {@link #entryAltitude} bloques sobre la esfera del astro.</li>
 * </ul>
 *
 * @param gravity  gravedad respecto a la de la Tierra
 * @param pressure presión del aire en la superficie respecto a la de la Tierra
 */
public record PlanetSurface(String bodyId, ResourceKey<Level> dimension, double radiusMeters, int minY, int height, int exitY,
                            double datumY, double heightScale, double shoulderMeters, double shoulderScale, double gravity,
                            double pressure, double entryAltitude, double craterDensity) {

    // Minecraft solo permite alturas entre Y −2.032 y 2.031: Marte (del fondo de Hellas a la cima del Olimpo, con el
    // "hombro") se baja 600 bloques para caber entero.
    public static final PlanetSurface MOON = new PlanetSurface("moon", key("moon"), 1_737_400.0, -1408, 3264, 1820,
            0.0, 0.15, 1.0e9, 0.15, 0.165, 0.0, 40.0, 1.0);
    public static final PlanetSurface MARS = new PlanetSurface("mars", key("mars"), 3_389_500.0, -2032, 4064, 2000,
            -600.0, 0.15, 12_000.0, 0.06, 0.38, 0.006, 60.0, 0.3);

    public static final List<PlanetSurface> ALL = List.of(MOON, MARS);

    private static ResourceKey<Level> key(String name) {
        return ResourceKey.create(Registries.DIMENSION, CreateUniversal.id(name));
    }

    public static PlanetSurface byDimension(ResourceKey<Level> dimension) {
        for (PlanetSurface p : ALL) {
            if (p.dimension().equals(dimension)) {
                return p;
            }
        }
        return null;
    }

    public static PlanetSurface byBody(String bodyId) {
        for (PlanetSurface p : ALL) {
            if (p.bodyId().equals(bodyId)) {
                return p;
            }
        }
        return null;
    }

    public SolarSystem.Body body() {
        return SolarSystem.get(bodyId);
    }

    /** Media vuelta en bloques: el mapa va de x = −halfWidth a +halfWidth. */
    public double halfWidth() {
        return Math.PI * radiusMeters;
    }

    /** Del polo norte (z = −halfHeight) al polo sur (z = +halfHeight). */
    public double halfHeight() {
        return Math.PI * 0.5 * radiusMeters;
    }

    public double longitude(double x) {
        return x / radiusMeters;
    }

    public double latitude(double z) {
        return Math.max(-Math.PI * 0.5, Math.min(Math.PI * 0.5, -z / radiusMeters));
    }

    public double x(double longitude) {
        return longitude * radiusMeters;
    }

    public double z(double latitude) {
        return -latitude * radiusMeters;
    }

    /** Altura real (metros) a la y de Minecraft, con el "hombro" para las montañas más altas. */
    public double scaleHeight(double meters) {
        if (meters <= shoulderMeters) {
            return datumY + meters * heightScale;
        }
        return datumY + shoulderMeters * heightScale + (meters - shoulderMeters) * shoulderScale;
    }
}
