package dev.createuniversal.registry;

import dev.createuniversal.CreateUniversal;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.ArrayList;
import java.util.List;

/** Bloques de las superficies (alpha.5): el suelo de la Luna y de Marte. */
public final class CUBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(CreateUniversal.MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(CreateUniversal.MOD_ID);
    private static final List<DeferredBlock<Block>> ALL = new ArrayList<>();

    // Luna: regolito claro de las tierras altas, regolito oscuro de los mares, roca (anortosita) y basalto bajo los mares.
    public static final DeferredBlock<Block> LUNAR_REGOLITH = soil("lunar_regolith", MapColor.COLOR_LIGHT_GRAY);
    public static final DeferredBlock<Block> LUNAR_MARE_REGOLITH = soil("lunar_mare_regolith", MapColor.COLOR_GRAY);
    public static final DeferredBlock<Block> LUNAR_ROCK = rock("lunar_rock", MapColor.STONE);
    public static final DeferredBlock<Block> LUNAR_BASALT = rock("lunar_basalt", MapColor.DEEPSLATE);

    // Marte: polvo rojizo, regolito oscuro, roca y basalto.
    public static final DeferredBlock<Block> MARTIAN_DUST = soil("martian_dust", MapColor.COLOR_ORANGE);
    public static final DeferredBlock<Block> MARTIAN_REGOLITH = soil("martian_regolith", MapColor.TERRACOTTA_BROWN);
    public static final DeferredBlock<Block> MARTIAN_ROCK = rock("martian_rock", MapColor.TERRACOTTA_RED);
    public static final DeferredBlock<Block> MARTIAN_BASALT = rock("martian_basalt", MapColor.TERRACOTTA_BLACK);

    private CUBlocks() {}

    private static DeferredBlock<Block> soil(String name, MapColor color) {
        return register(name, BlockBehaviour.Properties.of().mapColor(color).strength(0.6F).sound(SoundType.SAND));
    }

    private static DeferredBlock<Block> rock(String name, MapColor color) {
        return register(name, BlockBehaviour.Properties.of().mapColor(color).strength(1.5F, 6.0F).requiresCorrectToolForDrops()
                .sound(SoundType.STONE));
    }

    private static DeferredBlock<Block> register(String name, BlockBehaviour.Properties props) {
        DeferredBlock<Block> block = BLOCKS.registerSimpleBlock(name, props);
        ITEMS.register(name, () -> new BlockItem(block.get(), new Item.Properties()));
        ALL.add(block);
        return block;
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        modBus.addListener(CUBlocks::addToTabs);
    }

    private static void addToTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.NATURAL_BLOCKS) {
            for (DeferredBlock<Block> b : ALL) {
                event.accept(b.get());
            }
        }
    }
}
