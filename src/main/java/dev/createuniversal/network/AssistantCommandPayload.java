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

/**
 * Cliente → servidor: encender el piloto automático hacia un destino del mapa, o apagarlo (alpha.12.1). Para lo que no es
 * un astro, el cliente manda también su posición (marcadores, restos, naves aparcadas y puntos de Xaero no se mueven).
 */
public record AssistantCommandPayload(BlockPos pos, boolean engage, String target, double x, double y, double z) implements CustomPacketPayload {
    public static final Type<AssistantCommandPayload> TYPE = new Type<>(CreateUniversal.id("assistant_command"));
    public static final StreamCodec<ByteBuf, AssistantCommandPayload> STREAM_CODEC = StreamCodec.of(AssistantCommandPayload::write, AssistantCommandPayload::read);

    private static void write(ByteBuf buf, AssistantCommandPayload p) {
        FriendlyByteBuf f = new FriendlyByteBuf(buf);
        f.writeBlockPos(p.pos());
        f.writeBoolean(p.engage());
        f.writeUtf(p.target(), 128);
        f.writeDouble(p.x()).writeDouble(p.y()).writeDouble(p.z());
    }

    private static AssistantCommandPayload read(ByteBuf buf) {
        FriendlyByteBuf f = new FriendlyByteBuf(buf);
        return new AssistantCommandPayload(f.readBlockPos(), f.readBoolean(), f.readUtf(128), f.readDouble(), f.readDouble(), f.readDouble());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(AssistantCommandPayload p, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            Vec3 at = dev.ryanhcode.sable.Sable.HELPER.projectOutOfSubLevel(player.level(), Vec3.atCenterOf(p.pos()));
            if (at.distanceToSqr(player.position()) > 16 * 16) {
                return;
            }
            if (player.level().getBlockEntity(p.pos()) instanceof ShipAssistantBlockEntity be) {
                if (p.engage() && !p.target().isEmpty()) {
                    be.engage(p.target(), p.target().startsWith("body:") ? null : new Vec3(p.x(), p.y(), p.z()));
                } else {
                    be.disengage("stopped");
                }
                be.openFor(player);
            }
        });
    }
}
