package dev.createuniversal;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Configuración común (servidor y cliente). Se ampliará en cada alpha. */
public final class CUConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue EXIT_ALTITUDE;
    public static final ModConfigSpec.IntValue DEBRIS_MINUTES;
    public static final ModConfigSpec.IntValue SHIPS_PER_PLAYER;
    public static final ModConfigSpec.IntValue ROCK_COLLISION_CHUNKS;
    public static final ModConfigSpec.IntValue MAX_ACTIVE_ROCKS;
    public static final ModConfigSpec.IntValue ROCK_RENDER_DISTANCE;
    public static final ModConfigSpec.DoubleValue ROCK_DENSITY;
    public static final ModConfigSpec.DoubleValue RING_SPEED;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("space");
        EXIT_ALTITUDE = b
                .comment("Altura del Overworld a la que se sale al espacio (D1). / Overworld height at which you leave for space.")
                .defineInRange("exitAltitude", 15000, 400, 30_000_000);
        DEBRIS_MINUTES = b
                .comment("Minutos que duran los restos de una nave destruida y sus objetos (D44). / Minutes the debris of a destroyed ship and its items last.")
                .defineInRange("debrisMinutes", 60, 1, 10_080);
        SHIPS_PER_PLAYER = b
                .comment("Naves de cada jugador que se mantienen cargadas en el espacio aunque no haya nadie cerca (D70). / Ships per player kept loaded in space with nobody around.")
                .defineInRange("shipsPerPlayer", 10, 0, 100);
        b.pop();
        b.push("rocks");
        ROCK_COLLISION_CHUNKS = b
                .comment("Chunks alrededor de un jugador en los que las rocas del espacio tienen colisión (D14). / Chunks around a player where space rocks have collision.")
                .defineInRange("collisionChunks", 6, 1, 32);
        MAX_ACTIVE_ROCKS = b
                .comment("Máximo de rocas con colisión a la vez. / Maximum rocks with collision at once.")
                .defineInRange("maxActive", 48, 1, 512);
        ROCK_RENDER_DISTANCE = b
                .comment("Distancia (bloques) a la que se dibujan las rocas. / Distance (blocks) at which rocks are drawn.")
                .defineInRange("renderDistance", 800, 128, 8000);
        ROCK_DENSITY = b
                .comment("Cuántas rocas hay (1 = normal). / How many rocks there are (1 = normal).")
                .defineInRange("density", 1.0, 0.0, 10.0);
        RING_SPEED = b
                .comment("Velocidad de giro de los anillos (1 = como una órbita en esa zona, 0 = quietos). / Ring spin speed (1 = like an orbit there, 0 = still).")
                .defineInRange("ringSpeed", 1.0, 0.0, 10.0);
        b.pop();
        SPEC = b.build();
    }

    private CUConfig() {}
}
