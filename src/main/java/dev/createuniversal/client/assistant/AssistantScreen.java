package dev.createuniversal.client.assistant;

import dev.createuniversal.network.AssistantInfoPayload;
import dev.createuniversal.network.AssistantMarkPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Locale;

/**
 * Pantalla del Asistente de nave (alpha.12, D61): lo que ha encontrado en la nave (propulsores por tipo y dirección, si
 * funcionan aquí, combustible y masa) y la lista de "Otros" (bloques que parecen motores y no conoce), donde cada tipo se
 * puede marcar como motor.
 */
public class AssistantScreen extends Screen {
    private AssistantInfoPayload info;
    private int scroll;

    private AssistantScreen(AssistantInfoPayload info) {
        super(Component.translatable("block.create_universal.ship_assistant"));
        this.info = info;
    }

    /** Llega la ficha: abre la pantalla o, si ya está abierta para ese asistente, la actualiza. */
    public static void receive(AssistantInfoPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof AssistantScreen s && s.info.pos().equals(payload.pos())) {
            s.info = payload;
            s.rebuildWidgets();
        } else {
            mc.setScreen(new AssistantScreen(payload));
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private int left() {
        return width / 2 - 160;
    }

    private int othersTop() {
        return 46 + Math.max(1, info.engines().size()) * 11 + 30;
    }

    @Override
    protected void init() {
        String dest = dev.createuniversal.client.nav.NavClient.destination();
        if (info.piloting()) {
            addRenderableWidget(Button.builder(Component.translatable("create_universal.assistant.stop"),
                    b -> PacketDistributor.sendToServer(new dev.createuniversal.network.AssistantCommandPayload(info.pos(), false, "", 0, 0, 0)))
                    .bounds(width / 2 - 100, height - 52, 200, 20).build());
        } else {
            Component label = dest != null
                    ? Component.translatable("create_universal.assistant.go", dev.createuniversal.client.nav.NavClient.targetNameOf(dest))
                    : Component.translatable("create_universal.assistant.go_none");
            Button go = Button.builder(label, b -> {
                net.minecraft.world.phys.Vec3 p = dev.createuniversal.client.nav.NavClient.targetPosition(dest);
                if (p != null) {
                    PacketDistributor.sendToServer(new dev.createuniversal.network.AssistantCommandPayload(info.pos(), true, dest, p.x, p.y, p.z));
                }
            }).bounds(width / 2 - 100, height - 52, 200, 20).build();
            go.active = dest != null && info.gyroReady() && !info.engines().isEmpty();
            addRenderableWidget(go);
        }
        int y = othersTop() + 12;
        for (int i = scroll; i < info.others().size() && y < height - 64; i++) {
            AssistantInfoPayload.OtherRow o = info.others().get(i);
            Component label = Component.translatable(o.marked() ? "create_universal.assistant.marked" : "create_universal.assistant.unmarked");
            addRenderableWidget(Button.builder(label, b -> PacketDistributor.sendToServer(new AssistantMarkPayload(info.pos(), o.block())))
                    .bounds(left() + 230, y - 2, 90, 14).build());
            y += 16;
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(width / 2 - 50, height - 28, 100, 20).build());
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        int max = Math.max(0, info.others().size() - 1);
        scroll = Math.max(0, Math.min(max, scroll - (int) Math.signum(dy)));
        rebuildWidgets();
        return true;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int x = left();
        g.drawCenteredString(font, title, width / 2, 10, 0xFFFFFFFF);
        Component gyro = Component.translatable(info.gyroReady() ? "create_universal.assistant.gyro_ready" : "create_universal.assistant.gyro_off", 64);
        g.drawCenteredString(font, gyro, width / 2, 22, info.gyroReady() ? 0xFF7CFF8A : 0xFFFFB050);

        int y = 36;
        g.drawString(font, Component.translatable("create_universal.assistant.engines_title"), x, y, 0xFF8FD6FF);
        y += 10;
        if (info.engines().isEmpty()) {
            g.drawString(font, Component.translatable("create_universal.assistant.no_engines"), x + 8, y, 0xFFFF8080);
            y += 11;
        }
        for (AssistantInfoPayload.EngineRow e : info.engines()) {
            Component line = Component.literal(e.count() + " × ")
                    .append(Component.translatable("create_universal.assistant.kind." + e.kind()))
                    .append(" — ").append(Component.translatable("create_universal.assistant.push", Component.translatable(
                            "create_universal.assistant.dir." + e.push())));
            int color = e.efficiency() >= 100 ? 0xFFDDDDDD : e.efficiency() > 0 ? 0xFFFFD080 : 0xFFFF8080;
            g.drawString(font, line, x + 8, y, color);
            String eff = e.efficiency() + "%" + (e.active() ? " ●" : "");
            g.drawString(font, eff, x + 320 - font.width(eff), y, color);
            y += 11;
        }
        y += 4;
        String fuel = info.fuelCapacity() > 0
                ? String.format(Locale.ROOT, "%,d / %,d mB", info.fuel(), info.fuelCapacity()) : "—";
        g.drawString(font, Component.translatable("create_universal.assistant.fuel_line", fuel,
                String.format(Locale.ROOT, "%.1f", info.mass() / 1000.0)), x, y, 0xFFB8C8E0);

        int oy = othersTop();
        g.drawString(font, Component.translatable("create_universal.assistant.others_title"), x, oy, 0xFF8FD6FF);
        oy += 12;
        if (info.others().isEmpty()) {
            g.drawString(font, Component.translatable("create_universal.assistant.no_others"), x + 8, oy, 0xFF9AA4B4);
        }
        for (int i = scroll; i < info.others().size() && oy < height - 64; i++) {
            AssistantInfoPayload.OtherRow o = info.others().get(i);
            ResourceLocation id = ResourceLocation.tryParse(o.block());
            Component name = id != null && BuiltInRegistries.BLOCK.containsKey(id)
                    ? BuiltInRegistries.BLOCK.get(id).getName() : Component.literal(o.block());
            g.drawString(font, Component.literal(o.count() + " × ").append(name), x + 8, oy + 1, o.marked() ? 0xFF7CFF8A : 0xFFDDDDDD);
            oy += 16;
        }
        g.drawCenteredString(font, Component.translatable("create_universal.assistant.others_help"), width / 2, height - 64, 0xFF7A8496);
    }
}
