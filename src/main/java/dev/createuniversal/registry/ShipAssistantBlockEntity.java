package dev.createuniversal.registry;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.createuniversal.network.AssistantHudPayload;
import dev.createuniversal.space.Breathing;
import dev.createuniversal.space.ShipAssistant;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Asistente de nave (alpha.12, D61, D64). Puesto en una nave de Aeronautics/Sable, cada segundo la revisa
 * ({@link ShipAssistant#scan}) y manda a todos los que van a bordo el panel de arriba de la pantalla: velocidad, motores,
 * combustible, casco y aire. Su giroscopio (para el piloto automático) necesita al menos 64 RPM y gasta 24 SU por RPM.
 */
public class ShipAssistantBlockEntity extends KineticBlockEntity {
    public static final float MIN_RPM = 64.0F;

    public enum Status { NOT_ON_SHIP, NO_ROTATION, READY }

    /** Tipos de bloque de "Otros" que el jugador marcó como motores. */
    private final Set<String> marked = new TreeSet<>();
    /** La mayor masa que se le ha visto a la nave (para el estado del casco). */
    private double maxMass;
    private ShipAssistant.Result last;
    private int timer;

    public ShipAssistantBlockEntity(BlockPos pos, BlockState state) {
        super(CUMachines.SHIP_ASSISTANT_BE.get(), pos, state);
    }

    public Status status() {
        if (level == null || Sable.HELPER.getContaining(level, worldPosition) == null) {
            return Status.NOT_ON_SHIP;
        }
        if (Math.abs(getSpeed()) < MIN_RPM || isOverStressed()) {
            return Status.NO_ROTATION;
        }
        return Status.READY;
    }

    private ServerSubLevel ship() {
        return level instanceof ServerLevel && Sable.HELPER.getContaining(level, worldPosition) instanceof ServerSubLevel s ? s : null;
    }

    @Override
    public void tick() {
        super.tick();
        if (!(level instanceof ServerLevel server) || ++timer < 20) {
            return;
        }
        timer = 0;
        ServerSubLevel ship = ship();
        if (ship == null || ship.isRemoved()) {
            last = null;
            return;
        }
        last = ShipAssistant.scan(server, ship);
        if (last.mass() > maxMass) {
            maxMass = last.mass();
            setChanged();
        }
        int hull = maxMass > 0 ? (int) Math.round(100.0 * last.mass() / maxMass) : 100;
        ShipAssistant.sendHud(server, ship, new AssistantHudPayload(status().name().toLowerCase(Locale.ROOT), "",
                (float) ShipAssistant.speed(server, ship), last.working(marked), last.total(marked), last.fuel(), last.fuelCapacity(),
                hull, Breathing.hasAirAround(server, worldPosition), -1.0F));
    }

    /** Manda a este jugador la ficha de la nave (abre su pantalla). */
    public void openFor(ServerPlayer player) {
        ServerSubLevel ship = ship();
        if (ship == null) {
            player.displayClientMessage(Component.translatable("create_universal.assistant.status.not_on_ship")
                    .withStyle(ChatFormatting.GOLD), true);
            return;
        }
        last = ShipAssistant.scan((ServerLevel) level, ship);
        PacketDistributor.sendToPlayer(player, ShipAssistant.info(worldPosition, last, marked, status() == Status.READY));
    }

    public void toggleMark(String block) {
        if (!marked.remove(block)) {
            marked.add(block);
        }
        setChanged();
    }

    public Set<String> marked() {
        return marked;
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        ListTag list = new ListTag();
        for (String s : marked) {
            list.add(StringTag.valueOf(s));
        }
        tag.put("Marked", list);
        tag.putDouble("MaxMass", maxMass);
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        marked.clear();
        for (Tag t : tag.getList("Marked", Tag.TAG_STRING)) {
            marked.add(t.getAsString());
        }
        maxMass = tag.getDouble("MaxMass");
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        super.addToGoggleTooltip(tooltip, isPlayerSneaking);
        Status status = status();
        ChatFormatting color = status == Status.READY ? ChatFormatting.AQUA : ChatFormatting.GOLD;
        tooltip.add(Component.literal("    ").append(Component.translatable(
                "create_universal.assistant.status." + status.name().toLowerCase(Locale.ROOT), (int) MIN_RPM).withStyle(color)));
        return true;
    }
}
