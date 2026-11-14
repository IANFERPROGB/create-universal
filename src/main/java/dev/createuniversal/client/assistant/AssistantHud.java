package dev.createuniversal.client.assistant;

import com.mojang.blaze3d.platform.InputConstants;
import dev.createuniversal.client.nav.NavClient;
import dev.createuniversal.network.AssistantHudPayload;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

/**
 * El panel del Asistente de nave arriba de la pantalla (alpha.12, D64): lo ven todos los que van a bordo y se oculta con
 * una tecla (H por defecto). Desaparece solo si deja de llegar (al bajar de la nave).
 */
public final class AssistantHud {
    public static final KeyMapping TOGGLE = new KeyMapping("key.create_universal.assistant_hud", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_H, "key.categories.create_universal");
    private static AssistantHudPayload hud;
    private static long receivedAt;
    private static boolean hidden;

    private AssistantHud() {}

    public static void receive(AssistantHudPayload payload) {
        hud = payload;
        receivedAt = System.currentTimeMillis();
    }

    public static void tick() {
        while (TOGGLE.consumeClick()) {
            hidden = !hidden;
        }
    }

    public static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (hud == null || hidden || mc.options.hideGui || mc.player == null || System.currentTimeMillis() - receivedAt > 3000) {
            return;
        }
        Font font = mc.font;
        int w = 300;
        int x0 = g.guiWidth() / 2 - w / 2;
        int y0 = 16;
        boolean extra = hud.energyCapacity() > 0 || hud.solid() > 0;
        g.fill(x0, y0, x0 + w, y0 + (extra ? 47 : 36), 0x90101820);
        g.fill(x0, y0, x0 + w, y0 + 1, 0xFF58E1FF);

        // Línea 1: estado y destino.
        boolean ready = !hud.status().equals("no_rotation") && !hud.status().equals("not_on_ship") && !hud.status().equals("manual")
                && !hud.status().equals("lost") && !hud.status().equals("not_in_space") && !hud.status().equals("no_engines");
        Component status = Component.translatable("create_universal.assistant.title").append(" · ")
                .append(Component.translatable("create_universal.assistant.status." + hud.status(), 64));
        g.drawString(font, status, x0 + 6, y0 + 4, ready ? 0xFF8FD6FF : 0xFFFFB050);
        if (!hud.target().isEmpty()) {
            Component t = Component.literal("→ ").append(NavClient.targetNameOf(hud.target()));
            if (hud.eta() >= 0) {
                t = t.copy().append(" · " + NavClient.formatTime(hud.eta()));
            }
            g.drawString(font, t, x0 + w - 6 - font.width(t), y0 + 4, 0xFFFFE066);
        }

        // Línea 2: velocidad, motores, aire.
        String speed = String.format(Locale.ROOT, "%.1f b/s", hud.speed());
        g.drawString(font, speed, x0 + 6, y0 + 14, 0xFFFFFFFF);
        Component engines = Component.translatable("create_universal.assistant.engines", hud.working(), hud.total());
        g.drawString(font, engines, x0 + 80, y0 + 14, hud.working() > 0 ? 0xFFDDDDDD : 0xFFFF6060);
        Component air = Component.translatable(hud.air() ? "create_universal.assistant.air_ok" : "create_universal.assistant.air_none");
        g.drawString(font, air, x0 + w - 6 - font.width(air), y0 + 14, hud.air() ? 0xFF7CFF8A : 0xFFFF6060);

        // Línea 3: barras de combustible y casco.
        int fuelPct = hud.fuelCapacity() > 0 ? (int) Math.round(100.0 * hud.fuel() / hud.fuelCapacity()) : -1;
        bar(g, font, x0 + 6, y0 + 25, Component.translatable("create_universal.assistant.fuel"), fuelPct, 0xFFFFB050);
        bar(g, font, x0 + 156, y0 + 25, Component.translatable("create_universal.assistant.hull"), hud.hull(), 0xFF7CFF8A);
        // Línea 4: energía (FE) y combustible sólido, si los hay.
        if (hud.energyCapacity() > 0) {
            bar(g, font, x0 + 6, y0 + 36, Component.translatable("create_universal.assistant.energy"),
                    (int) Math.round(100.0 * hud.energy() / hud.energyCapacity()), 0xFF8FD6FF);
        }
        if (hud.solid() > 0) {
            g.drawString(font, Component.translatable("create_universal.assistant.solid", hud.solid()), x0 + 156, y0 + 36, 0xFFFFB050);
        }
    }

    private static void bar(GuiGraphics g, Font font, int x, int y, Component label, int pct, int color) {
        g.drawString(font, label, x, y, 0xFFB8C8E0);
        int bx = x + font.width(label) + 4;
        int bw = 60;
        g.fill(bx, y + 1, bx + bw, y + 7, 0xFF303844);
        if (pct >= 0) {
            int fill = Math.max(0, Math.min(bw, bw * pct / 100));
            int c = pct < 20 ? 0xFFFF5050 : color;
            g.fill(bx, y + 1, bx + fill, y + 7, c);
            g.drawString(font, pct + "%", bx + bw + 4, y, 0xFFDDDDDD);
        } else {
            g.drawString(font, "—", bx + bw + 4, y, 0xFF888888);
        }
    }
}
