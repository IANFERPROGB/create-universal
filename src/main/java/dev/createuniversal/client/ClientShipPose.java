package dev.createuniversal.client;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import org.joml.Quaterniond;

/** Solo cliente: la orientación con la que se dibuja una nave en este fotograma. */
public final class ClientShipPose {
    private ClientShipPose() {}

    public static Quaterniond renderOrientation(SubLevel ship) {
        if (ship instanceof ClientSubLevel client) {
            return new Quaterniond(client.renderPose().orientation());
        }
        return new Quaterniond(ship.logicalPose().orientation());
    }
}
