package dev.createuniversal.network;

import dev.createuniversal.CreateUniversal;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/** Llevar al jugador pegado a un punto de una nave (coordenadas de la nave) un momento al entrar en el mundo. */
public record ShipCarryPayload(UUID ship, Vec3 local) implements CustomPacketPayload {
    public static final Type<ShipCarryPayload> TYPE = new Type<>(CreateUniversal.id("ship_carry"));
    public static final StreamCodec<ByteBuf, ShipCarryPayload> STREAM_CODEC = StreamCodec.of(ShipCarryPayload::write, ShipCarryPayload::read);

    private static void write(ByteBuf buf, ShipCarryPayload p) {
        FriendlyByteBuf f = new FriendlyByteBuf(buf);
        f.writeUUID(p.ship());
        f.writeDouble(p.local().x).writeDouble(p.local().y).writeDouble(p.local().z);
    }

    private static ShipCarryPayload read(ByteBuf buf) {
        FriendlyByteBuf f = new FriendlyByteBuf(buf);
        return new ShipCarryPayload(f.readUUID(), new Vec3(f.readDouble(), f.readDouble(), f.readDouble()));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ShipCarryPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> dev.createuniversal.client.ShipCarry.start(payload.ship(), payload.local()));
    }
}
