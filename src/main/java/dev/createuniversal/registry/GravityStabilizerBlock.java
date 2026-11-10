package dev.createuniversal.registry;

import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Estabilizador de gravedad (D50, D51): se pega a una cara (suelo, techo o pared) y esa cara pasa a ser "abajo". FACING
 * apunta hacia la cara donde está pegado: por ahí entra el eje y hacia ahí tira la gravedad. Ver
 * {@link GravityStabilizerBlockEntity}.
 */
public class GravityStabilizerBlock extends DirectionalKineticBlock implements IBE<GravityStabilizerBlockEntity> {
    public GravityStabilizerBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getClickedFace().getOpposite());
    }

    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
        return face == state.getValue(FACING);
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return state.getValue(FACING).getAxis();
    }

    @Override
    public Class<GravityStabilizerBlockEntity> getBlockEntityClass() {
        return GravityStabilizerBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends GravityStabilizerBlockEntity> getBlockEntityType() {
        return CUMachines.GRAVITY_STABILIZER_BE.get();
    }
}
