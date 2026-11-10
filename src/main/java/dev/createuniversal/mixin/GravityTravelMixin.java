package dev.createuniversal.mixin;

import dev.createuniversal.space.Gravity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gravedad del Estabilizador de gravedad en una nave, en cualquier dirección. Minecraft aplica la gravedad y el rozamiento
 * pensando que "abajo" es −Y: el rozamiento del suelo a X y Z y el del aire a Y. Con otro "abajo" eso hace resbalar al
 * andar por una pared y frena la caída. Así que, con gravedad propia, la gravedad normal es 0 ({@code EntitySpaceGravityMixin})
 * y al terminar {@code travel} se rehace el rozamiento repartido según el "arriba" de la entidad y se añade la gravedad
 * hacia su "abajo".
 */
@Mixin(LivingEntity.class)
public abstract class GravityTravelMixin extends Entity {
    @Unique
    private float createUniversal$friction = -1.0F;
    @Unique
    private Vector3d createUniversal$up;

    protected GravityTravelMixin(EntityType<?> type, Level level) {
        super(type, level);
    }

    @Inject(method = "travel", at = @At("HEAD"))
    private void createUniversal$before(Vec3 input, CallbackInfo ci) {
        createUniversal$up = Gravity.up(this);
        createUniversal$friction = -1.0F;
        if (createUniversal$up == null) {
            return;
        }
        LivingEntity self = (LivingEntity) (Object) this;
        boolean normal = !isInWater() && !isInLava() && !self.isFallFlying()
                && !(self instanceof Player p && p.getAbilities().flying);
        if (normal) {
            BlockPos below = getBlockPosBelowThatAffectsMyMovement();
            float block = level().getBlockState(below).getFriction(level(), below, this);
            createUniversal$friction = onGround() ? block * 0.91F : 0.91F;
        }
    }

    @Inject(method = "travel", at = @At("TAIL"))
    private void createUniversal$after(Vec3 input, CallbackInfo ci) {
        Vector3d up = createUniversal$up;
        createUniversal$up = null;
        if (up == null) {
            return;
        }
        LivingEntity self = (LivingEntity) (Object) this;
        if (self instanceof Player p && p.getAbilities().flying) {
            return;
        }
        Gravity.State state = Gravity.state(this);
        if (state == null) {
            return;
        }
        Vec3 v = getDeltaMovement();
        float f = createUniversal$friction;
        if (f > 0.0F) {
            // Deshacer el rozamiento de Minecraft (f en X y Z, 0,98 en Y) y ponerlo bien: f en el plano del suelo, 0,98 en
            // la dirección de la gravedad.
            Vector3d mid = new Vector3d(v.x / f, v.y / 0.98, v.z / f);
            double along = mid.dot(up);
            Vector3d fixed = new Vector3d(mid).mul(f).fma(along * (0.98 - f), up);
            v = new Vec3(fixed.x, fixed.y, fixed.z);
        }
        double g = Gravity.EARTH * state.strength() * (isInWater() || isInLava() ? 0.25 : 1.0);
        v = v.subtract(up.x * g, up.y * g, up.z * g);
        setDeltaMovement(v);
    }
}
