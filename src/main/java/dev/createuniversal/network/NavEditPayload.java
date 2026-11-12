package dev.createuniversal.network;

import dev.createuniversal.CreateUniversal;
import dev.createuniversal.space.Navigation;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Cliente → servidor: poner, renombrar o quitar un marcador del mapa (alpha.11). */
public record NavEditPayload(int action, String id, String name, double x, double y, double z) implements CustomPacketPayload {
    public static final int ADD = 0;
    public static final int REMOVE = 1;
    public static final int RENAME = 2;
    /** Quitar el punto de unos restos de nave (por su posición). */
    public static final int REMOVE_DEBRIS = 3;

    public static final Type<NavEditPayload> TYPE = new Type<>(CreateUniversal.id("nav_edit"));
    public static final StreamCodec<ByteBuf, NavEditPayload> STREAM_CODEC = StreamCodec.of(NavEditPayload::write, NavEditPayload::read);

    private static void write(ByteBuf buf, NavEditPayload p) {
        FriendlyByteBuf f = new FriendlyByteBuf(buf);
        f.writeVarInt(p.action());
        f.writeUtf(p.id());
        f.writeUtf(p.name(), 64);
        f.writeDouble(p.x()).writeDouble(p.y()).writeDouble(p.z());
    }

    private static NavEditPayload read(ByteBuf buf) {
        FriendlyByteBuf f = new FriendlyByteBuf(buf);
        return new NavEditPayload(f.readVarInt(), f.readUtf(), f.readUtf(64), f.readDouble(), f.readDouble(), f.readDouble());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(NavEditPayload p, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            switch (p.action()) {
                case ADD -> Navigation.addMarker(player, p.name(), p.x(), p.y(), p.z());
                case REMOVE -> Navigation.removeMarker(player, p.id());
                case RENAME -> Navigation.renameMarker(player, p.id(), p.name());
                case REMOVE_DEBRIS -> dev.createuniversal.space.DebrisSites.dismiss(player, p.x(), p.y(), p.z());
                default -> {
                }
            }
        });
    }
}
