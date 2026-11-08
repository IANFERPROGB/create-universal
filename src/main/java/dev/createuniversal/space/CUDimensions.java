package dev.createuniversal.space;

import dev.createuniversal.CreateUniversal;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public final class CUDimensions {
    public static final ResourceKey<Level> SPACE = ResourceKey.create(Registries.DIMENSION, CreateUniversal.id("space"));

    private CUDimensions() {}
}
