package dev.createuniversal.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.createuniversal.space.Gravity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.effect.MobEffects;
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
 * andar por una pared y frena la caída. Así que, con gravedad propia, la gravedad normal es 0 ({@code EntitySpaceGravityMixin});
 * se guarda la velocidad justo después de moverse (antes del rozamiento de Minecraft) y al terminar {@code travel} se pone
 * el rozamiento repartido según el "arriba" de la entidad y la gravedad hacia su "abajo".
 *
 * Solo donde se calcula de verdad el movimiento de la entidad (en el cliente para el propio jugador, en el servidor para
 * los mobs): en el otro lado {@code travel} no mueve nada (alpha.9.2.1: tocarlo ahí disparaba al jugador).
 */
@Mixin(LivingEntity.class)
public abstract class GravityTravelMixin extends Entity {
    /** Por encima de esto (bloques/tick) no se toca la velocidad: algo raro pasa y mejor no empeorarlo. */
    @Unique
    private static final double CREATE_UNIVERSAL$MAX_SPEED = 4.0;

    @Unique
    private float createUniversal$friction = -1.0F;
    @Unique
    private Vector3d createUniversal$up;
    @Unique
    private Vec3 createUniversal$moved;

    protected GravityTravelMixin(EntityType<?> type, Level level) {
        super(type, level);
    }

    @Inject(method = "travel", at = @At("HEAD"))
    private void createUniversal$before(Vec3 input, CallbackInfo ci) {
        createUniversal$up = null;
        createUniversal$moved = null;
        createUniversal$friction = -1.0F;
        if (!isControlledByLocalInstance()) {
            return;
        }
        LivingEntity self = (LivingEntity) (Object) this;
        if (self instanceof Player p && p.getAbilities().flying) {
            return;
        }
        createUniversal$up = Gravity.up(this);
        if (createUniversal$up == null) {
            return;
        }
        createUniversal$keepWithShip();
        BlockPos below = getBlockPosBelowThatAffectsMyMovement();
        float block = level().getBlockState(below).getFriction(level(), below, this);
        createUniversal$friction = onGround() ? block * 0.91F : 0.91F;
    }

    /**
     * En el aire, Sable deja que quien salta se vaya quedando atrás respecto a la nave (va frenando la velocidad heredada).
     * Dentro de la sala con gravedad de la nave se va con ella: la velocidad heredada es la de la nave en los ojos (con su
     * giro), así un salto acaba donde empezó aunque la nave vuele deprisa (alpha.9.4.1). Pisando el suelo Sable ya lleva
     * a la entidad con la nave y no usa este valor.
     */
    @Unique
    private void createUniversal$keepWithShip() {
        Gravity.State state = Gravity.state(this);
        if (state == null || state.ship() == null) {
            return;
        }
        dev.ryanhcode.sable.sublevel.SubLevel ship = dev.createuniversal.space.OxygenZones.ship(level(), state.ship());
        if (ship == null) {
            return;
        }
        Vec3 eye = getEyePosition();
        Vector3d local = ship.logicalPose().transformPositionInverse(new Vector3d(eye.x, eye.y, eye.z));
        Vector3d perTick = dev.ryanhcode.sable.Sable.HELPER.getVelocity(level(), ship, local, new Vector3d()).mul(0.05);
        ((dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.LivingEntityMovementExtension) (Object) this)
                .sable$getInheritedVelocity().set(perTick);
    }

    /** La velocidad tras moverse, antes de que Minecraft le ponga su rozamiento (solo en el caso normal: ni agua ni lava). */
    @WrapOperation(method = "travel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;handleRelativeFrictionAndCalculateMovement(Lnet/minecraft/world/phys/Vec3;F)Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 createUniversal$captureMoved(LivingEntity instance, Vec3 input, float friction, Operation<Vec3> original) {
        Vec3 moved = original.call(instance, input, friction);
        if (createUniversal$up != null) {
            createUniversal$moved = moved;
        }
        return moved;
    }

    @Inject(method = "travel", at = @At("TAIL"))
    private void createUniversal$after(Vec3 input, CallbackInfo ci) {
        Vector3d up = createUniversal$up;
        Vec3 moved = createUniversal$moved;
        float f = createUniversal$friction;
        createUniversal$up = null;
        createUniversal$moved = null;
        if (up == null) {
            return;
        }
        Gravity.State state = Gravity.state(this);
        if (state == null) {
            return;
        }
        LivingEntity self = (LivingEntity) (Object) this;
        Vec3 v = getDeltaMovement();
        if (v.lengthSqr() > CREATE_UNIVERSAL$MAX_SPEED * CREATE_UNIVERSAL$MAX_SPEED) {
            return;
        }
        if (moved != null && f > 0.0F && !self.hasEffect(MobEffects.LEVITATION) && !self.shouldDiscardFriction()) {
            // Rozamiento bien repartido: f en el plano del "suelo", 0,98 en la dirección de la gravedad.
            Vector3d m = new Vector3d(moved.x, moved.y, moved.z);
            double along = m.dot(up);
            Vector3d fixed = m.mul(f).fma(along * (0.98 - f), up);
            v = new Vec3(fixed.x, fixed.y, fixed.z);
        }
        double g = Gravity.EARTH * state.strength() * (isInWater() || isInLava() ? 0.25 : 1.0);
        setDeltaMovement(v.subtract(up.x * g, up.y * g, up.z * g));
    }
}
