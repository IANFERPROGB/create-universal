package dev.createuniversal.registry;

import com.simibubi.create.api.stress.BlockStressValues;
import dev.createuniversal.CreateUniversal;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Bloques de nave (alpha.9): Estabilizador orbital y Estabilizador de gravedad. */
public final class CUMachines {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(CreateUniversal.MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(CreateUniversal.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, CreateUniversal.MOD_ID);

    public static final DeferredBlock<OrbitalStabilizerBlock> ORBITAL_STABILIZER = BLOCKS.register("orbital_stabilizer",
            () -> new OrbitalStabilizerBlock(BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_YELLOW)
                    .strength(3.0F, 6.0F).sound(SoundType.NETHERITE_BLOCK).requiresCorrectToolForDrops().noOcclusion()));

    static {
        ITEMS.register("orbital_stabilizer", () -> new BlockItem(ORBITAL_STABILIZER.get(), new Item.Properties()));
    }

    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<OrbitalStabilizerBlockEntity>> ORBITAL_STABILIZER_BE =
            BLOCK_ENTITIES.register("orbital_stabilizer",
                    () -> BlockEntityType.Builder.of(OrbitalStabilizerBlockEntity::new, ORBITAL_STABILIZER.get()).build(null));

    public static final DeferredBlock<GravityStabilizerBlock> GRAVITY_STABILIZER = BLOCKS.register("gravity_stabilizer",
            () -> new GravityStabilizerBlock(BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_PURPLE)
                    .strength(3.0F, 6.0F).sound(SoundType.NETHERITE_BLOCK).requiresCorrectToolForDrops().noOcclusion()));

    static {
        ITEMS.register("gravity_stabilizer", () -> new BlockItem(GRAVITY_STABILIZER.get(), new Item.Properties()));
    }

    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<GravityStabilizerBlockEntity>> GRAVITY_STABILIZER_BE =
            BLOCK_ENTITIES.register("gravity_stabilizer",
                    () -> BlockEntityType.Builder.of(GravityStabilizerBlockEntity::new, GRAVITY_STABILIZER.get()).build(null));

    // Navegación (alpha.11): mesa de mapa.
    public static final DeferredBlock<MapTableBlock> MAP_TABLE = BLOCKS.register("map_table",
            () -> new MapTableBlock(BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(2.0F, 4.0F)
                    .sound(SoundType.WOOD).noOcclusion()));

    static {
        ITEMS.register("map_table", () -> new BlockItem(MAP_TABLE.get(), new Item.Properties()));
    }

    private CUMachines() {}

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        modBus.addListener((FMLCommonSetupEvent e) -> e.enqueueWork(() -> {
            // 16 SU por RPM: 2.048 SU a 128 RPM (D42).
            BlockStressValues.IMPACTS.register(ORBITAL_STABILIZER.get(), () -> 16.0);
            // El doble: 4.096 SU a 128 RPM (D51).
            BlockStressValues.IMPACTS.register(GRAVITY_STABILIZER.get(), () -> 32.0);
        }));
    }
}
