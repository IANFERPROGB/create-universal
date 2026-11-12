package dev.createuniversal.network;

import dev.createuniversal.CreateUniversal;
import dev.createuniversal.registry.HoloProjectorBlockEntity;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Cliente → servidor: el panel del proyector holográfico cambia su tamaño, vista o nombres (alpha.11.2). */
public record HoloConfigPayload(BlockPos pos, int size, int view, boolean names) implements CustomPacketPayload {
    public static final Type<HoloConfigPayload> TYPE = new Type<>(CreateUniversal.id("holo_config"));
    public static final StreamCodec<ByteBuf, HoloConfigPayload> STREAM_CODEC = StreamCodec.of(HoloConfigPayload::write, HoloConfigPayload::read);

    private static void write(ByteBuf buf, HoloConfigPayload p) {
        FriendlyByteBuf f = new FriendlyByteBuf(buf);
        f.writeBlockPos(p.pos());
        f.writeVarInt(p.size());
        f.writeVarInt(p.view());
        f.writeBoolean(p.names());
    }

    private static HoloConfigPayload read(ByteBuf buf) {
        FriendlyByteBuf f = new FriendlyByteBuf(buf);
        return new HoloConfigPayload(f.readBlockPos(), f.readVarInt(), f.readVarInt(), f.readBoolean());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(HoloConfigPayload p, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            // El bloque puede estar en una nave (zona interna de Sable): se comprueba con su posición real.
            net.minecraft.world.phys.Vec3 at = dev.ryanhcode.sable.Sable.HELPER.projectOutOfSubLevel(player.level(),
                    net.minecraft.world.phys.Vec3.atCenterOf(p.pos()));
            if (at.distanceToSqr(player.position()) > 16 * 16) {
                return;
            }
            if (player.level().getBlockEntity(p.pos()) instanceof HoloProjectorBlockEntity be) {
                be.configure(p.size(), p.view(), p.names());
            }
        });
    }
}
