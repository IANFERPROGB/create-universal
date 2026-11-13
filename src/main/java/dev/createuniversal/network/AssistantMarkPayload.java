package dev.createuniversal.network;

import dev.createuniversal.CreateUniversal;
import dev.createuniversal.registry.ShipAssistantBlockEntity;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Cliente → servidor: marcar o desmarcar en "Otros" un tipo de bloque como motor (alpha.12). */
public record AssistantMarkPayload(BlockPos pos, String block) implements CustomPacketPayload {
    public static final Type<AssistantMarkPayload> TYPE = new Type<>(CreateUniversal.id("assistant_mark"));
    public static final StreamCodec<ByteBuf, AssistantMarkPayload> STREAM_CODEC = StreamCodec.of(AssistantMarkPayload::write, AssistantMarkPayload::read);

    private static void write(ByteBuf buf, AssistantMarkPayload p) {
        FriendlyByteBuf f = new FriendlyByteBuf(buf);
        f.writeBlockPos(p.pos());
        f.writeUtf(p.block(), 256);
    }

    private static AssistantMarkPayload read(ByteBuf buf) {
        FriendlyByteBuf f = new FriendlyByteBuf(buf);
        return new AssistantMarkPayload(f.readBlockPos(), f.readUtf(256));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(AssistantMarkPayload p, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            Vec3 at = dev.ryanhcode.sable.Sable.HELPER.projectOutOfSubLevel(player.level(), Vec3.atCenterOf(p.pos()));
            if (at.distanceToSqr(player.position()) > 16 * 16) {
                return;
            }
            if (player.level().getBlockEntity(p.pos()) instanceof ShipAssistantBlockEntity be) {
                be.toggleMark(p.block());
                be.openFor(player);
            }
        });
    }
}
