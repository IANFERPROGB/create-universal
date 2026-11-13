package dev.createuniversal.registry;

import com.simibubi.create.content.kinetics.base.RotatedPillarKineticBlock;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** Asistente de nave (alpha.12, D61): eje de Create que lo atraviesa (mueve su giroscopio); ver {@link ShipAssistantBlockEntity}. */
public class ShipAssistantBlock extends RotatedPillarKineticBlock implements IBE<ShipAssistantBlockEntity> {
    public ShipAssistantBlock(Properties properties) {
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

    /** Clic derecho: la ficha de la nave (la manda el servidor y el cliente abre la pantalla). */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && player instanceof ServerPlayer sp && level.getBlockEntity(pos) instanceof ShipAssistantBlockEntity be) {
            be.openFor(sp);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public Class<ShipAssistantBlockEntity> getBlockEntityClass() {
        return ShipAssistantBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends ShipAssistantBlockEntity> getBlockEntityType() {
        return CUMachines.SHIP_ASSISTANT_BE.get();
    }
}
