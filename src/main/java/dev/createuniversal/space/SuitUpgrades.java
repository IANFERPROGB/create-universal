package dev.createuniversal.space;

import dev.createuniversal.registry.CUMinerals;
import dev.createuniversal.registry.CUSuit;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Mejoras del traje (D29, D32, D87): van en la pechera, que es el soporte vital. Se ponen con un Deployer de Create; la
 * pechera conserva su oxígeno, sus encantamientos y las mejoras que ya tuviera. El blindaje de Venus incluye las tres
 * protecciones (frío, radiación y calor) además de la presión y el ácido.
 */
public final class SuitUpgrades {
    public static final String THERMAL = "thermal";
    public static final String RADIATION = "radiation";
    public static final String COOLING = "cooling";
    public static final String VENUS = "venus";
    public static final String TANK = "tank";

    private SuitUpgrades() {}

    public static List<String> of(ItemStack stack) {
        List<String> list = stack.get(CUMinerals.SUIT_UPGRADES.get());
        return list != null ? list : List.of();
    }

    /** Junta las mejoras de dos pechera (la que había y la nueva de la receta), sin repetir. */
    public static List<String> merge(List<String> a, List<String> b) {
        Set<String> all = new LinkedHashSet<>(a);
        all.addAll(b);
        return new ArrayList<>(all);
    }

    /** ¿Le protege el traje de este peligro? Hace falta el traje entero y la mejora en la pechera. */
    public static boolean protects(LivingEntity entity, String upgrade) {
        if (!CUSuit.wearsFullSuit(entity)) {
            return false;
        }
        List<String> installed = of(entity.getItemBySlot(EquipmentSlot.CHEST));
        if (installed.contains(upgrade)) {
            return true;
        }
        return installed.contains(VENUS) && !upgrade.equals(TANK);
    }

    @SubscribeEvent
    public static void tooltip(ItemTooltipEvent event) {
        List<String> installed = of(event.getItemStack());
        if (installed.isEmpty()) {
            return;
        }
        event.getToolTip().add(Component.translatable("create_universal.suit.upgrades").withStyle(ChatFormatting.GRAY));
        for (String id : installed) {
            event.getToolTip().add(Component.literal(" ").append(Component.translatable("create_universal.suit.upgrade." + id))
                    .withStyle(ChatFormatting.GOLD));
        }
    }
}
