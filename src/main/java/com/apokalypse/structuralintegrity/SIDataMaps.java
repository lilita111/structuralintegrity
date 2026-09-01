package com.apokalypse.structuralintegrity;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.registries.datamaps.DataMapType;
import net.neoforged.neoforge.registries.datamaps.RegisterDataMapTypesEvent;

/** naturalintegrityreg - block type to its integrity when first placed or generated.
 * Shipped in the jar, not a config. Overridable by datapack. */
public final class SIDataMaps {
    private SIDataMaps() {}

    public static final DataMapType<Block, BlockIntegrity> NATURAL = DataMapType.builder(
            ResourceLocation.fromNamespaceAndPath(StructuralIntegrity.MODID, "natural_integrity"),
            Registries.BLOCK,
            BlockIntegrity.CODEC
    ).build();

    public static void onRegisterDataMaps(RegisterDataMapTypesEvent event) {
        event.register(NATURAL);
        StructuralIntegrity.LOGGER.info("[SI] registered naturalintegrityreg data map {}", NATURAL.id());
    }
}
