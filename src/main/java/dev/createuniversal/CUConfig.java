package dev.createuniversal;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Configuración común (servidor y cliente). Se ampliará en cada alpha. */
public final class CUConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue EXIT_ALTITUDE;
    public static final ModConfigSpec.IntValue DEBRIS_MINUTES;
    public static final ModConfigSpec.IntValue SHIPS_PER_PLAYER;
    public static final ModConfigSpec.IntValue NO_CHUNKS_ABOVE;
    public static final ModConfigSpec.IntValue SLEEP_MINUTES;
    public static final ModConfigSpec.IntValue SLEEP_COOLDOWN_MINUTES;
    public static final ModConfigSpec.DoubleValue CRUISE_FACTOR;
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
        NO_CHUNKS_ABOVE = b
                .comment("En el espacio, por encima de esta velocidad (b/s) y lejos de astros y rocas no se cargan chunks alrededor del jugador (D94). 0 = siempre se cargan. / In space, above this speed and far from bodies and rocks, no chunks are loaded around the player. 0 = always loaded.")
                .defineInRange("noChunksAboveSpeed", 99, 0, 100_000);
        SLEEP_MINUTES = b
                .comment("Minutos reales que se saltan al dormir en el espacio (D54). / Real minutes skipped when sleeping in space.")
                .defineInRange("sleepMinutes", 30, 1, 600);
        SLEEP_COOLDOWN_MINUTES = b
                .comment("Minutos reales de espera para volver a dormir en el espacio (D54). / Real minutes before you can sleep in space again.")
                .defineInRange("sleepCooldownMinutes", 30, 0, 600);
        CRUISE_FACTOR = b
                .comment("Cuánto más empujan los propulsores con el Motor de crucero en marcha (D95). / How much harder thrusters push with the Cruise Drive running.")
                .defineInRange("cruiseFactor", 4.0, 1.0, 50.0);
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
