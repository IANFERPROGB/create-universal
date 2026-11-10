package dev.createuniversal.registry;

import dev.createuniversal.space.MoonletGrip;
import dev.ryanhcode.sable.api.block.BlockWithSubLevelCollisionCallback;
import dev.ryanhcode.sable.api.physics.callback.BlockSubLevelCollisionCallback;
import net.minecraft.world.level.block.Block;

/** La roca de Fobos y Deimos: avisa a Sable de que quiere saber qué choca con ella, para que las naves se enganchen. */
public class MoonletRockBlock extends Block implements BlockWithSubLevelCollisionCallback {
    public MoonletRockBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockSubLevelCollisionCallback sable$getCallback() {
        return MoonletGrip.CALLBACK;
    }
}
