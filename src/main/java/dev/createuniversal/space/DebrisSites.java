package dev.createuniversal.space;

import dev.createuniversal.CreateUniversal;
import dev.createuniversal.network.DebrisMarkersPayload;
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
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Dónde quedaron los restos de cada nave destruida (D45), para el punto de ruta en la pantalla de quien iba a bordo o
 * estaba cerca. Se guarda con el mundo y caduca a la vez que los restos.
 */
public final class DebrisSites extends SavedData {
    private static final String NAME = CreateUniversal.MOD_ID + "_debris";

    public record Site(ResourceKey<Level> dimension, Vec3 pos, long expires, Set<UUID> players) {}

    private final List<Site> sites = new ArrayList<>();

    private static DebrisSites get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(DebrisSites::new, DebrisSites::load, null), NAME);
    }

    public static void add(ServerLevel level, Vec3 pos, long expires, List<UUID> players) {
        DebrisSites data = get(level.getServer());
        data.sites.add(new Site(level.dimension(), pos, expires, new HashSet<>(players)));
        data.setDirty();
        for (UUID id : players) {
            if (level.getServer().getPlayerList().getPlayer(id) instanceof ServerPlayer p) {
                send(p, data);
            }
        }
    }

    /** El jugador ya no quiere ver estos restos (lo quita desde el mapa, alpha.11.2.1). Los restos siguen ahí. */
    public static void dismiss(ServerPlayer player, double x, double y, double z) {
        DebrisSites data = get(player.getServer());
        Vec3 at = new Vec3(x, y, z);
        boolean changed = false;
        for (Site s : data.sites) {
            if (s.pos().distanceToSqr(at) < 4.0 && s.players().remove(player.getUUID())) {
                changed = true;
            }
        }
        if (changed) {
            data.sites.removeIf(s -> s.players().isEmpty());
            data.setDirty();
        }
        send(player, data);
    }

    /** Quita los caducados. */
    public static void prune(MinecraftServer server) {
        DebrisSites data = get(server);
        long now = server.overworld().getGameTime();
        if (data.sites.removeIf(s -> s.expires() <= now)) {
            data.setDirty();
        }
    }

    private static void send(ServerPlayer player, DebrisSites data) {
        List<DebrisMarkersPayload.Marker> markers = new ArrayList<>();
        for (Site s : data.sites) {
            if (s.players().contains(player.getUUID())) {
                markers.add(new DebrisMarkersPayload.Marker(s.dimension().location(), s.pos().x, s.pos().y, s.pos().z, s.expires()));
            }
        }
        PacketDistributor.sendToPlayer(player, new DebrisMarkersPayload(markers));
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            send(p, get(p.getServer()));
        }
    }

    private static DebrisSites load(CompoundTag tag, HolderLookup.Provider registries) {
        DebrisSites data = new DebrisSites();
        for (Tag t : tag.getList("sites", Tag.TAG_COMPOUND)) {
            CompoundTag s = (CompoundTag) t;
            Set<UUID> players = new HashSet<>();
            for (Tag p : s.getList("players", Tag.TAG_INT_ARRAY)) {
                players.add(net.minecraft.nbt.NbtUtils.loadUUID(p));
            }
            ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(s.getString("dim")));
            data.sites.add(new Site(dim, new Vec3(s.getDouble("x"), s.getDouble("y"), s.getDouble("z")), s.getLong("expires"), players));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Site s : sites) {
            CompoundTag t = new CompoundTag();
            t.putString("dim", s.dimension().location().toString());
            t.putDouble("x", s.pos().x);
            t.putDouble("y", s.pos().y);
            t.putDouble("z", s.pos().z);
            t.putLong("expires", s.expires());
            ListTag players = new ListTag();
            for (UUID id : s.players()) {
                players.add(net.minecraft.nbt.NbtUtils.createUUID(id));
            }
            t.put("players", players);
            list.add(t);
        }
        tag.put("sites", list);
        return tag;
    }
}
