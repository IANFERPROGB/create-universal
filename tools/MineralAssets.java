import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Texturas de los minerales nuevos y de las mejoras del traje (alpha.7.1). Las menas usan la piedra y la pizarra de
 * Minecraft con las manchas de la mena de hierro recoloreadas; lingotes, menas en bruto y gemas, las siluetas de los de
 * Minecraft recoloreadas; las mejoras se dibujan aquí.
 *
 * Uso: java tools/MineralAssets.java <carpeta con assets/minecraft/textures> <src/main/resources>
 */
public class MineralAssets {
    public static void main(String[] args) throws Exception {
        File mc = new File(args[0]);
        File res = new File(args[1]);
        File blocks = new File(res, "assets/create_universal/textures/block");
        File items = new File(res, "assets/create_universal/textures/item");
        blocks.mkdirs();
        items.mkdirs();
        BufferedImage stone = read(mc, "block/stone");
        BufferedImage deep = read(mc, "block/deepslate");
        BufferedImage ironOre = read(mc, "block/iron_ore");
        BufferedImage deepIronOre = read(mc, "block/deepslate_iron_ore");
        BufferedImage emeraldOre = read(mc, "block/emerald_ore");
        BufferedImage deepEmeraldOre = read(mc, "block/deepslate_emerald_ore");

        // Cromita: negra con brillo metálico pardo. Colemanita: cristales blancos. Berilo: verde azulado (gema).
        write(ore(stone, ironOre, 0x2E2A2B, 0x6E5F55), blocks, "chromite_ore");
        write(ore(deep, deepIronOre, 0x2E2A2B, 0x6E5F55), blocks, "deepslate_chromite_ore");
        write(ore(stone, ironOre, 0xE8E6E0, 0xFFFFFF), blocks, "colemanite_ore");
        write(ore(deep, deepIronOre, 0xE8E6E0, 0xFFFFFF), blocks, "deepslate_colemanite_ore");
        write(ore(stone, emeraldOre, 0x3FA79A, 0x9FE7DC), blocks, "beryl_ore");
        write(ore(deep, deepEmeraldOre, 0x3FA79A, 0x9FE7DC), blocks, "deepslate_beryl_ore");

        BufferedImage ingot = read(mc, "item/iron_ingot");
        BufferedImage raw = read(mc, "item/raw_iron");
        BufferedImage gem = read(mc, "item/emerald");
        BufferedImage shard = read(mc, "item/amethyst_shard");
        BufferedImage netherite = read(mc, "item/netherite_ingot");
        write(tint(raw, 0x3A3436, 0x8A7A6C), items, "raw_chromite");
        write(tint(shard, 0xC9C6BE, 0xFFFFFF), items, "colemanite");
        write(tint(gem, 0x2C8C80, 0xB6F2E8), items, "beryl");
        write(tint(ingot, 0x7E8790, 0xE9F0F5), items, "chromium_ingot");
        write(tint(ingot, 0x3D3A35, 0x8F8A80), items, "boron_ingot");
        write(tint(ingot, 0x8E9A93, 0xDDE8E2), items, "beryllium_ingot");
        write(tint(netherite, 0x5E6C78, 0xD0DCE6), items, "chromium_superalloy");
        write(aerogel(), items, "aerogel");
        write(module(0xE7E3DA, 0x8ED3F0, 'T'), items, "thermal_insulation");
        write(module(0x4F5A3C, 0xE8D33A, 'R'), items, "radiation_shield");
        write(module(0x6F7B86, 0x6FC3F7, 'C'), items, "cooling_system");
        write(module(0x8A6A3A, 0xE8A23A, 'V'), items, "venus_plating");
        write(module(0x3F3637, 0xE8892E, 'O'), items, "tank_expansion");
        System.out.println("Hecho.");
    }

    static BufferedImage read(File mc, String name) throws Exception {
        return ImageIO.read(new File(mc, name + ".png"));
    }

    static void write(BufferedImage img, File dir, String name) throws Exception {
        ImageIO.write(img, "png", new File(dir, name + ".png"));
    }

    /** La piedra de fondo y, donde la mena de Minecraft se aparta de su piedra, las manchas recoloreadas. */
    static BufferedImage ore(BufferedImage base, BufferedImage ore, int dark, int light) {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int o = ore.getRGB(x, y);
                // Las manchas de la mena son las que tienen color; la piedra es gris.
                if (sat(o) > 0.2) {
                    img.setRGB(x, y, 0xFF000000 | mix(dark, light, lum(o) * 1.4 - 0.15));
                } else {
                    img.setRGB(x, y, o);
                }
            }
        }
        return img;
    }

    /** Silueta del objeto de Minecraft con otro color: lo oscuro a {@code dark}, lo claro a {@code light}. */
    static BufferedImage tint(BufferedImage src, int dark, int light) {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        double min = 1, max = 0;
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                if ((src.getRGB(x, y) >>> 24) > 100) {
                    min = Math.min(min, lum(src.getRGB(x, y)));
                    max = Math.max(max, lum(src.getRGB(x, y)));
                }
            }
        }
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int p = src.getRGB(x, y);
                if ((p >>> 24) <= 100) {
                    continue;
                }
                double t = (lum(p) - min) / Math.max(1e-6, max - min);
                img.setRGB(x, y, 0xFF000000 | mix(dark, light, t));
            }
        }
        return img;
    }

    /** Bloque de aerogel: azul pálido, casi transparente, con borde. */
    static BufferedImage aerogel() {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 3; y <= 12; y++) {
            for (int x = 3; x <= 12; x++) {
                boolean edge = x == 3 || x == 12 || y == 3 || y == 12;
                int a = edge ? 0xE0 : 0x90;
                int c = edge ? 0x9CC7E6 : ((x + y) % 5 == 0 ? 0xE6F4FF : 0xBEDDF2);
                img.setRGB(x, y, a << 24 | c);
            }
        }
        return img;
    }

    /** Módulo de mejora: placa con marco oscuro, panel de color y una marca. */
    static BufferedImage module(int body, int accent, char mark) {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        int frame = 0x2A2324;
        for (int y = 2; y <= 13; y++) {
            for (int x = 2; x <= 13; x++) {
                boolean edge = x == 2 || x == 13 || y == 2 || y == 13;
                int c = edge ? frame : (y < 5 ? mix(body, 0xFFFFFF, 0.25) : y > 10 ? mix(body, 0x000000, 0.25) : body);
                img.setRGB(x, y, 0xFF000000 | c);
            }
        }
        // Tornillos en las esquinas.
        int[][] screws = {{3, 3}, {12, 3}, {3, 12}, {12, 12}};
        for (int[] s : screws) {
            img.setRGB(s[0], s[1], 0xFFD9A84C);
        }
        // Marca central de 4×4 en el color de acento.
        String[] glyph = switch (mark) {
            case 'T' -> new String[] {"####", ".##.", ".##.", ".##."};
            case 'R' -> new String[] {"#..#", ".##.", ".##.", "#..#"};
            case 'C' -> new String[] {".##.", "#...", "#...", ".##."};
            case 'V' -> new String[] {"#..#", "#..#", ".##.", ".##."};
            default -> new String[] {".##.", "#..#", "#..#", ".##."};
        };
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                if (glyph[y].charAt(x) == '#') {
                    img.setRGB(6 + x, 6 + y, 0xFF000000 | accent);
                }
            }
        }
        return img;
    }

    static double lum(int p) {
        return (((p >> 16) & 255) * 0.3 + ((p >> 8) & 255) * 0.59 + (p & 255) * 0.11) / 255.0;
    }

    static double sat(int p) {
        int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
        int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        return max == 0 ? 0 : (max - min) / (double) max;
    }

    static int mix(int a, int b, double t) {
        t = Math.max(0, Math.min(1, t));
        int r = (int) Math.round(((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) Math.round(((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) Math.round((a & 255) * (1 - t) + (b & 255) * t);
        return r << 16 | g << 8 | bl;
    }
}
