package dev.createuniversal;

import com.mojang.logging.LogUtils;
import dev.createuniversal.space.SpaceTransitions;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(CreateUniversal.MOD_ID)
public final class CreateUniversal {
    public static final String MOD_ID = "create_universal";
    public static final Logger LOGGER = LogUtils.getLogger();

    public CreateUniversal(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, CUConfig.SPEC);
        modBus.addListener(CreateUniversal::registerPayloads);
        dev.createuniversal.registry.CUBlocks.register(modBus);
        dev.createuniversal.registry.CUSuit.register(modBus);
        dev.createuniversal.registry.CUMinerals.register(modBus);
        dev.createuniversal.registry.CUMachines.register(modBus);
        dev.createuniversal.registry.CUTab.register(modBus);
        dev.createuniversal.registry.CUWorldgen.register(modBus);
        NeoForge.EVENT_BUS.register(SpaceTransitions.class);
        NeoForge.EVENT_BUS.register(dev.createuniversal.space.Breathing.class);
        NeoForge.EVENT_BUS.register(dev.createuniversal.space.SuitJetpack.class);
        NeoForge.EVENT_BUS.register(dev.createuniversal.space.Hazards.class);
        NeoForge.EVENT_BUS.register(dev.createuniversal.space.OxygenZones.class);
        NeoForge.EVENT_BUS.register(dev.createuniversal.space.SuitUpgrades.class);
        NeoForge.EVENT_BUS.register(dev.createuniversal.space.SpaceCommands.class);
        NeoForge.EVENT_BUS.register(dev.createuniversal.space.SpaceGate.class);
        NeoForge.EVENT_BUS.register(dev.createuniversal.space.ShipDestruction.class);
        NeoForge.EVENT_BUS.register(dev.createuniversal.space.DebrisSites.class);
        NeoForge.EVENT_BUS.register(dev.createuniversal.space.GravityFields.class);
        NeoForge.EVENT_BUS.register(dev.createuniversal.space.LoginHold.class);
        NeoForge.EVENT_BUS.register(dev.createuniversal.space.RockPicking.class);
        NeoForge.EVENT_BUS.register(dev.createuniversal.space.Navigation.class);
        NeoForge.EVENT_BUS.register(dev.createuniversal.space.ParkedShips.class);
        dev.createuniversal.space.ShipAnchor.register();
        NeoForge.EVENT_BUS.addListener(dev.createuniversal.space.Moonlets::onBreak);
        NeoForge.EVENT_BUS.addListener(dev.createuniversal.space.Moonlets::onPlace);
        NeoForge.EVENT_BUS.addListener(dev.createuniversal.space.Moonlets::onServerStopping);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppingEvent e) ->
                dev.createuniversal.space.ShipVelocityMemory.clear());
        LOGGER.info("Create Universal cargando");
    }

    private static void registerPayloads(net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(dev.createuniversal.network.TransitionEffectPayload.TYPE,
                dev.createuniversal.network.TransitionEffectPayload.STREAM_CODEC, dev.createuniversal.network.TransitionEffectPayload::handle);
        event.registrar("1").playToClient(dev.createuniversal.network.SkyShipsPayload.TYPE,
                dev.createuniversal.network.SkyShipsPayload.STREAM_CODEC, dev.createuniversal.network.SkyShipsPayload::handle);
        event.registrar("1").playToClient(dev.createuniversal.network.DebrisMarkersPayload.TYPE,
                dev.createuniversal.network.DebrisMarkersPayload.STREAM_CODEC, dev.createuniversal.network.DebrisMarkersPayload::handle);
        event.registrar("1").playToClient(dev.createuniversal.network.ParkedShipsPayload.TYPE,
                dev.createuniversal.network.ParkedShipsPayload.STREAM_CODEC, dev.createuniversal.network.ParkedShipsPayload::handle);
        event.registrar("1").playToClient(dev.createuniversal.network.NavSyncPayload.TYPE,
                dev.createuniversal.network.NavSyncPayload.STREAM_CODEC, dev.createuniversal.network.NavSyncPayload::handle);
        event.registrar("1").playToServer(dev.createuniversal.network.HoloConfigPayload.TYPE,
                dev.createuniversal.network.HoloConfigPayload.STREAM_CODEC, dev.createuniversal.network.HoloConfigPayload::handle);
        event.registrar("1").playToServer(dev.createuniversal.network.NavEditPayload.TYPE,
                dev.createuniversal.network.NavEditPayload.STREAM_CODEC, dev.createuniversal.network.NavEditPayload::handle);
        event.registrar("1").playToClient(dev.createuniversal.network.ShipCarryPayload.TYPE,
                dev.createuniversal.network.ShipCarryPayload.STREAM_CODEC, dev.createuniversal.network.ShipCarryPayload::handle);
        event.registrar("1").playToClient(dev.createuniversal.network.GravityStatePayload.TYPE,
                dev.createuniversal.network.GravityStatePayload.STREAM_CODEC, dev.createuniversal.network.GravityStatePayload::handle);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
