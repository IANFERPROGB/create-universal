package dev.createuniversal.space.surface;

import dev.createuniversal.CreateUniversal;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * El terreno de una superficie: el relieve real de la NASA (LOLA en la Luna, MOLA en Marte; una muestra cada ~3,8 km en
 * la Luna y ~7,4 km en Marte) para las formas grandes, y encima detalle generado a escala casi real: cráteres de cinco
 * tamaños, de 8 a 2.000 bloques, y ondulaciones del suelo. Todo determinista: el mismo sitio sale siempre igual, sin
 * guardar nada.
 */
public final class PlanetTerrain {
    private static final Map<String, PlanetTerrain> CACHE = new HashMap<>();

    /** Celdas de cráteres generados: tamaño en bloques y probabilidad de que la celda tenga uno. */
    private static final double[] CRATER_CELLS = {2048, 512, 128, 32, 8};
    private static final double[] CRATER_CHANCE = {0.35, 0.45, 0.55, 0.6, 0.5};

    private final PlanetSurface surface;
    private final int width;
    private final int height;
    private final short[] heights;
    private final byte[] albedo;
    private final long seed;

    private PlanetTerrain(PlanetSurface surface, int width, int height, short[] heights, byte[] albedo) {
        this.surface = surface;
        this.width = width;
        this.height = height;
        this.heights = heights;
        this.albedo = albedo;
        this.seed = surface.bodyId().hashCode() * 0x9E3779B97F4A7C15L;
    }

    public static synchronized PlanetTerrain get(PlanetSurface surface) {
        return CACHE.computeIfAbsent(surface.bodyId(), id -> load(surface));
    }

    private static PlanetTerrain load(PlanetSurface surface) {
        String path = "/terrain/" + surface.bodyId() + ".bin";
        try (InputStream raw = PlanetTerrain.class.getResourceAsStream(path)) {
            if (raw == null) {
                throw new IOException("falta " + path);
            }
            DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(raw, 1 << 16)));
            byte[] magic = new byte[4];
            in.readFully(magic);
            int w = in.readInt();
            int h = in.readInt();
            short[] heights = new short[w * h];
            for (int i = 0; i < heights.length; i++) {
                heights[i] = in.readShort();
            }
            byte[] albedo = new byte[w * h];
            in.readFully(albedo);
            CreateUniversal.LOGGER.info("Relieve de {} cargado: {}×{}", surface.bodyId(), w, h);
            return new PlanetTerrain(surface, w, h, heights, albedo);
        } catch (IOException e) {
            CreateUniversal.LOGGER.error("No se pudo cargar el relieve de {}: será llano", surface.bodyId(), e);
            return new PlanetTerrain(surface, 2, 1, new short[2], new byte[] {(byte) 128, (byte) 128});
        }
    }

    // ---------------------------------------------------------------- consulta

    /** Altura del suelo en bloques (y) en esa columna. */
    public double surfaceY(double x, double z) {
        double lon = surface.longitude(x);
        double lat = surface.latitude(z);
        double big = surface.scaleHeight(bicubic(lon, lat));
        double mare = darkness(lon, lat);
        return big + craters(x, z, mare) + roughness(x, z);
    }

    /** 0 = zona clara (tierras altas de la Luna, polvo de Marte), 1 = zona oscura (mares lunares, roca marciana). */
    public double darkness(double lon, double lat) {
        double u = (lon / (Math.PI * 2.0) + 0.5) * width - 0.5;
        double v = (0.5 - lat / Math.PI) * height - 0.5;
        int x0 = (int) Math.floor(u);
        int y0 = Math.max(0, Math.min(height - 1, (int) Math.round(v)));
        double a = (albedo[y0 * width + Math.floorMod(x0, width)] & 255) / 255.0;
        return Math.max(0.0, Math.min(1.0, (0.55 - a) / 0.3));
    }

    // ---------------------------------------------------------------- relieve real

    private double sample(int x, int y) {
        x = Math.floorMod(x, width);
        y = Math.max(0, Math.min(height - 1, y));
        return heights[y * width + x];
    }

    /** Interpolación bicúbica (Catmull-Rom) del mapa de alturas: laderas suaves entre muestras de varios km. */
    private double bicubic(double lon, double lat) {
        double u = (lon / (Math.PI * 2.0) + 0.5) * width - 0.5;
        double v = (0.5 - lat / Math.PI) * height - 0.5;
        int x0 = (int) Math.floor(u);
        int y0 = (int) Math.floor(v);
        double fx = u - x0;
        double fy = v - y0;
        double[] col = new double[4];
        for (int j = -1; j <= 2; j++) {
            col[j + 1] = cubic(sample(x0 - 1, y0 + j), sample(x0, y0 + j), sample(x0 + 1, y0 + j), sample(x0 + 2, y0 + j), fx);
        }
        return cubic(col[0], col[1], col[2], col[3], fy);
    }

    private static double cubic(double p0, double p1, double p2, double p3, double t) {
        return p1 + 0.5 * t * (p2 - p0 + t * (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3 + t * (3.0 * (p1 - p2) + p3 - p0)));
    }

    // ---------------------------------------------------------------- detalle generado

    /**
     * Cráteres simples (cuenco con borde levantado), como los reales: profundidad ~0,2 del diámetro y borde ~0,04. Los
     * grandes se aplanan algo para que el suelo siga siendo jugable. En los mares lunares hay menos (son más jóvenes).
     */
    private double craters(double x, double z, double mare) {
        double total = 0.0;
        for (int level = 0; level < CRATER_CELLS.length; level++) {
            double cell = CRATER_CELLS[level];
            double chance = CRATER_CHANCE[level] * surface.craterDensity() * (1.0 - 0.55 * mare);
            long cx = (long) Math.floor(x / cell);
            long cz = (long) Math.floor(z / cell);
            for (long i = cx - 1; i <= cx + 1; i++) {
                for (long k = cz - 1; k <= cz + 1; k++) {
                    long h = hash(i, k, level);
                    if (unit(h) > chance) {
                        continue;
                    }
                    double r = cell * (0.12 + 0.33 * unit(h * 31 + 7));
                    double ox = (i + unit(h * 17 + 3)) * cell;
                    double oz = (k + unit(h * 13 + 5)) * cell;
                    double dx = x - ox;
                    double dz = z - oz;
                    double d2 = (dx * dx + dz * dz) / (r * r);
                    if (d2 > 4.0) {
                        continue;
                    }
                    double d = Math.sqrt(d2);
                    double flatten = r > 400 ? 0.35 : r > 100 ? 0.55 : 0.8;
                    double depth = 0.4 * r * flatten;
                    double rim = 0.08 * r * flatten;
                    double bowl = d < 1.0 ? depth * (d2 - 1.0) : 0.0;
                    double rimBump = rim * Math.exp(-((d - 1.0) * (d - 1.0)) / 0.09);
                    total += bowl + rimBump;
                }
            }
        }
        return total;
    }

    /** Ondulaciones suaves del suelo (regolito), de unos pocos bloques. */
    private double roughness(double x, double z) {
        return (fbm(x / 96.0, z / 96.0, 4) - 0.5) * 7.0 + (fbm(x / 640.0, z / 640.0, 3) - 0.5) * 18.0;
    }

    private long hash(long a, long b, long c) {
        long h = seed ^ a * 0x632BE59BD9B4E019L ^ b * 0x85157AF5L ^ c * 0x1B873593L;
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

    private double noise(double x, double z) {
        long xi = (long) Math.floor(x);
        long zi = (long) Math.floor(z);
        double fx = x - xi;
        double fz = z - zi;
        fx = fx * fx * (3 - 2 * fx);
        fz = fz * fz * (3 - 2 * fz);
        double a = unit(hash(xi, zi, 99));
        double b = unit(hash(xi + 1, zi, 99));
        double c = unit(hash(xi, zi + 1, 99));
        double d = unit(hash(xi + 1, zi + 1, 99));
        return a + (b - a) * fx + (c - a) * fz + (a - b - c + d) * fx * fz;
    }

    private double fbm(double x, double z, int octaves) {
        double sum = 0.0;
        double amp = 0.5;
        double norm = 0.0;
        for (int o = 0; o < octaves; o++) {
            sum += amp * noise(x, z);
            norm += amp;
            amp *= 0.5;
            x *= 2.03;
            z *= 2.03;
        }
        return sum / norm;
    }
}
