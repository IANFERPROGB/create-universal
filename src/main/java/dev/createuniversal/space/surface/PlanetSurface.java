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
 * Los astros sin mapa de alturas de la NASA en el mod (Mercurio, Venus y las lunas grandes, alpha.6) usan
 * {@link ProceduralRelief}, con la misma escala de alturas; el "hombro" se aplica también hacia abajo, a las cuencas
 * más hondas.
 *
 * @param gravity  gravedad respecto a la de la Tierra
 * @param pressure presión del aire en la superficie respecto a la de la Tierra
 * @param palette  los bloques del suelo
 * @param fogColor color de la niebla del horizonte a pleno día (sin aire no hay niebla)
 * @param haze     cuánto tapa el aire las estrellas de noche (Venus y Titán: nubes y bruma espesas)
 */
public record PlanetSurface(String bodyId, ResourceKey<Level> dimension, double radiusMeters, int minY, int height, int exitY,
                            double datumY, double heightScale, double shoulderMeters, double shoulderScale, double gravity,
                            double pressure, double entryAltitude, double craterDensity, Palette palette, int fogColor,
                            double haze) {

    /** Suelo suelto y roca de las zonas claras y de las oscuras (nombres de bloque de Create Universal). */
    public record Palette(String soil, String darkSoil, String rock, String darkRock) {}

    private static final Palette ICE = new Palette("ice_regolith", "dark_ice_regolith", "cryo_ice", "dark_cryo_ice");

    // Minecraft solo permite alturas entre Y −2.032 y 2.031: Marte (del fondo de Hellas a la cima del Olimpo, con el
    // "hombro") se baja 600 bloques para caber entero.
    public static final PlanetSurface MOON = new PlanetSurface("moon", key("moon"), 1_737_400.0, -1408, 3264, 1820,
            0.0, 0.15, 1.0e9, 0.15, 0.165, 0.0, 40.0, 1.0,
            new Palette("lunar_regolith", "lunar_mare_regolith", "lunar_rock", "lunar_basalt"), 0, 0.0);
    public static final PlanetSurface MARS = new PlanetSurface("mars", key("mars"), 3_389_500.0, -2032, 4064, 2000,
            -600.0, 0.15, 12_000.0, 0.06, 0.38, 0.006, 60.0, 0.3,
            new Palette("martian_dust", "martian_regolith", "martian_rock", "martian_basalt"), 0xB8855C, 0.0);

    // alpha.6. Todos comparten el mismo hueco de alturas (Y −1.280 a 1.535): el relieve generado cabe holgado.
    public static final PlanetSurface MERCURY = generated("mercury", 2_439_700.0, 0.378, 0.0, 60.0, 1.2,
            new Palette("mercurian_regolith", "mercurian_dark_regolith", "mercurian_rock", "mercurian_basalt"), 0, 0.0);
    // 92 atmósferas de verdad; para la física de Sable (hélices, globos) se queda en 8 hasta la alpha.8 (D78).
    public static final PlanetSurface VENUS = generated("venus", 6_051_800.0, 0.904, 92.0, 60.0, 0.03,
            new Palette("venusian_dust", "venusian_regolith", "venusian_rock", "venusian_basalt"), 0xC9944A, 1.0);
    public static final PlanetSurface IO = generated("io", 1_821_600.0, 0.183, 0.0, 50.0, 0.0,
            new Palette("sulfur_frost", "ionian_sulfur_dust", "ionian_rock", "ionian_basalt"), 0, 0.0);
    public static final PlanetSurface EUROPA = generated("europa", 1_560_800.0, 0.134, 0.0, 50.0, 0.03,
            new Palette("ice_regolith", "salt_ice_regolith", "cryo_ice", "cryo_ice"), 0, 0.0);
    public static final PlanetSurface GANYMEDE = generated("ganymede", 2_634_100.0, 0.146, 0.0, 50.0, 0.6, ICE, 0, 0.0);
    public static final PlanetSurface CALLISTO = generated("callisto", 2_410_300.0, 0.126, 0.0, 50.0, 1.3, ICE, 0, 0.0);
    public static final PlanetSurface MIMAS = generated("mimas", 198_200.0, 0.0065, 0.0, 20.0, 1.2, ICE, 0, 0.0);
    public static final PlanetSurface ENCELADUS = generated("enceladus", 252_100.0, 0.0115, 0.0, 20.0, 0.3, ICE, 0, 0.0);
    public static final PlanetSurface TETHYS = generated("tethys", 531_100.0, 0.0149, 0.0, 30.0, 1.1, ICE, 0, 0.0);
    public static final PlanetSurface DIONE = generated("dione", 561_400.0, 0.0237, 0.0, 30.0, 0.9, ICE, 0, 0.0);
    public static final PlanetSurface RHEA = generated("rhea", 763_800.0, 0.0269, 0.0, 30.0, 1.2, ICE, 0, 0.0);
    public static final PlanetSurface TITAN = generated("titan", 2_574_700.0, 0.138, 1.45, 60.0, 0.02,
            new Palette("tholin_dust", "tholin_sand", "cryo_ice", "dark_cryo_ice"), 0xB8803C, 0.85);
    public static final PlanetSurface IAPETUS = generated("iapetus", 734_500.0, 0.0228, 0.0, 30.0, 1.2, ICE, 0, 0.0);
    public static final PlanetSurface MIRANDA = generated("miranda", 235_800.0, 0.0081, 0.0, 20.0, 0.7, ICE, 0, 0.0);
    public static final PlanetSurface ARIEL = generated("ariel", 578_900.0, 0.0275, 0.0, 30.0, 0.5, ICE, 0, 0.0);
    public static final PlanetSurface UMBRIEL = generated("umbriel", 584_700.0, 0.024, 0.0, 30.0, 1.1,
            new Palette("dark_ice_regolith", "dark_ice_regolith", "dark_cryo_ice", "dark_cryo_ice"), 0, 0.0);
    public static final PlanetSurface TITANIA = generated("titania", 788_400.0, 0.0387, 0.0, 30.0, 0.8, ICE, 0, 0.0);
    public static final PlanetSurface OBERON = generated("oberon", 761_400.0, 0.0354, 0.0, 30.0, 1.1, ICE, 0, 0.0);
    // Tritón tiene una atmósfera de nitrógeno 70.000 veces más fina que la nuestra: a efectos de juego, sin aire.
    public static final PlanetSurface TRITON = generated("triton", 1_353_400.0, 0.0794, 0.0, 40.0, 0.05,
            new Palette("nitrogen_frost", "dark_ice_regolith", "cryo_ice", "dark_cryo_ice"), 0, 0.0);

    public static final List<PlanetSurface> ALL = List.of(MOON, MARS, MERCURY, VENUS, IO, EUROPA, GANYMEDE, CALLISTO, MIMAS,
            ENCELADUS, TETHYS, DIONE, RHEA, TITAN, IAPETUS, MIRANDA, ARIEL, UMBRIEL, TITANIA, OBERON, TRITON);

    private static PlanetSurface generated(String id, double radius, double gravity, double pressure, double entry,
                                           double craters, Palette palette, int fog, double haze) {
        return new PlanetSurface(id, key(id), radius, -1280, 2816, 1500, 0.0, 0.15, 4000.0, 0.04, gravity, pressure, entry,
                craters, palette, fog, haze);
    }

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

    /** Sin aire que cuente: cielo negro, estrellas de día, sin niebla. */
    public boolean airless() {
        return pressure < 0.001;
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

    /** Altura real (metros) a la y de Minecraft, con el "hombro" para las montañas más altas y las cuencas más hondas. */
    public double scaleHeight(double meters) {
        if (meters > shoulderMeters) {
            return datumY + shoulderMeters * heightScale + (meters - shoulderMeters) * shoulderScale;
        }
        if (meters < -shoulderMeters) {
            return datumY - shoulderMeters * heightScale + (meters + shoulderMeters) * shoulderScale;
        }
        return datumY + meters * heightScale;
    }
}
