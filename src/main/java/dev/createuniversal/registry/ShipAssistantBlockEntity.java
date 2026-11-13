package dev.createuniversal.registry;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.createuniversal.network.AssistantHudPayload;
import dev.createuniversal.space.Autopilot;
import dev.createuniversal.space.Breathing;
import dev.createuniversal.space.CUDimensions;
import dev.createuniversal.space.ShipAssistant;
import dev.createuniversal.space.SpaceLayout;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Vector3d;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Asistente de nave (alpha.12, D61, D64). Puesto en una nave de Aeronautics/Sable, cada segundo la revisa
 * ({@link ShipAssistant#scan}) y manda a todos los que van a bordo el panel de arriba de la pantalla: velocidad, motores,
 * combustible, casco y aire. Su giroscopio necesita al menos 64 RPM y gasta 24 SU por RPM.
 *
 * Con el piloto automático puesto (alpha.12.1, {@link Autopilot}) lleva la nave al destino: cada tick decide hacia dónde
 * empujar, gira la nave con el giroscopio y da potencia a los propulsores principales (a los de "Otros", con su salida de
 * redstone). Se desconecta si alguien cambia la redstone de un propulsor (tocar los controles), al llegar, o si se apaga.
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

    // ---- piloto automático
    private boolean engaged;
    private String target = "";
    private Vec3 fixed;
    /** Aceleración medida a tope (bloques/s²); empieza con una suposición prudente. */
    private double accel = 3.0;
    private String pilotStatus = "";
    private long pilotStatusUntil;
    private double eta = -1;
    private int redstoneOut;
    private int arrivedTicks;
    private Vec3 lastVel;
    private double lastThrottle;
    private Vector3d lastThrust;
    /** Señal de redstone de cada propulsor al encender el piloto: si cambia, alguien ha tocado los controles. */
    private final Map<BlockPos, Integer> manual = new HashMap<>();

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

    public int redstoneOut() {
        return redstoneOut;
    }

    @Override
    public void tick() {
        super.tick();
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        ServerSubLevel ship = ship();
        if (engaged) {
            pilotTick(server, ship);
        }
        if (++timer < 20) {
            return;
        }
        timer = 0;
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
        String shown;
        if (engaged || server.getGameTime() < pilotStatusUntil) {
            shown = pilotStatus.isEmpty() ? "ready" : pilotStatus;
        } else {
            shown = status().name().toLowerCase(Locale.ROOT);
        }
        ShipAssistant.sendHud(server, ship, new AssistantHudPayload(shown, engaged ? target : "",
                (float) ShipAssistant.speed(server, ship), last.working(marked), last.total(marked), last.fuel(), last.fuelCapacity(),
                hull, Breathing.hasAirAround(server, worldPosition), engaged ? (float) eta : -1.0F));
    }

    // ---------------------------------------------------------------- piloto automático

    /** Enciende el piloto hacia un destino (claves del mapa; {@code fixed} es la posición si no es un astro). */
    public void engage(String target, Vec3 fixed) {
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        ServerSubLevel ship = ship();
        if (ship == null || level.dimension() != CUDimensions.SPACE) {
            note("not_in_space");
            return;
        }
        if (last == null) {
            last = ShipAssistant.scan(server, ship);
        }
        this.target = target;
        this.fixed = fixed;
        this.engaged = true;
        this.arrivedTicks = 0;
        this.lastVel = null;
        manual.clear();
        for (ShipAssistant.Engine e : last.engines()) {
            manual.put(e.pos(), level.getBestNeighborSignal(e.pos()));
        }
        pilotStatus = "turning";
        setChanged();
    }

    public void disengage(String reason) {
        if (engaged && level instanceof ServerLevel && last != null) {
            for (ShipAssistant.Engine e : last.engines()) {
                ShipAssistant.setPower(level.getBlockEntity(e.pos()), -1.0F);
            }
        }
        ServerSubLevel ship = ship();
        if (ship != null) {
            Autopilot.release(ship.getUniqueId());
        }
        engaged = false;
        eta = -1;
        setRedstone(0);
        note(reason);
        setChanged();
    }

    private void note(String status) {
        pilotStatus = status;
        pilotStatusUntil = level != null ? level.getGameTime() + 200 : 0;
    }

    private void pilotTick(ServerLevel server, ServerSubLevel ship) {
        if (ship == null || ship.isRemoved() || server.dimension() != CUDimensions.SPACE) {
            disengage("not_in_space");
            return;
        }
        if (last == null) {
            return;
        }
        if (manual.isEmpty()) {
            // Tras cargar el mundo con el piloto puesto: se vuelve a tomar la señal de cada propulsor.
            for (ShipAssistant.Engine e : last.engines()) {
                manual.put(e.pos(), server.getBestNeighborSignal(e.pos()));
            }
        }
        if (server.getGameTime() % 5 == 0) {
            for (Map.Entry<BlockPos, Integer> m : manual.entrySet()) {
                if (server.getBestNeighborSignal(m.getKey()) != m.getValue()) {
                    disengage("manual");
                    return;
                }
            }
        }
        if (status() != Status.READY) {
            powerAll(null, 0.0);
            Autopilot.release(ship.getUniqueId());
            pilotStatus = "no_rotation";
            return;
        }
        Autopilot.Goal goal = Autopilot.goal(target, fixed, SpaceLayout.time(server));
        if (goal == null) {
            disengage("lost");
            return;
        }
        Direction main = mainDirection();
        if (main == null) {
            powerAll(null, 0.0);
            pilotStatus = "no_engines";
            return;
        }
        // Mide lo que de verdad acelera la nave a tope (cambio de velocidad en la dirección del empuje).
        Vec3 v = Autopilot.velocity(server, ship);
        if (lastVel != null && lastThrust != null && lastThrottle > 0.4) {
            Vec3 dv = v.subtract(lastVel);
            double a = (dv.x * lastThrust.x + dv.y * lastThrust.y + dv.z * lastThrust.z) * 20.0 / lastThrottle;
            if (a > 0.05 && a < 500.0) {
                accel = accel * 0.92 + a * 0.08;
            }
        }
        lastVel = v;
        Autopilot.Command c = Autopilot.guide(server, ship, goal, main, accel, getSpeed());
        powerAll(main, c.throttle());
        lastThrottle = c.throttle();
        lastThrust = c.thrustWorld();
        eta = c.eta();
        pilotStatus = c.status();
        if (c.status().equals("arrived")) {
            if (++arrivedTicks > 40) {
                disengage("arrived");
            }
        } else {
            arrivedTicks = 0;
        }
    }

    /** El lado de la nave hacia el que más empujan sus propulsores (contando cuánto rinden aquí). */
    private Direction mainDirection() {
        EnumMap<Direction, Integer> sum = new EnumMap<>(Direction.class);
        for (ShipAssistant.Engine e : last.engines()) {
            if (e.efficiency() > 0) {
                sum.merge(e.push(), e.efficiency(), Integer::sum);
            }
        }
        Direction best = null;
        int bestSum = 0;
        for (Map.Entry<Direction, Integer> s : sum.entrySet()) {
            if (s.getValue() > bestSum) {
                best = s.getKey();
                bestSum = s.getValue();
            }
        }
        return best;
    }

    /** Potencia a los propulsores que empujan hacia {@code main} y nada a los demás; lo mismo por la redstone. */
    private void powerAll(Direction main, double throttle) {
        for (ShipAssistant.Engine e : last.engines()) {
            ShipAssistant.setPower(level.getBlockEntity(e.pos()), e.push() == main ? (float) throttle : 0.0F);
        }
        setRedstone((int) Math.round(Math.max(0.0, Math.min(1.0, throttle)) * 15.0));
    }

    private void setRedstone(int power) {
        if (power != redstoneOut && level != null) {
            redstoneOut = power;
            level.updateNeighborsAt(worldPosition, getBlockState().getBlock());
        }
    }

    @Override
    public void remove() {
        if (engaged) {
            disengage("stopped");
        }
        super.remove();
    }

    // ---------------------------------------------------------------- pantalla

    /** Manda a este jugador la ficha de la nave (abre su pantalla). */
    public void openFor(ServerPlayer player) {
        ServerSubLevel ship = ship();
        if (ship == null) {
            player.displayClientMessage(Component.translatable("create_universal.assistant.status.not_on_ship")
                    .withStyle(ChatFormatting.GOLD), true);
            return;
        }
        last = ShipAssistant.scan((ServerLevel) level, ship);
        PacketDistributor.sendToPlayer(player, ShipAssistant.info(worldPosition, last, marked, status() == Status.READY, engaged));
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
        tag.putInt("RedstoneOut", redstoneOut);
        if (!clientPacket) {
            tag.putBoolean("Engaged", engaged);
            tag.putString("Target", target);
            tag.putDouble("Accel", accel);
            if (fixed != null) {
                tag.putDouble("FixedX", fixed.x);
                tag.putDouble("FixedY", fixed.y);
                tag.putDouble("FixedZ", fixed.z);
            }
        }
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        marked.clear();
        for (Tag t : tag.getList("Marked", Tag.TAG_STRING)) {
            marked.add(t.getAsString());
        }
        maxMass = tag.getDouble("MaxMass");
        redstoneOut = tag.getInt("RedstoneOut");
        if (!clientPacket) {
            engaged = tag.getBoolean("Engaged");
            target = tag.getString("Target");
            accel = tag.contains("Accel") ? tag.getDouble("Accel") : 3.0;
            fixed = tag.contains("FixedX") ? new Vec3(tag.getDouble("FixedX"), tag.getDouble("FixedY"), tag.getDouble("FixedZ")) : null;
        }
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
