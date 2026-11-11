package dev.createuniversal.registry;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import dev.createuniversal.space.GravityFields;
import dev.ryanhcode.sable.Sable;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Estabilizador de gravedad (D50, D51, D53): con al menos 128 RPM (32 SU por RPM, 4.096 SU a 128 RPM) y dentro de un área
 * cerrada, da gravedad hacia la cara donde está pegado a todos los jugadores y mobs de esa área, con la fuerza que se
 * elija (5 % a 150 % de la de la Tierra). En una nave la gravedad gira con la nave (a la cara del mundo más cercana: la
 * dirección la cambia Gravity Control, que solo tiene 6). Si el área se abre, se apaga y lo avisa.
 */
public class GravityStabilizerBlockEntity extends KineticBlockEntity {
    public static final float MIN_RPM = 128.0F;
    /** Cada cuánto se vuelve a comprobar que el área sigue cerrada. */
    private static final int RESCAN_TICKS = 20;

    public enum Status { TOO_SLOW, SHIP_NOT_FLOOR, OPEN, ACTIVE }

    private ScrollValueBehaviour strength;
    /** Último estado calculado en el servidor (y enviado al cliente para las gafas). */
    private Status status = Status.TOO_SLOW;
    private int scanCooldown;

    public GravityStabilizerBlockEntity(BlockPos pos, BlockState state) {
        super(CUMachines.GRAVITY_STABILIZER_BE.get(), pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        super.addBehaviours(behaviours);
        strength = new ScrollValueBehaviour(Component.translatable("create_universal.gravity_stabilizer.strength"), this, new FrontValueBox());
        strength.between(5, 150);
        strength.value = 100;
        strength.withFormatter(v -> v + " %");
        strength.withCallback(v -> scanCooldown = 0);
        behaviours.add(strength);
    }

    /** Fuerza elegida, en "gravedades de la Tierra". */
    public float strength() {
        return strength != null ? strength.getValue() / 100.0F : 1.0F;
    }

    /** Hacia dónde tira, en las coordenadas del bloque (las de la nave si va en una). */
    public Direction down() {
        return getBlockState().getValue(GravityStabilizerBlock.FACING);
    }

    @Override
    public void tick() {
        super.tick();
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        Status before = status;
        if (Math.abs(getSpeed()) < MIN_RPM || isOverStressed()) {
            status = Status.TOO_SLOW;
        } else if (--scanCooldown <= 0) {
            scanCooldown = RESCAN_TICKS;
            status = GravityFields.update(server, worldPosition, down(), strength()) ? Status.ACTIVE : Status.OPEN;
        } else if (status == Status.ACTIVE) {
            GravityFields.keep(server, worldPosition, down(), strength());
        }
        if (status != Status.ACTIVE) {
            GravityFields.remove(server, worldPosition);
            scanCooldown = Math.min(scanCooldown, 0);
        }
        if (status == Status.OPEN && level.getGameTime() % 40 == 0) {
            Vec3 c = Vec3.atCenterOf(worldPosition);
            var sub = Sable.HELPER.getContaining(level, worldPosition);
            Vec3 world = sub != null ? sub.logicalPose().transformPosition(c) : c;
            for (ServerPlayer p : server.players()) {
                if (p.distanceToSqr(world) < 12 * 12) {
                    p.displayClientMessage(Component.translatable("create_universal.gravity_stabilizer.status.open")
                            .withStyle(ChatFormatting.GOLD), true);
                }
            }
        }
        if (before != status) {
            sendData();
        }
    }

    @Override
    public void remove() {
        super.remove();
        if (level instanceof ServerLevel server) {
            GravityFields.remove(server, worldPosition);
        }
    }

    @Override
    protected void write(net.minecraft.nbt.CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        if (clientPacket) {
            tag.putString("Status", status.name());
        }
    }

    @Override
    protected void read(net.minecraft.nbt.CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        if (clientPacket && tag.contains("Status")) {
            try {
                status = Status.valueOf(tag.getString("Status"));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        super.addToGoggleTooltip(tooltip, isPlayerSneaking);
        Status shown = Math.abs(getSpeed()) < MIN_RPM || isOverStressed() ? Status.TOO_SLOW : status;
        ChatFormatting color = shown == Status.ACTIVE ? ChatFormatting.AQUA : ChatFormatting.GOLD;
        tooltip.add(Component.literal("    ").append(Component.translatable(
                "create_universal.gravity_stabilizer.status." + shown.name().toLowerCase(java.util.Locale.ROOT),
                (int) MIN_RPM).withStyle(color)));
        if (shown == Status.ACTIVE) {
            tooltip.add(Component.literal("    ").append(Component.translatable("create_universal.gravity_stabilizer.strength_value",
                    Math.round(strength() * 100))).withStyle(ChatFormatting.GRAY));
        }
        return true;
    }

    /** La casilla de la fuerza, en la cara de delante (la contraria al eje). */
    private static final class FrontValueBox extends ValueBoxTransform.Sided {
        @Override
        protected Vec3 getSouthLocation() {
            return VecHelper.voxelSpace(8.0, 8.0, 15.5);
        }

        @Override
        protected boolean isSideActive(BlockState state, Direction direction) {
            return direction == state.getValue(GravityStabilizerBlock.FACING).getOpposite();
        }

        @Override
        public void rotate(LevelAccessor level, BlockPos pos, BlockState state, PoseStack ms) {
            super.rotate(level, pos, state, ms);
        }
    }
}
