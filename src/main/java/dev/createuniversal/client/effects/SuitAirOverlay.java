package dev.createuniversal.client.effects;

import com.simibubi.create.content.equipment.armor.BacktankUtil;
import dev.createuniversal.registry.CUSuit;
import dev.createuniversal.space.Breathing;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringUtil;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/**
 * Oxígeno que queda en el traje, junto a la barra de objetos (con el mismo aspecto que el de Create bajo el agua), cuando
 * estás donde no hay aire. Sin el traje completo, un aviso en rojo.
 */
public final class SuitAirOverlay {
    private SuitAirOverlay() {}

    public static void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.options.hideGui || !Breathing.withoutAir(player)) {
            return;
        }
        int x = graphics.guiWidth() / 2 + 90;
        int y = graphics.guiHeight() - 44;
        if (!CUSuit.wearsFullSuit(player)) {
            Component warning = Component.translatable("create_universal.hud.no_air").withStyle(ChatFormatting.RED);
            graphics.drawString(mc.font, warning, graphics.guiWidth() / 2 - mc.font.width(warning) / 2, graphics.guiHeight() - 72, -1);
            return;
        }
        ItemStack tank = player.getItemBySlot(EquipmentSlot.CHEST);
        int seconds = BacktankUtil.getAir(tank);
        graphics.renderItem(tank, x, y);
        int color = -1;
        if (seconds < 60 && seconds % 2 == 0) {
            color = 0xFFFF5555;
        }
        String time = StringUtil.formatTickDuration(Math.max(0, seconds) * 20, mc.level.tickRateManager().tickrate());
        graphics.drawString(mc.font, time, x + 17, y + 5, color);
    }
}
