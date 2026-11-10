package dev.createuniversal.network;

import dev.createuniversal.CreateUniversal;
import dev.createuniversal.space.Gravity;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/** Gravedad propia de una entidad (Estabilizador de gravedad), o null = ya no tiene. */
public record GravityStatePayload(int entityId, Gravity.State state) implements CustomPacketPayload {
    public static final Type<GravityStatePayload> TYPE = new Type<>(CreateUniversal.id("gravity_state"));
    public static final StreamCodec<ByteBuf, GravityStatePayload> STREAM_CODEC = StreamCodec.of(GravityStatePayload::write, GravityStatePayload::read);

    private static void write(ByteBuf buf, GravityStatePayload p) {
        FriendlyByteBuf f = new FriendlyByteBuf(buf);
        f.writeVarInt(p.entityId());
        Gravity.State s = p.state();
        f.writeBoolean(s != null);
        if (s != null) {
            f.writeBoolean(s.ship() != null);
            if (s.ship() != null) {
                f.writeUUID(s.ship());
            }
            f.writeByte(s.down().get3DDataValue());
            f.writeFloat(s.strength());
        }
    }

    private static GravityStatePayload read(ByteBuf buf) {
        FriendlyByteBuf f = new FriendlyByteBuf(buf);
        int id = f.readVarInt();
        if (!f.readBoolean()) {
            return new GravityStatePayload(id, null);
        }
        UUID ship = f.readBoolean() ? f.readUUID() : null;
        Direction down = Direction.from3DDataValue(f.readByte());
        float strength = f.readFloat();
        return new GravityStatePayload(id, new Gravity.State(ship, down, strength));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(GravityStatePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> Gravity.receive(payload.entityId(), payload.state()));
    }
}
