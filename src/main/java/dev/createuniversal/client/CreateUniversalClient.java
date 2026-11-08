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
        NeoForge.EVENT_BUS.register(AtmosphericsCompat.class);
    }

    private static void registerDimensionEffects(RegisterDimensionSpecialEffectsEvent event) {
        event.register(CreateUniversal.id("space"), new SpaceSkyEffects());
    }
}
