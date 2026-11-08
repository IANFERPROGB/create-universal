package dev.createuniversal.network;

import dev.createuniversal.CreateUniversal;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * El servidor avisa al cliente de que acaba de cambiarle de dimensión para que reproduzca la animación (D2, idea 3).
 * {@link #EXIT}: salida al espacio. {@link #REENTRY}: reentrada en la Tierra.
 */
public record TransitionEffectPayload(int kind) implements CustomPacketPayload {
    public static final int EXIT = 0;
    public static final int REENTRY = 1;

    public static final Type<TransitionEffectPayload> TYPE = new Type<>(CreateUniversal.id("transition_effect"));
    public static final StreamCodec<ByteBuf, TransitionEffectPayload> STREAM_CODEC =
            ByteBufCodecs.VAR_INT.map(TransitionEffectPayload::new, TransitionEffectPayload::kind);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Solo se ejecuta en el cliente (paquete servidor → cliente); la clase de cliente se carga aquí, nunca en un servidor dedicado. */
    public static void handle(TransitionEffectPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> dev.createuniversal.client.effects.TransitionEffects.onTransition(payload.kind()));
    }
}
