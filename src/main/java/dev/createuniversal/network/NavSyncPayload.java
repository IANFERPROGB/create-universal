package dev.createuniversal.network;

import dev.createuniversal.CreateUniversal;
import dev.createuniversal.space.Navigation;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/** Servidor → cliente: astros descubiertos y marcadores del jugador (alpha.11). */
public record NavSyncPayload(List<String> discovered, List<Navigation.Marker> markers) implements CustomPacketPayload {
    public static final Type<NavSyncPayload> TYPE = new Type<>(CreateUniversal.id("nav_sync"));
    public static final StreamCodec<ByteBuf, NavSyncPayload> STREAM_CODEC = StreamCodec.of(NavSyncPayload::write, NavSyncPayload::read);

    private static void write(ByteBuf buf, NavSyncPayload p) {
        FriendlyByteBuf f = new FriendlyByteBuf(buf);
        f.writeVarInt(p.discovered().size());
        p.discovered().forEach(f::writeUtf);
        f.writeVarInt(p.markers().size());
        for (Navigation.Marker m : p.markers()) {
            f.writeUtf(m.id());
            f.writeUtf(m.name());
            f.writeDouble(m.x()).writeDouble(m.y()).writeDouble(m.z());
        }
    }

    private static NavSyncPayload read(ByteBuf buf) {
        FriendlyByteBuf f = new FriendlyByteBuf(buf);
        int n = f.readVarInt();
        List<String> found = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            found.add(f.readUtf());
        }
        int m = f.readVarInt();
        List<Navigation.Marker> markers = new ArrayList<>(m);
        for (int i = 0; i < m; i++) {
            markers.add(new Navigation.Marker(f.readUtf(), f.readUtf(), f.readDouble(), f.readDouble(), f.readDouble()));
        }
        return new NavSyncPayload(found, markers);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(NavSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> dev.createuniversal.client.nav.NavClient.receive(payload.discovered(), payload.markers()));
    }
}
