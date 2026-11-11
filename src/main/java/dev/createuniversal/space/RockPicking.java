package dev.createuniversal.space;

import dev.createuniversal.CreateUniversal;
import dev.createuniversal.registry.CUBlocks;
import dev.createuniversal.registry.MoonletRockBlock;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Picar las rocas del espacio (D36, alpha.10.1): las rocas del cinturón y de los anillos de Saturno, Fobos y Deimos no se
 * rompen, pero cada golpe con un pico de diamante o mejor (también de otros mods: el que sirve para la obsidiana) da un
 * fragmento decorativo con el nombre de su origen. Cada roca da {@link #PER_ROCK}; agotada, se recarga a los
 * {@link #REFILL_TICKS}. El contador es de la roca (se guarda con el mundo) y lo comparten todos los jugadores.
 */
public final class RockPicking {
    public static final int PER_ROCK = 5;
    private static final long REFILL_TICKS = 5 * 60 * 20;
    /** Un fragmento por golpe, pero no más de uno cada medio segundo. */
    private static final long HIT_COOLDOWN = 10;

    private static final Map<UUID, Long> LAST_HIT = new HashMap<>();

    private RockPicking() {}

    @SubscribeEvent
    public static void onHit(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getAction() != PlayerInteractEvent.LeftClickBlock.Action.START
                || !(event.getEntity() instanceof ServerPlayer player) || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockState state = level.getBlockState(event.getPos());
        if (!(state.getBlock() instanceof MoonletRockBlock)
                || !(Sable.HELPER.getContaining(level, event.getPos()) instanceof ServerSubLevel body) || !Moonlets.isMoonlet(body)) {
            return;
        }
        String id = rockId(body);
        Block fragment = fragmentFor(body);
        if (id == null || fragment == null) {
            return;
        }
        long now = level.getGameTime();
        Long last = LAST_HIT.get(player.getUUID());
        if (last != null && now - last < HIT_COOLDOWN && now >= last) {
            return;
        }
        LAST_HIT.put(player.getUUID(), now);
        ItemStack tool = player.getMainHandItem();
        if (!tool.is(ItemTags.PICKAXES) || !tool.isCorrectToolForDrops(Blocks.OBSIDIAN.defaultBlockState())) {
            player.displayClientMessage(Component.translatable("create_universal.message.rock_tool").withStyle(ChatFormatting.GRAY), true);
            return;
        }
        Counters counters = Counters.get(level);
        int left = counters.take(id, now);
        if (left < 0) {
            long minutes = Math.max(1, (counters.refillAt(id) - now + 1199) / 1200);
            player.displayClientMessage(Component.translatable("create_universal.message.rock_empty", minutes).withStyle(ChatFormatting.GOLD), true);
            return;
        }
        ItemStack drop = new ItemStack(fragment);
        if (!player.getInventory().add(drop)) {
            player.drop(drop, false);
        }
        Vec3 at = player.getEyePosition().add(player.getLookAngle().scale(1.5));
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state), at.x, at.y, at.z, 12, 0.2, 0.2, 0.2, 0.05);
        level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.STONE_BREAK, SoundSource.BLOCKS, 0.8F, 0.9F);
        tool.hurtAndBreak(1, player, net.minecraft.world.entity.EquipmentSlot.MAINHAND);
        player.displayClientMessage(Component.translatable("create_universal.message.rock_picked", left).withStyle(ChatFormatting.AQUA), true);
    }

    /** Id fijo de la roca (o "phobos", "deimos"). */
    private static String rockId(ServerSubLevel body) {
        CompoundTag ours = body.getUserDataTag() != null ? body.getUserDataTag().getCompound(CreateUniversal.MOD_ID) : new CompoundTag();
        if (ours.contains(SpaceRocks.KEY)) {
            return ours.getString(SpaceRocks.KEY);
        }
        return ours.contains(Moonlets.KEY) ? ours.getString(Moonlets.KEY) : null;
    }

    private static Block fragmentFor(ServerSubLevel body) {
        String id = rockId(body);
        if (id == null) {
            return null;
        }
        if (id.startsWith("asteroid_belt")) {
            return CUBlocks.BELT_FRAGMENT.get();
        }
        if (id.startsWith("saturn_rings")) {
            return CUBlocks.SATURN_RING_FRAGMENT.get();
        }
        if (id.equals("phobos")) {
            return CUBlocks.PHOBOS_FRAGMENT.get();
        }
        if (id.equals("deimos")) {
            return CUBlocks.DEIMOS_FRAGMENT.get();
        }
        return null;
    }

    /** Fragmentos que le quedan a cada roca, guardados con el mundo. */
    private static final class Counters extends SavedData {
        private static final String NAME = CreateUniversal.MOD_ID + "_rock_picks";
        /** id → {quedan, tick en que se recarga (0 = sin agotar)}. */
        private final Map<String, long[]> rocks = new HashMap<>();

        static Counters get(ServerLevel any) {
            return any.getServer().overworld().getDataStorage().computeIfAbsent(
                    new SavedData.Factory<>(Counters::new, Counters::load, null), NAME);
        }

        /** Saca un fragmento: los que quedan después, o −1 si está agotada. */
        int take(String id, long now) {
            long[] c = rocks.computeIfAbsent(id, k -> new long[] {PER_ROCK, 0});
            if (c[0] <= 0 && c[1] > 0 && now >= c[1]) {
                c[0] = PER_ROCK;
                c[1] = 0;
            }
            if (c[0] <= 0) {
                return -1;
            }
            c[0]--;
            if (c[0] == 0) {
                c[1] = now + REFILL_TICKS;
            }
            setDirty();
            if (c[0] == PER_ROCK - 1 && rocks.size() > 5000) {
                // Para que no crezca sin fin: se olvidan las rocas ya recargadas.
                rocks.values().removeIf(v -> v[0] >= PER_ROCK || (v[1] > 0 && now >= v[1]));
                rocks.put(id, c);
            }
            return (int) c[0];
        }

        long refillAt(String id) {
            long[] c = rocks.get(id);
            return c != null ? c[1] : 0;
        }

        static Counters load(CompoundTag tag, HolderLookup.Provider registries) {
            Counters c = new Counters();
            CompoundTag all = tag.getCompound("rocks");
            for (String id : all.getAllKeys()) {
                CompoundTag r = all.getCompound(id);
                c.rocks.put(id, new long[] {r.getInt("left"), r.getLong("refill")});
            }
            return c;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            CompoundTag all = new CompoundTag();
            for (Map.Entry<String, long[]> e : rocks.entrySet()) {
                CompoundTag r = new CompoundTag();
                r.putInt("left", (int) e.getValue()[0]);
                r.putLong("refill", e.getValue()[1]);
                all.put(e.getKey(), r);
            }
            tag.put("rocks", all);
            return tag;
        }
    }
}
