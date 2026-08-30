package com.apokalypse.structuralintegrity;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * One row of "integrityreg" - the natural integrity a block of this type has when
 * first placed or generated.
 *
 * The unit is literally "how many blocks may hang off me": a value of 10 means a
 * ten-block cantilever stands and an eleven-block one does not.
 *
 * Shipped in the jar at data/structuralintegrity/data_maps/block/integrity.json.
 * A datapack may override it. It is not a config.
 */
public record BlockIntegrity(int integrity) {

    public static final Codec<BlockIntegrity> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("integrity").forGetter(BlockIntegrity::integrity)
    ).apply(i, BlockIntegrity::new));
}
