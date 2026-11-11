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

/** Bloques de las superficies: el suelo de la Luna y de Marte (alpha.5) y del resto de astros (alpha.6). */
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

    // Mercurio: como la Luna pero más oscuro y gris; basalto de las llanuras volcánicas.
    public static final DeferredBlock<Block> MERCURIAN_REGOLITH = soil("mercurian_regolith", MapColor.COLOR_LIGHT_GRAY);
    public static final DeferredBlock<Block> MERCURIAN_DARK_REGOLITH = soil("mercurian_dark_regolith", MapColor.COLOR_GRAY);
    public static final DeferredBlock<Block> MERCURIAN_ROCK = rock("mercurian_rock", MapColor.STONE);
    public static final DeferredBlock<Block> MERCURIAN_BASALT = rock("mercurian_basalt", MapColor.DEEPSLATE);

    // Venus: polvo pardo-amarillento bajo la luz de las nubes, regolito y roca volcánica.
    public static final DeferredBlock<Block> VENUSIAN_DUST = soil("venusian_dust", MapColor.TERRACOTTA_YELLOW);
    public static final DeferredBlock<Block> VENUSIAN_REGOLITH = soil("venusian_regolith", MapColor.TERRACOTTA_BROWN);
    public static final DeferredBlock<Block> VENUSIAN_ROCK = rock("venusian_rock", MapColor.TERRACOTTA_LIGHT_GRAY);
    public static final DeferredBlock<Block> VENUSIAN_BASALT = rock("venusian_basalt", MapColor.TERRACOTTA_BLACK);

    // Ío: escarcha de azufre, polvo de azufre anaranjado, roca de silicatos y lava negra.
    public static final DeferredBlock<Block> SULFUR_FROST = soil("sulfur_frost", MapColor.COLOR_YELLOW);
    public static final DeferredBlock<Block> IONIAN_SULFUR_DUST = soil("ionian_sulfur_dust", MapColor.COLOR_ORANGE);
    public static final DeferredBlock<Block> IONIAN_ROCK = rock("ionian_rock", MapColor.TERRACOTTA_YELLOW);
    public static final DeferredBlock<Block> IONIAN_BASALT = rock("ionian_basalt", MapColor.COLOR_BLACK);

    // Lunas heladas: escarcha clara, regolito oscuro (polvo y roca sobre el hielo) y el hielo duro de debajo.
    public static final DeferredBlock<Block> ICE_REGOLITH = soil("ice_regolith", MapColor.SNOW);
    public static final DeferredBlock<Block> DARK_ICE_REGOLITH = soil("dark_ice_regolith", MapColor.COLOR_GRAY);
    public static final DeferredBlock<Block> CRYO_ICE = ice("cryo_ice", MapColor.ICE);
    public static final DeferredBlock<Block> DARK_CRYO_ICE = ice("dark_cryo_ice", MapColor.DEEPSLATE);
    // Europa: las sales rojizas de sus grietas.
    public static final DeferredBlock<Block> SALT_ICE_REGOLITH = soil("salt_ice_regolith", MapColor.TERRACOTTA_ORANGE);
    // Titán: polvo y arena de tolinas (lo que cae de su bruma).
    public static final DeferredBlock<Block> THOLIN_DUST = soil("tholin_dust", MapColor.COLOR_ORANGE);
    public static final DeferredBlock<Block> THOLIN_SAND = soil("tholin_sand", MapColor.TERRACOTTA_BROWN);
    // Tritón: escarcha de nitrógeno rosada.
    public static final DeferredBlock<Block> NITROGEN_FROST = soil("nitrogen_frost", MapColor.TERRACOTTA_WHITE);

    // Fobos y Deimos (cuerpos sólidos en el espacio): roca carbonácea muy oscura. No se puede romper ni sale en el
    // inventario creativo; es la "piel" de las dos lunas.
    public static final DeferredBlock<MoonletRockBlock> CARBONACEOUS_REGOLITH = unlisted("carbonaceous_regolith",
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK).strength(-1.0F, 3600000.0F).noLootTable()
                    .sound(SoundType.GRAVEL));

    // Rocas espaciales (alpha.10): la piel de las rocas del cinturón de asteroides y de los anillos de Saturno. Como la de
    // Fobos y Deimos: no se rompe (se pica para sacar fragmentos) y no sale en el inventario creativo.
    public static final DeferredBlock<MoonletRockBlock> ASTEROID_ROCK = unlisted("asteroid_rock",
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BROWN).strength(-1.0F, 3600000.0F).noLootTable()
                    .sound(SoundType.STONE));
    public static final DeferredBlock<MoonletRockBlock> RING_ICE = unlisted("ring_ice",
            BlockBehaviour.Properties.of().mapColor(MapColor.ICE).strength(-1.0F, 3600000.0F).noLootTable()
                    .sound(SoundType.GLASS));

    private CUBlocks() {}

    private static DeferredBlock<Block> soil(String name, MapColor color) {
        return register(name, BlockBehaviour.Properties.of().mapColor(color).strength(0.6F).sound(SoundType.SAND));
    }

    private static DeferredBlock<Block> rock(String name, MapColor color) {
        return register(name, BlockBehaviour.Properties.of().mapColor(color).strength(1.5F, 6.0F).requiresCorrectToolForDrops()
                .sound(SoundType.STONE));
    }

    /** Hielo a −180 °C: duro como la roca, se pica con pico. */
    private static DeferredBlock<Block> ice(String name, MapColor color) {
        return register(name, BlockBehaviour.Properties.of().mapColor(color).strength(1.2F, 4.0F).requiresCorrectToolForDrops()
                .sound(SoundType.GLASS));
    }

    private static DeferredBlock<MoonletRockBlock> unlisted(String name, BlockBehaviour.Properties props) {
        DeferredBlock<MoonletRockBlock> block = BLOCKS.registerBlock(name, MoonletRockBlock::new, props);
        ITEMS.register(name, () -> new BlockItem(block.get(), new Item.Properties()));
        return block;
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
