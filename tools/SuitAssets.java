import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Random;

/**
 * Genera las texturas del traje espacial (alpha.7.0): las dos capas de armadura (64×32) y los iconos (16×16).
 * Traje blanco de una pieza, sellado (el casco cubre toda la cabeza), con visor dorado, franjas naranjas al estilo de
 * Create, guantes, cinturón y botas de netherita, y el tanque de netherita a la espalda. Los iconos de las cuatro piezas
 * usan la silueta de los de netherita de Minecraft.
 *
 * Uso: java tools/SuitAssets.java <carpeta con assets/minecraft/textures/item> <src/main/resources>
 */
public class SuitAssets {
    static final int WHITE_HI = 0xEEECE7, WHITE = 0xDAD6CE, WHITE_SH = 0xBBB5AA, SEAM = 0x8F887D;
    static final int DARK_HI = 0x5C5152, DARK = 0x3F3637, DARK_SH = 0x2A2324;
    static final int ORANGE = 0xE8892E, ORANGE_SH = 0xB2601B;
    static final int GOLD_HI = 0xFCE79A, GOLD = 0xE2B042, GOLD_SH = 0x9A681B;
    static final int BRASS = 0xD9A84C, GREEN = 0x5ED16A, RED = 0xE0473A;

    static final Random RNG = new Random(7);

    public static void main(String[] args) throws Exception {
        File vanilla = new File(args[0]);
        File res = new File(args[1]);
        File armor = new File(res, "assets/create_universal/textures/models/armor");
        File items = new File(res, "assets/create_universal/textures/item");
        armor.mkdirs();
        items.mkdirs();
        BufferedImage l1 = layer1();
        ImageIO.write(l1, "png", new File(armor, "space_suit_layer_1.png"));
        // La pechera colocada como bloque usa la misma textura (en una lámina cuadrada para el atlas de bloques).
        BufferedImage block = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        block.getGraphics().drawImage(l1, 0, 0, null);
        File blocks = new File(res, "assets/create_universal/textures/block");
        blocks.mkdirs();
        ImageIO.write(block, "png", new File(blocks, "space_suit_tank.png"));
        ImageIO.write(layer2(), "png", new File(armor, "space_suit_layer_2.png"));
        ImageIO.write(helmetIcon(read(vanilla, "netherite_helmet"), true), "png", new File(items, "space_helmet.png"));
        ImageIO.write(helmetIcon(read(vanilla, "netherite_helmet"), false), "png", new File(items, "incomplete_space_helmet.png"));
        ImageIO.write(chestIcon(read(vanilla, "netherite_chestplate")), "png", new File(items, "space_chestplate.png"));
        ImageIO.write(legsIcon(read(vanilla, "netherite_leggings")), "png", new File(items, "space_leggings.png"));
        ImageIO.write(bootsIcon(read(vanilla, "netherite_boots")), "png", new File(items, "space_boots.png"));
        ImageIO.write(visorIcon(), "png", new File(items, "space_visor.png"));
        ImageIO.write(sealsIcon(), "png", new File(items, "pressure_seals.png"));
        System.out.println("Hecho.");
    }

    static BufferedImage read(File dir, String name) throws Exception {
        return ImageIO.read(new File(dir, name + ".png"));
    }

    // ------------------------------------------------------------------ capas de armadura

    /** Caras de una caja del modelo humanoide: origen (u, v) y tamaño (ancho w, alto h, fondo d). */
    interface Face {
        void paint(BufferedImage img, int x0, int y0, int w, int h, String side);
    }

    static void box(BufferedImage img, int u, int v, int w, int h, int d, Face f) {
        f.paint(img, u + d, v, w, d, "top");
        f.paint(img, u + d + w, v, w, d, "bottom");
        f.paint(img, u, v + d, d, h, "right");
        f.paint(img, u + d, v + d, w, h, "front");
        f.paint(img, u + d + w, v + d, d, h, "left");
        f.paint(img, u + d + w + d, v + d, w, h, "back");
    }

    /** Tela blanca con un poco de sombra hacia abajo, grano y costura en el borde. */
    static void cloth(BufferedImage img, int x0, int y0, int w, int h) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = y < h / 3 ? WHITE_HI : y < h * 2 / 3 ? WHITE : WHITE_SH;
                if (RNG.nextInt(7) == 0) {
                    c = c == WHITE_HI ? WHITE : c == WHITE ? (RNG.nextBoolean() ? WHITE_HI : WHITE_SH) : WHITE;
                }
                if (x == 0 || x == w - 1) {
                    c = mix(c, SEAM, 0.45);
                }
                set(img, x0 + x, y0 + y, c);
            }
        }
    }

    static void metal(BufferedImage img, int x0, int y0, int w, int h) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = y == 0 ? DARK_HI : y == h - 1 ? DARK_SH : DARK;
                if (RNG.nextInt(6) == 0) {
                    c = RNG.nextBoolean() ? DARK_HI : DARK_SH;
                }
                set(img, x0 + x, y0 + y, c);
            }
        }
    }

    static BufferedImage layer1() {
        BufferedImage img = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        // Casco sellado.
        box(img, 0, 0, 8, 8, 8, (g, x0, y0, w, h, side) -> {
            cloth(g, x0, y0, w, h);
            if (side.equals("front")) {
                // Marco y visor dorado.
                for (int x = 0; x < 8; x++) {
                    set(g, x0 + x, y0 + 1, DARK);
                    set(g, x0 + x, y0 + 6, DARK);
                }
                for (int y = 2; y <= 5; y++) {
                    set(g, x0, y0 + y, DARK);
                    set(g, x0 + 7, y0 + y, DARK);
                    for (int x = 1; x <= 6; x++) {
                        int c = y == 2 ? GOLD_HI : y == 5 ? GOLD_SH : GOLD;
                        if (x + y == 5 || x + y == 6) {
                            c = GOLD_HI; // brillo en diagonal
                        }
                        set(g, x0 + x, y0 + y, c);
                    }
                }
            } else if (side.equals("right") || side.equals("left")) {
                set(g, x0 + 3, y0 + 3, ORANGE);
                set(g, x0 + 4, y0 + 3, ORANGE);
                set(g, x0 + 3, y0 + 4, ORANGE_SH);
                set(g, x0 + 4, y0 + 4, ORANGE_SH);
            } else if (side.equals("back")) {
                metal(g, x0 + 2, y0 + 4, 4, 3);
            } else if (side.equals("bottom")) {
                metal(g, x0, y0, w, h);
            }
        });
        // Torso: franja naranja, panel de control, cinturón; a la espalda, el tanque de netherita.
        box(img, 16, 16, 8, 12, 4, (g, x0, y0, w, h, side) -> {
            if (side.equals("back")) {
                metal(g, x0, y0, w, h);
                for (int x = 0; x < w; x++) {
                    set(g, x0 + x, y0 + 2, ORANGE);
                    set(g, x0 + x, y0 + 9, ORANGE_SH);
                }
                set(g, x0 + 1, y0 + 5, BRASS);
                set(g, x0 + 6, y0 + 5, BRASS);
                return;
            }
            cloth(g, x0, y0, w, h);
            if (side.equals("top") || side.equals("bottom")) {
                return;
            }
            for (int x = 0; x < w; x++) {
                set(g, x0 + x, y0 + 3, ORANGE);
                set(g, x0 + x, y0 + 10, DARK);
                set(g, x0 + x, y0 + 11, DARK_SH);
            }
            if (side.equals("front")) {
                metal(g, x0 + 3, y0 + 5, 2, 3);
                set(g, x0 + 3, y0 + 6, GREEN);
                set(g, x0 + 4, y0 + 6, RED);
                set(g, x0 + 3, y0 + 10, BRASS);
                set(g, x0 + 4, y0 + 10, BRASS);
            }
        });
        // Brazos: banda naranja y guantes de netherita.
        box(img, 40, 16, 4, 12, 4, (g, x0, y0, w, h, side) -> {
            if (side.equals("bottom")) {
                metal(g, x0, y0, w, h);
                return;
            }
            cloth(g, x0, y0, w, h);
            if (side.equals("top")) {
                return;
            }
            for (int x = 0; x < w; x++) {
                set(g, x0 + x, y0 + 3, ORANGE);
            }
            metal(g, x0, y0 + 9, w, 3);
        });
        // Botas (en la capa 1 van en la caja de la pierna): solo la parte de abajo, de netherita.
        box(img, 0, 16, 4, 12, 4, (g, x0, y0, w, h, side) -> {
            if (side.equals("top")) {
                return;
            }
            if (side.equals("bottom")) {
                metal(g, x0, y0, w, h);
                return;
            }
            metal(g, x0, y0 + 7, w, 5);
            for (int x = 0; x < w; x++) {
                set(g, x0 + x, y0 + 7, ORANGE);
            }
        });
        return img;
    }

    static BufferedImage layer2() {
        BufferedImage img = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        // Piernas: tela con rodilleras de netherita.
        box(img, 0, 16, 4, 12, 4, (g, x0, y0, w, h, side) -> {
            if (side.equals("bottom")) {
                return;
            }
            cloth(g, x0, y0, w, h);
            if (side.equals("front")) {
                metal(g, x0, y0 + 5, w, 2);
            }
        });
        // Cadera: cinturón con hebilla.
        box(img, 16, 16, 8, 12, 4, (g, x0, y0, w, h, side) -> {
            if (side.equals("top")) {
                return;
            }
            if (side.equals("bottom")) {
                cloth(g, x0, y0, w, h);
                return;
            }
            cloth(g, x0, y0 + 8, w, 4);
            metal(g, x0, y0 + 8, w, 2);
            if (side.equals("front")) {
                set(g, x0 + 3, y0 + 8, BRASS);
                set(g, x0 + 4, y0 + 8, BRASS);
            }
        });
        return img;
    }

    // ------------------------------------------------------------------ iconos

    /** Silueta del icono de netherita pasada a traje: claro por dentro, contorno oscuro. */
    static BufferedImage suitFrom(BufferedImage src) {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        // El brillo se toma relativo al del propio icono (la netherita es oscura): lo más oscuro, contorno; el resto, blanco.
        double min = 1.0;
        double max = 0.0;
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                if ((src.getRGB(x, y) >>> 24) >= 128) {
                    double l = lum(src.getRGB(x, y));
                    min = Math.min(min, l);
                    max = Math.max(max, l);
                }
            }
        }
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int p = src.getRGB(x, y);
                if ((p >>> 24) < 128) {
                    continue;
                }
                double t = (lum(p) - min) / Math.max(1e-6, max - min);
                int c = t < 0.15 ? DARK_SH : t < 0.32 ? SEAM : t < 0.5 ? WHITE_SH : t < 0.75 ? WHITE : WHITE_HI;
                set(img, x, y, c);
            }
        }
        return img;
    }

    static BufferedImage helmetIcon(BufferedImage src, boolean visor) {
        BufferedImage img = suitFrom(src);
        // El hueco de la cara se cierra con el visor.
        for (int y = 5; y <= 10; y++) {
            for (int x = 4; x <= 11; x++) {
                if (y >= 6 && y <= 9 && x >= 5 && x <= 10) {
                    int c = !visor ? DARK : y == 6 ? GOLD_HI : y == 9 ? GOLD_SH : GOLD;
                    if (visor && x - y == -1) {
                        c = GOLD_HI;
                    }
                    set(img, x, y, c);
                } else if (alpha(img, x, y) == 0 || y == 5 || y == 10) {
                    set(img, x, y, DARK);
                }
            }
        }
        set(img, 3, 8, ORANGE);
        set(img, 12, 8, ORANGE);
        return img;
    }

    static BufferedImage chestIcon(BufferedImage src) {
        BufferedImage img = suitFrom(src);
        for (int x = 0; x < 16; x++) {
            if (alpha(img, x, 6) > 0 && img.getRGB(x, 6) != (0xFF000000 | DARK_SH)) {
                set(img, x, 6, ORANGE);
            }
        }
        set(img, 7, 9, DARK);
        set(img, 8, 9, DARK);
        set(img, 7, 10, GREEN);
        set(img, 8, 10, RED);
        return img;
    }

    static BufferedImage legsIcon(BufferedImage src) {
        BufferedImage img = suitFrom(src);
        for (int x = 0; x < 16; x++) {
            for (int y = 9; y <= 10; y++) {
                if (alpha(img, x, y) > 0 && img.getRGB(x, y) != (0xFF000000 | DARK_SH)) {
                    set(img, x, y, DARK);
                }
            }
            if (alpha(img, x, 2) > 0 && img.getRGB(x, 2) != (0xFF000000 | DARK_SH)) {
                set(img, x, 2, DARK);
            }
        }
        return img;
    }

    static BufferedImage bootsIcon(BufferedImage src) {
        BufferedImage img = suitFrom(src);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                if (alpha(img, x, y) > 0 && y >= 11 && img.getRGB(x, y) != (0xFF000000 | DARK_SH)) {
                    set(img, x, y, y == 11 ? ORANGE : DARK);
                }
            }
        }
        return img;
    }

    static BufferedImage visorIcon() {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 3; y <= 12; y++) {
            for (int x = 2; x <= 13; x++) {
                boolean corner = (y == 3 || y == 12) && (x <= 3 || x >= 12);
                if (corner) {
                    continue;
                }
                boolean edge = y == 3 || y == 12 || x == 2 || x == 13 || ((y == 4 || y == 11) && (x == 3 || x == 12));
                int c = edge ? DARK : y < 6 ? GOLD_HI : y > 9 ? GOLD_SH : GOLD;
                if (!edge && (x + y == 11 || x + y == 12)) {
                    c = GOLD_HI;
                }
                set(img, x, y, c);
            }
        }
        return img;
    }

    static BufferedImage sealsIcon() {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                double d = Math.hypot(x - 7.5, y - 7.5);
                if (d <= 6.6 && d >= 4.2) {
                    int c = d > 6.0 ? DARK_SH : d < 4.8 ? DARK_HI : DARK;
                    if (d > 5.0 && d < 5.8 && Math.abs(x - 7.5) < 1.0) {
                        c = BRASS;
                    }
                    if (d > 5.0 && d < 5.8 && Math.abs(y - 7.5) < 1.0) {
                        c = BRASS;
                    }
                    set(img, x, y, c);
                }
            }
        }
        return img;
    }

    // ------------------------------------------------------------------ utilidades

    static void set(BufferedImage img, int x, int y, int rgb) {
        if (x >= 0 && y >= 0 && x < img.getWidth() && y < img.getHeight()) {
            img.setRGB(x, y, 0xFF000000 | rgb);
        }
    }

    static int alpha(BufferedImage img, int x, int y) {
        return img.getRGB(x, y) >>> 24;
    }

    static double lum(int p) {
        return (((p >> 16) & 255) * 0.3 + ((p >> 8) & 255) * 0.59 + (p & 255) * 0.11) / 255.0;
    }

    static int mix(int a, int b, double t) {
        int r = (int) Math.round(((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) Math.round(((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) Math.round((a & 255) * (1 - t) + (b & 255) * t);
        return r << 16 | g << 8 | bl;
    }
}
