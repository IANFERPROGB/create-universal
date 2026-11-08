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
                {"mars", 1440}, {"phobos", 1024}, {"deimos", 1024},
                {"venus_surface", 1024}, {"io", 1024}, {"europa", 1024}, {"ganymede", 1024}, {"callisto", 1024},
                {"saturn", 2048}, {"titan", 512}, {"enceladus", 1024}, {"mimas", 1024}, {"tethys", 1024}, {"dione", 1024},
                {"rhea", 1024}, {"iapetus", 1024}, {"miranda", 1024}, {"ariel", 1024}, {"umbriel", 1024}, {"titania", 1024},
                {"oberon", 1024}, {"neptune", 1024}, {"triton", 1024}
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
            if (name.equals("saturn")) {
                img = desaturate(img, 0.4, 1.08); // el mapa de la NASA es más naranja que el Saturno real, color mantequilla
            }
            int w = Math.min((Integer) e[1], img.getWidth());
            writeLevels(out, name, img, w);
        }

        // Alta resolución: Tierra 8K de la SVS con las nubes separadas, luces nocturnas 8K, Luna LRO 4K con el relieve LOLA,
        // Júpiter de Cassini y el cielo de las Deep Star Maps 2020.
        earth(src, out);
        writeLevels(out, "earth_night", nightLights(ImageIO.read(new File(src, "night_8k_compare.jpg"))), 4096);
        writeLevels(out, "moon", ImageIO.read(new File(src, "moon_4k.tif")), 4096);
        write(moonNormals(new File(src, "moon_ldem16.tif"), 2048), new File(out, "moon_normal_hi.png"));
        blur(out, "moon_normal_hi.png");
        writeLevels(out, "jupiter", fillEmptyRows(ImageIO.read(new File(src, "jupiter_cassini.jpg"))), 4096);
        sky(src, out);
        writeLevels(out, "mercury", rocky(1024, 512, 7L, new int[]{0x8a8580, 0x5c5854, 0xb3aea8}), 1024);
        writeLevels(out, "uranus", bands(1024, 512, 11L, 0x9fd8e3, 0.04), 1024);
        writeLevels(out, "venus", bands(1024, 512, 13L, 0xe6d3a3, 0.07), 1024);
        writeLevels(out, "sun", sun(1024, 512, 3L), 1024);
        write(ringStrip(2048), new File(out, "saturn_rings.png"));
        blur(out, "saturn_rings.png");
        System.out.println("Hecho.");
    }

    private static BufferedImage desaturate(BufferedImage src, double amount, double gain) {
        BufferedImage img = toArgb(src);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int c = img.getRGB(x, y);
                double r = (c >> 16 & 255) / 255.0, g = (c >> 8 & 255) / 255.0, b = (c & 255) / 255.0;
                double l = 0.299 * r + 0.587 * g + 0.114 * b;
                img.setRGB(x, y, 0xff000000 | rgb((r + (l - r) * amount) * gain, (g + (l - g) * amount) * gain, (b + (l - b) * amount) * gain));
            }
        }
        return img;
    }

    // ------------------------------------------------------------------ alta resolución

    /**
     * Tierra 4K a partir del render de 8K de la SVS (3615), que trae nubes reales pintadas encima, y del Blue Marble sin
     * nubes (2048). Las nubes se separan comparando las dos imágenes (blancas, poco saturadas y más claras que el suelo
     * real) y van a {@code earth_clouds}; debajo de ellas el suelo sale del Blue Marble. El océano se oscurece al azul
     * marino que se ve desde el espacio y el hielo marino lila del render pasa a blanco grisáceo.
     */
    private static void earth(File src, File out) throws Exception {
        int w = 4096, h = 2048;
        BufferedImage big = resize(ImageIO.read(new File(src, "earth_8k_3615.jpg")), w, h);
        BufferedImage bm = scale(toArgb(ImageIO.read(new File(src, "earth_day.png"))), w, h);
        BufferedImage day = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        BufferedImage clouds = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        BufferedImage procedural = clouds(2048, 1024, 20261108L);
        for (int y = 0; y < h; y++) {
            double lat = Math.abs(90.0 - 180.0 * (y + 0.5) / h);
            for (int x = 0; x < w; x++) {
                int c = big.getRGB(x, y), d = bm.getRGB(x, y);
                double r = (c >> 16 & 255) / 255.0, g = (c >> 8 & 255) / 255.0, b = (c & 255) / 255.0;
                double br = (d >> 16 & 255) / 255.0, bg = (d >> 8 & 255) / 255.0, bb = (d & 255) / 255.0;
                double lum = 0.299 * r + 0.587 * g + 0.114 * b;
                double lumBm = 0.299 * br + 0.587 * bg + 0.114 * bb;
                double max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
                double sat = max > 0 ? (max - min) / max : 0;
                boolean seaIce = lat > 55 && b - r > 0.045 && sat < 0.25 && lum > 0.42;
                boolean iceSheet = lumBm > 0.72;
                double cloud = smooth(0.06, 0.30, lum - lumBm) * (1 - smooth(0.16, 0.32, sat)) * smooth(0.45, 0.65, min);
                boolean bmOcean = bb > br + 0.08 && bb > bg + 0.06 && lumBm < 0.25;
                // La nieve del render (Canadá, Siberia, la costa antártica) es tan blanca como una nube: ahí no se separa y
                // se ponen nubes generadas, fundidas poco a poco con las reales.
                double fake = (bmOcean ? smooth(58, 68, lat) : smooth(42, 54, lat));
                if (seaIce || iceSheet) {
                    fake = Math.max(fake, smooth(50, 62, lat));
                }
                int pc = procedural.getRGB(x * procedural.getWidth() / w, y * procedural.getHeight() / h);
                double generated = (pc >>> 24) / 255.0 * 0.9;
                double real = cloud * (1 - fake);
                cloud = real + generated * fake;
                boolean ocean = (b > r + 0.10 && b >= g && !seaIce) || (real > 0.3 && bmOcean);
                double sr, sg, sb;
                if (seaIce) {
                    double v = Math.min(1, lum * 1.08);
                    sr = v * 0.93;
                    sg = v * 0.96;
                    sb = v;
                } else if (ocean) {
                    double cr = r * 0.16, cg = g * 0.27, cb = b * 0.45; // el océano desde el espacio: azul marino oscuro
                    double k = smooth(0.1, 0.6, real);
                    sr = cr + (0.024 - cr) * k;
                    sg = cg + (0.094 - cg) * k;
                    sb = cb + (0.227 - cb) * k;
                } else {
                    double k = smooth(0.15, 0.6, real);
                    sr = r + (br * 1.2 - r) * k;
                    sg = g + (bg * 1.2 - g) * k;
                    sb = b + (bb * 1.2 - b) * k;
                }
                day.setRGB(x, y, 0xff000000 | rgb(sr, sg, sb));
                double a = Math.pow(cloud, 0.85) * 0.96;
                int shade = (int) Math.round(215 + 40 * clamp((lum - 0.5) * 2));
                clouds.setRGB(x, y, (int) Math.round(clamp(a) * 255) << 24 | shade << 16 | shade << 8 | shade);
            }
        }
        writeLevels(out, "earth_day", day, 4096);
        writeLevels(out, "earth_clouds", clouds, 4096);
    }

    /** Luces nocturnas 2023 de la SVS (8K, el mapa estirado a 16:9 con el año impreso): solo las luces y la tierra muy tenue. */
    private static BufferedImage nightLights(BufferedImage src) {
        BufferedImage img = toArgb(src);
        var g = img.createGraphics();
        g.setColor(new java.awt.Color(1, 2, 7));
        g.fillRect(3300, 2580, 460, 260);
        g.dispose();
        BufferedImage map = scale(img, 4096, 2048);
        for (int y = 0; y < map.getHeight(); y++) {
            for (int x = 0; x < map.getWidth(); x++) {
                int c = map.getRGB(x, y);
                double r = Math.pow((c >> 16 & 255) / 255.0, 1.8) * 1.5;
                double gg = Math.pow((c >> 8 & 255) / 255.0, 1.8) * 1.5;
                double b = Math.pow((c & 255) / 255.0, 1.8) * 1.5;
                map.setRGB(x, y, 0xff000000 | rgb(r, gg, b));
            }
        }
        return map;
    }

    /**
     * Mapa de normales de la Luna a partir del relieve LOLA (ldem_16_uint: 16 píxeles por grado, alturas en medios metros).
     * Relieve exagerado ×3 para que los cráteres se vean en la línea entre el día y la noche.
     */
    private static BufferedImage moonNormals(File ldem, int w) throws Exception {
        BufferedImage src = ImageIO.read(ldem);
        int sw = src.getWidth(), sh = src.getHeight();
        var raster = src.getRaster();
        int h = w / 2;
        double[] height = new double[w * h];
        int step = Math.max(1, sw / w);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double sum = 0;
                int n = 0;
                for (int dy = 0; dy < step; dy++) {
                    for (int dx = 0; dx < step; dx++) {
                        sum += raster.getSample(Math.min(sw - 1, x * sw / w + dx), Math.min(sh - 1, y * sh / h + dy), 0);
                        n++;
                    }
                }
                height[y * w + x] = sum / n * 0.5; // metros
            }
        }
        double pixelMeters = 1737400.0 * Math.PI / h;
        double exaggeration = 3.0;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            double cosLat = Math.max(0.05, Math.cos(Math.PI * (0.5 - (y + 0.5) / h)));
            for (int x = 0; x < w; x++) {
                double hx = height[y * w + Math.floorMod(x + 1, w)] - height[y * w + Math.floorMod(x - 1, w)];
                double hy = height[Math.max(0, y - 1) * w + x] - height[Math.min(h - 1, y + 1) * w + x];
                double sx = hx / (2 * pixelMeters * cosLat) * exaggeration;
                double sy = hy / (2 * pixelMeters) * exaggeration;
                double len = Math.sqrt(sx * sx + sy * sy + 1);
                // Normal en el espacio tangente (x = este, y = norte, z = hacia fuera), guardada en 0..1.
                img.setRGB(x, y, 0xff000000 | rgb((-sx / len + 1) * 0.5, (-sy / len + 1) * 0.5, (1 / len + 1) * 0.5));
            }
        }
        return img;
    }

    /** El mapa de Cassini no tiene datos cerca de los polos (filas grises lisas): se rellenan con la última fila buena. */
    private static BufferedImage fillEmptyRows(BufferedImage src) {
        BufferedImage img = toArgb(src);
        int w = img.getWidth(), h = img.getHeight();
        boolean[] good = new boolean[h];
        for (int y = 0; y < h; y++) {
            double sum = 0, sum2 = 0;
            int n = 0;
            for (int x = 0; x < w; x += 3) {
                int c = img.getRGB(x, y);
                double l = (c >> 16 & 255) + (c >> 8 & 255) + (c & 255);
                sum += l;
                sum2 += l * l;
                n++;
            }
            double var = sum2 / n - (sum / n) * (sum / n);
            good[y] = var > 30;
        }
        int first = 0, last = h - 1;
        while (first < h - 1 && !good[first]) first++;
        while (last > 0 && !good[last]) last--;
        for (int y = 0; y < first; y++) {
            for (int x = 0; x < w; x++) img.setRGB(x, y, img.getRGB(x, Math.min(h - 1, first + 2)));
        }
        for (int y = last + 1; y < h; y++) {
            for (int x = 0; x < w; x++) img.setRGB(x, y, img.getRGB(x, Math.max(0, last - 2)));
        }
        System.out.println("jupiter: filas con datos " + first + ".." + last + " de " + h);
        return img;
    }

    // ------------------------------------------------------------------ cielo (NASA SVS Deep Star Maps 2020)

    /**
     * Vía Láctea difusa (textura de fondo, coordenadas ecuatoriales) y estrellas como puntos nítidos: se buscan los máximos
     * locales del mapa de estrellas de 4K, menos el fondo difuso, y se guardan en {@code sky/stars.bin}
     * (cantidad; por estrella: ascensión recta y declinación en radianes, color RGB y brillo).
     */
    private static void sky(File src, File out) throws Exception {
        float[][] mw = readExr(new File(src, "milkyway_4k.exr"));
        float[][] st = readExr(new File(src, "starmap_4k.exr"));
        int w = exrWidth, h = exrHeight;
        float[] lum = new float[w * h];
        for (int i = 0; i < lum.length; i++) lum[i] = (float) luminance(mw, i);
        float[] sorted = lum.clone();
        java.util.Arrays.sort(sorted);
        // Exposición: el núcleo (percentil 99,9) queda en ~0,5 y se resta casi todo el fondo (mediana) para que el cielo sea negro.
        double expo = 0.5 / Math.max(1e-6, sorted[(int) (sorted.length * 0.999)]);
        double floor = sorted[sorted.length / 2] * expo * 0.85;
        int ow = 2048, oh = 1024;
        BufferedImage bg = new BufferedImage(ow, oh, BufferedImage.TYPE_INT_ARGB);
        Random dither = new Random(5);
        for (int y = 0; y < oh; y++) {
            for (int x = 0; x < ow; x++) {
                double r = 0, g = 0, b = 0;
                for (int dy = 0; dy < 2; dy++) {
                    for (int dx = 0; dx < 2; dx++) {
                        int i = (y * 2 + dy) * w + x * 2 + dx;
                        r += mw[0][i];
                        g += mw[1][i];
                        b += mw[2][i];
                    }
                }
                double n = (dither.nextDouble() - 0.5) / 255.0;
                bg.setRGB(x, y, 0xff000000 | rgb(Math.pow(clamp(r / 4 * expo - floor), 1 / 1.6) + n,
                        Math.pow(clamp(g / 4 * expo - floor), 1 / 1.6) + n, Math.pow(clamp(b / 4 * expo - floor), 1 / 1.6) + n));
            }
        }
        File skyDir = new File(out.getParentFile(), "sky");
        skyDir.mkdirs();
        write(bg, new File(skyDir, "milkyway.png"));
        blur(skyDir, "milkyway.png");

        record Star(double ra, double dec, double r, double g, double b, double flux) {}
        java.util.List<Star> stars = new java.util.ArrayList<>();
        float[] diff = new float[w * h];
        for (int i = 0; i < diff.length; i++) diff[i] = (float) (luminance(st, i) - lum[i]);
        for (int y = 2; y < h - 2; y++) {
            for (int x = 2; x < w - 2; x++) {
                int i = y * w + x;
                float l = diff[i];
                if (l < 0.002f) continue;
                boolean peak = true;
                for (int dy = -2; dy <= 2 && peak; dy++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        if ((dx != 0 || dy != 0) && diff[(y + dy) * w + x + dx] > l) {
                            peak = false;
                            break;
                        }
                    }
                }
                if (!peak) continue;
                double r = 0, g = 0, b = 0, sx = 0, sy = 0, sw = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int j = (y + dy) * w + x + dx;
                        double pr = Math.max(0, st[0][j] - mw[0][j]), pg = Math.max(0, st[1][j] - mw[1][j]), pb = Math.max(0, st[2][j] - mw[2][j]);
                        r += pr;
                        g += pg;
                        b += pb;
                        double pl = 0.2126 * pr + 0.7152 * pg + 0.0722 * pb;
                        sx += (x + dx + 0.5) * pl;
                        sy += (y + dy + 0.5) * pl;
                        sw += pl;
                    }
                }
                if (sw <= 0) continue;
                double flux = 0.2126 * r + 0.7152 * g + 0.0722 * b;
                double u = sx / sw / w, v = sy / sw / h;
                double m = Math.max(r, Math.max(g, b));
                stars.add(new Star((0.5 - u) * Math.PI * 2, (0.5 - v) * Math.PI, r / m, g / m, b / m, flux));
            }
        }
        stars.sort((a, b) -> Double.compare(b.flux(), a.flux()));
        int count = Math.min(14000, stars.size());
        try (var o = new java.io.DataOutputStream(new java.io.BufferedOutputStream(new java.io.FileOutputStream(new File(skyDir, "stars.bin"))))) {
            o.writeInt(count);
            for (int i = 0; i < count; i++) {
                Star s = stars.get(i);
                o.writeFloat((float) s.ra());
                o.writeFloat((float) s.dec());
                o.writeFloat((float) s.r());
                o.writeFloat((float) s.g());
                o.writeFloat((float) s.b());
                o.writeFloat((float) s.flux());
            }
        }
        System.out.printf("cielo: %d estrellas encontradas, %d guardadas (brillo de la mas fuerte %.3f, de la mas debil %.4f)%n",
                stars.size(), count, stars.get(0).flux(), stars.get(count - 1).flux());
    }

    private static double luminance(float[][] c, int i) {
        return 0.2126 * c[0][i] + 0.7152 * c[1][i] + 0.0722 * c[2][i];
    }

    private static int exrWidth, exrHeight;

    /** Lector mínimo de OpenEXR: por líneas, compresión ZIP o ninguna, canales HALF o FLOAT. Devuelve R, G, B. */
    private static float[][] readExr(File f) throws Exception {
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(java.nio.file.Files.readAllBytes(f.toPath())).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        bb.position(8);
        java.util.List<String> channels = new java.util.ArrayList<>();
        java.util.List<Integer> types = new java.util.ArrayList<>();
        int compression = 0, xmin = 0, ymin = 0, xmax = 0, ymax = 0;
        while (true) {
            String name = cstr(bb);
            if (name.isEmpty()) break;
            cstr(bb);
            int size = bb.getInt();
            int start = bb.position();
            switch (name) {
                case "channels" -> {
                    while (true) {
                        String ch = cstr(bb);
                        if (ch.isEmpty()) break;
                        types.add(bb.getInt());
                        bb.position(bb.position() + 12);
                        channels.add(ch);
                    }
                }
                case "compression" -> compression = bb.get();
                case "dataWindow" -> {
                    xmin = bb.getInt();
                    ymin = bb.getInt();
                    xmax = bb.getInt();
                    ymax = bb.getInt();
                }
                default -> { }
            }
            bb.position(start + size);
        }
        int w = xmax - xmin + 1, h = ymax - ymin + 1;
        exrWidth = w;
        exrHeight = h;
        int lines = compression == 3 ? 16 : 1;
        int chunks = (h + lines - 1) / lines;
        long[] offsets = new long[chunks];
        for (int i = 0; i < chunks; i++) offsets[i] = bb.getLong();
        int[] bytes = new int[channels.size()];
        int lineBytes = 0;
        for (int c = 0; c < bytes.length; c++) {
            bytes[c] = types.get(c) == 1 ? 2 : 4;
            lineBytes += bytes[c] * w;
        }
        float[][] rgb = new float[3][w * h];
        java.util.zip.Inflater inf = new java.util.zip.Inflater();
        for (int k = 0; k < chunks; k++) {
            bb.position((int) offsets[k]);
            int y0 = bb.getInt() - ymin;
            int size = bb.getInt();
            int n = Math.min(lines, h - y0);
            byte[] packed = new byte[size];
            bb.get(packed);
            byte[] raw = new byte[n * lineBytes];
            if (size < raw.length) {
                inf.reset();
                inf.setInput(packed);
                byte[] tmp = new byte[raw.length];
                int got = 0;
                while (got < tmp.length) {
                    int r = inf.inflate(tmp, got, tmp.length - got);
                    if (r == 0 && (inf.finished() || inf.needsInput())) break;
                    got += r;
                }
                for (int i = 1; i < tmp.length; i++) tmp[i] = (byte) (tmp[i - 1] + tmp[i] - 128);
                int t1 = 0, t2 = (tmp.length + 1) / 2;
                for (int i = 0; i < raw.length; ) {
                    raw[i++] = tmp[t1++];
                    if (i < raw.length) raw[i++] = tmp[t2++];
                }
            } else {
                raw = packed;
            }
            java.nio.ByteBuffer rb = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            for (int line = 0; line < n; line++) {
                for (int c = 0; c < channels.size(); c++) {
                    int target = switch (channels.get(c)) {
                        case "R" -> 0;
                        case "G" -> 1;
                        case "B" -> 2;
                        default -> -1;
                    };
                    for (int x = 0; x < w; x++) {
                        float v = bytes[c] == 2 ? Float.float16ToFloat(rb.getShort()) : rb.getFloat();
                        if (target >= 0) rgb[target][(y0 + line) * w + x] = Float.isFinite(v) ? Math.max(0, v) : 0;
                    }
                }
            }
        }
        return rgb;
    }

    private static String cstr(java.nio.ByteBuffer bb) {
        StringBuilder sb = new StringBuilder();
        byte b;
        while ((b = bb.get()) != 0) sb.append((char) b);
        return sb.toString();
    }

    private static int rgb(double r, double g, double b) {
        return (int) Math.round(clamp(r) * 255) << 16 | (int) Math.round(clamp(g) * 255) << 8 | (int) Math.round(clamp(b) * 255);
    }

    private static void writeLevels(File out, String name, BufferedImage img, int hiWidth) throws Exception {
        write(smoothPoles(resize(img, hiWidth, hiWidth / 2)), new File(out, name + "_hi.png"));
        write(resize(img, 256, 128), new File(out, name + "_lo.png"));
        blur(out, name + "_hi.png");
        blur(out, name + "_lo.png");
        System.out.printf("%-14s %dx%d -> %d / 256%n", name, img.getWidth(), img.getHeight(), hiWidth);
    }

    /**
     * Cerca de los polos un mapa equirectangular estira cada píxel en horizontal: todas las columnas convergen en un punto
     * y se ve una estrella de rayas. Se suaviza cada fila en horizontal en proporción a 1/cos(latitud), con su alfa.
     */
    private static BufferedImage smoothPoles(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            double cos = Math.cos(Math.PI * (0.5 - (y + 0.5) / h));
            int radius = (int) Math.min(w / 2.0, Math.max(0, 0.5 / Math.max(cos, 1e-3) - 0.5));
            img.getRGB(0, y, w, 1, row, 0, w);
            if (radius < 1) {
                out.setRGB(0, y, w, 1, row, 0, w);
                continue;
            }
            long sa = 0, sr = 0, sg = 0, sb = 0;
            for (int k = -radius; k <= radius; k++) {
                int c = row[Math.floorMod(k, w)];
                sa += c >>> 24; sr += c >> 16 & 255; sg += c >> 8 & 255; sb += c & 255;
            }
            int n = 2 * radius + 1;
            int[] res = new int[w];
            for (int x = 0; x < w; x++) {
                res[x] = (int) (sa / n) << 24 | (int) (sr / n) << 16 | (int) (sg / n) << 8 | (int) (sb / n);
                int add = row[Math.floorMod(x + radius + 1, w)], rem = row[Math.floorMod(x - radius, w)];
                sa += (add >>> 24) - (rem >>> 24);
                sr += (add >> 16 & 255) - (rem >> 16 & 255);
                sg += (add >> 8 & 255) - (rem >> 8 & 255);
                sb += (add & 255) - (rem & 255);
            }
            out.setRGB(0, y, w, 1, res, 0, w);
        }
        return out;
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
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            double v = (double) y / h;
            double lat = (0.5 - v) * Math.PI;
            // Más nubes en las borrascas de latitudes medias (~50°) y en la zona ecuatorial, menos en los desiertos (~25°).
            double belt = 0.08 * Math.exp(-Math.pow((Math.abs(lat) - 0.9) / 0.25, 2))
                    + 0.05 * Math.exp(-Math.pow(lat / 0.15, 2))
                    - 0.07 * Math.exp(-Math.pow((Math.abs(lat) - 0.42) / 0.15, 2));
            for (int x = 0; x < w; x++) {
                // Ruido 3D sobre la esfera: sin rayas que converjan en los polos.
                double phi = 2 * Math.PI * x / w;
                double px = Math.cos(lat) * Math.cos(phi), py = Math.sin(lat), pz = -Math.cos(lat) * Math.sin(phi);
                double wx = px + 0.25 * (fbm3(seed + 5, px * 2, py * 2, pz * 2, 4) - 0.5);
                double wy = py + 0.25 * (fbm3(seed + 6, px * 2, py * 2, pz * 2, 4) - 0.5);
                double wz = pz + 0.25 * (fbm3(seed + 7, px * 2, py * 2, pz * 2, 4) - 0.5);
                double base = fbm3(seed, wx * 3, wy * 3, wz * 3, 6);
                double fine = fbm3(seed + 1, wx * 24, wy * 24, wz * 24, 5);
                double c = base * 0.75 + fine * 0.25 + belt;
                double a = Math.pow(smooth(0.47, 0.86, c), 1.3) * 0.92;
                int alpha = (int) Math.round(clamp(a) * 255);
                int shade = 228 + (int) Math.round(27 * clamp((c - 0.5) * 2));
                img.setRGB(x, y, alpha << 24 | shade << 16 | shade << 8 | shade);
            }
        }
        return img;
    }

    /** Ruido de valor 3D (hash entero), en [0, 1]. */
    private static double noise3(long seed, double x, double y, double z) {
        long xi = (long) Math.floor(x), yi = (long) Math.floor(y), zi = (long) Math.floor(z);
        double fx = x - xi, fy = y - yi, fz = z - zi;
        fx = fx * fx * (3 - 2 * fx);
        fy = fy * fy * (3 - 2 * fy);
        fz = fz * fz * (3 - 2 * fz);
        double r = 0;
        for (int c = 0; c < 8; c++) {
            int dx = c & 1, dy = c >> 1 & 1, dz = c >> 2 & 1;
            long h = seed * 0x9E3779B97F4A7C15L + (xi + dx) * 0x632BE59BD9B4E019L + (yi + dy) * 0x85157AF5L + (zi + dz) * 0x1B873593L;
            h ^= h >>> 29;
            h *= 0xBF58476D1CE4E5B9L;
            h ^= h >>> 32;
            double v = (h >>> 11) * 0x1.0p-53;
            r += v * (dx == 1 ? fx : 1 - fx) * (dy == 1 ? fy : 1 - fy) * (dz == 1 ? fz : 1 - fz);
        }
        return r;
    }

    private static double fbm3(long seed, double x, double y, double z, int octaves) {
        double sum = 0, amp = 0.5, norm = 0;
        for (int o = 0; o < octaves; o++) {
            sum += amp * noise3(seed + o, x, y, z);
            norm += amp;
            amp *= 0.5;
            x *= 2.03;
            y *= 2.03;
            z *= 2.03;
        }
        return sum / norm;
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

    /**
     * Franja radial de los anillos de Saturno (x = de 1,24 a 2,27 radios). Perfil a partir de las medidas de Cassini:
     * anillo C tenue y gris, anillo B opaco y color crema, división de Cassini, anillo A con las divisiones de Encke y
     * Keeler. Encima, cientos de anillitos con ruido en el radio.
     */
    private static BufferedImage ringStrip(int w) {
        Noise n = new Noise(42);
        BufferedImage img = new BufferedImage(w, 8, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < w; x++) {
            double t = (double) x / (w - 1);
            double radius = 1.24 + t * 1.03;
            double fine = n.at(radius * 900, 0.5, 1 << 20) - 0.5;
            double mid = n.at(radius * 120, 3.5, 1 << 20) - 0.5;
            double alpha;
            double[] c;
            if (radius < 1.53) {            // anillo C
                alpha = 0.16 + 0.10 * mid + 0.08 * fine + (radius > 1.43 && radius < 1.50 ? 0.08 : 0);
                c = new double[] {0.55, 0.50, 0.45};
            } else if (radius < 1.95) {     // anillo B
                double inB = (radius - 1.53) / 0.42;
                alpha = 0.84 + 0.14 * Math.sin(inB * Math.PI) + 0.08 * mid + 0.04 * fine;
                double bright = 0.85 + 0.15 * Math.sin(inB * Math.PI);
                c = new double[] {0.90 * bright, 0.82 * bright, 0.68 * bright};
            } else if (radius < 2.03) {     // división de Cassini
                alpha = 0.06 + 0.04 * fine + (radius > 1.99 && radius < 2.0 ? 0.08 : 0);
                c = new double[] {0.5, 0.47, 0.43};
            } else {                         // anillo A
                alpha = 0.58 + 0.08 * mid + 0.05 * fine - 0.12 * smooth(2.15, 2.27, radius);
                c = new double[] {0.82, 0.76, 0.66};
                if (Math.abs(radius - 2.2166) < 0.0045 || Math.abs(radius - 2.2648) < 0.0012) {
                    alpha = 0.03;            // divisiones de Encke y Keeler
                }
            }
            double edge = smooth(0.0, 0.004, t) * (1 - smooth(0.996, 1.0, t));
            int a = (int) Math.round(clamp(alpha * edge) * 255);
            int color = rgb(c[0] * (1 + 0.15 * mid), c[1] * (1 + 0.15 * mid), c[2] * (1 + 0.12 * mid));
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
