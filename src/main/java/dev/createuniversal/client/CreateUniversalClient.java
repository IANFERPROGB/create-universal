package dev.createuniversal.client;

import dev.createuniversal.CreateUniversal;
import dev.createuniversal.client.sky.SpaceSkyEffects;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterDimensionSpecialEffectsEvent;

@Mod(value = CreateUniversal.MOD_ID, dist = Dist.CLIENT)
public final class CreateUniversalClient {
    public CreateUniversalClient(IEventBus modBus) {
        modBus.addListener(CreateUniversalClient::registerDimensionEffects);
    }

    private static void registerDimensionEffects(RegisterDimensionSpecialEffectsEvent event) {
        event.register(CreateUniversal.id("space"), new SpaceSkyEffects());
    }
}
