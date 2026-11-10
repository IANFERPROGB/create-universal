package dev.createuniversal.mixin;

import dev.createuniversal.space.FastTravelChunks;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * La cola de chunks de Immersive Portals ({@link FastTravelChunks}): menos chunks nuevos por tick si el servidor va con
 * retraso, y primero los que hay delante de quien va deprisa. Si Immersive Portals no está, no hace nada.
 */
@Pseudo
@Mixin(targets = "qouteall.imm_ptl.core.chunk_loading.ImmPtlChunkTickets", remap = false)
public abstract class ImmPortalsChunkQueueMixin {
    @Shadow
    @Final
    private LongOpenHashSet waitingForLoading;

    @Shadow
    public abstract void markForLoading(long chunkPos, int distanceToSource, int generation);

    @Unique
    private ServerLevel createUniversal$level;
    @Unique
    private int createUniversal$budget = 4;
    @Unique
    private boolean createUniversal$requeue;

    @Inject(method = "flushThrottling", at = @At("HEAD"), require = 0)
    private void createUniversal$startTick(ServerLevel world, CallbackInfo ci) {
        createUniversal$level = world;
        createUniversal$budget = FastTravelChunks.ticketBudget();
    }

    /** "Como mucho 4 esperando" pasa a "como mucho 4, y no más de los que quedan en este tick". */
    @ModifyConstant(method = "flushThrottling", constant = @Constant(intValue = 4), require = 0)
    private int createUniversal$limit(int original) {
        return Math.min(original, waitingForLoading.size() + Math.max(0, createUniversal$budget));
    }

    @Inject(method = "flushThrottling", require = 0,
            at = @At(value = "INVOKE", target = "Lqouteall/imm_ptl/core/chunk_loading/ImmPtlChunkTickets;addTicket(Lnet/minecraft/server/level/DistanceManager;J)V"))
    private void createUniversal$spend(ServerLevel world, CallbackInfo ci) {
        createUniversal$budget--;
    }

    @Inject(method = "markForLoading", at = @At("HEAD"), cancellable = true, require = 0)
    private void createUniversal$aheadFirst(long chunkPos, int distanceToSource, int generation, CallbackInfo ci) {
        if (createUniversal$requeue || createUniversal$level == null) {
            return;
        }
        int wanted = FastTravelChunks.priority(createUniversal$level, chunkPos, distanceToSource);
        if (wanted != distanceToSource && wanted >= 0) {
            createUniversal$requeue = true;
            try {
                markForLoading(chunkPos, wanted, generation);
            } finally {
                createUniversal$requeue = false;
            }
            ci.cancel();
        }
    }
}
