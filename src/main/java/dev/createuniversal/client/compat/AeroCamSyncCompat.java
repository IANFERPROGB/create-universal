package dev.createuniversal.client.compat;

import com.playsi.aero_cam_sync.api.AcsHandle;
import com.playsi.aero_cam_sync.api.AeroCamSyncApi;
import dev.createuniversal.CreateUniversal;
import dev.createuniversal.space.Gravity;
import net.minecraft.client.Minecraft;

/**
 * AeroCamSync inclina la cámara con la nave (hasta un tope). Dentro de la sala de un Estabilizador de gravedad de una nave
 * la cámara ya la gira Sable con la gravedad de la nave, en cualquier ángulo: si AeroCamSync también la inclinara, se
 * sumarían los dos giros. Mientras tanto se le pide que se aparte (su API, {@code suppress}).
 */
public final class AeroCamSyncCompat {
    private static AcsHandle handle;
    private static boolean suppressing;

    private AeroCamSyncCompat() {}

    /** Cada tick del cliente (solo si AeroCamSync está cargado). */
    public static void tick() {
        var player = Minecraft.getInstance().player;
        Gravity.State state = player != null ? Gravity.state(player) : null;
        boolean wanted = state != null && state.ship() != null;
        try {
            if (handle == null) {
                handle = AeroCamSyncApi.forMod(CreateUniversal.MOD_ID);
            }
            if (wanted) {
                // Se renueva cada tick: si algo falla, se suelta solo.
                handle.suppress(1000L);
                suppressing = true;
            } else if (suppressing) {
                handle.release();
                suppressing = false;
            }
        } catch (LinkageError | RuntimeException e) {
            CreateUniversal.LOGGER.warn("No se pudo apartar AeroCamSync (¿versión distinta?)", e);
            handle = null;
        }
    }
}
