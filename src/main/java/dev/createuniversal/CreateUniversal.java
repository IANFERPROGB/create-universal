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
        NeoForge.EVENT_BUS.register(SpaceTransitions.class);
        dev.createuniversal.space.ShipAnchor.register();
        LOGGER.info("Create Universal cargando");
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
