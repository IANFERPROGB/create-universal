package dev.createuniversal.network;

import dev.createuniversal.CreateUniversal;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/** Servidor → cliente: las naves aparcadas con un Estabilizador orbital (alpha.11.3). Sustituye la lista anterior. */
public record ParkedShipsPayload(List<Ship> ships) implements CustomPacketPayload {
    public record Ship(String id, String name, ResourceLocation dimension, double x, double y, double z) {}

    public static final Type<ParkedShipsPayload> TYPE = new Type<>(CreateUniversal.id("parked_ships"));
    public static final StreamCodec<ByteBuf, ParkedShipsPayload> STREAM_CODEC = StreamCodec.of(ParkedShipsPayload::write, ParkedShipsPayload::read);

    private static void write(ByteBuf buf, ParkedShipsPayload p) {
        FriendlyByteBuf f = new FriendlyByteBuf(buf);
        f.writeVarInt(p.ships().size());
        for (Ship s : p.ships()) {
            f.writeUtf(s.id());
            f.writeUtf(s.name(), 128);
            f.writeResourceLocation(s.dimension());
            f.writeDouble(s.x()).writeDouble(s.y()).writeDouble(s.z());
        }
    }

    private static ParkedShipsPayload read(ByteBuf buf) {
        FriendlyByteBuf f = new FriendlyByteBuf(buf);
        int n = f.readVarInt();
        List<Ship> ships = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            ships.add(new Ship(f.readUtf(), f.readUtf(128), f.readResourceLocation(), f.readDouble(), f.readDouble(), f.readDouble()));
        }
        return new ParkedShipsPayload(ships);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ParkedShipsPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> dev.createuniversal.client.nav.NavClient.receiveShips(payload.ships()));
    }
}
