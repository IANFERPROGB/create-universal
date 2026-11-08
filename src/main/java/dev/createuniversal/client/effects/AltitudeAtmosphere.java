package dev.createuniversal.client.effects;

import dev.createuniversal.CUConfig;
import dev.createuniversal.space.CUDimensions;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * La atmósfera de la Tierra se va acabando al subir (idea 1, sección 4.1): desde la mitad de la altura de salida hasta la
 * salida, el cielo y la niebla pasan a negro, salen las estrellas y la niebla se aleja para ver la Tierra desde arriba.
 * El color del cielo y el brillo de las estrellas se cambian en {@code ClientLevelSkyMixin}; aquí la niebla.
 */
public final class AltitudeAtmosphere {
    /** Fracción de la altura de salida a la que empieza a apagarse la atmósfera. */
    private static final double FADE_START = 0.5;

    private AltitudeAtmosphere() {}

    /** 0 = cielo normal, 1 = cielo del espacio. Solo en el Overworld; en el espacio el cielo lo dibuja SpaceSkyEffects. */
    public static float fade() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.level.dimension() != Level.OVERWORLD || mc.gameRenderer == null) {
            return 0.0F;
        }
        double exit = CUConfig.EXIT_ALTITUDE.get();
        double y = mc.gameRenderer.getMainCamera().getPosition().y;
        double start = exit * FADE_START;
        return (float) Mth.clamp((y - start) / (exit - start), 0.0, 1.0);
    }

    @SubscribeEvent
    public static void onFogColor(ViewportEvent.ComputeFogColor event) {
        float f = fade();
        if (f <= 0.0F) {
            return;
        }
        float keep = 1.0F - f;
        event.setRed(event.getRed() * keep);
        event.setGreen(event.getGreen() * keep);
        event.setBlue(event.getBlue() * keep);
    }

    @SubscribeEvent
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        float f = fade();
        if (f <= 0.0F) {
            return;
        }
        float scale = 1.0F + f * 4.0F;
        event.setNearPlaneDistance(event.getNearPlaneDistance() * scale);
        event.setFarPlaneDistance(event.getFarPlaneDistance() * scale);
        event.setCanceled(true);
    }

    /** Para AtmosphericsCompat: en el espacio, o ya muy arriba, su niebla y su cielo se apagan. */
    public static boolean atmosphericsShouldBeOff() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && (mc.level.dimension() == CUDimensions.SPACE || fade() > 0.6F);
    }
}
