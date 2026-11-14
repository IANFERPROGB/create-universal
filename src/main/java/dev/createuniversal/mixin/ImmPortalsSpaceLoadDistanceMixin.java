package dev.createuniversal.mixin;

import dev.createuniversal.space.CUDimensions;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Immersive Portals (en el pack, dentro de ip_aeronautics) carga los chunks alrededor de cada jugador con la distancia de
 * visión del servidor, sin mirar la de cada dimensión. En el espacio los chunks están vacíos: se le da un radio corto,
 * como a la dimensión (SpaceTransitions), para que ir muy deprisa no obligue a cargar cientos de chunks por segundo.
 * Si Immersive Portals no está, este mixin no hace nada.
 */
@Pseudo
@Mixin(targets = "qouteall.imm_ptl.core.chunk_loading.ChunkVisibility", remap = false)
public abstract class ImmPortalsSpaceLoadDistanceMixin {
    private static final int CREATE_UNIVERSAL$SPACE_RADIUS = 4;

    @Redirect(method = "playerDirectLoader", require = 0,
            at = @At(value = "INVOKE", target = "Lqouteall/imm_ptl/core/McHelper;getPlayerLoadDistance(Lnet/minecraft/server/level/ServerPlayer;)I"))
    private static int createUniversal$shortRadiusInSpace(ServerPlayer player) {
        int normal = Math.max(2, Math.min(player.requestedViewDistance(), player.server.getPlayerList().getViewDistance()));
        if (player.level().dimension() != CUDimensions.SPACE) {
            return normal;
        }
        // A más de 99 b/s lejos de los astros, solo el chunk en el que está (alpha.12.3, FastTravelChunks).
        return dev.createuniversal.space.FastTravelChunks.skipSpaceChunks(player) ? 0 : Math.min(CREATE_UNIVERSAL$SPACE_RADIUS, normal);
    }
}
