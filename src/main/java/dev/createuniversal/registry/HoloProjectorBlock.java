package dev.createuniversal.registry;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Proyector holográfico (D46, D48, alpha.11.2): proyecta encima un holograma 3D del sistema solar. Clic derecho: el mapa
 * (igual que la mesa). Con Shift: el panel para elegir tamaño, vista y nombres.
 */
public class HoloProjectorBlock extends Block implements EntityBlock {
    private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 10, 16);

    public HoloProjectorBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new HoloProjectorBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) {
            if (player.isShiftKeyDown()) {
                if (level.getBlockEntity(pos) instanceof HoloProjectorBlockEntity be) {
                    dev.createuniversal.client.nav.NavOpen.openProjector(be);
                }
            } else {
                dev.createuniversal.client.nav.NavOpen.open();
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
