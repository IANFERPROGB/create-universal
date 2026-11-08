package dev.createuniversal.client.effects;

import dev.createuniversal.CUConfig;
import dev.createuniversal.network.TransitionEffectPayload;
import dev.createuniversal.space.CUDimensions;
import dev.createuniversal.space.SpaceLayout;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * Animaciones de salida al espacio y de reentrada (D2, ideas 2 y 3, sección 4.1).
 *
 * <ul>
 *   <li><b>Subida:</b> en el último 10 % antes de la altura de salida la cámara empieza a temblar.</li>
 *   <li><b>Salida:</b> cuando el servidor cambia de dimensión al jugador ({@link TransitionEffectPayload#EXIT}) se espera a que
 *       termine la pantalla de carga y se hace un destello blanco que se funde desde negro a las estrellas, con un temblor que
 *       se va calmando.</li>
 *   <li><b>Acercamiento a la Tierra desde el espacio:</b> resplandor de plasma naranja en los bordes, partículas de fuego en
 *       la dirección del movimiento y temblor, más fuertes cuanto más cerca de la atmósfera y más rápido se va.</li>
 *   <li><b>Reentrada:</b> ({@link TransitionEffectPayload#REENTRY}) fogonazo naranja que se apaga, con chisporroteo.</li>
 * </ul>
 */
public final class TransitionEffects {
    private static final RandomSource RANDOM = RandomSource.create();
    /** Distancia sobre la atmósfera a la que empieza el plasma. */
    private static final double REENTRY_GLOW_RANGE = 400.0;

    private static int pendingKind = -1;
    private static int pendingTicks;
    private static float flash;
    private static float black;
    private static float orange;
    private static float shake;
    private static float reentryGlow;
    private static Vec3 lastCamera;
    private static double lastEarthDistance = Double.NaN;

    private TransitionEffects() {}

    /** El servidor acaba de cambiar de dimensión al jugador. La animación arranca cuando el nuevo mundo ya se ve. */
    public static void onTransition(int kind) {
        pendingKind = kind;
        pendingTicks = 0;
        if (kind == TransitionEffectPayload.EXIT) {
            black = 1.0F; // tapa el salto mientras carga
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        startPendingWhenVisible(mc);

        flash = Math.max(0.0F, flash - 0.08F);
        if (flash < 0.5F) {
            black = Math.max(0.0F, black - 0.025F);
        }
        orange = Math.max(0.0F, orange - 0.025F);
        shake *= 0.94F;

        updateReentryGlow(mc);
    }

    private static void startPendingWhenVisible(Minecraft mc) {
        if (pendingKind < 0) {
            return;
        }
        if (++pendingTicks > 20 * 30) {
            pendingKind = -1;
            black = 0.0F;
            return;
        }
        boolean exit = pendingKind == TransitionEffectPayload.EXIT;
        boolean rightWorld = mc.level.dimension() == (exit ? CUDimensions.SPACE : Level.OVERWORLD);
        if (!rightWorld || mc.screen != null) {
            return;
        }
        if (exit) {
            flash = 1.0F;
            black = 1.0F;
            shake = 1.0F;
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BEACON_ACTIVATE, 0.55F, 1.0F));
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.FIREWORK_ROCKET_LARGE_BLAST_FAR, 0.6F, 0.8F));
        } else {
            orange = 1.0F;
            shake = 1.0F;
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.FIRECHARGE_USE, 0.6F, 1.0F));
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.GENERIC_EXTINGUISH_FIRE, 0.5F, 0.8F));
        }
        pendingKind = -1;
    }

    private static void updateReentryGlow(Minecraft mc) {
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        if (mc.level.dimension() != CUDimensions.SPACE) {
            reentryGlow = Math.max(0.0F, reentryGlow - 0.05F);
            lastCamera = camera;
            lastEarthDistance = Double.NaN;
            return;
        }
        Vec3 earth = SpaceLayout.earthCenter(mc.level.getDayTime());
        double distance = camera.distanceTo(earth);
        double aboveAtmosphere = distance - (SpaceLayout.EARTH_RADIUS + SpaceLayout.EARTH_ATMOSPHERE);
        // Velocidad de acercamiento (bloques/s). La Tierra se mueve y quien va con ella no se acerca, aunque se mueva.
        double closingSpeed = Double.isNaN(lastEarthDistance) ? 0.0 : (lastEarthDistance - distance) * 20.0;
        lastEarthDistance = distance;

        float target = 0.0F;
        if (aboveAtmosphere < REENTRY_GLOW_RANGE && closingSpeed > 3.0) {
            float near = (float) Mth.clamp(1.0 - aboveAtmosphere / REENTRY_GLOW_RANGE, 0.0, 1.0);
            float fast = (float) Mth.clamp(closingSpeed / 60.0, 0.2, 1.0);
            target = near * fast;
        }
        reentryGlow += (target - reentryGlow) * 0.15F;

        if (reentryGlow > 0.05F && lastCamera != null) {
            Vec3 motion = camera.subtract(lastCamera).subtract(SpaceLayout.earthVelocity(mc.level.getDayTime()).scale(1.0 / 20.0));
            Vec3 dir = motion.lengthSqr() > 1.0E-4 ? motion.normalize() : earth.subtract(camera).normalize();
            int count = Math.round(reentryGlow * 8.0F);
            for (int i = 0; i < count; i++) {
                Vec3 p = camera.add(dir.scale(2.5 + RANDOM.nextDouble() * 2.0))
                        .add((RANDOM.nextDouble() - 0.5) * 3.0, (RANDOM.nextDouble() - 0.5) * 3.0, (RANDOM.nextDouble() - 0.5) * 3.0);
                Vec3 v = dir.scale(-0.35 - RANDOM.nextDouble() * 0.3);
                mc.level.addParticle(RANDOM.nextFloat() < 0.7F ? ParticleTypes.FLAME : ParticleTypes.LAVA, p.x, p.y, p.z, v.x, v.y, v.z);
            }
        }
        lastCamera = camera;
    }

    /** Temblor de cámara: subida final, salida, acercamiento y reentrada. */
    @SubscribeEvent
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        float amplitude = Math.max(Math.max(shake, exitApproach() * 0.8F), reentryGlow);
        if (amplitude < 0.01F) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        float t = (mc.level != null ? mc.level.getGameTime() : 0) + (float) event.getPartialTick();
        float a = amplitude * 1.6F;
        event.setYaw(event.getYaw() + a * 0.5F * (Mth.sin(t * 1.7F) + 0.5F * Mth.sin(t * 3.1F)));
        event.setPitch(event.getPitch() + a * 0.5F * Mth.sin(t * 2.3F + 1.0F));
        event.setRoll(event.getRoll() + a * 0.7F * Mth.sin(t * 1.3F + 2.0F));
    }

    /** 0 lejos de la salida, 1 justo al llegar: el último 10 % de la subida. */
    private static float exitApproach() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.level.dimension() != Level.OVERWORLD) {
            return 0.0F;
        }
        double exit = CUConfig.EXIT_ALTITUDE.get();
        double y = mc.gameRenderer.getMainCamera().getPosition().y;
        return (float) Mth.clamp((y - exit * 0.9) / (exit * 0.1), 0.0, 1.0);
    }

    /** Capa por encima de todo el HUD. */
    public static void render(GuiGraphics g, DeltaTracker delta) {
        int w = g.guiWidth();
        int h = g.guiHeight();
        if (black > 0.0F) {
            g.fill(0, 0, w, h, argb(black, 0, 0, 0));
        }
        if (reentryGlow > 0.02F) {
            vignette(g, w, h, reentryGlow, 255, 120, 30);
        }
        if (orange > 0.0F) {
            g.fill(0, 0, w, h, argb(orange * 0.65F, 255, 140, 40));
        }
        if (flash > 0.0F) {
            g.fill(0, 0, w, h, argb(flash, 235, 245, 255));
        }
    }

    /** Resplandor en los bordes de la pantalla, más fuerte cuanto más cerca del borde. */
    private static void vignette(GuiGraphics g, int w, int h, float strength, int r, int gr, int b) {
        int depth = Math.max(8, (int) (Math.min(w, h) * 0.28F * strength));
        int edge = argb(Math.min(0.85F, strength), r, gr, b);
        int none = argb(0.0F, r, gr, b);
        g.fillGradient(0, 0, w, depth, edge, none);
        g.fillGradient(0, h - depth, w, h, none, edge);
        int steps = 12;
        for (int i = 0; i < steps; i++) {
            float k = 1.0F - (float) i / steps;
            int x0 = depth * i / steps;
            int x1 = depth * (i + 1) / steps;
            int c = argb(Math.min(0.85F, strength) * k, r, gr, b);
            g.fill(x0, 0, x1, h, c);
            g.fill(w - x1, 0, w - x0, h, c);
        }
    }

    private static int argb(float alpha, int r, int g, int b) {
        int a = Mth.clamp((int) (alpha * 255.0F), 0, 255);
        return a << 24 | r << 16 | g << 8 | b;
    }
}
