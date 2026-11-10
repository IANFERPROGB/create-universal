package dev.createuniversal.registry;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.createuniversal.space.OrbitalStabilizers;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Estabilizador orbital (D37, D41, D42): dentro de una nave de Aeronautics/Sable, con al menos 128 RPM y señal de
 * redstone, deja la nave fija en su punto del mundo (ver {@link OrbitalStabilizers}). Consume 16 SU por RPM (2.048 SU a
 * 128 RPM, lo que da un Portable Engine). Colocado en el suelo no hace nada.
 */
public class OrbitalStabilizerBlockEntity extends KineticBlockEntity {
    public static final float MIN_RPM = 128.0F;

    public enum Status { NOT_ON_SHIP, TOO_SLOW, NO_SIGNAL, ACTIVE }

    public OrbitalStabilizerBlockEntity(BlockPos pos, BlockState state) {
        super(CUMachines.ORBITAL_STABILIZER_BE.get(), pos, state);
    }

    public Status status() {
        if (level == null) {
            return Status.NOT_ON_SHIP;
        }
        SubLevel ship = Sable.HELPER.getContaining(level, worldPosition);
        if (ship == null) {
            return Status.NOT_ON_SHIP;
        }
        if (Math.abs(getSpeed()) < MIN_RPM || isOverStressed()) {
            return Status.TOO_SLOW;
        }
        if (!level.hasNeighborSignal(worldPosition)) {
            return Status.NO_SIGNAL;
        }
        return Status.ACTIVE;
    }

    @Override
    public void tick() {
        super.tick();
        if (level instanceof ServerLevel server && status() == Status.ACTIVE) {
            OrbitalStabilizers.hold(server, Sable.HELPER.getContaining(level, worldPosition));
        }
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        super.addToGoggleTooltip(tooltip, isPlayerSneaking);
        Status status = status();
        ChatFormatting color = status == Status.ACTIVE ? ChatFormatting.AQUA : ChatFormatting.GOLD;
        tooltip.add(Component.literal("    ").append(Component.translatable(
                "create_universal.orbital_stabilizer.status." + status.name().toLowerCase(java.util.Locale.ROOT),
                (int) MIN_RPM).withStyle(color)));
        return true;
    }
}
