package dev.createuniversal.space;

import com.simibubi.create.content.contraptions.AssemblyException;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;

/** D38: en el espacio no se pueden desensamblar las estructuras de Create Aeronautics/Sable. */
public final class NoDisassemblyInSpace {
    private NoDisassemblyInSpace() {}

    public static boolean blocked(Level level) {
        return level != null && level.dimension() == CUDimensions.SPACE;
    }

    /** Error que ven los jugadores en el Ensamblador de física o al usar el Assembler Stick. */
    public static AssemblyException exception() {
        return new AssemblyException(Component.translatable("create_universal.disassembly.blocked_in_space"));
    }
}
