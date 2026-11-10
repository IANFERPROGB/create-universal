package dev.createuniversal.registry;

import com.simibubi.create.content.equipment.armor.BacktankBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * La pechera del traje colocada en el suelo: con la forma de la pechera puesta (torso y hombros,
 * 16 de ancho, 12 de alto y 4 de fondo) y el eje de Create en el cuello, por donde entra la rotación para recargarla.
 * Todo lo demás (recarga, comparador, recogerla con el aire que tenga) es el backtank de Create.
 */
public class SpaceSuitTankBlock extends BacktankBlock {
    private static final VoxelShape NORTH_SOUTH = Shapes.or(Block.box(0, 0, 6, 16, 12, 10), Block.box(6, 12, 6, 10, 16, 10));
    private static final VoxelShape EAST_WEST = Shapes.or(Block.box(6, 0, 0, 10, 12, 16), Block.box(6, 12, 6, 10, 16, 10));

    public SpaceSuitTankBlock(Properties properties) {
        super(properties);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        Direction facing = state.getValue(HORIZONTAL_FACING);
        return facing.getAxis() == Direction.Axis.X ? EAST_WEST : NORTH_SOUTH;
    }
}
