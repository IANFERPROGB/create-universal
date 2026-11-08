package dev.createuniversal.client.compat;

import dev.createuniversal.CreateUniversal;
import dev.createuniversal.space.CUDimensions;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Compatibilidad con Atmospherics (D6, sección 4.11).
 * Atmospherics pinta niebla, bruma, color del cielo y su propio sol según el bioma, sin mirar la dimensión: en el espacio
 * sale el fondo azul y un sol encima de los planetas. Todos sus mixins consultan {@code FogConfig.masterEnabled}, así que
 * mientras el jugador está en el espacio se pone a false y al salir se devuelve el valor que tenía. Nunca se guarda en su
 * archivo de configuración. Sin dependencia de compilación: todo por reflexión y solo si el mod está instalado.
 */
public final class AtmosphericsCompat {
    private static final boolean LOADED = ModList.get().isLoaded("atmospherics");
    private static Field masterEnabled;
    private static Method getConfig;
    private static boolean broken;
    /** Valor original mientras estamos en el espacio; null si no lo hemos tocado. */
    private static Boolean savedValue;

    private AtmosphericsCompat() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!LOADED || broken) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        boolean inSpace = mc.level != null && mc.level.dimension() == CUDimensions.SPACE;
        try {
            Object config = config();
            if (config == null) {
                return;
            }
            if (inSpace && savedValue == null) {
                savedValue = masterEnabled.getBoolean(config);
                masterEnabled.setBoolean(config, false);
            } else if (!inSpace && savedValue != null) {
                masterEnabled.setBoolean(config, savedValue);
                savedValue = null;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            broken = true;
            CreateUniversal.LOGGER.warn("Compatibilidad con Atmospherics desactivada: su API ha cambiado ({})", e.toString());
        }
    }

    private static Object config() throws ReflectiveOperationException {
        if (getConfig == null) {
            Class<?> main = Class.forName("com.beash.atmospherics.Atmospherics");
            getConfig = main.getMethod("getConfig");
            masterEnabled = Class.forName("com.beash.atmospherics.config.FogConfig").getField("masterEnabled");
        }
        return getConfig.invoke(null);
    }
}
