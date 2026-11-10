package dev.createuniversal.client;

import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import dev.createuniversal.registry.GravityStabilizerBlockEntity;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.block.state.BlockState;

/** Medio eje que sale por detrás del Estabilizador de gravedad (como el del motor de Create), sin Flywheel. */
public class GravityStabilizerRenderer extends KineticBlockEntityRenderer<GravityStabilizerBlockEntity> {
    public GravityStabilizerRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    protected SuperByteBuffer getRotatedModel(GravityStabilizerBlockEntity be, BlockState state) {
        return CachedBuffers.partialFacing(AllPartialModels.SHAFT_HALF, state);
    }
}
