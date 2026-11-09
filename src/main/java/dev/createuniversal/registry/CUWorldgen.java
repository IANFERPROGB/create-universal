package dev.createuniversal.registry;

import com.mojang.serialization.MapCodec;
import dev.createuniversal.CreateUniversal;
import dev.createuniversal.space.surface.PlanetChunkGenerator;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Generador de las superficies ({@code create_universal:planet} en los JSON de dimensión). */
public final class CUWorldgen {
    private static final DeferredRegister<MapCodec<? extends ChunkGenerator>> GENERATORS =
            DeferredRegister.create(Registries.CHUNK_GENERATOR, CreateUniversal.MOD_ID);

    static {
        GENERATORS.register("planet", () -> PlanetChunkGenerator.CODEC);
    }

    private CUWorldgen() {}

    public static void register(IEventBus modBus) {
        GENERATORS.register(modBus);
    }
}
