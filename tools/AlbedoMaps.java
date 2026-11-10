import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.GZIPOutputStream;

/**
 * Saca de la textura de cada astro su mapa de zonas claras y oscuras (alpha.6) y lo guarda en
 * {@code terrain/<id>.alb}: con él el generador decide dónde va el suelo claro y el oscuro, dónde hay más cráteres
 * (Ganimedes), dónde crece la cresta de Jápeto o dónde hay dunas (Titán). Va aparte de las texturas para que el terreno
 * no cambie si un día se mejora una textura.
 *
 * Uso: java tools/AlbedoMaps.java src/main/resources
 *
 * Formato (gzip): "CUA1", ancho, alto y un byte por celda (0 = claro, 255 = oscuro). Columna 0 = longitud −180°,
 * fila 0 = polo norte, como las texturas.
 */
public class AlbedoMaps {
    static final int W = 720;
    static final int H = 360;

    /**
     * Astro, textura, qué parte de su superficie es "oscura" y, si su textura tiene las zonas sin fotografiar rellenas
     * de blanco, a partir de qué brillo medio se toma una zona por una de esas.
     */
    record Body(String id, String texture, double darkFraction, double whiteGap) {}

    static final Body[] BODIES = {
            new Body("mercury", "mercury_hi", 0.25, 0.0),
            new Body("venus", "venus_surface_hi", 0.5, 0.0),
            new Body("io", "io_hi", 0.3, 0.0),
            new Body("europa", "europa_hi", 0.3, 0.0),
            new Body("ganymede", "ganymede_hi", 0.35, 0.0),
            new Body("callisto", "callisto_hi", 0.85, 0.78),
            new Body("mimas", "mimas_hi", 0.02, 0.0),
            new Body("enceladus", "enceladus_hi", 0.02, 0.0),
            new Body("tethys", "tethys_hi", 0.04, 0.0),
            new Body("dione", "dione_hi", 0.3, 0.0),
            new Body("rhea", "rhea_hi", 0.03, 0.0),
            new Body("titan", null, 0.0, 0.0),
            new Body("iapetus", "iapetus_hi", 0.4, 0.0),
            new Body("miranda", "miranda_hi", 0.3, 0.0),
            new Body("ariel", "ariel_hi", 0.25, 0.0),
            new Body("umbriel", "umbriel_hi", 0.9, 0.0),
            new Body("titania", "titania_hi", 0.3, 0.0),
            new Body("oberon", "oberon_hi", 0.4, 0.0),
            new Body("triton", "triton_hi", 0.3, 0.0),
    };

    public static void main(String[] args) throws IOException {
        Path resources = Path.of(args[0]);
        Path textures = resources.resolve("assets/create_universal/textures/planet");
        Path out = resources.resolve("terrain");
        Files.createDirectories(out);
        for (Body body : BODIES) {
            float[] dark = body.texture() == null ? titan() : fromTexture(body, textures.resolve(body.texture() + ".png"));
            double mean = 0.0;
            double weight = 0.0;
            for (int y = 0; y < H; y++) {
                double c = Math.cos((0.5 - (y + 0.5) / H) * Math.PI);
                for (int x = 0; x < W; x++) {
                    mean += dark[y * W + x] * c;
                    weight += c;
                }
            }
            Path file = out.resolve(body.id() + ".alb");
            try (DataOutputStream data = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(file)))) {
                data.writeBytes("CUA1");
                data.writeInt(W);
                data.writeInt(H);
                for (float d : dark) {
                    data.writeByte(Math.round(255f * Math.max(0f, Math.min(1f, d))));
                }
            }
            System.out.printf("%-10s oscuro %.0f %%  (%d KB)%n", body.id(), 100.0 * mean / weight, Files.size(file) / 1024);
        }
    }

    static float[] fromTexture(Body body, Path file) throws IOException {
        BufferedImage img = ImageIO.read(file.toFile());
        int w = img.getWidth();
        int h = img.getHeight();
        // Brillo medio de cada celda. Lo negro del todo es "sin fotografiar" (medio Urano y Tritón solo los vio la
        // Voyager 2 de pasada), salvo en Jápeto, cuya cara oscura es así de oscura de verdad.
        boolean gaps = !body.id().equals("iapetus");
        float[] lum = new float[W * H];
        float[] valid = new float[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int x0 = x * w / W;
                int x1 = Math.max(x0 + 1, (x + 1) * w / W);
                int y0 = y * h / H;
                int y1 = Math.max(y0 + 1, (y + 1) * h / H);
                double sum = 0.0;
                int good = 0;
                int all = 0;
                for (int j = y0; j < y1; j++) {
                    for (int i = x0; i < x1; i++) {
                        int c = img.getRGB(Math.min(i, w - 1), Math.min(j, h - 1));
                        double l = (0.299 * (c >> 16 & 255) + 0.587 * (c >> 8 & 255) + 0.114 * (c & 255)) / 255.0;
                        all++;
                        if (!gaps || l >= 0.04) {
                            sum += l;
                            good++;
                        }
                    }
                }
                lum[y * W + x] = good > 0 ? (float) (sum / good) : 0f;
                valid[y * W + x] = (float) good / all;
            }
        }
        if (body.whiteGap() > 0.0) {
            float[] wide = blur(lum, 6);
            for (int i = 0; i < valid.length; i++) {
                if (wide[i] > body.whiteGap()) {
                    valid[i] = 0f;
                }
            }
            // El borde de esas zonas es un degradado de blanco a superficie: se descarta también.
            float[] near = blur(valid, 10);
            for (int i = 0; i < valid.length; i++) {
                if (near[i] < 0.97f) {
                    valid[i] = 0f;
                }
            }
        }
        // Umbrales: el brillo por debajo del cual queda la parte "oscura" del astro, con un margen para el paso.
        float[] sorted = new float[W * H * 2];
        int n = 0;
        for (int y = 0; y < H; y++) {
            int step = Math.max(1, (int) Math.round(1.0 / Math.max(0.02, Math.cos((0.5 - (y + 0.5) / H) * Math.PI))));
            for (int x = 0; x < W; x += step) {
                if (valid[y * W + x] > 0.5f) {
                    sorted[n++] = lum[y * W + x];
                }
            }
        }
        sorted = Arrays.copyOf(sorted, n);
        Arrays.sort(sorted);
        double f = body.darkFraction();
        double lo = sorted[(int) Math.max(0, Math.min(n - 1, (f - 0.08) * n))];
        double hi = sorted[(int) Math.max(0, Math.min(n - 1, (f + 0.08) * n))];
        if (hi - lo < 0.04) {
            double mid = (lo + hi) * 0.5;
            lo = mid - 0.02;
            hi = mid + 0.02;
        }
        // Lo no fotografiado se rellena con manchas inventadas en la misma proporción.
        float[] noise = new float[W * H];
        for (int y = 0; y < H; y++) {
            double lat = (0.5 - (y + 0.5) / H) * Math.PI;
            for (int x = 0; x < W; x++) {
                double lon = ((x + 0.5) / W - 0.5) * 2.0 * Math.PI;
                noise[y * W + x] = (float) fbm(lon, lat, 3.0, 5, body.id().hashCode());
            }
        }
        float[] ns = noise.clone();
        Arrays.sort(ns);
        double cut = ns[(int) Math.max(0, Math.min(ns.length - 1, f * ns.length))];
        valid = blur(valid, 6);
        float[] dark = new float[W * H];
        for (int i = 0; i < dark.length; i++) {
            double real = 1.0 - smooth(lo, hi, lum[i]);
            double made = 1.0 - smooth(cut - 0.02, cut + 0.02, noise[i]);
            double v = smooth(0.5, 0.95, valid[i]);
            dark[i] = (float) (made + (real - made) * v);
        }
        return dark;
    }

    /**
     * Titán: su textura es la de la bruma, sin superficie. Se dibuja lo que vio la Cassini con el radar: mares de dunas
     * oscuras en el ecuador (Shangri-La, Belet, Fensal, Senkyo), con la región clara de Xanadu en medio (10° S, 100° O).
     */
    static float[] titan() {
        float[] dark = new float[W * H];
        double[] xanadu = at(-10.0, -100.0);
        for (int y = 0; y < H; y++) {
            double lat = (0.5 - (y + 0.5) / H) * Math.PI;
            for (int x = 0; x < W; x++) {
                double lon = ((x + 0.5) / W - 0.5) * 2.0 * Math.PI;
                double band = 1.0 - smooth(Math.toRadians(16.0), Math.toRadians(32.0), Math.abs(lat) + 0.25 * (fbm(lon, lat, 5.0, 3, 7) - 0.5));
                double patches = smooth(0.4, 0.52, fbm(lon, lat, 2.5, 5, 99));
                double[] p = at(Math.toDegrees(lat), Math.toDegrees(lon));
                double ang = Math.acos(Math.max(-1.0, Math.min(1.0, p[0] * xanadu[0] + p[1] * xanadu[1] + p[2] * xanadu[2])));
                double bright = 1.0 - smooth(Math.toRadians(22.0), Math.toRadians(34.0), ang + 0.2 * (fbm(lon, lat, 6.0, 3, 55) - 0.5));
                dark[y * W + x] = (float) (band * patches * (1.0 - bright));
            }
        }
        return dark;
    }

    static double[] at(double latDeg, double lonDeg) {
        double lat = Math.toRadians(latDeg);
        double lon = Math.toRadians(lonDeg);
        return new double[] {Math.cos(lat) * Math.cos(lon), Math.sin(lat), Math.cos(lat) * Math.sin(lon)};
    }

    static float[] blur(float[] src, int radius) {
        float[] tmp = new float[src.length];
        float[] dst = new float[src.length];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                float s = 0f;
                for (int k = -radius; k <= radius; k++) {
                    s += src[y * W + Math.floorMod(x + k, W)];
                }
                tmp[y * W + x] = s / (2 * radius + 1);
            }
        }
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                float s = 0f;
                for (int k = -radius; k <= radius; k++) {
                    s += tmp[Math.max(0, Math.min(H - 1, y + k)) * W + x];
                }
                dst[y * W + x] = s / (2 * radius + 1);
            }
        }
        return dst;
    }

    static double smooth(double a, double b, double v) {
        double t = Math.max(0.0, Math.min(1.0, (v - a) / (b - a)));
        return t * t * (3.0 - 2.0 * t);
    }

    // Ruido sobre la esfera (sin costura).

    static double fbm(double lon, double lat, double frequency, int octaves, long salt) {
        double cl = Math.cos(lat);
        double x = cl * Math.cos(lon);
        double y = Math.sin(lat);
        double z = cl * Math.sin(lon);
        double sum = 0.0;
        double amp = 0.5;
        double norm = 0.0;
        double f = frequency;
        for (int o = 0; o < octaves; o++) {
            sum += amp * noise(x * f + 31.7 * o, y * f + 11.3 * o, z * f + 5.9 * o, salt + o);
            norm += amp;
            amp *= 0.5;
            f *= 2.03;
        }
        return sum / norm;
    }

    static double noise(double x, double y, double z, long salt) {
        long xi = (long) Math.floor(x);
        long yi = (long) Math.floor(y);
        long zi = (long) Math.floor(z);
        double fx = x - xi;
        double fy = y - yi;
        double fz = z - zi;
        fx = fx * fx * (3 - 2 * fx);
        fy = fy * fy * (3 - 2 * fy);
        fz = fz * fz * (3 - 2 * fz);
        double a = lerp(unit(xi, yi, zi, salt), unit(xi + 1, yi, zi, salt), fx);
        double b = lerp(unit(xi, yi + 1, zi, salt), unit(xi + 1, yi + 1, zi, salt), fx);
        double c = lerp(unit(xi, yi, zi + 1, salt), unit(xi + 1, yi, zi + 1, salt), fx);
        double d = lerp(unit(xi, yi + 1, zi + 1, salt), unit(xi + 1, yi + 1, zi + 1, salt), fx);
        return lerp(lerp(a, b, fy), lerp(c, d, fy), fz);
    }

    static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    static double unit(long a, long b, long c, long salt) {
        long h = a * 0x632BE59BD9B4E019L ^ b * 0x9E3779B97F4A7C15L ^ c * 0xC2B2AE3D27D4EB4FL ^ salt * 0x165667B19E3779F9L;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 29;
        return (h >>> 11) * 0x1.0p-53;
    }
}
