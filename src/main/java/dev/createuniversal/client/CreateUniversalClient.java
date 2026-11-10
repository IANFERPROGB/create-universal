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
        CUPartialModels.init();
        modBus.addListener(CreateUniversalClient::registerDimensionEffects);
        modBus.addListener(CreateUniversalClient::registerShaders);
        NeoForge.EVENT_BUS.register(dev.createuniversal.client.sky.SpaceClock.class);
        NeoForge.EVENT_BUS.register(AtmosphericsCompat.class);
        NeoForge.EVENT_BUS.register(dev.createuniversal.client.effects.TransitionEffects.class);
        modBus.addListener((net.neoforged.neoforge.client.event.RegisterGuiLayersEvent e) ->
                e.registerAboveAll(CreateUniversal.id("transition"), dev.createuniversal.client.effects.TransitionEffects::render));
        modBus.addListener((net.neoforged.neoforge.client.event.RegisterGuiLayersEvent e) ->
                e.registerAbove(net.neoforged.neoforge.client.gui.VanillaGuiLayers.AIR_LEVEL, CreateUniversal.id("suit_air"),
                        dev.createuniversal.client.effects.SuitAirOverlay::render));
        if (net.neoforged.fml.ModList.get().isLoaded("distanthorizons")) {
            try {
                dev.createuniversal.client.compat.DistantHorizonsCompat.register();
            } catch (LinkageError | RuntimeException e) {
                CreateUniversal.LOGGER.warn("No se pudo enganchar Distant Horizons (¿versión distinta?): seguirá activo en el espacio", e);
            }
        }
    }

    private static void registerShaders(net.neoforged.neoforge.client.event.RegisterShadersEvent event) {
        try {
            event.registerShader(new net.minecraft.client.renderer.ShaderInstance(event.getResourceProvider(), CreateUniversal.id("planet"),
                    com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX), dev.createuniversal.client.sky.PlanetRenderer::setShader);
            event.registerShader(new net.minecraft.client.renderer.ShaderInstance(event.getResourceProvider(), CreateUniversal.id("stars"),
                    com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX_COLOR), dev.createuniversal.client.sky.SpaceSky::setStarShader);
        } catch (java.io.IOException e) {
            CreateUniversal.LOGGER.error("No se pudo cargar el shader de los planetas", e);
        }
    }

    private static void registerDimensionEffects(RegisterDimensionSpecialEffectsEvent event) {
        event.register(CreateUniversal.id("space"), new SpaceSkyEffects());
        for (dev.createuniversal.space.surface.PlanetSurface surface : dev.createuniversal.space.surface.PlanetSurface.ALL) {
            event.register(surface.dimension().location(), new dev.createuniversal.client.sky.SurfaceSkyEffects(surface));
        }
    }
}
