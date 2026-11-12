package dev.createuniversal.registry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Proyector holográfico (D46, alpha.11.2): guarda cómo se ve su holograma (tamaño, vista y nombres) y lo manda al cliente,
 * que es quien lo dibuja ({@code HoloProjectorRenderer}).
 */
public class HoloProjectorBlockEntity extends BlockEntity {
    /** Vistas: la del mapa, el sistema solar entero, alrededor del jugador. */
    public static final int VIEW_MAP = 0;
    public static final int VIEW_SYSTEM = 1;
    public static final int VIEW_LOCAL = 2;
    public static final int[] SIZES = {1, 2, 3, 4, 6, 8};

    private int size = 3;
    private int view = VIEW_SYSTEM;
    private boolean names = true;

    public HoloProjectorBlockEntity(BlockPos pos, BlockState state) {
        super(CUMachines.HOLO_PROJECTOR_BE.get(), pos, state);
    }

    public int size() {
        return size;
    }

    public int view() {
        return view;
    }

    public boolean names() {
        return names;
    }

    public void configure(int size, int view, boolean names) {
        int s = SIZES[0];
        for (int candidate : SIZES) {
            if (candidate == size) {
                s = candidate;
            }
        }
        this.size = s;
        this.view = Math.floorMod(view, 3);
        this.names = names;
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("Size", size);
        tag.putInt("View", view);
        tag.putBoolean("Names", names);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("Size")) {
            size = tag.getInt("Size");
            view = tag.getInt("View");
            names = tag.getBoolean("Names");
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
