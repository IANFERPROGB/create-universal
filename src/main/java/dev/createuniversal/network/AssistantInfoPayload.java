package dev.createuniversal.network;

import dev.createuniversal.CreateUniversal;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/** Servidor → cliente: la ficha del Asistente de nave para su pantalla (alpha.12). Abre la pantalla o la actualiza. */
public record AssistantInfoPayload(BlockPos pos, List<EngineRow> engines, List<OtherRow> others, long fuel, long fuelCapacity,
                                   double mass, boolean gyroReady, boolean piloting) implements CustomPacketPayload {
    /** Un grupo de propulsores iguales: tipo, hacia dónde empujan (en la nave), rendimiento aquí, si están encendidos. */
    public record EngineRow(String kind, String push, int efficiency, boolean active, int count) {}

    /** Un bloque que parece motor y que el asistente no conoce. */
    public record OtherRow(String block, int count, boolean marked) {}

    public static final Type<AssistantInfoPayload> TYPE = new Type<>(CreateUniversal.id("assistant_info"));
    public static final StreamCodec<ByteBuf, AssistantInfoPayload> STREAM_CODEC = StreamCodec.of(AssistantInfoPayload::write, AssistantInfoPayload::read);

    private static void write(ByteBuf buf, AssistantInfoPayload p) {
        FriendlyByteBuf f = new FriendlyByteBuf(buf);
        f.writeBlockPos(p.pos());
        f.writeVarInt(p.engines().size());
        for (EngineRow e : p.engines()) {
            f.writeUtf(e.kind(), 32);
            f.writeUtf(e.push(), 16);
            f.writeVarInt(e.efficiency());
            f.writeBoolean(e.active());
            f.writeVarInt(e.count());
        }
        f.writeVarInt(p.others().size());
        for (OtherRow o : p.others()) {
            f.writeUtf(o.block(), 256);
            f.writeVarInt(o.count());
            f.writeBoolean(o.marked());
        }
        f.writeVarLong(p.fuel());
        f.writeVarLong(p.fuelCapacity());
        f.writeDouble(p.mass());
        f.writeBoolean(p.gyroReady());
        f.writeBoolean(p.piloting());
    }

    private static AssistantInfoPayload read(ByteBuf buf) {
        FriendlyByteBuf f = new FriendlyByteBuf(buf);
        BlockPos pos = f.readBlockPos();
        int n = f.readVarInt();
        List<EngineRow> engines = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            engines.add(new EngineRow(f.readUtf(32), f.readUtf(16), f.readVarInt(), f.readBoolean(), f.readVarInt()));
        }
        int m = f.readVarInt();
        List<OtherRow> others = new ArrayList<>(m);
        for (int i = 0; i < m; i++) {
            others.add(new OtherRow(f.readUtf(256), f.readVarInt(), f.readBoolean()));
        }
        return new AssistantInfoPayload(pos, engines, others, f.readVarLong(), f.readVarLong(), f.readDouble(), f.readBoolean(), f.readBoolean());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(AssistantInfoPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> dev.createuniversal.client.assistant.AssistantScreen.receive(payload));
    }
}
