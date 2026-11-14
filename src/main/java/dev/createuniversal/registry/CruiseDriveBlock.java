package dev.createuniversal.registry;

import com.simibubi.create.content.kinetics.base.RotatedPillarKineticBlock;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/** Motor de crucero (alpha.12.4, D95): eje de Create que lo atraviesa; ver {@link CruiseDriveBlockEntity}. */
public class CruiseDriveBlock extends RotatedPillarKineticBlock implements IBE<CruiseDriveBlockEntity> {
    public CruiseDriveBlock(Properties properties) {
        super(properties);
    }

    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
        return face.getAxis() == state.getValue(AXIS);
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return state.getValue(AXIS);
    }

    @Override
    public Class<CruiseDriveBlockEntity> getBlockEntityClass() {
        return CruiseDriveBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends CruiseDriveBlockEntity> getBlockEntityType() {
        return CUMachines.CRUISE_DRIVE_BE.get();
    }
}
