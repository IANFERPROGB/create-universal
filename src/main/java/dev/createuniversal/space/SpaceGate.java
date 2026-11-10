package dev.createuniversal.space;

import dev.createuniversal.CreateUniversal;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Sin portales ni teletransportes en el espacio (D71, D72): ni hacia el espacio, ni desde él, ni dentro de él. La única
 * forma de llegar y de moverse por el espacio es volando. En los astros y entre ellos sí funcionan.
 *
 * <ul>
 *   <li>Cualquier cambio de dimensión que salga del espacio o vaya a él se cancela, salvo los viajes del propio mod
 *       ({@link #travel}), el comando /tp y los espectadores. Reaparecer en una cama del espacio no pasa por aquí.</li>
 *   <li>Los bloques y objetos de teletransporte (etiqueta {@code create_universal:space_teleporters}: Waystones, Create
 *       Teleporters, Mekanism) no se pueden colocar ni usar en el espacio.</li>
 *   <li>No se encienden portales del Nether en el espacio, y los portales de Immersive Portals que estén en el espacio o
 *       lleven a él no aparecen.</li>
 * </ul>
 */
public final class SpaceGate {
    public static final TagKey<Block> BLOCKS = TagKey.create(Registries.BLOCK, CreateUniversal.id("space_teleporters"));
    public static final TagKey<Item> ITEMS = TagKey.create(Registries.ITEM, CreateUniversal.id("space_teleporters"));

    /** Viajes permitidos en curso (los del mod y /tp). Se pone a 0 al acabar cada tick por si algo falló a medias. */
    private static int allowed;
    private static final Map<UUID, Long> LAST_WARNING = new HashMap<>();
    private static final Map<Class<?>, Method> DEST_DIM = new java.util.concurrent.ConcurrentHashMap<>();

    private SpaceGate() {}

    /** Un viaje del propio mod (subir al espacio, bajar a un astro, mover una nave con su gente): no se bloquea. */
    public static void travel(Runnable move) {
        allowed++;
        try {
            move.run();
        } finally {
            allowed--;
        }
    }

    public static void allowBegin() {
        allowed++;
    }

    public static void allowEnd() {
        if (allowed > 0) {
            allowed--;
        }
    }

    private static boolean isSpace(ResourceKey<Level> dim) {
        return dim == CUDimensions.SPACE;
    }

    private static boolean inSpace(LevelAccessor level) {
        return level instanceof Level l && isSpace(l.dimension());
    }

    public static void warn(Entity entity) {
        if (!(entity instanceof ServerPlayer player)) {
            return;
        }
        long now = player.level().getGameTime();
        Long last = LAST_WARNING.get(player.getUUID());
        if (last != null && now - last < 40) {
            return;
        }
        LAST_WARNING.put(player.getUUID(), now);
        player.displayClientMessage(Component.translatable("create_universal.message.no_portals_in_space"), true);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onTravel(EntityTravelToDimensionEvent event) {
        Entity entity = event.getEntity();
        if (allowed > 0 || entity.isSpectator()) {
            return;
        }
        if (isSpace(entity.level().dimension()) || isSpace(event.getDimension())) {
            event.setCanceled(true);
            warn(entity);
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        allowed = 0;
    }

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (inSpace(event.getLevel()) && event.getPlacedBlock().is(BLOCKS)) {
            event.setCanceled(true);
            warn(event.getEntity());
        }
    }

    @SubscribeEvent
    public static void onUseBlock(PlayerInteractEvent.RightClickBlock event) {
        if (inSpace(event.getLevel()) && event.getLevel().getBlockState(event.getPos()).is(BLOCKS)) {
            event.setCanceled(true);
            warn(event.getEntity());
        }
    }

    @SubscribeEvent
    public static void onUseItem(PlayerInteractEvent.RightClickItem event) {
        if (inSpace(event.getLevel()) && event.getItemStack().is(ITEMS)) {
            event.setCanceled(true);
            warn(event.getEntity());
        }
    }

    @SubscribeEvent
    public static void onPortalSpawn(BlockEvent.PortalSpawnEvent event) {
        if (inSpace(event.getLevel())) {
            event.setCanceled(true);
        }
    }

    /** Portales de Immersive Portals (Immersive Portals: Aeronautics) en el espacio o hacia él. */
    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        Entity entity = event.getEntity();
        if (event.getLevel().isClientSide() || !entity.getClass().getName().startsWith("qouteall.imm_ptl")) {
            return;
        }
        boolean blocked = isSpace(event.getLevel().dimension());
        if (!blocked) {
            ResourceKey<Level> dest = destination(entity);
            blocked = dest != null && isSpace(dest);
        }
        if (blocked) {
            event.setCanceled(true);
            for (Player p : event.getLevel().players()) {
                if (p.distanceToSqr(entity) < 32 * 32) {
                    warn(p);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static ResourceKey<Level> destination(Entity portal) {
        Method m = DEST_DIM.computeIfAbsent(portal.getClass(), c -> {
            try {
                return c.getMethod("getDestDim");
            } catch (NoSuchMethodException e) {
                return null;
            }
        });
        if (m == null) {
            return null;
        }
        try {
            return (ResourceKey<Level>) m.invoke(portal);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
