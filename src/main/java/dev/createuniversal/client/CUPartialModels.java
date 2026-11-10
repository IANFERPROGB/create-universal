package dev.createuniversal.client;

import dev.createuniversal.CreateUniversal;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;

/** Modelos sueltos que dibuja el mod (se registran al arrancar el cliente, antes de cargar los modelos). */
public final class CUPartialModels {
    /** Nada: la pechera del traje colocada no lleva los engranajes del backtank a la espalda. */
    public static final PartialModel SUIT_TANK_NO_COGS = PartialModel.of(CreateUniversal.id("block/space_suit_tank/no_cogs"));

    private CUPartialModels() {}

    public static void init() {
    }
}
