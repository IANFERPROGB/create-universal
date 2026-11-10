package dev.createuniversal;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Configuración común (servidor y cliente). Se ampliará en cada alpha. */
public final class CUConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue EXIT_ALTITUDE;
    public static final ModConfigSpec.IntValue DEBRIS_MINUTES;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("space");
        EXIT_ALTITUDE = b
                .comment("Altura del Overworld a la que se sale al espacio (D1). / Overworld height at which you leave for space.")
                .defineInRange("exitAltitude", 15000, 400, 30_000_000);
        DEBRIS_MINUTES = b
                .comment("Minutos que duran los restos de una nave destruida y sus objetos (D44). / Minutes the debris of a destroyed ship and its items last.")
                .defineInRange("debrisMinutes", 60, 1, 10_080);
        b.pop();
        SPEC = b.build();
    }

    private CUConfig() {}
}
