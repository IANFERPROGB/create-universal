package dev.createuniversal.registry;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.createuniversal.space.CUDimensions;
import dev.createuniversal.space.CruiseDrives;
import dev.createuniversal.space.FastTravelChunks;
import dev.createuniversal.space.SpaceLayout;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;

/**
 * Motor de crucero (alpha.12.4, D95): con al menos 128 RPM, en el espacio y lejos de astros y campos de rocas, los
 * propulsores de su nave empujan ×4 ({@link CruiseDrives}). Gasta 64 SU por RPM (8.192 SU a 128 RPM).
 */
public class CruiseDriveBlockEntity extends KineticBlockEntity {
    public static final float MIN_RPM = 128.0F;

    public enum Status { NOT_ON_SHIP, NOT_IN_SPACE, TOO_SLOW, NEAR, ACTIVE }

    private Status status = Status.NOT_ON_SHIP;
    private int timer;

    public CruiseDriveBlockEntity(BlockPos pos, BlockState state) {
        super(CUMachines.CRUISE_DRIVE_BE.get(), pos, state);
    }

    @Override
    public void tick() {
        super.tick();
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        if (++timer < 5) {
            if (status == Status.ACTIVE) {
                SubLevel ship = Sable.HELPER.getContaining(level, worldPosition);
                if (ship != null) {
                    CruiseDrives.mark(ship, level.getGameTime());
                }
            }
            return;
        }
        timer = 0;
        Status before = status;
        SubLevel ship = Sable.HELPER.getContaining(level, worldPosition);
        if (ship == null) {
            status = Status.NOT_ON_SHIP;
        } else if (server.dimension() != CUDimensions.SPACE) {
            status = Status.NOT_IN_SPACE;
        } else if (Math.abs(getSpeed()) < MIN_RPM || isOverStressed()) {
            status = Status.TOO_SLOW;
        } else {
            var b = ship.boundingBox();
            Vec3 c = new Vec3((b.minX() + b.maxX()) * 0.5, (b.minY() + b.maxY()) * 0.5, (b.minZ() + b.maxZ()) * 0.5);
            status = FastTravelChunks.nearSomething(c, SpaceLayout.time(server)) ? Status.NEAR : Status.ACTIVE;
        }
        if (status == Status.ACTIVE) {
            CruiseDrives.mark(ship, level.getGameTime());
        }
        if (status != before) {
            sendData();
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
        ChatFormatting color = status == Status.ACTIVE ? ChatFormatting.AQUA : ChatFormatting.GOLD;
        tooltip.add(Component.literal("    ").append(Component.translatable(
                "create_universal.cruise_drive.status." + status.name().toLowerCase(Locale.ROOT), (int) MIN_RPM).withStyle(color)));
        return true;
    }
}
