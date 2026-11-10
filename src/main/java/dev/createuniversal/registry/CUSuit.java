package dev.createuniversal.registry;

import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.content.equipment.armor.BacktankBlock;
import com.simibubi.create.content.equipment.armor.BacktankItem;
import com.simibubi.create.content.equipment.armor.BaseArmorItem;
import com.simibubi.create.content.equipment.armor.DivingBootsItem;
import com.simibubi.create.content.equipment.armor.DivingHelmetItem;
import com.simibubi.create.content.processing.sequenced.SequencedAssemblyItem;
import dev.createuniversal.CreateUniversal;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BlockEntityTypeAddBlocksEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.EnumMap;
import java.util.List;

/**
 * El traje espacial (D20, D21, D28, D67, D68): cuatro piezas de netherita, cada una = pieza de netherita normal + su
 * componente, juntadas con un Deployer de Create.
 *
 * <ul>
 *   <li>Casco: es un casco de buceo de Create (gasta el aire del tanque y avisa como él).</li>
 *   <li>Pechera: es un backtank de Create (lleva el oxígeno, se coloca como bloque y se recarga con rotación igual que
 *       él, usando su misma entidad de bloque).</li>
 *   <li>Botas: botas de buceo de Create. Grebas: armadura de netherita con juntas presurizadas.</li>
 * </ul>
 * Con las cuatro piezas, y como son de netherita, también protegen de la lava igual que el equipo de buceo de netherita.
 */
public final class CUSuit {
    public static final DeferredRegister<ArmorMaterial> MATERIALS = DeferredRegister.create(Registries.ARMOR_MATERIAL, CreateUniversal.MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(CreateUniversal.MOD_ID);
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(CreateUniversal.MOD_ID);

    private static final ResourceLocation TEXTURE = CreateUniversal.id("space_suit");

    /** Como la netherita: misma defensa, dureza y resistencia al empuje. */
    public static final DeferredHolder<ArmorMaterial, ArmorMaterial> MATERIAL = MATERIALS.register("space_suit", () -> {
        EnumMap<ArmorItem.Type, Integer> defense = new EnumMap<>(ArmorItem.Type.class);
        defense.put(ArmorItem.Type.BOOTS, 3);
        defense.put(ArmorItem.Type.LEGGINGS, 6);
        defense.put(ArmorItem.Type.CHESTPLATE, 8);
        defense.put(ArmorItem.Type.HELMET, 3);
        defense.put(ArmorItem.Type.BODY, 11);
        return new ArmorMaterial(defense, 15, SoundEvents.ARMOR_EQUIP_NETHERITE, () -> Ingredient.of(Items.NETHERITE_INGOT),
                List.of(new ArmorMaterial.Layer(TEXTURE)), 3.0F, 0.1F);
    });

    // Componentes.
    public static final DeferredItem<Item> VISOR = ITEMS.registerSimpleItem("space_visor");
    public static final DeferredItem<Item> PRESSURE_SEALS = ITEMS.registerSimpleItem("pressure_seals");
    public static final DeferredItem<SequencedAssemblyItem> INCOMPLETE_HELMET = ITEMS.register("incomplete_space_helmet",
            () -> new SequencedAssemblyItem(new Item.Properties().fireResistant()));

    // Bloque de la pechera colocada (para recargarla, como el backtank).
    public static final DeferredBlock<BacktankBlock> TANK_BLOCK = BLOCKS.register("space_suit_tank",
            () -> new BacktankBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK).strength(3.0F, 1200.0F)
                    .sound(SoundType.NETHERITE_BLOCK).noOcclusion()));

    // Piezas del traje.
    public static final DeferredItem<DivingHelmetItem> HELMET = ITEMS.register("space_helmet",
            () -> new DivingHelmetItem(holder(), props(), TEXTURE));
    public static final DeferredItem<BacktankItem> CHESTPLATE = ITEMS.register("space_chestplate",
            () -> new BacktankItem(holder(), props(), TEXTURE, CUSuit::placeable));
    public static final DeferredItem<BacktankItem.BacktankBlockItem> TANK_PLACEABLE = ITEMS.register("space_suit_tank",
            () -> new BacktankItem.BacktankBlockItem(TANK_BLOCK.get(), CHESTPLATE::get, new Item.Properties()));
    public static final DeferredItem<BaseArmorItem> LEGGINGS = ITEMS.register("space_leggings",
            () -> new BaseArmorItem(holder(), ArmorItem.Type.LEGGINGS, props(), TEXTURE));
    public static final DeferredItem<DivingBootsItem> BOOTS = ITEMS.register("space_boots",
            () -> new DivingBootsItem(holder(), props(), TEXTURE));

    private CUSuit() {}

    private static Holder<ArmorMaterial> holder() {
        return MATERIAL;
    }

    private static Item.Properties props() {
        return new Item.Properties().fireResistant();
    }

    private static BacktankItem.BacktankBlockItem placeable() {
        return TANK_PLACEABLE.get();
    }

    /** ¿Lleva las cuatro piezas del traje? */
    public static boolean wearsFullSuit(net.minecraft.world.entity.LivingEntity entity) {
        return entity.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).is(HELMET.get())
                && entity.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).is(CHESTPLATE.get())
                && entity.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.LEGS).is(LEGGINGS.get())
                && entity.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.FEET).is(BOOTS.get());
    }

    public static void register(IEventBus modBus) {
        MATERIALS.register(modBus);
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        modBus.addListener(CUSuit::addTankToBacktankEntity);
        modBus.addListener(CUSuit::addToTabs);
    }

    /** La pechera colocada usa la misma entidad de bloque que el backtank de Create (recarga, comparador, render). */
    private static void addTankToBacktankEntity(BlockEntityTypeAddBlocksEvent event) {
        event.modify(AllBlockEntityTypes.BACKTANK.get(), TANK_BLOCK.get());
    }

    private static void addToTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.COMBAT) {
            event.accept(HELMET.get());
            event.accept(CHESTPLATE.get());
            event.accept(LEGGINGS.get());
            event.accept(BOOTS.get());
        } else if (event.getTabKey() == CreativeModeTabs.INGREDIENTS) {
            event.accept(VISOR.get());
            event.accept(PRESSURE_SEALS.get());
        }
    }
}
