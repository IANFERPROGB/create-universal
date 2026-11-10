package dev.createuniversal.space.surface;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Relieve generado para los astros de los que el mod no trae mapa de alturas (alpha.6): Mercurio, Venus y las lunas
 * grandes. Cada astro tiene su estilo, sacado de lo que se sabe de él (cuencas de impacto, volcanes, surcos del hielo,
 * dunas...), y sus accidentes más conocidos están en sus coordenadas reales (Caloris, Herschel, Odysseus, la cresta
 * ecuatorial de Jápeto, los Montes Maxwell...).
 *
 * <ul>
 *   <li>{@link #meters}: las formas grandes, en metros reales, calculadas sobre la esfera (sin costura ni polos raros).
 *       Luego pasan por la misma escala de alturas que la Luna y Marte.</li>
 *   <li>{@link #detail}: el detalle a escala de bloque (cráteres de 8 bloques a 100 km, ondulaciones, crestas, dunas),
 *       ya en bloques, con las celdas ajustadas para que el mapa case al dar la vuelta al astro.</li>
 * </ul>
 *
 * Todo determinista y sin guardar nada. No usa clases de Minecraft: se puede probar suelto.
 */
public final class ProceduralRelief {
    /** Zona oscura (1) o clara (0) del astro en esa longitud y latitud, suavizado. */
    public interface DarkMap {
        double at(double lon, double lat);
    }

    /**
     * @param broad          amplitud (m) del relieve a escala de continente
     * @param broadPower     1 = normal; mayor = llanuras planas con pocas tierras altas
     * @param mid            amplitud (m) del relieve de decenas de km
     * @param basins         nº de grandes cuencas de impacto repartidas al azar
     * @param basinMaxKm     diámetro de la mayor
     * @param relax          1 = cráteres frescos; menor = relajados (el hielo cede con el tiempo)
     * @param midCraters     densidad de cráteres de 2 a 100 km
     * @param mareCraters    cuánto bajan los cráteres en las zonas oscuras (negativo: hay más)
     * @param mountains      nº de montañas o volcanes
     * @param mountainKm     altura típica
     * @param shields        true = volcanes en escudo con caldera; false = macizos abruptos
     * @param pits           nº de páteras (calderas hundidas de fondo plano)
     * @param ridgeM         altura (m) de crestas largas; negativo = cañones
     * @param ridgeKm        separación entre crestas
     * @param ridgeSharp     lo afiladas que son (2 = lomas anchas, 14 = líneas finas)
     * @param bands          0 = crestas curvas; si no, nº de familias de líneas rectas paralelas que se cruzan (las
     *                       fracturas del hielo de Europa, los surcos de Ganimedes, los cañones de Dione)
     * @param ridgesInBright solo en el terreno claro (Ganimedes)
     * @param fineRidge      altura (bloques) de los surcos pequeños
     * @param dunes          altura (bloques) de las dunas del ecuador en las zonas oscuras (Titán)
     * @param rough          ondulación del suelo respecto a la de la Luna
     */
    public record Style(double broad, double broadPower, double mid, int basins, double basinMaxKm, double relax,
                        double midCraters, double mareCraters, int mountains, double mountainKm, boolean shields, int pits,
                        double ridgeM, double ridgeKm, double ridgeSharp, int bands, boolean ridgesInBright,
                        double fineRidge,
                        double dunes, double rough) {}

    private static final Map<String, Style> STYLES = new HashMap<>();

    static {
        //                              broad pow  mid  bas maxKm relax midCr mare mnt km  shield pits ridge km sharp bands bright fine dunes rough
        STYLES.put("mercury", new Style(1800, 1.0, 500, 26, 700, 0.8, 1.2, 0.4, 0, 0, false, 0, 600, 300, 10, 0, false, 0, 0, 1.0));
        STYLES.put("venus", new Style(1500, 1.6, 250, 0, 0, 0.8, 0.03, 0.0, 30, 3.5, true, 0, 300, 60, 6, 0, false, 3, 0, 0.8));
        STYLES.put("io", new Style(800, 1.2, 150, 0, 0, 1.0, 0.0, 0.0, 70, 6.0, false, 150, 0, 0, 0, 0, false, 0, 0, 0.7));
        STYLES.put("europa", new Style(150, 1.0, 60, 0, 0, 0.4, 0.03, 0.0, 0, 0, false, 0, 220, 45, 14, 7, false, 8, 0, 0.6));
        STYLES.put("ganymede", new Style(900, 1.0, 250, 10, 500, 0.4, 0.6, -0.8, 0, 0, false, 0, 500, 10, 2, 6, true, 6, 0, 0.8));
        STYLES.put("callisto", new Style(600, 1.0, 250, 12, 400, 0.35, 1.3, 0.0, 0, 0, false, 0, 0, 0, 0, 0, false, 0, 0, 1.0));
        STYLES.put("mimas", new Style(1200, 1.0, 500, 4, 60, 0.9, 1.2, 0.0, 0, 0, false, 0, 0, 0, 0, 0, false, 0, 0, 1.0));
        STYLES.put("enceladus", new Style(500, 1.0, 150, 0, 0, 0.5, 0.3, 0.0, 0, 0, false, 0, 350, 12, 6, 6, false, 8, 0, 0.7));
        STYLES.put("tethys", new Style(1200, 1.0, 400, 5, 150, 0.6, 1.1, 0.0, 0, 0, false, 0, 0, 0, 0, 0, false, 0, 0, 1.0));
        STYLES.put("dione", new Style(900, 1.0, 300, 5, 150, 0.6, 0.9, 0.0, 0, 0, false, 0, -600, 80, 12, 5, false, 0, 0, 0.9));
        STYLES.put("rhea", new Style(1000, 1.0, 400, 6, 200, 0.6, 1.2, 0.0, 0, 0, false, 0, 0, 0, 0, 0, false, 0, 0, 1.0));
        STYLES.put("titan", new Style(400, 1.3, 120, 2, 300, 0.2, 0.02, 0.0, 25, 1.5, false, 0, 0, 0, 0, 0, false, 0, 14, 0.6));
        STYLES.put("iapetus", new Style(2500, 1.0, 600, 8, 400, 0.9, 1.2, 0.0, 0, 0, false, 0, 0, 0, 0, 0, false, 0, 0, 1.0));
        STYLES.put("miranda", new Style(1500, 1.0, 600, 2, 50, 0.8, 0.7, 0.0, 0, 0, false, 0, 600, 25, 3, 0, false, 5, 0, 0.9));
        STYLES.put("ariel", new Style(800, 1.0, 300, 3, 80, 0.6, 0.5, 0.0, 0, 0, false, 0, -1500, 90, 10, 5, false, 4, 0, 0.8));
        STYLES.put("umbriel", new Style(900, 1.0, 350, 5, 150, 0.6, 1.1, 0.0, 0, 0, false, 0, 0, 0, 0, 0, false, 0, 0, 1.0));
        STYLES.put("titania", new Style(900, 1.0, 300, 4, 200, 0.6, 0.8, 0.0, 0, 0, false, 0, -1200, 150, 12, 4, false, 0, 0, 0.9));
        STYLES.put("oberon", new Style(1000, 1.0, 350, 5, 200, 0.6, 1.1, 0.0, 0, 0, false, 0, 0, 0, 0, 0, false, 0, 0, 1.0));
        STYLES.put("triton", new Style(300, 1.0, 100, 0, 0, 0.5, 0.05, 0.0, 0, 0, false, 0, 350, 30, 2, 0, false, 3, 0, 0.6));
    }

    public static boolean has(String bodyId) {
        return STYLES.containsKey(bodyId);
    }

    // ---------------------------------------------------------------- accidentes sobre la esfera

    private static final int BASIN = 0;
    private static final int MASSIF = 1;
    private static final int SHIELD = 2;
    private static final int PATERA = 3;
    private static final int RINGS = 4;
    private static final int TROUGH = 5;
    private static final int CORONA = 6;

    /** Un accidente grande: centro (vector unitario), radio angular, tamaño vertical en metros y un dato más según el tipo. */
    private record Feature(int type, double x, double y, double z, double radius, double cosLimit, double size, double extra) {}

    /** Celdas de cráteres (bloques) y probabilidad de que la celda tenga uno. Las tres primeras son los "medianos". */
    private static final double[] CELLS = {131072, 32768, 8192, 2048, 512, 128, 32, 8};
    private static final double[] CHANCE = {0.5, 0.5, 0.5, 0.35, 0.45, 0.55, 0.6, 0.5};
    private static final int MID_LEVELS = 3;

    private final String id;
    private final Style style;
    private final double radius;
    private final double radiusKm;
    private final double craterDensity;
    private final DarkMap dark;
    private final long seed;
    private final Feature[] features;
    private final double mapWidth;
    private final double[] cellSize = new double[CELLS.length];
    private final long[] cellCount = new long[CELLS.length];
    private final double roughFine;
    private final long roughFinePeriod;
    private final double roughCoarse;
    private final long roughCoarsePeriod;
    private final double[][] bandDirs = new double[8][];

    public ProceduralRelief(String bodyId, double radiusMeters, double craterDensity, DarkMap dark) {
        this.id = bodyId;
        this.style = STYLES.get(bodyId);
        this.radius = radiusMeters;
        this.radiusKm = radiusMeters / 1000.0;
        this.craterDensity = craterDensity;
        this.dark = dark;
        this.seed = bodyId.hashCode() * 0x9E3779B97F4A7C15L;
        this.mapWidth = 2.0 * Math.PI * radiusMeters;
        for (int i = 0; i < CELLS.length; i++) {
            // Un número entero de celdas alrededor del astro: los cráteres casan en la costura del mapa.
            cellCount[i] = Math.max(1, Math.round(mapWidth / CELLS[i]));
            cellSize[i] = mapWidth / cellCount[i];
        }
        roughFinePeriod = Math.max(1, Math.round(mapWidth / 96.0));
        roughFine = mapWidth / roughFinePeriod;
        roughCoarsePeriod = Math.max(1, Math.round(mapWidth / 640.0));
        roughCoarse = mapWidth / roughCoarsePeriod;
        Random rng = new Random(seed ^ 0xBA2D5L);
        for (int i = 0; i < bandDirs.length; i++) {
            double[] p = randomPoint(rng);
            bandDirs[i] = new double[] {p[0], p[1], p[2], 0.7 + 0.8 * rng.nextDouble()};
        }
        this.features = buildFeatures();
    }

    private Feature[] buildFeatures() {
        List<Feature> list = new ArrayList<>();
        Random rng = new Random(seed);
        landmarks(list);
        double minKm = Math.min(60.0, 0.15 * radiusKm);
        double maxKm = Math.max(minKm * 1.01, style.basinMaxKm() * 0.5);
        for (int i = 0; i < style.basins(); i++) {
            // Tamaños como los reales: muchas pequeñas, pocas enormes (N(>r) ∝ r⁻²).
            double u = rng.nextDouble();
            double q = minKm / maxKm;
            double rKm = minKm / Math.sqrt(1.0 - u * (1.0 - q * q));
            double[] p = randomPoint(rng);
            list.add(basin(p, rKm, craterDepth(rKm * 2.0) * style.relax(), 1.0));
        }
        for (int i = 0; i < style.mountains(); i++) {
            double[] p = randomPoint(rng);
            double h = style.mountainKm() * 1000.0 * (0.4 + 1.2 * rng.nextDouble() * rng.nextDouble() + 0.3 * rng.nextDouble());
            double rKm = style.shields() ? 60.0 + 240.0 * rng.nextDouble() : 25.0 + 90.0 * rng.nextDouble();
            list.add(feature(style.shields() ? SHIELD : MASSIF, p, rKm, 1.0, h, rng.nextInt(1000)));
        }
        for (int i = 0; i < style.pits(); i++) {
            double[] p = randomPoint(rng);
            double rKm = 8.0 + 35.0 * rng.nextDouble() * rng.nextDouble();
            list.add(feature(PATERA, p, rKm, 1.3, 800.0 + 1400.0 * rng.nextDouble(), rng.nextInt(1000)));
        }
        return list.toArray(new Feature[0]);
    }

    /** Los accidentes más conocidos de cada astro, en sus coordenadas reales (latitud, longitud este, en grados). */
    private void landmarks(List<Feature> list) {
        switch (id) {
            case "mercury" -> {
                list.add(basin(at(31.5, 162.7), 775, 2000, 2.0));   // Caloris
                list.add(basin(at(-33.0, 88.0), 357, 2500, 2.0));   // Rembrandt
                list.add(basin(at(-20.0, -123.9), 315, 2200, 2.0)); // Beethoven
                list.add(basin(at(-16.0, -164.9), 178, 2500, 1.0)); // Tolstoj
                list.add(basin(at(27.6, 57.6), 145, 3500, 2.0));    // Rachmaninoff
            }
            case "venus" -> {
                list.add(feature(MASSIF, at(68.0, -30.0), 900, 1.0, 3500, 7));    // Ishtar Terra (Lakshmi Planum)
                list.add(feature(MASSIF, at(65.2, 3.3), 380, 1.0, 8500, 11));     // Montes Maxwell (11 km con Ishtar)
                list.add(feature(MASSIF, at(-5.0, 75.0), 1500, 1.0, 3500, 21));   // Aphrodite Terra: Ovda
                list.add(feature(MASSIF, at(-10.0, 105.0), 1100, 1.0, 3500, 31)); // Aphrodite Terra: Thetis
                list.add(feature(MASSIF, at(-12.0, 135.0), 900, 1.0, 2500, 41));
                list.add(feature(MASSIF, at(25.0, -77.0), 800, 1.0, 3500, 51));   // Beta Regio
                list.add(feature(SHIELD, at(0.5, -165.4), 200, 1.0, 8000, 61));   // Maat Mons
                list.add(feature(SHIELD, at(22.0, -2.0), 350, 1.0, 5000, 71));    // Gula y Sif Mons
            }
            case "io" -> {
                list.add(feature(PATERA, at(13.0, 51.2), 100, 1.3, 1500, 3));     // Loki Patera
                list.add(feature(MASSIF, at(-18.0, -53.0), 70, 1.0, 17500, 5));   // Boösaule Montes
            }
            case "ganymede" -> list.add(basin(at(-62.0, -125.0), 295, 1200, 2.0)); // Gilgamesh
            case "callisto" -> {
                list.add(feature(RINGS, at(14.7, -56.0), 1500, 1.0, 700, 55));    // Valhalla
                list.add(feature(RINGS, at(32.0, -140.0), 800, 1.0, 600, 45));    // Asgard
            }
            case "mimas" -> list.add(basin(at(-1.4, -111.8), 69.5, 10000, 3.0));  // Herschel: 10 km de hondo, pico de 6
            case "tethys" -> {
                double[] odysseus = at(32.8, -128.9);
                list.add(basin(odysseus, 222, 3000, 2.0));
                list.add(feature(TROUGH, odysseus, 1.0, 1.0, 3000, 0));           // Ithaca Chasma, a 90° de Odysseus
            }
            case "dione" -> list.add(basin(at(-57.0, -145.0), 175, 2500, 1.0));   // Evander
            case "rhea" -> {
                list.add(basin(at(34.2, -151.7), 180, 3500, 1.0));                // Tirawa
                list.add(basin(at(16.0, 177.0), 240, 2500, 1.0));                 // Mamaldi
            }
            case "iapetus" -> {
                list.add(basin(at(17.0, -28.4), 290, 6000, 1.0));                 // Turgis
                list.add(basin(at(-41.0, 95.3), 252, 6000, 1.0));                 // Engelier
            }
            case "titania" -> list.add(basin(at(-15.8, -72.9), 163, 2500, 1.0));  // Gertrude
            case "oberon" -> {
                list.add(basin(at(-46.0, 44.0), 103, 2500, 1.0));                 // Hamlet
                list.add(feature(MASSIF, at(-10.0, -80.0), 45, 1.0, 11000, 9));   // la montaña de 11 km vista por la Voyager 2
            }
            case "miranda" -> {
                // Las tres "coronas": óvalos de surcos concéntricos, únicos en el sistema solar.
                list.add(feature(CORONA, at(-29.1, 73.7), 140, 1.0, 1200, 7));     // Arden
                list.add(feature(CORONA, at(-24.8, -102.9), 140, 1.0, 1200, 7));   // Elsinore
                list.add(feature(CORONA, at(-66.9, -34.3), 100, 1.0, 1200, 6));    // Inverness
            }
            case "umbriel" -> list.add(basin(at(-7.9, -86.4), 65, 2500, 1.0));    // Wunda
            case "titan" -> list.add(basin(at(19.6, -87.0), 196, 500, 2.0));      // Menrva
            default -> {
            }
        }
    }

    private static double[] at(double latDeg, double lonDeg) {
        double lat = Math.toRadians(latDeg);
        double lon = Math.toRadians(lonDeg);
        return new double[] {Math.cos(lat) * Math.cos(lon), Math.sin(lat), Math.cos(lat) * Math.sin(lon)};
    }

    private static double[] randomPoint(Random rng) {
        double y = 2.0 * rng.nextDouble() - 1.0;
        double a = 2.0 * Math.PI * rng.nextDouble();
        double c = Math.sqrt(1.0 - y * y);
        return new double[] {c * Math.cos(a), y, c * Math.sin(a)};
    }

    /** @param kind 1 = pico central si le toca por tamaño, 2 = además anillo interior, 3 = pico central enorme */
    private Feature basin(double[] p, double radiusKm, double depth, double kind) {
        return feature(BASIN, p, radiusKm, 2.5, depth, kind);
    }

    private Feature feature(int type, double[] p, double radiusKm, double reach, double size, double extra) {
        double ang = radiusKm / this.radiusKm;
        double limit = ang * reach;
        return new Feature(type, p[0], p[1], p[2], ang, limit >= Math.PI || type == TROUGH ? -2.0 : Math.cos(limit), size, extra);
    }

    /** Profundidad real (m) de un cráter complejo según su diámetro: crece poco con el tamaño. */
    private static double craterDepth(double diameterKm) {
        return 1000.0 * Math.min(4.5, 0.9 * Math.pow(diameterKm, 0.3));
    }

    // ---------------------------------------------------------------- formas grandes (metros)

    /** Altura real en metros sobre el nivel medio del astro. */
    public double meters(double lon, double lat) {
        double cl = Math.cos(lat);
        double px = cl * Math.cos(lon);
        double py = Math.sin(lat);
        double pz = cl * Math.sin(lon);
        double dk = dark.at(lon, lat);
        double m = 0.0;

        if (style.broad() > 0.0) {
            double c = Math.max(-1.0, Math.min(1.0, (2.0 * fbm3(px, py, pz, 1.3, 5, 11) - 1.0) * 1.8));
            m += style.broad() * Math.signum(c) * Math.pow(Math.abs(c), style.broadPower());
        }
        if (style.mid() > 0.0) {
            m += style.mid() * 1.8 * (2.0 * fbm3(px, py, pz, radiusKm / 60.0, 5, 23) - 1.0);
        }
        if (style.ridgeM() != 0.0) {
            double r;
            if (style.bands() > 0) {
                r = bands(px, py, pz, style.ridgeKm() * 1000.0, style.ridgeSharp(), 31, style.bands(), 2.5);
            } else {
                double f = radiusKm / style.ridgeKm();
                r = ridged(px, py, pz, f, 31, style.ridgeSharp()) + 0.5 * ridged(px, py, pz, f * 2.7, 37, style.ridgeSharp());
            }
            m += style.ridgeM() * r * ridgeMask(px, py, pz, dk);
        }

        for (Feature f : features) {
            double dot = px * f.x + py * f.y + pz * f.z;
            if (dot < f.cosLimit) {
                continue;
            }
            double ang = Math.acos(Math.max(-1.0, Math.min(1.0, dot)));
            double d = ang / f.radius;
            switch (f.type) {
                case BASIN -> m += basinProfile(d, f.size, f.radius * radiusKm, f.extra);
                case SHIELD -> {
                    if (d < 1.0) {
                        double s = 1.0 - d * d;
                        m += f.size * s * s - 0.08 * f.size * (1.0 - smooth(0.05, 0.09, d));
                    }
                }
                case MASSIF -> {
                    if (d < 1.0) {
                        double s = 1.0 - d * d;
                        double rugged = 0.55 + 0.9 * fbm3(px, py, pz, radiusKm / 18.0, 4, 41 + (long) f.extra);
                        m += f.size * s * s * rugged;
                    }
                }
                case PATERA -> {
                    // Borde irregular: las páteras de Ío no son redondas.
                    double k = radiusKm / 25.0;
                    double dd = d * (0.8 + 0.5 * noise3(px * k, py * k, pz * k, 53 + (long) f.extra));
                    if (dd < 1.0) {
                        m -= f.size * (1.0 - smooth(0.8, 1.0, dd));
                    }
                }
                case RINGS -> {
                    if (d < 1.0) {
                        // Anillos concéntricos de escarpes (Valhalla): cada vez más bajos hacia fuera.
                        double w = 0.5 + 0.5 * Math.cos(2.0 * Math.PI * ang * radiusKm / f.extra);
                        m += f.size * w * w * w * w * smooth(0.1, 0.2, d) * (1.0 - d);
                    }
                }
                case CORONA -> {
                    double dd = d * (0.85 + 0.3 * noise3(px * 3.0, py * 3.0, pz * 3.0, 59));
                    if (dd < 1.0) {
                        double w = Math.cos(2.0 * Math.PI * dd * f.radius * radiusKm / f.extra);
                        m += (1.0 - smooth(0.85, 1.0, dd)) * f.size * (0.5 * w - 0.6);
                    }
                }
                case TROUGH -> {
                    // Una fosa que sigue un círculo máximo (Ithaca Chasma), con tramos más y menos hondos.
                    double t = (ang - Math.PI * 0.5) / 0.09;
                    if (Math.abs(t) < 3.0) {
                        m -= f.size * Math.exp(-t * t) * (0.35 + 0.65 * noise3(px * 2.0, py * 2.0, pz * 2.0, 67));
                    }
                }
                default -> {
                }
            }
        }

        if (id.equals("iapetus")) {
            // La cresta ecuatorial: 13 km de alto y 20 de ancho, sobre todo en el hemisferio oscuro.
            double t = lat * radiusKm / 10.0;
            if (Math.abs(t) < 3.5) {
                m += 13000.0 * Math.exp(-t * t) * (0.25 + 0.75 * dk) * (0.55 + 0.45 * noise3(px * 14.0, py * 14.0, pz * 14.0, 71));
            }
        } else if (id.equals("enceladus") && py < -0.9) {
            // Las "rayas de tigre" del polo sur: cuatro fracturas paralelas de 130 km.
            double a = px * radiusKm;
            double b = pz * radiusKm;
            if (Math.abs(a) < 80.0) {
                double fade = 1.0 - smooth(55.0, 80.0, Math.abs(a));
                for (double line = -52.5; line <= 52.5; line += 35.0) {
                    double t = (b - line) / 1.6;
                    if (Math.abs(t) < 4.0) {
                        double edge = Math.abs(t) - 1.8;
                        m += fade * (-600.0 * Math.exp(-t * t) + 150.0 * Math.exp(-edge * edge));
                    }
                }
            }
        }
        return Math.max(-12000.0, Math.min(20000.0, m));
    }

    private double ridgeMask(double px, double py, double pz, double dk) {
        if (style.ridgesInBright()) {
            return 1.0 - smooth(0.3, 0.7, dk);
        }
        double k = radiusKm / 400.0;
        return 0.35 + 0.65 * smooth(0.3, 0.6, noise3(px * k, py * k, pz * k, 43));
    }

    /**
     * Familias de líneas casi rectas y paralelas que se cruzan entre sí (0..1,5). Cada familia sigue círculos de la
     * esfera perpendiculares a una dirección, vive en unas zonas y no en otras, y con líneas afiladas no todas salen.
     *
     * @param spacing separación entre líneas, en bloques
     * @param patch   tamaño de las zonas de cada familia (ciclos de ruido por radio del astro)
     */
    private double bands(double px, double py, double pz, double spacing, double sharp, long salt, int families, double patch) {
        double total = 0.0;
        double wk = radius / (spacing * 12.0);
        for (int k = 0; k < families; k++) {
            double[] n = bandDirs[k % bandDirs.length];
            double dot = px * n[0] + py * n[1] + pz * n[2];
            double mask = smooth(0.42, 0.6, noise3(px * patch + 9.1 * k, py * patch + 3.3 * k, pz * patch + 6.7 * k, salt + 100 + k))
                    * (1.0 - smooth(0.8, 0.95, Math.abs(dot)));
            if (mask <= 0.0) {
                continue;
            }
            double t = dot * radius / (spacing * n[3]) + 1.5 * noise3(px * wk, py * wk, pz * wk, salt + 200 + k);
            double line = Math.floor(t);
            double tri = 1.0 - Math.abs(2.0 * (t - line) - 1.0);
            double amp = 1.0;
            if (sharp >= 6.0) {
                double u = unit(hash3((long) line, k, 0, salt + 300));
                if (u < 0.35) {
                    continue;
                }
                amp = 0.4 + 0.6 * u;
            }
            total += mask * amp * Math.pow(tri, sharp);
        }
        return Math.min(1.5, total);
    }

    /** Cuenca de impacto: fondo plano, borde levantado, manto de eyecciones y, según el tamaño, pico o anillo interior. */
    private static double basinProfile(double d, double depth, double radiusKm, double kind) {
        double rim = 0.25 * depth;
        double h;
        if (d < 1.0) {
            h = -depth * (1.0 - smooth(0.6, 1.0, d)) + rim * Math.exp(-((d - 1.0) * (d - 1.0)) / 0.0144);
            if (kind >= 3.0) {
                h += 0.6 * depth * Math.exp(-(d * d) / 0.0225);
            } else if (radiusKm > 150.0 && kind >= 2.0) {
                h += 0.3 * depth * Math.exp(-((d - 0.5) * (d - 0.5)) / 0.0049);
            } else if (radiusKm > 15.0 && radiusKm <= 150.0) {
                h += 0.35 * depth * Math.exp(-(d * d) / 0.01);
            }
        } else if (d < 2.5) {
            double t = 1.0 - (d - 1.0) / 1.5;
            h = rim * t * t * t;
        } else {
            h = 0.0;
        }
        return h;
    }

    // ---------------------------------------------------------------- detalle (bloques)

    /** Lo que se suma, en bloques, a la altura ya escalada: cráteres, ondulaciones, surcos y dunas. */
    public double detail(double x, double z, double lon, double lat) {
        double total = craters(x, z);
        double u = x + mapWidth * 0.5;
        total += style.rough() * ((fbmWrapped(u / roughFine, z / roughFine, 4, roughFinePeriod, 99) - 0.5) * 7.0
                + (fbmWrapped(u / roughCoarse, z / roughCoarse, 3, roughCoarsePeriod, 199) - 0.5) * 18.0);
        if (style.fineRidge() > 0.0 || style.dunes() > 0.0) {
            double cl = Math.cos(lat);
            double px = cl * Math.cos(lon);
            double py = Math.sin(lat);
            double pz = cl * Math.sin(lon);
            double dk = dark.at(lon, lat);
            if (style.fineRidge() > 0.0) {
                double r;
                if (style.bands() > 0) {
                    r = bands(px, py, pz, 450.0, Math.min(style.ridgeSharp(), 6.0), 83, 5, radius / 20000.0);
                } else {
                    double f = radius / 1200.0;
                    r = ridged(px, py, pz, f, 83, 6.0) + 0.5 * ridged(px, py, pz, f * 3.3, 89, 6.0);
                }
                total += style.fineRidge() * r * ridgeMask(px, py, pz, dk);
            }
            if (style.dunes() > 0.0) {
                // Dunas largas de este a oeste, cada 3 km, en los mares de arena oscura del ecuador.
                double w = dk * (1.0 - smooth(0.35, 0.55, Math.abs(lat)));
                if (w > 0.0) {
                    double k = radius / 15000.0;
                    double warp = 3.0 * (noise3(px * k, py * k, pz * k, 97) - 0.5);
                    double t = z / 3000.0 + warp;
                    double tri = 1.0 - Math.abs(2.0 * (t - Math.floor(t)) - 1.0);
                    total += style.dunes() * w * tri * tri * (3.0 - 2.0 * tri);
                }
            }
        }
        return total;
    }

    private double craters(double x, double z) {
        double total = 0.0;
        double u0 = x + mapWidth * 0.5;
        for (int level = 0; level < CELLS.length; level++) {
            boolean mid = level < MID_LEVELS;
            double density = mid ? style.midCraters() : craterDensity;
            double cell = cellSize[level];
            // En las lunas pequeñas no caben cráteres generados de 100 km: los grandes van aparte, contados.
            if (density <= 0.0 || (mid && cell > radius * 0.3)) {
                continue;
            }
            double chance = CHANCE[level] * density;
            double most = chance * (1.0 + Math.abs(style.mareCraters()));
            long cx = (long) Math.floor(u0 / cell);
            long cz = (long) Math.floor(z / cell);
            for (long i = cx - 1; i <= cx + 1; i++) {
                for (long k = cz - 1; k <= cz + 1; k++) {
                    long h = hash(Math.floorMod(i, cellCount[level]), k, level);
                    double pick = unit(h);
                    if (pick > most) {
                        continue;
                    }
                    double r = cell * (0.12 + 0.33 * unit(h * 31 + 7));
                    double ox = (i + unit(h * 17 + 3)) * cell - mapWidth * 0.5;
                    double oz = (k + unit(h * 13 + 5)) * cell;
                    double dx = x - ox;
                    double dz = z - oz;
                    double d2 = (dx * dx + dz * dz) / (r * r);
                    if (d2 > 4.0) {
                        continue;
                    }
                    if (style.mareCraters() != 0.0) {
                        // La zona (clara u oscura) se mira en el centro del cráter: o sale entero o no sale.
                        double lat = Math.max(-Math.PI * 0.5, Math.min(Math.PI * 0.5, -oz / radius));
                        if (pick > chance * (1.0 - style.mareCraters() * dark.at(ox / radius, lat))) {
                            continue;
                        }
                    } else if (pick > chance) {
                        continue;
                    }
                    double d = Math.sqrt(d2);
                    double depth;
                    if (r <= 1000.0) {
                        depth = 0.4 * r * (r > 400 ? 0.35 : r > 100 ? 0.55 : 0.8);
                    } else {
                        depth = 140.0 * Math.pow(r / 1000.0, 0.35) * style.relax();
                    }
                    double rim = 0.2 * depth;
                    double bowl;
                    if (d >= 1.0) {
                        bowl = 0.0;
                    } else if (r > 1500.0) {
                        // Cráter complejo: fondo plano y, si es grande, pico central.
                        bowl = -depth * (1.0 - smooth(0.55, 1.0, d));
                        if (r > 6000.0) {
                            bowl += 0.3 * depth * Math.exp(-(d * d) / 0.0144);
                        }
                    } else {
                        bowl = depth * (d2 - 1.0);
                    }
                    total += bowl + rim * Math.exp(-((d - 1.0) * (d - 1.0)) / 0.09);
                }
            }
        }
        return total;
    }

    // ---------------------------------------------------------------- ruido

    private static double smooth(double a, double b, double v) {
        double t = Math.max(0.0, Math.min(1.0, (v - a) / (b - a)));
        return t * t * (3.0 - 2.0 * t);
    }

    private long hash(long a, long b, long c) {
        long h = seed ^ a * 0x632BE59BD9B4E019L ^ b * 0x85157AF5L ^ c * 0x1B873593L;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return h;
    }

    private long hash3(long a, long b, long c, long salt) {
        long h = seed ^ a * 0x632BE59BD9B4E019L ^ b * 0x9E3779B97F4A7C15L ^ c * 0xC2B2AE3D27D4EB4FL ^ salt * 0x165667B19E3779F9L;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return h;
    }

    private static double unit(long h) {
        h ^= h >>> 31;
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 29;
        return (h >>> 11) * 0x1.0p-53;
    }

    /** Ruido de valores en 3D (0..1): se evalúa sobre la esfera, así no tiene costura. */
    private double noise3(double x, double y, double z, long salt) {
        long xi = (long) Math.floor(x);
        long yi = (long) Math.floor(y);
        long zi = (long) Math.floor(z);
        double fx = x - xi;
        double fy = y - yi;
        double fz = z - zi;
        fx = fx * fx * (3 - 2 * fx);
        fy = fy * fy * (3 - 2 * fy);
        fz = fz * fz * (3 - 2 * fz);
        double a = lerp(unit(hash3(xi, yi, zi, salt)), unit(hash3(xi + 1, yi, zi, salt)), fx);
        double b = lerp(unit(hash3(xi, yi + 1, zi, salt)), unit(hash3(xi + 1, yi + 1, zi, salt)), fx);
        double c = lerp(unit(hash3(xi, yi, zi + 1, salt)), unit(hash3(xi + 1, yi, zi + 1, salt)), fx);
        double e = lerp(unit(hash3(xi, yi + 1, zi + 1, salt)), unit(hash3(xi + 1, yi + 1, zi + 1, salt)), fx);
        return lerp(lerp(a, b, fy), lerp(c, e, fy), fz);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private double fbm3(double x, double y, double z, double frequency, int octaves, long salt) {
        double sum = 0.0;
        double amp = 0.5;
        double norm = 0.0;
        double f = frequency;
        for (int o = 0; o < octaves; o++) {
            sum += amp * noise3(x * f + 17.3 * o, y * f + 5.1 * o, z * f + 11.7 * o, salt + o);
            norm += amp;
            amp *= 0.5;
            f *= 2.03;
        }
        return sum / norm;
    }

    /** Líneas donde el ruido cruza su valor medio: crestas largas y curvas (0..1). */
    private double ridged(double x, double y, double z, double frequency, long salt, double sharp) {
        double n = noise3(x * frequency, y * frequency, z * frequency, salt);
        return Math.pow(1.0 - Math.abs(2.0 * n - 1.0), sharp);
    }

    /** Ruido 2D cuyo dibujo se repite cada {@code period} celdas en x: casa al dar la vuelta al mapa. */
    private double noiseWrapped(double x, double z, long period, long salt) {
        long xi = (long) Math.floor(x);
        long zi = (long) Math.floor(z);
        double fx = x - xi;
        double fz = z - zi;
        fx = fx * fx * (3 - 2 * fx);
        fz = fz * fz * (3 - 2 * fz);
        long x0 = Math.floorMod(xi, period);
        long x1 = Math.floorMod(xi + 1, period);
        double a = unit(hash3(x0, zi, 0, salt));
        double b = unit(hash3(x1, zi, 0, salt));
        double c = unit(hash3(x0, zi + 1, 0, salt));
        double d = unit(hash3(x1, zi + 1, 0, salt));
        return a + (b - a) * fx + (c - a) * fz + (a - b - c + d) * fx * fz;
    }

    private double fbmWrapped(double x, double z, int octaves, long period, long salt) {
        double sum = 0.0;
        double amp = 0.5;
        double norm = 0.0;
        for (int o = 0; o < octaves; o++) {
            sum += amp * noiseWrapped(x, z, period, salt + o);
            norm += amp;
            amp *= 0.5;
            x *= 2.0;
            z *= 2.0;
            period *= 2;
        }
        return sum / norm;
    }
}
