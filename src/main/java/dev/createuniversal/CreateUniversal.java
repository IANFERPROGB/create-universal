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
        dev.createuniversal.space.ShipAnchor.register();
        NeoForge.EVENT_BUS.addListener(dev.createuniversal.space.Moonlets::onBreak);
        NeoForge.EVENT_BUS.addListener(dev.createuniversal.space.Moonlets::onPlace);
        NeoForge.EVENT_BUS.addListener(dev.createuniversal.space.Moonlets::onServerStopping);
        LOGGER.info("Create Universal cargando");
    }

    private static void registerPayloads(net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(dev.createuniversal.network.TransitionEffectPayload.TYPE,
                dev.createuniversal.network.TransitionEffectPayload.STREAM_CODEC, dev.createuniversal.network.TransitionEffectPayload::handle);
        event.registrar("1").playToClient(dev.createuniversal.network.SkyShipsPayload.TYPE,
                dev.createuniversal.network.SkyShipsPayload.STREAM_CODEC, dev.createuniversal.network.SkyShipsPayload::handle);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
