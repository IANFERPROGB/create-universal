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

    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MapTableBlockEntity>> MAP_TABLE_BE =
            BLOCK_ENTITIES.register("map_table",
                    () -> BlockEntityType.Builder.of(MapTableBlockEntity::new, MAP_TABLE.get()).build(null));

    // Proyector holográfico (alpha.11.2).
    public static final DeferredBlock<HoloProjectorBlock> HOLO_PROJECTOR = BLOCKS.register("holo_projector",
            () -> new HoloProjectorBlock(BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_YELLOW).strength(3.0F, 6.0F)
                    .sound(SoundType.NETHERITE_BLOCK).requiresCorrectToolForDrops().noOcclusion().lightLevel(s -> 7)));

    static {
        ITEMS.register("holo_projector", () -> new BlockItem(HOLO_PROJECTOR.get(), new Item.Properties()));
    }

    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<HoloProjectorBlockEntity>> HOLO_PROJECTOR_BE =
            BLOCK_ENTITIES.register("holo_projector",
                    () -> BlockEntityType.Builder.of(HoloProjectorBlockEntity::new, HOLO_PROJECTOR.get()).build(null));

    // Asistente de nave (alpha.12).
    public static final DeferredBlock<ShipAssistantBlock> SHIP_ASSISTANT = BLOCKS.register("ship_assistant",
            () -> new ShipAssistantBlock(BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_YELLOW).strength(3.0F, 6.0F)
                    .sound(SoundType.NETHERITE_BLOCK).requiresCorrectToolForDrops().noOcclusion().lightLevel(s -> 5)));

    static {
        ITEMS.register("ship_assistant", () -> new BlockItem(SHIP_ASSISTANT.get(), new Item.Properties().rarity(net.minecraft.world.item.Rarity.EPIC)));
        ITEMS.register("assistant_core", () -> new Item(new Item.Properties().rarity(net.minecraft.world.item.Rarity.RARE)));
        ITEMS.register("incomplete_assistant_core",
                () -> new com.simibubi.create.content.processing.sequenced.SequencedAssemblyItem(new Item.Properties()));
    }

    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ShipAssistantBlockEntity>> SHIP_ASSISTANT_BE =
            BLOCK_ENTITIES.register("ship_assistant",
                    () -> BlockEntityType.Builder.of(ShipAssistantBlockEntity::new, SHIP_ASSISTANT.get()).build(null));

    // Motor de crucero (alpha.12.4).
    public static final DeferredBlock<CruiseDriveBlock> CRUISE_DRIVE = BLOCKS.register("cruise_drive",
            () -> new CruiseDriveBlock(BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_BLUE).strength(3.0F, 6.0F)
                    .sound(SoundType.NETHERITE_BLOCK).requiresCorrectToolForDrops().noOcclusion().lightLevel(s -> 4)));

    static {
        ITEMS.register("cruise_drive", () -> new BlockItem(CRUISE_DRIVE.get(), new Item.Properties().rarity(net.minecraft.world.item.Rarity.EPIC)));
    }

    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CruiseDriveBlockEntity>> CRUISE_DRIVE_BE =
            BLOCK_ENTITIES.register("cruise_drive",
                    () -> BlockEntityType.Builder.of(CruiseDriveBlockEntity::new, CRUISE_DRIVE.get()).build(null));

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
            // Giroscopio del asistente: 1.536 SU a 64 RPM (alpha.12).
            BlockStressValues.IMPACTS.register(SHIP_ASSISTANT.get(), () -> 24.0);
            // Motor de crucero: 8.192 SU a 128 RPM (alpha.12.4).
            BlockStressValues.IMPACTS.register(CRUISE_DRIVE.get(), () -> 64.0);
        }));
    }
}
