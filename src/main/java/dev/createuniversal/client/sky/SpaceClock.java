package dev.createuniversal.client.sky;

import dev.createuniversal.space.CUDimensions;
import dev.createuniversal.space.SolarSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Lado cliente del sistema solar:
 * <ul>
 *   <li>Tiempo suave para dibujar (el dayTime avanza a saltos de un tick; entre medias se interpola).</li>
 *   <li>El jugador que flota junto a un astro se mueve con él ({@link SolarSystem#frameAcceleration}). Se hace aquí
 *       porque es el cliente quien mueve al jugador; las naves lo reciben en el servidor ({@code OrbitalFrame}).</li>
 * </ul>
 */
public final class SpaceClock {
    private static long previous;
    private static long current;

    private SpaceClock() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        previous = current;
        current = mc.level.getDayTime();

        LocalPlayer player = mc.player;
        if (player != null && mc.level.dimension() == CUDimensions.SPACE && !player.isPassenger() && !mc.isPaused()) {
            Vec3 a = SolarSystem.frameAcceleration(player.position(), current);
            if (a.lengthSqr() > 0.0) {
                player.setDeltaMovement(player.getDeltaMovement().add(a.scale(1.0 / 400.0))); // b/s² → b/tick²
            }
        }
    }

    /** Tiempo del sistema solar para este fotograma. */
    public static double renderTime(float partialTick) {
        long step = current - previous;
        if (step >= 0 && step <= 2) {
            return previous + step * (double) partialTick;
        }
        return current;
    }
}
