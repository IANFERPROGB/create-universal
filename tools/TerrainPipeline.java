import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Prepara el relieve de las superficies (D74): alturas reales de la NASA y un mapa de "albedo" para elegir los bloques.
 *
 * Uso: java tools/TerrainPipeline.java <carpeta_fuentes> <carpeta_salida>
 *
 * Fuentes (fuera del repo):
 *  - Luna: ldem_16_uint.tif del CGI Moon Kit de la SVS (LOLA, 16 px/grado, medios metros + 10 km) y moon_4k.tif (LRO).
 *  - Marte: megt90n000eb.img de PDS Geosciences (MOLA MEGDR, 16 px/grado, int16 big-endian en metros, de 0° a 360° E)
 *    y mars.jpg (NASA 3D Resources).
 *
 * Salida: terrain/<astro>.bin (gzip): "CUT1", ancho, alto, alturas int16 en metros (columna 0 = longitud −180°, fila 0
 * = polo norte, igual que las texturas) y un byte de albedo por muestra.
 */
public final class TerrainPipeline {
    private static final int W = 2880;
    private static final int H = 1440;

    public static void main(String[] args) throws Exception {
        File src = new File(args[0]);
        File out = new File(args[1]);
        out.mkdirs();

        // Luna: LOLA, columna 0 = −180° (como el resto del CGI Moon Kit).
        BufferedImage ldem = ImageIO.read(new File(src, "moon_ldem16.tif"));
        Raster lr = ldem.getRaster();
        short[] moon = downsample(ldem.getWidth(), ldem.getHeight(), (x, y) -> lr.getSample(x, y, 0) * 0.5 - 10000.0, 0);
        write(new File(out, "moon.bin"), moon, albedo(ImageIO.read(new File(src, "moon_4k.tif"))));

        // Marte: MOLA, columna 0 = 0° E → se desplaza media vuelta para empezar en −180°.
        int mw = 5760, mh = 2880;
        short[] mola = new short[mw * mh];
        try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(new FileInputStream(new File(src, "mars_mola16.img"))))) {
            for (int i = 0; i < mola.length; i++) {
                mola[i] = in.readShort(); // big-endian, como pide el formato
            }
        }
        short[] mars = downsample(mw, mh, (x, y) -> mola[y * mw + x], mw / 2);
        write(new File(out, "mars.bin"), mars, albedo(ImageIO.read(new File(src, "mars.jpg"))));
        System.out.println("Hecho.");
    }

    private interface Sampler {
        double at(int x, int y);
    }

    /** Media de cada bloque de píxeles hasta {@link #W}×{@link #H}, con un desplazamiento en longitud. */
    private static short[] downsample(int sw, int sh, Sampler s, int shift) {
        short[] out = new short[W * H];
        int fx = sw / W, fy = sh / H;
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                double sum = 0;
                for (int dy = 0; dy < fy; dy++) {
                    for (int dx = 0; dx < fx; dx++) {
                        sum += s.at(Math.floorMod(x * fx + dx + shift, sw), y * fy + dy);
                    }
                }
                double v = sum / (fx * fy);
                min = Math.min(min, v);
                max = Math.max(max, v);
                out[y * W + x] = (short) Math.round(v);
            }
        }
        System.out.printf("alturas de %.0f m a %.0f m%n", min, max);
        return out;
    }

    private static byte[] albedo(BufferedImage img) {
        byte[] out = new byte[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int c = img.getRGB(x * img.getWidth() / W, y * img.getHeight() / H);
                int l = (int) Math.round(0.299 * (c >> 16 & 255) + 0.587 * (c >> 8 & 255) + 0.114 * (c & 255));
                out[y * W + x] = (byte) l;
            }
        }
        return out;
    }

    private static void write(File f, short[] heights, byte[] albedo) throws Exception {
        try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(f), 1 << 16)))) {
            o.writeBytes("CUT1");
            o.writeInt(W);
            o.writeInt(H);
            for (short h : heights) {
                o.writeShort(h);
            }
            o.write(albedo);
        }
        System.out.println(f.getName() + ": " + f.length() / 1024 + " KB");
    }
}
