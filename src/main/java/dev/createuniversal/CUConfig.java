package dev.createuniversal;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Configuración común (servidor y cliente). Se ampliará en cada alpha. */
public final class CUConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue EXIT_ALTITUDE;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("space");
        EXIT_ALTITUDE = b
                .comment("Altura del Overworld a la que se sale al espacio (D1). / Overworld height at which you leave for space.")
                .defineInRange("exitAltitude", 15000, 400, 30_000_000);
        b.pop();
        SPEC = b.build();
    }

    private CUConfig() {}
}
