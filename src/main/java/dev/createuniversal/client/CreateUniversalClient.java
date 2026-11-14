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
        modBus.addListener((net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers e) ->
                e.registerBlockEntityRenderer(dev.createuniversal.registry.CUMachines.ORBITAL_STABILIZER_BE.get(),
                        com.simibubi.create.content.kinetics.base.ShaftRenderer::new));
        modBus.addListener((net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers e) ->
                e.registerBlockEntityRenderer(dev.createuniversal.registry.CUMachines.SHIP_ASSISTANT_BE.get(),
                        com.simibubi.create.content.kinetics.base.ShaftRenderer::new));
        modBus.addListener((net.neoforged.fml.event.lifecycle.FMLClientSetupEvent e) -> e.enqueueWork(() ->
                dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer
                        .builder(dev.createuniversal.registry.CUMachines.SHIP_ASSISTANT_BE.get())
                        .factory(com.simibubi.create.content.kinetics.base.SingleAxisRotatingVisual::shaft)
                        .skipVanillaRender(be -> true)
                        .apply()));
        modBus.addListener((net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers e) ->
                e.registerBlockEntityRenderer(dev.createuniversal.registry.CUMachines.CRUISE_DRIVE_BE.get(),
                        com.simibubi.create.content.kinetics.base.ShaftRenderer::new));
        modBus.addListener((net.neoforged.fml.event.lifecycle.FMLClientSetupEvent e) -> e.enqueueWork(() ->
                dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer
                        .builder(dev.createuniversal.registry.CUMachines.CRUISE_DRIVE_BE.get())
                        .factory(com.simibubi.create.content.kinetics.base.SingleAxisRotatingVisual::shaft)
                        .skipVanillaRender(be -> true)
                        .apply()));
        modBus.addListener((net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent e) ->
                e.register(dev.createuniversal.client.assistant.AssistantHud.TOGGLE));
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientTickEvent.Post e) ->
                dev.createuniversal.client.assistant.AssistantHud.tick());
        modBus.addListener((net.neoforged.neoforge.client.event.RegisterGuiLayersEvent e) ->
                e.registerBelow(net.neoforged.neoforge.client.gui.VanillaGuiLayers.CHAT, CreateUniversal.id("assistant_hud"),
                        dev.createuniversal.client.assistant.AssistantHud::render));
        modBus.addListener((net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers e) ->
                e.registerBlockEntityRenderer(dev.createuniversal.registry.CUMachines.GRAVITY_STABILIZER_BE.get(),
                        GravityStabilizerRenderer::new));
        modBus.addListener((net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers e) ->
                e.registerBlockEntityRenderer(dev.createuniversal.registry.CUMachines.MAP_TABLE_BE.get(),
                        dev.createuniversal.client.nav.MapTableRenderer::new));
        modBus.addListener((net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers e) ->
                e.registerBlockEntityRenderer(dev.createuniversal.registry.CUMachines.HOLO_PROJECTOR_BE.get(),
                        dev.createuniversal.client.nav.HoloProjectorRenderer::new));
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut e) ->
                dev.createuniversal.space.Gravity.clearClient());
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.PlayerTickEvent.Post e) -> {
            if (e.getEntity() == net.minecraft.client.Minecraft.getInstance().player
                    && e.getEntity() instanceof net.minecraft.client.player.LocalPlayer local) {
                ShipCarry.afterTick(local);
                ShipGravityAnchor.afterTick(local);
            }
        });
        if (net.neoforged.fml.ModList.get().isLoaded("aero_cam_sync")) {
            NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientTickEvent.Post e) ->
                    dev.createuniversal.client.compat.AeroCamSyncCompat.tick());
        }
        // Con Flywheel activo (lo normal) Create dibuja los ejes con su "visual", no con el renderizador de arriba.
        modBus.addListener((net.neoforged.fml.event.lifecycle.FMLClientSetupEvent e) -> e.enqueueWork(() ->
                dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer
                        .builder(dev.createuniversal.registry.CUMachines.ORBITAL_STABILIZER_BE.get())
                        .factory(com.simibubi.create.content.kinetics.base.SingleAxisRotatingVisual::shaft)
                        .skipVanillaRender(be -> true)
                        .apply()));
        modBus.addListener((net.neoforged.fml.event.lifecycle.FMLClientSetupEvent e) -> e.enqueueWork(() ->
                dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer
                        .builder(dev.createuniversal.registry.CUMachines.GRAVITY_STABILIZER_BE.get())
                        .factory(com.simibubi.create.content.kinetics.base.OrientedRotatingVisual.of(com.simibubi.create.AllPartialModels.SHAFT_HALF))
                        .skipVanillaRender(be -> true)
                        .apply()));
        NeoForge.EVENT_BUS.register(dev.createuniversal.client.sky.SpaceClock.class);
        NeoForge.EVENT_BUS.register(dev.createuniversal.client.sky.RockRenderer.class);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientTickEvent.Post e) ->
                dev.createuniversal.client.nav.NavClient.tick());
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientTickEvent.Post e) ->
                dev.createuniversal.client.compat.XaeroCompat.tick());
        modBus.addListener((net.neoforged.neoforge.client.event.RegisterGuiLayersEvent e) ->
                e.registerBelow(net.neoforged.neoforge.client.gui.VanillaGuiLayers.CHAT, CreateUniversal.id("nav_destination"),
                        dev.createuniversal.client.nav.NavClient::renderHud));
        NeoForge.EVENT_BUS.register(AtmosphericsCompat.class);
        NeoForge.EVENT_BUS.register(dev.createuniversal.client.effects.TransitionEffects.class);
        modBus.addListener((net.neoforged.neoforge.client.event.RegisterGuiLayersEvent e) ->
                e.registerAboveAll(CreateUniversal.id("transition"), dev.createuniversal.client.effects.TransitionEffects::render));
        modBus.addListener((net.neoforged.neoforge.client.event.RegisterGuiLayersEvent e) ->
                e.registerAbove(net.neoforged.neoforge.client.gui.VanillaGuiLayers.AIR_LEVEL, CreateUniversal.id("suit_air"),
                        dev.createuniversal.client.effects.SuitAirOverlay::render));
        modBus.addListener((net.neoforged.neoforge.client.event.RegisterGuiLayersEvent e) ->
                e.registerBelow(net.neoforged.neoforge.client.gui.VanillaGuiLayers.CHAT, CreateUniversal.id("debris_markers"),
                        dev.createuniversal.client.effects.DebrisMarkers::render));
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
