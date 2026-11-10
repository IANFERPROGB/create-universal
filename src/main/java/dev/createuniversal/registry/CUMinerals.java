package dev.createuniversal.registry;

import com.mojang.serialization.Codec;
import dev.createuniversal.CreateUniversal;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DropExperienceBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.ArrayList;
import java.util.List;

/**
 * Minerales nuevos de la Tierra y las mejoras del traje (D29, D30, D87, alpha.7.1).
 *
 * <ul>
 *   <li>Cromita (cromo), colemanita (boro) y berilo (berilio), en piedra y en pizarra profunda.</li>
 *   <li>Materiales: lingotes de cromo, boro y berilio; aerogel (Create, mezcladora con calor: arena y agua);
 *       superaleación de cromo (mezcladora con calor intenso).</li>
 *   <li>Mejoras, que se ponen en la pechera del traje con un Deployer: aislamiento térmico (frío), escudo de radiación,
 *       refrigeración (calor), blindaje de Venus (todo lo anterior más presión y ácido) y tanque ampliado (30 min).</li>
 * </ul>
 */
public final class CUMinerals {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(CreateUniversal.MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(CreateUniversal.MOD_ID);
    public static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, CreateUniversal.MOD_ID);

    /** Mejoras puestas en la pechera del traje (identificadores: thermal, radiation, cooling, venus, tank). */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<List<String>>> SUIT_UPGRADES = COMPONENTS.registerComponentType(
            "suit_upgrades", b -> b.persistent(Codec.STRING.listOf()).networkSynchronized(ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list())));

    private static final List<DeferredItem<? extends Item>> NATURAL = new ArrayList<>();
    private static final List<DeferredItem<? extends Item>> INGREDIENTS = new ArrayList<>();

    public static final DeferredBlock<Block> CHROMITE_ORE = ore("chromite_ore", false, MapColor.STONE);
    public static final DeferredBlock<Block> DEEPSLATE_CHROMITE_ORE = ore("deepslate_chromite_ore", true, MapColor.DEEPSLATE);
    public static final DeferredBlock<Block> COLEMANITE_ORE = ore("colemanite_ore", false, MapColor.STONE);
    public static final DeferredBlock<Block> DEEPSLATE_COLEMANITE_ORE = ore("deepslate_colemanite_ore", true, MapColor.DEEPSLATE);
    public static final DeferredBlock<Block> BERYL_ORE = ore("beryl_ore", false, MapColor.STONE);
    public static final DeferredBlock<Block> DEEPSLATE_BERYL_ORE = ore("deepslate_beryl_ore", true, MapColor.DEEPSLATE);

    public static final DeferredItem<Item> RAW_CHROMITE = ingredient("raw_chromite");
    public static final DeferredItem<Item> COLEMANITE = ingredient("colemanite");
    public static final DeferredItem<Item> BERYL = ingredient("beryl");
    public static final DeferredItem<Item> CHROMIUM_INGOT = ingredient("chromium_ingot");
    public static final DeferredItem<Item> BORON_INGOT = ingredient("boron_ingot");
    public static final DeferredItem<Item> BERYLLIUM_INGOT = ingredient("beryllium_ingot");
    public static final DeferredItem<Item> AEROGEL = ingredient("aerogel");
    public static final DeferredItem<Item> CHROMIUM_SUPERALLOY = ingredient("chromium_superalloy");

    public static final DeferredItem<Item> THERMAL_INSULATION = ingredient("thermal_insulation");
    public static final DeferredItem<Item> RADIATION_SHIELD = ingredient("radiation_shield");
    public static final DeferredItem<Item> COOLING_SYSTEM = ingredient("cooling_system");
    public static final DeferredItem<Item> VENUS_PLATING = ingredient("venus_plating");
    public static final DeferredItem<Item> TANK_EXPANSION = ingredient("tank_expansion");

    private CUMinerals() {}

    private static DeferredBlock<Block> ore(String name, boolean deep, MapColor color) {
        BlockBehaviour.Properties props = BlockBehaviour.Properties.of().mapColor(color).instrument(NoteBlockInstrument.BASEDRUM)
                .requiresCorrectToolForDrops().strength(deep ? 4.5F : 3.0F, 3.0F).sound(deep ? SoundType.DEEPSLATE : SoundType.STONE);
        DeferredBlock<Block> block = BLOCKS.register(name, () -> new DropExperienceBlock(UniformInt.of(0, 2), props));
        NATURAL.add(ITEMS.register(name, () -> new BlockItem(block.get(), new Item.Properties())));
        return block;
    }

    private static DeferredItem<Item> ingredient(String name) {
        DeferredItem<Item> item = ITEMS.registerSimpleItem(name);
        INGREDIENTS.add(item);
        return item;
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        COMPONENTS.register(modBus);
        modBus.addListener(CUMinerals::addToTabs);
    }

    private static void addToTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.NATURAL_BLOCKS) {
            NATURAL.forEach(i -> event.accept(i.get()));
        } else if (event.getTabKey() == CreativeModeTabs.INGREDIENTS) {
            INGREDIENTS.forEach(i -> event.accept(i.get()));
        }
    }
}
