import javax.imageio.ImageIO;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Random;

/**
 * Prepara las texturas de los astros de Create Universal a partir de los mapas de la NASA (equirectangulares, 2:1).
 *
 * Uso: java tools/TexturePipeline.java info <carpeta_fuentes>
 *      java tools/TexturePipeline.java build <carpeta_fuentes> <carpeta_salida>
 *
 * Por cada astro escribe dos niveles: <nombre>_hi.png (cerca) y <nombre>_lo.png (lejos, 256×128), para que un planeta
 * pequeño en pantalla no use una textura grande (rendimiento y sin parpadeo). También genera lo que la NASA no tiene en
 * un mapa global: nubes de la Tierra (ruido fractal, sin costuras), Mercurio, Urano, el Sol y los anillos de Saturno.
 * Las fuentes son mapas de la NASA de uso libre (NASA 3D Resources, NASA NEO, NASA SVS); no se distribuyen aquí.
 */
public final class TexturePipeline {
    public static void main(String[] args) throws Exception {
        if (args.length >= 2 && args[0].equals("info")) {
            for (File f : new File(args[1]).listFiles()) {
                BufferedImage img = ImageIO.read(f);
                System.out.printf("%-16s %s%n", f.getName(), img == null ? "NO SE PUEDE LEER" : img.getWidth() + "x" + img.getHeight());
            }
            return;
        }
        if (args.length < 3 || !args[0].equals("build")) {
            System.err.println("uso: info <fuentes> | build <fuentes> <salida>");
            System.exit(1);
        }
        File src = new File(args[1]);
        File out = new File(args[2]);
        out.mkdirs();

        // Mapas de la NASA: nombre -> ancho del nivel "hi".
        Object[][] nasa = {
                {"earth_day", 2048}, {"earth_night", 2048}, {"moon", 1024}, {"mars", 1024}, {"phobos", 512}, {"deimos", 512},
                {"venus_surface", 1024}, {"jupiter", 2048}, {"io", 512}, {"europa", 512}, {"ganymede", 512}, {"callisto", 512},
                {"saturn", 2048}, {"titan", 512}, {"enceladus", 512}, {"mimas", 512}, {"tethys", 512}, {"dione", 512},
                {"rhea", 512}, {"iapetus", 512}, {"miranda", 512}, {"ariel", 512}, {"umbriel", 512}, {"titania", 512},
                {"oberon", 512}, {"neptune", 1024}, {"triton", 512}
        };
        for (Object[] e : nasa) {
            String name = (String) e[0];
            String file = name.equals("venus_surface") ? "venus" : name;
            File f = new File(src, file + ".jpg");
            if (!f.exists()) {
                f = new File(src, file + ".png");
            }
            BufferedImage img = ImageIO.read(f);
            if (img == null) {
                System.err.println("No se puede leer " + f);
                continue;
            }
            if (name.equals("earth_night")) {
                img = cleanNightLights(img);
            }
            int w = Math.min((Integer) e[1], img.getWidth());
            writeLevels(out, name, img, w);
        }

        writeLevels(out, "earth_clouds", clouds(2048, 1024, 20261108L), 2048);
        writeLevels(out, "mercury", rocky(1024, 512, 7L, new int[]{0x8a8580, 0x5c5854, 0xb3aea8}), 1024);
        writeLevels(out, "uranus", bands(1024, 512, 11L, 0x9fd8e3, 0.04), 1024);
        writeLevels(out, "venus", bands(1024, 512, 13L, 0xe6d3a3, 0.07), 1024);
        writeLevels(out, "sun", sun(1024, 512, 3L), 1024);
        write(ringStrip(1024), new File(out, "saturn_rings.png"));
        blur(out, "saturn_rings.png");
        System.out.println("Hecho.");
    }

    /**
     * El mapa de luces de la NASA SVS (2048×1152) es el mapa equirectangular de 2048×1024 con 64 px de margen arriba y
     * abajo y el año "2023" impreso en el Atlántico Sur. Se recorta y se borra el texto.
     */
    private static BufferedImage cleanNightLights(BufferedImage src) {
        int margin = (src.getHeight() - src.getWidth() / 2) / 2;
        BufferedImage img = toArgb(src).getSubimage(0, margin, src.getWidth(), src.getWidth() / 2);
        BufferedImage copy = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
        var g = copy.createGraphics();
        g.drawImage(img, 0, 0, null);
        g.setColor(java.awt.Color.BLACK);
        g.fillRect(870, 625, 135, 65);
        g.dispose();
        return copy;
    }

    private static void writeLevels(File out, String name, BufferedImage img, int hiWidth) throws Exception {
        write(resize(img, hiWidth, hiWidth / 2), new File(out, name + "_hi.png"));
        write(resize(img, 256, 128), new File(out, name + "_lo.png"));
        blur(out, name + "_hi.png");
        blur(out, name + "_lo.png");
        System.out.printf("%-14s %dx%d -> %d / 256%n", name, img.getWidth(), img.getHeight(), hiWidth);
    }

    /** Filtrado lineal en Minecraft (sin él, las texturas se ven pixeladas y parpadean al alejarse). */
    private static void blur(File out, String png) throws Exception {
        java.nio.file.Files.writeString(new File(out, png + ".mcmeta").toPath(), "{ \"texture\": { \"blur\": true } }\n");
    }

    private static void write(BufferedImage img, File f) throws Exception {
        ImageIO.write(img, "png", f);
    }

    /** Reescalado de calidad por pasos de mitad (evita el aliasing al reducir mucho). */
    private static BufferedImage resize(BufferedImage src, int w, int h) {
        BufferedImage cur = toArgb(src);
        while (cur.getWidth() / 2 >= w && cur.getHeight() / 2 >= h) {
            cur = scale(cur, cur.getWidth() / 2, cur.getHeight() / 2);
        }
        return scale(cur, w, h);
    }

    private static BufferedImage scale(BufferedImage src, int w, int h) {
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        var g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return dst;
    }

    private static BufferedImage toArgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_ARGB) {
            return src;
        }
        BufferedImage dst = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        var g = dst.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return dst;
    }

    // ------------------------------------------------------------------ texturas generadas

    /** Ruido de valor periódico en x (sin costura en la longitud 180°). */
    private static final class Noise {
        private final float[] grid;
        private final int size = 256;

        Noise(long seed) {
            Random r = new Random(seed);
            grid = new float[size * size];
            for (int i = 0; i < grid.length; i++) {
                grid[i] = r.nextFloat();
            }
        }

        float at(double x, double y, int period) {
            int x0 = (int) Math.floor(x);
            int y0 = (int) Math.floor(y);
            double fx = x - x0;
            double fy = y - y0;
            double sx = fx * fx * (3 - 2 * fx);
            double sy = fy * fy * (3 - 2 * fy);
            float a = cell(x0, y0, period), b = cell(x0 + 1, y0, period), c = cell(x0, y0 + 1, period), d = cell(x0 + 1, y0 + 1, period);
            return (float) (a + (b - a) * sx + (c - a) * sy + (a - b - c + d) * sx * sy);
        }

        private float cell(int x, int y, int period) {
            int px = Math.floorMod(x, period) & (size - 1);
            int py = Math.floorMod(y, size);
            return grid[py * size + px];
        }

        float fbm(double u, double v, int octaves, int basePeriod) {
            float sum = 0, amp = 0.5f, norm = 0;
            int period = basePeriod;
            for (int o = 0; o < octaves; o++) {
                sum += amp * at(u * period, v * period, period);
                norm += amp;
                amp *= 0.5f;
                period *= 2;
            }
            return sum / norm;
        }
    }

    /** Nubes de la Tierra: blanco con transparencia, ~40 % de cobertura, más en latitudes medias y menos en los trópicos. */
    private static BufferedImage clouds(int w, int h, long seed) {
        Noise n = new Noise(seed);
        Noise detail = new Noise(seed * 7 + 3);
        Noise warp = new Noise(seed * 31 + 7);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            double v = (double) y / h;
            double lat = (0.5 - v) * Math.PI;
            // Más nubes en las borrascas de latitudes medias (~50°) y en la zona ecuatorial, menos en los desiertos (~25°).
            double belt = 0.08 * Math.exp(-Math.pow((Math.abs(lat) - 0.9) / 0.25, 2))
                    + 0.05 * Math.exp(-Math.pow(lat / 0.15, 2))
                    - 0.07 * Math.exp(-Math.pow((Math.abs(lat) - 0.42) / 0.15, 2));
            for (int x = 0; x < w; x++) {
                double u = (double) x / w;
                double wu = u + 0.06 * (warp.fbm(u, v, 4, 6) - 0.5);
                double wv = v + 0.04 * (warp.fbm(u + 0.37, v, 4, 6) - 0.5);
                double base = n.fbm(wu, wv * 0.5, 6, 8);
                double fine = detail.fbm(wu * 1.0, wv * 0.5, 5, 64);
                double c = base * 0.75 + fine * 0.25 + belt;
                double a = Math.pow(smooth(0.47, 0.86, c), 1.3) * 0.92;
                int alpha = (int) Math.round(clamp(a) * 255);
                int shade = 228 + (int) Math.round(27 * clamp((c - 0.5) * 2));
                img.setRGB(x, y, alpha << 24 | shade << 16 | shade << 8 | shade);
            }
        }
        return img;
    }

    /** Superficie rocosa con cráteres suaves (Mercurio: no hay mapa global de la NASA en el repositorio). */
    private static BufferedImage rocky(int w, int h, long seed, int[] palette) {
        Noise n = new Noise(seed);
        Random r = new Random(seed);
        double[][] height = new double[h][w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                height[y][x] = n.fbm((double) x / w, (double) y / h * 0.5, 7, 8);
            }
        }
        for (int i = 0; i < 900; i++) {
            int cx = r.nextInt(w), cy = r.nextInt(h);
            double rad = 2 + Math.pow(r.nextDouble(), 3) * 40;
            for (int dy = (int) -rad * 2; dy <= rad * 2; dy++) {
                for (int dx = (int) -rad * 2; dx <= rad * 2; dx++) {
                    int x = Math.floorMod(cx + dx, w), y = cy + dy;
                    if (y < 0 || y >= h) continue;
                    double d = Math.sqrt(dx * dx + dy * dy) / rad;
                    if (d < 1) height[y][x] -= 0.12 * (1 - d * d);
                    else if (d < 1.4) height[y][x] += 0.06 * (1.4 - d) / 0.4;
                }
            }
        }
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double t = clamp(height[y][x] * 1.6 - 0.25);
                int c = t < 0.5 ? lerpColor(palette[1], palette[0], t * 2) : lerpColor(palette[0], palette[2], (t - 0.5) * 2);
                img.setRGB(x, y, 0xff000000 | c);
            }
        }
        return img;
    }

    /** Gigante de hielo con bandas muy suaves (Urano). */
    private static BufferedImage bands(int w, int h, long seed, int base, double strength) {
        Noise n = new Noise(seed);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            double v = (double) y / h;
            for (int x = 0; x < w; x++) {
                double u = (double) x / w;
                double b = Math.sin(v * Math.PI * 14 + 2 * n.fbm(u, v, 3, 3)) * strength + (n.fbm(u, v * 4, 4, 4) - 0.5) * strength;
                double polar = Math.pow(Math.abs(0.5 - v) * 2, 3) * 0.08;
                img.setRGB(x, y, 0xff000000 | shadeColor(base, b + polar));
            }
        }
        return img;
    }

    /** Fotosfera del Sol: granulación y manchas suaves. */
    private static BufferedImage sun(int w, int h, long seed) {
        Noise n = new Noise(seed);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double u = (double) x / w, v = (double) y / h;
                double g = n.fbm(u, v * 0.5, 6, 32);
                double t = clamp(0.55 + (g - 0.5) * 0.9);
                int c = lerpColor(0xff7a10, 0xfff3c8, t);
                img.setRGB(x, y, 0xff000000 | c);
            }
        }
        return img;
    }

    /** Franja radial de los anillos de Saturno (x = del borde interior al exterior). Bandas C, B, división de Cassini, A, Encke. */
    private static BufferedImage ringStrip(int w) {
        Random r = new Random(42);
        BufferedImage img = new BufferedImage(w, 8, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < w; x++) {
            double t = (double) x / (w - 1); // 0 = 1,24 radios de Saturno, 1 = 2,27 radios
            double radius = 1.24 + t * 1.03;
            double alpha;
            int color;
            if (radius < 1.53) { alpha = 0.18; color = 0x8f8578; }          // anillo C, tenue
            else if (radius < 1.95) { alpha = 0.85; color = 0xd8c8a8; }     // anillo B, brillante
            else if (radius < 2.03) { alpha = 0.06; color = 0x60584e; }     // división de Cassini
            else if (radius < 2.21) { alpha = 0.6; color = 0xc4b494; }      // anillo A
            else { alpha = 0.0; color = 0xc4b494; }
            if (radius > 2.14 && radius < 2.15) alpha = 0.05;               // división de Encke
            alpha *= 0.85 + 0.3 * r.nextDouble();
            int a = (int) Math.round(clamp(alpha) * 255);
            for (int y = 0; y < 8; y++) {
                img.setRGB(x, y, a << 24 | color);
            }
        }
        return img;
    }

    private static double smooth(double e0, double e1, double x) {
        double t = clamp((x - e0) / (e1 - e0));
        return t * t * (3 - 2 * t);
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private static int lerpColor(int a, int b, double t) {
        int r = (int) ((a >> 16 & 255) + ((b >> 16 & 255) - (a >> 16 & 255)) * t);
        int g = (int) ((a >> 8 & 255) + ((b >> 8 & 255) - (a >> 8 & 255)) * t);
        int bl = (int) ((a & 255) + ((b & 255) - (a & 255)) * t);
        return r << 16 | g << 8 | bl;
    }

    private static int shadeColor(int c, double d) {
        int r = (int) Math.max(0, Math.min(255, (c >> 16 & 255) * (1 + d)));
        int g = (int) Math.max(0, Math.min(255, (c >> 8 & 255) * (1 + d)));
        int b = (int) Math.max(0, Math.min(255, (c & 255) * (1 + d)));
        return r << 16 | g << 8 | b;
    }
}
