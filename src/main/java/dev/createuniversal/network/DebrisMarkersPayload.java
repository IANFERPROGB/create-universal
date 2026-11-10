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

/** Puntos de ruta de los restos de naves destruidas que ve este jugador (D45). Sustituye la lista anterior. */
public record DebrisMarkersPayload(List<Marker> markers) implements CustomPacketPayload {
    public record Marker(ResourceLocation dimension, double x, double y, double z, long expires) {}

    public static final Type<DebrisMarkersPayload> TYPE = new Type<>(CreateUniversal.id("debris_markers"));
    public static final StreamCodec<ByteBuf, DebrisMarkersPayload> STREAM_CODEC = StreamCodec.of(DebrisMarkersPayload::write, DebrisMarkersPayload::read);

    private static void write(ByteBuf buf, DebrisMarkersPayload p) {
        FriendlyByteBuf f = new FriendlyByteBuf(buf);
        f.writeVarInt(p.markers().size());
        for (Marker m : p.markers()) {
            f.writeResourceLocation(m.dimension());
            f.writeDouble(m.x()).writeDouble(m.y()).writeDouble(m.z());
            f.writeLong(m.expires());
        }
    }

    private static DebrisMarkersPayload read(ByteBuf buf) {
        FriendlyByteBuf f = new FriendlyByteBuf(buf);
        int n = f.readVarInt();
        List<Marker> markers = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            markers.add(new Marker(f.readResourceLocation(), f.readDouble(), f.readDouble(), f.readDouble(), f.readLong()));
        }
        return new DebrisMarkersPayload(markers);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(DebrisMarkersPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> dev.createuniversal.client.effects.DebrisMarkers.receive(payload.markers()));
    }
}
