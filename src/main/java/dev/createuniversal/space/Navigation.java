package dev.createuniversal.space;

import dev.createuniversal.CreateUniversal;
import dev.createuniversal.network.NavSyncPayload;
import dev.createuniversal.space.surface.PlanetSurface;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Navegación (alpha.11, D46–D49, D64): lo que sabe cada jugador para el mapa.
 *
 * <ul>
 *   <li><b>Astros por descubrir:</b> el Sol, los planetas y la Luna se conocen desde el principio (se ven desde la Tierra).
 *       Las demás lunas salen como "?" hasta que el jugador se acerca a ellas ({@link #discoverDistance}) o aterriza.</li>
 *   <li><b>Marcadores:</b> puntos fijos del espacio que el jugador pone en el mapa, con nombre; se ven en el mapa y en la
 *       pantalla. Se guardan con el mundo.</li>
 * </ul>
 * El cliente los recibe con {@link NavSyncPayload} al entrar y cada vez que cambian.
 */
public final class Navigation {
    /** Conocidos desde el principio. */
    private static final Set<String> KNOWN = Set.of("sun", "mercury", "venus", "earth", "moon", "mars", "jupiter", "saturn",
            "uranus", "neptune");
    public static final int MAX_MARKERS = 64;

    public record Marker(String id, String name, double x, double y, double z) {}

    private static final class Data extends SavedData {
        private static final String NAME = CreateUniversal.MOD_ID + "_navigation";
        final Map<UUID, Set<String>> discovered = new HashMap<>();
        final Map<UUID, List<Marker>> markers = new HashMap<>();

        static Data get(MinecraftServer server) {
            return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(Data::new, Data::load, null), NAME);
        }

        static Data load(CompoundTag tag, HolderLookup.Provider registries) {
            Data d = new Data();
            CompoundTag players = tag.getCompound("players");
            for (String key : players.getAllKeys()) {
                UUID id;
                try {
                    id = UUID.fromString(key);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                CompoundTag p = players.getCompound(key);
                Set<String> found = new LinkedHashSet<>();
                for (Tag t : p.getList("discovered", Tag.TAG_STRING)) {
                    found.add(t.getAsString());
                }
                d.discovered.put(id, found);
                List<Marker> list = new ArrayList<>();
                for (Tag t : p.getList("markers", Tag.TAG_COMPOUND)) {
                    CompoundTag m = (CompoundTag) t;
                    list.add(new Marker(m.getString("id"), m.getString("name"), m.getDouble("x"), m.getDouble("y"), m.getDouble("z")));
                }
                d.markers.put(id, list);
            }
            return d;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            CompoundTag players = new CompoundTag();
            Set<UUID> all = new LinkedHashSet<>(discovered.keySet());
            all.addAll(markers.keySet());
            for (UUID id : all) {
                CompoundTag p = new CompoundTag();
                ListTag found = new ListTag();
                for (String s : discovered.getOrDefault(id, Set.of())) {
                    found.add(StringTag.valueOf(s));
                }
                p.put("discovered", found);
                ListTag list = new ListTag();
                for (Marker m : markers.getOrDefault(id, List.of())) {
                    CompoundTag t = new CompoundTag();
                    t.putString("id", m.id());
                    t.putString("name", m.name());
                    t.putDouble("x", m.x());
                    t.putDouble("y", m.y());
                    t.putDouble("z", m.z());
                    list.add(t);
                }
                p.put("markers", list);
                players.put(id.toString(), p);
            }
            tag.put("players", players);
            return tag;
        }
    }

    private Navigation() {}

    /** A esta distancia del centro de un astro se descubre. */
    static double discoverDistance(SolarSystem.Body body) {
        return Math.max(SolarSystem.influenceRadius(body) * 2.0, body.radius() * 40.0);
    }

    public static boolean isKnownFromStart(String body) {
        return KNOWN.contains(body);
    }

    private static Set<String> discovered(Data d, UUID player) {
        return d.discovered.computeIfAbsent(player, k -> new LinkedHashSet<>(KNOWN));
    }

    /** Descubre un astro (con aviso). @return true si era nuevo */
    public static boolean discover(ServerPlayer player, String body) {
        Data d = Data.get(player.server);
        if (!discovered(d, player.getUUID()).add(body)) {
            return false;
        }
        d.setDirty();
        player.displayClientMessage(Component.translatable("create_universal.nav.discovered",
                Component.translatable("create_universal.body." + body)).withStyle(ChatFormatting.AQUA), false);
        player.serverLevel().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE,
                SoundSource.PLAYERS, 0.6F, 1.2F);
        sync(player);
        return true;
    }

    public static void addMarker(ServerPlayer player, String name, double x, double y, double z) {
        Data d = Data.get(player.server);
        List<Marker> list = d.markers.computeIfAbsent(player.getUUID(), k -> new ArrayList<>());
        if (list.size() >= MAX_MARKERS) {
            player.displayClientMessage(Component.translatable("create_universal.nav.too_many", MAX_MARKERS).withStyle(ChatFormatting.GOLD), true);
            return;
        }
        String clean = name == null || name.isBlank() ? "?" : name.strip();
        if (clean.length() > 32) {
            clean = clean.substring(0, 32);
        }
        list.add(new Marker(UUID.randomUUID().toString(), clean, x, y, z));
        d.setDirty();
        sync(player);
    }

    public static void removeMarker(ServerPlayer player, String id) {
        Data d = Data.get(player.server);
        List<Marker> list = d.markers.get(player.getUUID());
        if (list != null && list.removeIf(m -> m.id().equals(id))) {
            d.setDirty();
            sync(player);
        }
    }

    public static void renameMarker(ServerPlayer player, String id, String name) {
        Data d = Data.get(player.server);
        List<Marker> list = d.markers.get(player.getUUID());
        if (list == null) {
            return;
        }
        String clean = name == null || name.isBlank() ? "?" : name.strip();
        if (clean.length() > 32) {
            clean = clean.substring(0, 32);
        }
        for (int i = 0; i < list.size(); i++) {
            Marker m = list.get(i);
            if (m.id().equals(id)) {
                list.set(i, new Marker(m.id(), clean, m.x(), m.y(), m.z()));
                d.setDirty();
                sync(player);
                return;
            }
        }
    }

    public static void sync(ServerPlayer player) {
        Data d = Data.get(player.server);
        PacketDistributor.sendToPlayer(player, new NavSyncPayload(new ArrayList<>(discovered(d, player.getUUID())),
                new ArrayList<>(d.markers.getOrDefault(player.getUUID(), List.of()))));
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            sync(p);
        }
    }

    /** Cada 2 s: lo que tiene cerca cada jugador en el espacio, o el astro en el que está. */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % 40 != 0) {
            return;
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            PlanetSurface surface = PlanetSurface.byDimension(p.level().dimension());
            if (surface != null) {
                discover(p, surface.bodyId());
                continue;
            }
            if (p.level().dimension() != CUDimensions.SPACE) {
                continue;
            }
            double time = SpaceLayout.time(p.level());
            Vec3 pos = p.position();
            for (SolarSystem.Body body : SolarSystem.bodies()) {
                if (!KNOWN.contains(body.id()) && pos.distanceTo(SolarSystem.position(body, time)) < discoverDistance(body)) {
                    discover(p, body.id());
                }
            }
        }
    }
}
