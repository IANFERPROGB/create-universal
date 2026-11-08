package dev.createuniversal.client;

import dev.createuniversal.CreateUniversal;
import dev.createuniversal.client.compat.AtmosphericsCompat;
import dev.createuniversal.client.sky.SpaceSkyEffects;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterDimensionSpecialEffectsEvent;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = CreateUniversal.MOD_ID, dist = Dist.CLIENT)
public final class CreateUniversalClient {
    public CreateUniversalClient(IEventBus modBus) {
        modBus.addListener(CreateUniversalClient::registerDimensionEffects);
        modBus.addListener(CreateUniversalClient::registerShaders);
        NeoForge.EVENT_BUS.register(dev.createuniversal.client.sky.SpaceClock.class);
        NeoForge.EVENT_BUS.register(AtmosphericsCompat.class);
        NeoForge.EVENT_BUS.register(dev.createuniversal.client.effects.TransitionEffects.class);
        modBus.addListener((net.neoforged.neoforge.client.event.RegisterGuiLayersEvent e) ->
                e.registerAboveAll(CreateUniversal.id("transition"), dev.createuniversal.client.effects.TransitionEffects::render));
    }

    private static void registerShaders(net.neoforged.neoforge.client.event.RegisterShadersEvent event) {
        try {
            event.registerShader(new net.minecraft.client.renderer.ShaderInstance(event.getResourceProvider(), CreateUniversal.id("planet"),
                    com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX), dev.createuniversal.client.sky.PlanetRenderer::setShader);
        } catch (java.io.IOException e) {
            CreateUniversal.LOGGER.error("No se pudo cargar el shader de los planetas", e);
        }
    }

    private static void registerDimensionEffects(RegisterDimensionSpecialEffectsEvent event) {
        event.register(CreateUniversal.id("space"), new SpaceSkyEffects());
    }
}
