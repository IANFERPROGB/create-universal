package dev.createuniversal.client.sky;

import dev.createuniversal.network.SkyShipsPayload;

import java.util.List;

/** Las naves cercanas a la Tierra que manda el servidor (ver {@code SkyShipBroadcaster}), para el cielo del Overworld. */
public final class SkyShips {
    /** Si no llega nada nuevo en este tiempo, ya no hay naves cerca. */
    private static final double MAX_AGE_SECONDS = 1.0;

    private static List<SkyShipsPayload.Ship> ships = List.of();
    private static long receivedNanos;

    private SkyShips() {}

    public static void receive(List<SkyShipsPayload.Ship> list) {
        ships = list;
        receivedNanos = System.nanoTime();
    }

    public static List<SkyShipsPayload.Ship> current() {
        return age() > MAX_AGE_SECONDS ? List.of() : ships;
    }

    /** Segundos desde el último paquete: para adelantar cada nave según su velocidad y que se mueva suave. */
    public static double age() {
        return (System.nanoTime() - receivedNanos) / 1.0e9;
    }
}
