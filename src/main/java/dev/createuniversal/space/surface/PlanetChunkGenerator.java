package dev.createuniversal.space.surface;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.createuniversal.registry.CUBlocks;
import net.minecraft.core.BlockPos;
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
 * de la NASA), roca debajo y lecho de roca abajo del todo. Sin cuevas, sin estructuras, sin vida.
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

    public PlanetChunkGenerator(BiomeSource biomeSource, String planet) {
        super(biomeSource);
        PlanetSurface s = PlanetSurface.byBody(planet);
        this.surface = s != null ? s : PlanetSurface.MOON;
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    private boolean moon() {
        return surface == PlanetSurface.MOON;
    }

    private BlockState soil(double dark) {
        if (moon()) {
            return (dark > 0.5 ? CUBlocks.LUNAR_MARE_REGOLITH : CUBlocks.LUNAR_REGOLITH).get().defaultBlockState();
        }
        return (dark > 0.5 ? CUBlocks.MARTIAN_REGOLITH : CUBlocks.MARTIAN_DUST).get().defaultBlockState();
    }

    private BlockState rock(double dark) {
        if (moon()) {
            return (dark > 0.5 ? CUBlocks.LUNAR_BASALT : CUBlocks.LUNAR_ROCK).get().defaultBlockState();
        }
        return (dark > 0.5 ? CUBlocks.MARTIAN_BASALT : CUBlocks.MARTIAN_ROCK).get().defaultBlockState();
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
        double dark = terrain.darkness(surface.longitude(x0 + 8), surface.latitude(z0 + 8));
        BlockState soil = soil(dark);
        BlockState rock = rock(dark);
        int crustBottom = Math.max(minY + 1, lowest - CRUST);

        // Secciones enteras por debajo de la corteza: de golpe.
        LevelChunkSection[] sections = chunk.getSections();
        int fullSectionsTop = Math.floorDiv(crustBottom - minY, 16); // secciones [0, fullSectionsTop) quedan enteras
        for (int i = 0; i < Math.min(fullSectionsTop, sections.length); i++) {
            PalettedContainer<BlockState> states = new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, rock, PalettedContainer.Strategy.SECTION_STATES);
            sections[i] = new LevelChunkSection(states, sections[i].getBiomes());
        }
        int filledTop = minY + fullSectionsTop * 16; // primer y que no está relleno

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockState bedrock = Blocks.BEDROCK.defaultBlockState();
        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int top = h[(dz + 1) * 18 + dx + 1];
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
            states[i] = y > top ? Blocks.AIR.defaultBlockState() : y > top - SOIL_DEPTH ? soil(0) : rock(0);
        }
        return new NoiseColumn(height.getMinBuildHeight(), states);
    }

    @Override
    public void addDebugScreenInfo(List<String> info, RandomState random, BlockPos pos) {
        info.add(String.format(java.util.Locale.ROOT, "Create Universal: %s, lat %.3f°, lon %.3f°", surface.bodyId(),
                Math.toDegrees(surface.latitude(pos.getZ())), Math.toDegrees(surface.longitude(pos.getX()))));
    }
}
