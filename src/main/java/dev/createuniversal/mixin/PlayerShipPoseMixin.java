package dev.createuniversal.mixin;

import dev.createuniversal.space.Gravity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft pone al jugador a gatear (postura de nadar) si su caja no cabe de pie. En una nave que sube o baja muy deprisa,
 * un tick con el techo encima basta para dejarlo "aplastado". Dentro de la gravedad de una nave (Estabilizador de
 * gravedad) se está siempre de pie, o agachado si se pulsa agacharse (alpha.9.7).
 */
@Mixin(Player.class)
public abstract class PlayerShipPoseMixin extends LivingEntity {
    protected PlayerShipPoseMixin(EntityType<? extends LivingEntity> type, Level level) {
        super(type, level);
    }

    @Inject(method = "updatePlayerPose", at = @At("HEAD"), cancellable = true, require = 0)
    private void createUniversal$standOnShip(CallbackInfo ci) {
        Gravity.State state = Gravity.state(this);
        if (state == null || state.ship() == null || isPassenger() || isSleeping() || isFallFlying() || isSwimming()
                || isSpectator()) {
            return;
        }
        setPose(isShiftKeyDown() ? Pose.CROUCHING : Pose.STANDING);
        ci.cancel();
    }
}
