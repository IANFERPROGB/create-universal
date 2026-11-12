package dev.createuniversal.client.nav;

import dev.createuniversal.network.HoloConfigPayload;
import dev.createuniversal.registry.HoloProjectorBlockEntity;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** Panel del proyector holográfico (alpha.11.2): tamaño, vista y nombres. Cada cambio se manda al servidor. */
public class HoloConfigScreen extends Screen {
    private final BlockPos pos;
    private int size;
    private int view;
    private boolean names;
    private Button sizeButton;
    private Button viewButton;
    private Button namesButton;

    public HoloConfigScreen(HoloProjectorBlockEntity be) {
        super(Component.translatable("block.create_universal.holo_projector"));
        this.pos = be.getBlockPos();
        this.size = be.size();
        this.view = be.view();
        this.names = be.names();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        int x = width / 2 - 100;
        int y = height / 2 - 40;
        sizeButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            int[] sizes = HoloProjectorBlockEntity.SIZES;
            int i = 0;
            for (int k = 0; k < sizes.length; k++) {
                if (sizes[k] == size) {
                    i = k;
                }
            }
            size = sizes[(i + 1) % sizes.length];
            send();
        }).bounds(x, y, 200, 20).build());
        viewButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            view = (view + 1) % 3;
            send();
        }).bounds(x, y + 24, 200, 20).build());
        namesButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            names = !names;
            send();
        }).bounds(x, y + 48, 200, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(x, y + 80, 200, 20).build());
        refresh();
    }

    private void send() {
        PacketDistributor.sendToServer(new HoloConfigPayload(pos, size, view, names));
        refresh();
    }

    private void refresh() {
        sizeButton.setMessage(Component.translatable("create_universal.holo.size", size));
        viewButton.setMessage(Component.translatable("create_universal.holo.view." + view));
        namesButton.setMessage(Component.translatable(names ? "create_universal.holo.names_on" : "create_universal.holo.names_off"));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, height / 2 - 60, 0xFFFFFFFF);
    }
}
