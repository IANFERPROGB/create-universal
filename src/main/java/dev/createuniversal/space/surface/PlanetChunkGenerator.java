package dev.createuniversal.space.surface;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.createuniversal.CreateUniversal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Genera la superficie de un astro a partir de {@link PlanetTerrain}: suelo suelto arriba (claro u oscuro según el mapa
 * del astro, con bordes irregulares), roca debajo y lecho de roca abajo del todo. Los bloques de cada astro vienen de
 * su {@link PlanetSurface.Palette}. Sin cuevas, sin estructuras, sin vida.
 *
 * Rendimiento: el relieve puede tener 3.000 bloques de alto, así que las secciones (16³) que quedan enteras por debajo
 * de la corteza se rellenan de golpe con un solo bloque, sin recorrerlas bloque a bloque.
 */
public final class PlanetChunkGenerator extends ChunkGenerator {
    public static final MapCodec<PlanetChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> g.biomeSource),
            Codec.STRING.fieldOf("planet").forGetter(g -> g.surface.bodyId())
    ).apply(i, PlanetChunkGenerator::new));

    /** Grosor del suelo suelto sobre la roca. */
    private static final int SOIL_DEPTH = 4;
    /** Cuánto baja la corteza recorrida bloque a bloque por debajo del punto más bajo de la columna y sus vecinas. */
    private static final int CRUST = 24;

    private final PlanetSurface surface;
    /** Suelo claro, suelo oscuro, roca clara, roca oscura; se buscan la primera vez (los bloques ya están registrados). */
    private volatile BlockState[] palette;

    public PlanetChunkGenerator(BiomeSource biomeSource, String planet) {
        super(biomeSource);
        PlanetSurface s = PlanetSurface.byBody(planet);
        this.surface = s != null ? s : PlanetSurface.MOON;
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    private BlockState[] palette() {
        BlockState[] p = palette;
        if (p == null) {
            PlanetSurface.Palette names = surface.palette();
            p = new BlockState[] {block(names.soil()), block(names.darkSoil()), block(names.rock()), block(names.darkRock())};
            palette = p;
        }
        return p;
    }

    private static BlockState block(String name) {
        Block b = BuiltInRegistries.BLOCK.get(CreateUniversal.id(name));
        if (b == Blocks.AIR) {
            CreateUniversal.LOGGER.error("Falta el bloque {} del suelo: se usa piedra", name);
            b = Blocks.STONE;
        }
        return b.defaultBlockState();
    }

    private BlockState soil(boolean dark) {
        return palette()[dark ? 1 : 0];
    }

    private BlockState rock(boolean dark) {
        return palette()[dark ? 3 : 2];
    }

    /**
     * ¿Es oscura esta columna? El mapa del astro, interpolado, más un ruido de unas decenas de bloques: los bordes entre
     * zonas salen irregulares, con manchas sueltas, en vez de seguir la cuadrícula del mapa.
     */
    private boolean darkAt(PlanetTerrain terrain, int x, int z) {
        double dk = terrain.darknessSmooth(surface.longitude(x + 0.5), surface.latitude(z + 0.5));
        if (dk < 0.2 || dk > 0.8) {
            return dk > 0.5;
        }
        double n = 0.65 * valueNoise(x / 48.0, z / 48.0, 7) + 0.35 * valueNoise(x / 11.0, z / 11.0, 13);
        return dk + (n - 0.5) * 0.45 > 0.5;
    }

    private static double valueNoise(double x, double z, long salt) {
        long xi = (long) Math.floor(x);
        long zi = (long) Math.floor(z);
        double fx = x - xi;
        double fz = z - zi;
        fx = fx * fx * (3.0 - 2.0 * fx);
        fz = fz * fz * (3.0 - 2.0 * fz);
        double a = hashUnit(xi, zi, salt);
        double b = hashUnit(xi + 1, zi, salt);
        double c = hashUnit(xi, zi + 1, salt);
        double d = hashUnit(xi + 1, zi + 1, salt);
        return a + (b - a) * fx + (c - a) * fz + (a - b - c + d) * fx * fz;
    }

    private static double hashUnit(long a, long b, long salt) {
        long h = a * 0x632BE59BD9B4E019L ^ b * 0x85157AF5L ^ salt * 0x1B873593L;
        h ^= h >>> 31;
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 29;
        return (h >>> 11) * 0x1.0p-53;
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState randomState, StructureManager structureManager, ChunkAccess chunk) {
        PlanetTerrain terrain = PlanetTerrain.get(surface);
        int x0 = chunk.getPos().getMinBlockX();
        int z0 = chunk.getPos().getMinBlockZ();
        int minY = chunk.getMinBuildHeight();
        int maxY = chunk.getMaxBuildHeight() - 1;

        // Alturas de 18×18 columnas (con el borde) para saber dónde acaba la corteza visible.
        int[] h = new int[18 * 18];
        int lowest = Integer.MAX_VALUE;
        for (int dz = -1; dz <= 16; dz++) {
            for (int dx = -1; dx <= 16; dx++) {
                int y = (int) Math.floor(terrain.surfaceY(x0 + dx + 0.5, z0 + dz + 0.5));
                y = Math.max(minY + 1, Math.min(maxY - 1, y));
                h[(dz + 1) * 18 + dx + 1] = y;
                lowest = Math.min(lowest, y);
            }
        }
        BlockState deepRock = rock(terrain.darkness(surface.longitude(x0 + 8), surface.latitude(z0 + 8)) > 0.5);
        int crustBottom = Math.max(minY + 1, lowest - CRUST);

        // Secciones enteras por debajo de la corteza: de golpe.
        LevelChunkSection[] sections = chunk.getSections();
        int fullSectionsTop = Math.floorDiv(crustBottom - minY, 16); // secciones [0, fullSectionsTop) quedan enteras
        for (int i = 0; i < Math.min(fullSectionsTop, sections.length); i++) {
            PalettedContainer<BlockState> states = new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, deepRock, PalettedContainer.Strategy.SECTION_STATES);
            sections[i] = new LevelChunkSection(states, sections[i].getBiomes());
        }
        int filledTop = minY + fullSectionsTop * 16; // primer y que no está relleno

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockState bedrock = Blocks.BEDROCK.defaultBlockState();
        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int top = h[(dz + 1) * 18 + dx + 1];
                boolean dark = darkAt(terrain, x0 + dx, z0 + dz);
                BlockState soil = soil(dark);
                BlockState rock = rock(dark);
                for (int y = filledTop; y <= top; y++) {
                    BlockState state = y > top - SOIL_DEPTH ? soil : rock;
                    LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
                    section.setBlockState(dx, y & 15, dz, state, false);
                }
                if (fullSectionsTop == 0) {
                    chunk.getSection(0).setBlockState(dx, minY & 15, dz, bedrock, false);
                }
            }
        }
        if (fullSectionsTop > 0) {
            // Lecho de roca en la primera capa.
            for (int dz = 0; dz < 16; dz++) {
                for (int dx = 0; dx < 16; dx++) {
                    sections[0].setBlockState(dx, 0, dz, bedrock, false);
                }
            }
        }
        Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.OCEAN_FLOOR_WG, Heightmap.Types.WORLD_SURFACE_WG));
        return CompletableFuture.completedFuture(chunk);
    }

    @Override
    public void applyCarvers(WorldGenRegion level, long seed, RandomState random, BiomeManager biomeManager, StructureManager structureManager,
                             ChunkAccess chunk, GenerationStep.Carving step) {
    }

    @Override
    public void buildSurface(WorldGenRegion level, StructureManager structureManager, RandomState random, ChunkAccess chunk) {
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion level) {
    }

    @Override
    public int getGenDepth() {
        return surface.height();
    }

    @Override
    public int getSeaLevel() {
        return surface.minY();
    }

    @Override
    public int getMinY() {
        return surface.minY();
    }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState random) {
        return (int) Math.floor(PlanetTerrain.get(surface).surfaceY(x + 0.5, z + 0.5)) + 1;
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor height, RandomState random) {
        int top = getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, height, random) - 1;
        BlockState[] states = new BlockState[height.getHeight()];
        for (int i = 0; i < states.length; i++) {
            int y = height.getMinBuildHeight() + i;
            states[i] = y > top ? Blocks.AIR.defaultBlockState() : y > top - SOIL_DEPTH ? soil(false) : rock(false);
        }
        return new NoiseColumn(height.getMinBuildHeight(), states);
    }

    @Override
    public void addDebugScreenInfo(List<String> info, RandomState random, BlockPos pos) {
        info.add(String.format(java.util.Locale.ROOT, "Create Universal: %s, lat %.3f°, lon %.3f°", surface.bodyId(),
                Math.toDegrees(surface.latitude(pos.getZ())), Math.toDegrees(surface.longitude(pos.getX()))));
    }
}
