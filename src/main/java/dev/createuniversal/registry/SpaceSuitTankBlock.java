package dev.createuniversal.registry;

import com.simibubi.create.content.equipment.armor.BacktankBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * La pechera del traje.
 *
 * <ul>
 *   <li><b>Colocada en el suelo</b> ({@link #PLACED} = sí): la pechera del traje de pie, con el backtank de netherita de
 *       Create a la espalda (sus engranajes y su eje, por donde entra la rotación).</li>
 *   <li><b>Puesta</b>: Create dibuja en la espalda del jugador el modelo del bloque en su estado por defecto
 *       ({@link #PLACED} = no), que es solo el backtank de netherita de Create, igual que el suyo.</li>
 * </ul>
 * Todo lo demás (recarga, comparador, recogerla con el aire que tenga) es el backtank de Create.
 */
public class SpaceSuitTankBlock extends BacktankBlock {
    public static final BooleanProperty PLACED = BooleanProperty.create("placed");

    // Pechera delante (hacia quien la coloca) y el backtank detrás, mirando al norte; se gira con la orientación.
    private static final VoxelShape[] SHAPES = new VoxelShape[4];

    static {
        VoxelShape north = Shapes.or(Block.box(0, 0, 0, 16, 12, 3), Block.box(4, 0, 3, 12, 12, 12), Block.box(6, 12, 6, 10, 16, 10));
        for (Direction d : Direction.Plane.HORIZONTAL) {
            SHAPES[d.get2DDataValue()] = rotate(north, d);
        }
    }

    public SpaceSuitTankBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(PLACED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(PLACED);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = super.getStateForPlacement(context);
        return state == null ? null : state.setValue(PLACED, true);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES[state.getValue(HORIZONTAL_FACING).get2DDataValue()];
    }

    /** Gira una forma hecha mirando al norte hacia otra dirección horizontal. */
    private static VoxelShape rotate(VoxelShape north, Direction to) {
        VoxelShape[] out = {Shapes.empty()};
        north.forAllBoxes((x1, y1, z1, x2, y2, z2) -> {
            double ax1 = x1, az1 = z1, ax2 = x2, az2 = z2;
            switch (to) {
                case SOUTH -> {
                    ax1 = 1 - x2; ax2 = 1 - x1; az1 = 1 - z2; az2 = 1 - z1;
                }
                case EAST -> {
                    ax1 = 1 - z2; ax2 = 1 - z1; az1 = x1; az2 = x2;
                }
                case WEST -> {
                    ax1 = z1; ax2 = z2; az1 = 1 - x2; az2 = 1 - x1;
                }
                default -> {
                }
            }
            out[0] = Shapes.or(out[0], Shapes.box(ax1, y1, az1, ax2, y2, az2));
        });
        return out[0];
    }
}
