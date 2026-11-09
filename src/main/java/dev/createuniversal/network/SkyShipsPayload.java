package dev.createuniversal.network;

import dev.createuniversal.CreateUniversal;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Naves del espacio que están cerca de la Tierra, para dibujarlas en el cielo del Overworld (con un catalejo se ven).
 * Posición, velocidad (bloques/s) y medio tamaño de su caja, en coordenadas de la dimensión espacio.
 */
public record SkyShipsPayload(List<Ship> ships) implements CustomPacketPayload {
    public record Ship(double x, double y, double z, float vx, float vy, float vz, float hx, float hy, float hz) {}

    public static final Type<SkyShipsPayload> TYPE = new Type<>(CreateUniversal.id("sky_ships"));
    public static final StreamCodec<ByteBuf, SkyShipsPayload> STREAM_CODEC = StreamCodec.of(SkyShipsPayload::write, SkyShipsPayload::read);

    private static void write(ByteBuf buf, SkyShipsPayload p) {
        buf.writeShort(p.ships().size());
        for (Ship s : p.ships()) {
            buf.writeDouble(s.x()).writeDouble(s.y()).writeDouble(s.z());
            buf.writeFloat(s.vx()).writeFloat(s.vy()).writeFloat(s.vz());
            buf.writeFloat(s.hx()).writeFloat(s.hy()).writeFloat(s.hz());
        }
    }

    private static SkyShipsPayload read(ByteBuf buf) {
        int n = buf.readShort();
        List<Ship> ships = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            ships.add(new Ship(buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
                    buf.readFloat(), buf.readFloat(), buf.readFloat()));
        }
        return new SkyShipsPayload(ships);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SkyShipsPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> dev.createuniversal.client.sky.SkyShips.receive(payload.ships()));
    }
}
