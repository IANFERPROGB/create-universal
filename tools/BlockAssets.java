import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.Random;

/**
 * Genera las texturas (16×16) y los JSON de los bloques de las superficies: estados, modelos, objetos, botín y etiquetas
 * de herramienta. Colores tomados de las fotos de la NASA (LRO para la Luna, rovers y orbitadores para Marte).
 *
 * Uso: java tools/BlockAssets.java <src/main/resources>
 */
public final class BlockAssets {
    // nombre, color base, variación, tipo (0 = suelo suelto, 1 = roca con grietas, 2 = basalto con vesículas)
    private static final Object[][] BLOCKS = {
            {"lunar_regolith", 0x9b9995, 0.10, 0},
            {"lunar_mare_regolith", 0x605e5b, 0.10, 0},
            {"lunar_rock", 0x8a8783, 0.12, 1},
            {"lunar_basalt", 0x4b4947, 0.10, 2},
            {"martian_dust", 0xbf7448, 0.08, 0},
            {"martian_regolith", 0x8a4e33, 0.10, 0},
            {"martian_rock", 0x99583b, 0.12, 1},
            {"martian_basalt", 0x553a2e, 0.10, 2},
    };

    public static void main(String[] args) throws Exception {
        File res = new File(args[0]);
        File assets = new File(res, "assets/create_universal");
        File data = new File(res, "data/create_universal");
        StringBuilder shovel = new StringBuilder();
        StringBuilder pickaxe = new StringBuilder();
        for (Object[] b : BLOCKS) {
            String name = (String) b[0];
            int kind = (Integer) b[3];
            ImageIO.write(texture(name, (Integer) b[1], (Double) b[2], kind), "png", mk(new File(assets, "textures/block/" + name + ".png")));
            write(new File(assets, "blockstates/" + name + ".json"), "{\n  \"variants\": {\n    \"\": [\n"
                    + "      { \"model\": \"create_universal:block/" + name + "\" },\n"
                    + "      { \"model\": \"create_universal:block/" + name + "\", \"y\": 90 },\n"
                    + "      { \"model\": \"create_universal:block/" + name + "\", \"y\": 180 },\n"
                    + "      { \"model\": \"create_universal:block/" + name + "\", \"y\": 270 }\n    ]\n  }\n}\n");
            write(new File(assets, "models/block/" + name + ".json"),
                    "{\n  \"parent\": \"minecraft:block/cube_all\",\n  \"textures\": { \"all\": \"create_universal:block/" + name + "\" }\n}\n");
            write(new File(assets, "models/item/" + name + ".json"), "{\n  \"parent\": \"create_universal:block/" + name + "\"\n}\n");
            write(new File(data, "loot_table/blocks/" + name + ".json"), "{\n  \"type\": \"minecraft:block\",\n  \"pools\": [\n    {\n"
                    + "      \"rolls\": 1,\n      \"entries\": [ { \"type\": \"minecraft:item\", \"name\": \"create_universal:" + name + "\" } ],\n"
                    + "      \"conditions\": [ { \"condition\": \"minecraft:survives_explosion\" } ]\n    }\n  ]\n}\n");
            StringBuilder list = kind == 0 ? shovel : pickaxe;
            list.append(list.isEmpty() ? "" : ",\n").append("    \"create_universal:").append(name).append("\"");
        }
        File tags = new File(res, "data/minecraft/tags/block/mineable");
        write(new File(tags, "shovel.json"), "{\n  \"replace\": false,\n  \"values\": [\n" + shovel + "\n  ]\n}\n");
        write(new File(tags, "pickaxe.json"), "{\n  \"replace\": false,\n  \"values\": [\n" + pickaxe + "\n  ]\n}\n");
        System.out.println("Hecho.");
    }

    private static BufferedImage texture(String name, int base, double variation, int kind) {
        Random r = new Random(name.hashCode());
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        double[][] v = new double[16][16];
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                v[y][x] = (r.nextDouble() - 0.5) * 2 * variation;
            }
        }
        // Suavizado leve para que el grano no parezca ruido puro.
        double[][] s = new double[16][16];
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                double sum = 0;
                for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) sum += v[(y + dy + 16) % 16][(x + dx + 16) % 16];
                s[y][x] = v[y][x] * 0.55 + sum / 9 * 0.45;
            }
        }
        if (kind == 0) {
            // Piedrecitas sueltas.
            for (int i = 0; i < 7; i++) {
                int x = r.nextInt(16), y = r.nextInt(16);
                s[y][x] += r.nextBoolean() ? 0.16 : -0.14;
            }
        } else if (kind == 1) {
            // Grietas.
            for (int c = 0; c < 2; c++) {
                int x = r.nextInt(16), y = r.nextInt(16);
                for (int i = 0; i < 9; i++) {
                    s[(y + 16) % 16][(x + 16) % 16] -= 0.18;
                    x += r.nextInt(3) - 1;
                    y += r.nextBoolean() ? 1 : 0;
                }
            }
        } else {
            // Vesículas del basalto.
            for (int i = 0; i < 9; i++) {
                int x = r.nextInt(16), y = r.nextInt(16);
                s[y][x] -= 0.22;
            }
        }
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                double f = 1 + s[y][x];
                int cr = clamp((int) Math.round((base >> 16 & 255) * f));
                int cg = clamp((int) Math.round((base >> 8 & 255) * f));
                int cb = clamp((int) Math.round((base & 255) * f));
                img.setRGB(x, y, 0xff000000 | cr << 16 | cg << 8 | cb);
            }
        }
        return img;
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static File mk(File f) {
        f.getParentFile().mkdirs();
        return f;
    }

    private static void write(File f, String s) throws Exception {
        Files.writeString(mk(f).toPath(), s);
    }
}
