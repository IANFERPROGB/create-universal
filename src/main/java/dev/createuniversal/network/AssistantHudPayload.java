package dev.createuniversal.network;

import dev.createuniversal.CreateUniversal;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Servidor → cliente, cada segundo, a quien va a bordo: el panel del Asistente de nave (alpha.12, D64).
 *
 * @param status  qué hace el asistente (sufijo de {@code create_universal.assistant.status.*})
 * @param target  destino del piloto automático ("" si no hay), con las mismas claves que el mapa ("body:mars"...)
 * @param eta     segundos para llegar, o −1
 */
public record AssistantHudPayload(String status, String target, float speed, int working, int total, long fuel, long fuelCapacity,
                                  int hull, boolean air, float eta) implements CustomPacketPayload {
    public static final Type<AssistantHudPayload> TYPE = new Type<>(CreateUniversal.id("assistant_hud"));
    public static final StreamCodec<ByteBuf, AssistantHudPayload> STREAM_CODEC = StreamCodec.of(AssistantHudPayload::write, AssistantHudPayload::read);

    private static void write(ByteBuf buf, AssistantHudPayload p) {
        FriendlyByteBuf f = new FriendlyByteBuf(buf);
        f.writeUtf(p.status(), 64);
        f.writeUtf(p.target(), 128);
        f.writeFloat(p.speed());
        f.writeVarInt(p.working());
        f.writeVarInt(p.total());
        f.writeVarLong(p.fuel());
        f.writeVarLong(p.fuelCapacity());
        f.writeVarInt(p.hull());
        f.writeBoolean(p.air());
        f.writeFloat(p.eta());
    }

    private static AssistantHudPayload read(ByteBuf buf) {
        FriendlyByteBuf f = new FriendlyByteBuf(buf);
        return new AssistantHudPayload(f.readUtf(64), f.readUtf(128), f.readFloat(), f.readVarInt(), f.readVarInt(), f.readVarLong(),
                f.readVarLong(), f.readVarInt(), f.readBoolean(), f.readFloat());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(AssistantHudPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> dev.createuniversal.client.assistant.AssistantHud.receive(payload));
    }
}
