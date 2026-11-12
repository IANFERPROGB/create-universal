package dev.createuniversal.space;

import dev.createuniversal.CreateUniversal;
import dev.createuniversal.network.ParkedShipsPayload;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Naves aparcadas con un Estabilizador orbital (alpha.11.3, idea 22): dónde se quedaron, para verlas en el mapa, la mesa,
 * el proyector y Xaero aunque estén lejos y sin cargar. Se guarda con el mundo.
 *
 * Una nave entra en la lista cuando su estabilizador la fija. Sale cuando está cargada y lleva dos comprobaciones seguidas
 * (de 5 s) sin estar fijada (se apagó el estabilizador o la nave quedó destruida), o cuando alguien la quita del mapa.
 * Si la nave no está cargada, se queda en la lista: sigue aparcada donde estaba.
 */
public final class ParkedShips extends SavedData {
    private static final String NAME = CreateUniversal.MOD_ID + "_parked_ships";
    private static final int CHECK_TICKS = 100;

    public record Ship(UUID id, String name, ResourceKey<Level> dimension, Vec3 pos) {}

    private final Map<UUID, Ship> ships = new LinkedHashMap<>();
    /** Naves cargadas que la última comprobación vio sin fijar. */
    private static final Set<UUID> STRIKES = new HashSet<>();

    private static ParkedShips get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(ParkedShips::new, ParkedShips::load, null), NAME);
    }

    /** El estabilizador acaba de fijar esta nave. */
    public static void park(ServerLevel level, ServerSubLevel ship) {
        ParkedShips data = get(level.getServer());
        UUID id = ship.getUniqueId();
        STRIKES.remove(id);
        String name = ship.getName() == null ? "" : ship.getName();
        Ship now = new Ship(id, name, level.dimension(), OrbitalFrame.centerOf(ship));
        Ship old = data.ships.get(id);
        if (old != null && old.dimension() == now.dimension() && old.name().equals(name) && old.pos().distanceToSqr(now.pos()) < 1.0) {
            return;
        }
        data.ships.put(id, now);
        data.setDirty();
        broadcast(level.getServer(), data);
    }

    /** Alguien la quita del mapa. */
    public static void remove(ServerPlayer player, String id) {
        ParkedShips data = get(player.getServer());
        try {
            if (data.ships.remove(UUID.fromString(id)) != null) {
                data.setDirty();
                broadcast(player.getServer(), data);
            }
        } catch (IllegalArgumentException ignored) {
            // id mal formado: nada que quitar.
        }
    }

    private static ParkedShipsPayload payload(ParkedShips data) {
        List<ParkedShipsPayload.Ship> list = new ArrayList<>();
        for (Ship s : data.ships.values()) {
            list.add(new ParkedShipsPayload.Ship(s.id().toString(), s.name(), s.dimension().location(), s.pos().x, s.pos().y, s.pos().z));
        }
        return new ParkedShipsPayload(list);
    }

    private static void broadcast(MinecraftServer server, ParkedShips data) {
        PacketDistributor.sendToAllPlayers(payload(data));
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            PacketDistributor.sendToPlayer(p, payload(get(p.getServer())));
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % CHECK_TICKS != 0) {
            return;
        }
        ParkedShips data = get(server);
        boolean changed = false;
        for (Ship s : new ArrayList<>(data.ships.values())) {
            ServerLevel level = server.getLevel(s.dimension());
            ServerSubLevelContainer container = level != null ? SubLevelContainer.getContainer(level) : null;
            SubLevel sub = container != null ? container.getSubLevel(s.id()) : null;
            if (!(sub instanceof ServerSubLevel ship) || ship.isRemoved() || OrbitalStabilizers.isPinned(ship)) {
                STRIKES.remove(s.id());
                continue;
            }
            if (!STRIKES.add(s.id())) {
                STRIKES.remove(s.id());
                data.ships.remove(s.id());
                changed = true;
            }
        }
        if (changed) {
            data.setDirty();
            broadcast(server, data);
        }
    }

    private static ParkedShips load(CompoundTag tag, HolderLookup.Provider registries) {
        ParkedShips data = new ParkedShips();
        for (Tag t : tag.getList("ships", Tag.TAG_COMPOUND)) {
            CompoundTag s = (CompoundTag) t;
            ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(s.getString("dim")));
            UUID id = s.getUUID("id");
            data.ships.put(id, new Ship(id, s.getString("name"), dim, new Vec3(s.getDouble("x"), s.getDouble("y"), s.getDouble("z"))));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Ship s : ships.values()) {
            CompoundTag t = new CompoundTag();
            t.putUUID("id", s.id());
            t.putString("name", s.name());
            t.putString("dim", s.dimension().location().toString());
            t.putDouble("x", s.pos().x);
            t.putDouble("y", s.pos().y);
            t.putDouble("z", s.pos().z);
            list.add(t);
        }
        tag.put("ships", list);
        return tag;
    }
}
