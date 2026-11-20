package dev.createuniversal.space;

import dev.createuniversal.CUConfig;
import dev.createuniversal.CreateUniversal;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelObserver;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;
import net.neoforged.neoforge.common.world.chunk.TicketController;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Naves siempre cargadas y avisos al dueño (alpha.13.2, D69, D70).
 *
 * <ul>
 *   <li><b>Dueño:</b> quien ensambló la nave (el jugador más cerca cuando aparece la nave; si no había nadie, el primero
 *       que suba a bordo). Se guarda en la nave.</li>
 *   <li><b>Siempre cargadas (solo en el espacio):</b> la zona interna de cada nave con dueño queda cargada aunque no haya
 *       nadie cerca, así sus máquinas y motores siguen funcionando. Como mucho {@code shipsPerPlayer} naves por jugador
 *       (10 por defecto); si tiene más, se le avisa y las demás se cargan y descargan como siempre.</li>
 *   <li><b>Avisos al dueño, esté donde esté:</b> su nave va a chocar con un astro, el Sol o un campo de rocas (2 minutos
 *       antes), se queda sin combustible u oxígeno (5 minutos antes) o ha sido destruida (con el punto de los restos).</li>
 * </ul>
 */
public final class ShipKeeper {
    public static final String OWNER = "owner";
    private static final int CHECK_TICKS = 100;
    private static final double OWNER_RADIUS = 48.0;
    private static final double IMPACT_WARN = 120.0;
    private static final double SUPPLY_WARN = 300.0;

    public static final TicketController TICKETS = new TicketController(CreateUniversal.id("ships"),
            // Al arrancar se quitan todos: el primer repaso vuelve a poner los que tocan.
            (level, helper) -> new ArrayList<>(helper.getEntityTickets().keySet()).forEach(helper::removeAllTickets));

    /** Chunks cargados ahora por cada nave (clave del grupo). */
    private static final Map<UUID, Set<Long>> LOADED = new HashMap<>();
    /** Avisos ya dados (nave + tipo), para no repetirlos: hasta cuándo callar (tiempo del Overworld). */
    private static final Map<String, Long> WARNED = new HashMap<>();
    private static final Set<UUID> OVER_LIMIT_WARNED = new HashSet<>();

    private ShipKeeper() {}

    public static void registerTickets(RegisterTicketControllersEvent event) {
        event.register(TICKETS);
    }

    /** Engancha el reparto de dueño a cada contenedor de naves que se crea. */
    public static void register() {
        dev.ryanhcode.sable.platform.SableEventPlatform.INSTANCE.onSubLevelContainerReady((level, container) -> {
            if (level instanceof ServerLevel server) {
                container.addObserver(new SubLevelObserver() {
                    @Override
                    public void onSubLevelAdded(SubLevel subLevel) {
                        if (subLevel instanceof ServerSubLevel ship && owner(ship) == null) {
                            server.getServer().execute(() -> assignNearest(server, ship));
                        }
                    }
                });
            }
        });
    }

    // ---------------------------------------------------------------- dueño

    public static UUID owner(ServerSubLevel ship) {
        CompoundTag ud = ship.getUserDataTag();
        if (ud == null) {
            return null;
        }
        CompoundTag ours = ud.getCompound(CreateUniversal.MOD_ID);
        return ours.hasUUID(OWNER) ? ours.getUUID(OWNER) : null;
    }

    private static void setOwner(ServerSubLevel ship, UUID owner) {
        CompoundTag ud = ship.getUserDataTag() != null ? ship.getUserDataTag() : new CompoundTag();
        CompoundTag ours = ud.getCompound(CreateUniversal.MOD_ID);
        ours.putUUID(OWNER, owner);
        ud.put(CreateUniversal.MOD_ID, ours);
        ship.setUserDataTag(ud);
    }

    /** El dueño del grupo: el de cualquiera de sus piezas. */
    public static UUID owner(List<ServerSubLevel> group) {
        for (ServerSubLevel s : group) {
            UUID o = owner(s);
            if (o != null) {
                return o;
            }
        }
        return null;
    }

    private static void assignNearest(ServerLevel level, ServerSubLevel ship) {
        if (ship.isRemoved() || owner(ship) != null || SpaceRocks.isRock(ship) || Moonlets.isMoonlet(ship) || ShipDestruction.isDebris(ship)) {
            return;
        }
        Vec3 c = OrbitalFrame.centerOf(ship);
        ServerPlayer best = null;
        double bestD = OWNER_RADIUS;
        for (ServerPlayer p : level.players()) {
            double d = dev.ryanhcode.sable.Sable.HELPER.projectOutOfSubLevel(level, p.position()).distanceTo(c);
            if (d < bestD) {
                best = p;
                bestD = d;
            }
        }
        if (best != null) {
            setOwner(ship, best.getUUID());
        }
    }

    // ---------------------------------------------------------------- repaso

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % CHECK_TICKS != 0) {
            return;
        }
        ServerLevel space = server.getLevel(CUDimensions.SPACE);
        ServerSubLevelContainer container = space != null ? SubLevelContainer.getContainer(space) : null;
        if (container == null) {
            return;
        }
        int limit = CUConfig.SHIPS_PER_PLAYER.get();
        Map<UUID, List<List<ServerSubLevel>>> byOwner = new HashMap<>();
        Set<UUID> seen = new HashSet<>();
        for (ServerSubLevel s : new ArrayList<>(container.getAllSubLevels())) {
            if (s.isRemoved() || seen.contains(s.getUniqueId()) || Moonlets.isMoonlet(s)) {
                continue;
            }
            List<ServerSubLevel> group = ShipGroup.of(s);
            group.forEach(g -> seen.add(g.getUniqueId()));
            if (ShipDestruction.isDebris(group)) {
                continue;
            }
            UUID owner = owner(group);
            if (owner == null) {
                // Sin dueño (no había nadie al ensamblarla): el primer jugador que va a bordo.
                for (Entity e : ShipDestruction.aboard(space, group)) {
                    if (e instanceof ServerPlayer p) {
                        setOwner(group.get(0), p.getUUID());
                        owner = p.getUUID();
                        break;
                    }
                }
            }
            if (owner != null) {
                byOwner.computeIfAbsent(owner, k -> new ArrayList<>()).add(group);
            }
        }

        Map<UUID, Set<Long>> wanted = new HashMap<>();
        for (Map.Entry<UUID, List<List<ServerSubLevel>>> e : byOwner.entrySet()) {
            List<List<ServerSubLevel>> ships = e.getValue();
            ships.sort(Comparator.comparing(ShipSupplies::key));
            if (ships.size() > limit) {
                if (OVER_LIMIT_WARNED.add(e.getKey())) {
                    tell(server, e.getKey(), Component.translatable("create_universal.keeper.over_limit", ships.size(), limit)
                            .withStyle(ChatFormatting.GOLD));
                }
            } else {
                OVER_LIMIT_WARNED.remove(e.getKey());
            }
            for (int i = 0; i < ships.size(); i++) {
                List<ServerSubLevel> group = ships.get(i);
                if (i < limit) {
                    Set<Long> chunks = new HashSet<>();
                    for (ServerSubLevel s : group) {
                        var plot = s.getPlot();
                        for (int cx = plot.getChunkMin().x; cx <= plot.getChunkMax().x; cx++) {
                            for (int cz = plot.getChunkMin().z; cz <= plot.getChunkMax().z; cz++) {
                                chunks.add(ChunkPos.asLong(cx, cz));
                            }
                        }
                    }
                    wanted.put(ShipSupplies.key(group), chunks);
                }
                warn(server, space, e.getKey(), group);
            }
        }

        // Quitar lo que ya no toca y poner lo nuevo.
        for (Map.Entry<UUID, Set<Long>> old : LOADED.entrySet()) {
            Set<Long> keep = wanted.getOrDefault(old.getKey(), Set.of());
            for (long c : old.getValue()) {
                if (!keep.contains(c)) {
                    TICKETS.forceChunk(space, old.getKey(), ChunkPos.getX(c), ChunkPos.getZ(c), false, true);
                }
            }
        }
        for (Map.Entry<UUID, Set<Long>> now : wanted.entrySet()) {
            Set<Long> had = LOADED.getOrDefault(now.getKey(), Set.of());
            for (long c : now.getValue()) {
                if (!had.contains(c)) {
                    TICKETS.forceChunk(space, now.getKey(), ChunkPos.getX(c), ChunkPos.getZ(c), true, true);
                }
            }
        }
        LOADED.clear();
        LOADED.putAll(wanted);
        long now = server.overworld().getGameTime();
        WARNED.values().removeIf(until -> until < now);
    }

    /** Avisos de choque y de combustible u oxígeno para el dueño (una vez por cosa, durante unos minutos). */
    private static void warn(MinecraftServer server, ServerLevel space, UUID owner, List<ServerSubLevel> group) {
        Component name = shipName(group);
        String key = ShipSupplies.key(group).toString();
        long now = server.overworld().getGameTime();
        SpaceSleep.Danger danger = SpaceSleep.danger(space, group, IMPACT_WARN);
        if (danger != null && !WARNED.containsKey(key + ":impact")) {
            WARNED.put(key + ":impact", now + 20L * 60 * 3);
            tell(server, owner, Component.translatable("create_universal.keeper.danger", name, danger.what()).withStyle(ChatFormatting.RED));
        }
        ShipSupplies.Rates r = ShipSupplies.rates(group);
        if (r.fuelRate() > 0 && r.fuel() / r.fuelRate() < SUPPLY_WARN && !WARNED.containsKey(key + ":fuel")) {
            WARNED.put(key + ":fuel", now + 20L * 60 * 10);
            tell(server, owner, Component.translatable("create_universal.keeper.fuel", name, Math.max(1, Math.round(r.fuel() / r.fuelRate() / 60.0)))
                    .withStyle(ChatFormatting.GOLD));
        }
        if (r.oxygenRate() > 0 && r.oxygen() / r.oxygenRate() < SUPPLY_WARN && !WARNED.containsKey(key + ":oxygen")) {
            WARNED.put(key + ":oxygen", now + 20L * 60 * 10);
            tell(server, owner, Component.translatable("create_universal.keeper.oxygen", name,
                    Math.max(1, Math.round(r.oxygen() / r.oxygenRate() / 60.0))).withStyle(ChatFormatting.GOLD));
        }
    }

    /** La nave fue destruida: aviso al dueño (los restos ya le salen marcados, ver {@link ShipDestruction}). */
    public static void destroyed(MinecraftServer server, List<ServerSubLevel> group) {
        UUID owner = owner(group);
        if (owner != null) {
            tell(server, owner, Component.translatable("create_universal.keeper.destroyed", shipName(group)).withStyle(ChatFormatting.RED));
        }
    }

    public static Component shipName(List<ServerSubLevel> group) {
        for (ServerSubLevel s : group) {
            if (s.getName() != null && !s.getName().isBlank()) {
                return Component.literal(s.getName());
            }
        }
        return Component.translatable("create_universal.keeper.your_ship");
    }

    private static void tell(MinecraftServer server, UUID player, Component message) {
        ServerPlayer p = server.getPlayerList().getPlayer(player);
        if (p != null) {
            p.displayClientMessage(message, false);
        }
    }
}
