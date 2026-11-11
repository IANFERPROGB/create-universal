package dev.createuniversal.registry;

import dev.createuniversal.CreateUniversal;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.Set;

/**
 * Pestaña propia en el inventario creativo con todos los objetos del mod, como la de Create: el
 * traje y sus mejoras, los minerales y materiales, y los bloques del suelo de cada astro.
 */
public final class CUTab {
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, CreateUniversal.MOD_ID);

    /** Lo que no tiene sentido sacar suelto: la pechera "como bloque" (es la misma pechera) y el casco a medio montar. */
    private static final Set<String> HIDDEN = Set.of("space_suit_tank", "incomplete_space_helmet", "carbonaceous_regolith", "asteroid_rock", "ring_ice");

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.create_universal"))
            .icon(() -> new ItemStack(CUSuit.HELMET.get()))
            .withTabsBefore(CreativeModeTabs.SPAWN_EGGS)
            .displayItems((params, output) -> {
                // Primero los bloques de nave, luego el traje y sus mejoras, luego minerales y materiales, y al final los bloques de los astros.
                add(output, CUMachines.ITEMS);
                add(output, CUSuit.ITEMS);
                add(output, CUMinerals.ITEMS);
                add(output, CUBlocks.ITEMS);
            })
            .build());

    private CUTab() {}

    private static void add(CreativeModeTab.Output output, DeferredRegister<Item> items) {
        for (DeferredHolder<Item, ? extends Item> holder : items.getEntries()) {
            if (!HIDDEN.contains(holder.getId().getPath())) {
                output.accept(holder.get());
            }
        }
    }

    public static void register(IEventBus modBus) {
        TABS.register(modBus);
    }
}
