package dev.createuniversal.mixin;

import dev.createuniversal.space.NoDisassemblyInSpace;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Contenedores de la Toolgun de Create Aeronautics (Portable Structure Container y Disposable Vehicle Container):
 * <ul>
 *   <li>D38: en el espacio no se puede empaquetar una nave (sería desmontarla).</li>
 *   <li>D72: en el espacio no se puede sacar una nave guardada (sería teletransportarla desde la Tierra).</li>
 * </ul>
 * Se lanza una excepción desde el principio de cada método: la Toolgun ya la captura y le enseña al jugador su mensaje
 * de fallo con el motivo. La impresora de planos no se toca, porque construye con materiales. {@code @Pseudo} y
 * {@code require = 0}: si la Toolgun no está instalada o cambia, el mixin no se aplica y no rompe nada.
 */
@Pseudo
@Mixin(targets = "com.enxv.aeronauticsstructuretool.PortableStructureContainerItem", remap = false)
public abstract class ToolgunContainerNoSpaceMixin {
    @Inject(method = "captureVehicle", at = @At("HEAD"), require = 0)
    private void create_universal$noPackingInSpace(ServerLevel level, ServerPlayer player, ItemStack stack,
                                                   dev.ryanhcode.sable.sublevel.ServerSubLevel ship,
                                                   java.util.List<dev.ryanhcode.sable.sublevel.ServerSubLevel> connected,
                                                   long blocks, double progress, CallbackInfo ci) throws Exception {
        if (NoDisassemblyInSpace.blocked(level)) {
            Component reason = Component.translatable("create_universal.toolgun.pack_blocked_in_space");
            player.displayClientMessage(reason, true);
            throw new Exception(reason.getString());
        }
    }

    @Inject(method = "placeStoredBlueprint", at = @At("HEAD"), require = 0)
    private void create_universal$noReleaseInSpace(ServerLevel level, UseOnContext context, ItemStack stack, CallbackInfo ci) throws Exception {
        if (NoDisassemblyInSpace.blocked(level)) {
            Component reason = Component.translatable("create_universal.toolgun.release_blocked_in_space");
            if (context.getPlayer() != null) {
                context.getPlayer().displayClientMessage(reason, true);
            }
            throw new Exception(reason.getString());
        }
    }
}
