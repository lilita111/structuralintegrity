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
 *
 * @param neverAnchor this block can never read as ground. Loose material and
 *                    growth hold themselves up and nothing else: they are part of
 *                    a structure, they are never what a structure rests on. Such a
 *                    block is given a real wbireg row the first time the solver
 *                    looks at it, because "absent" is what ground means.
 */
public record BlockIntegrity(int integrity, boolean neverAnchor) {

    public static final Codec<BlockIntegrity> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("integrity").forGetter(BlockIntegrity::integrity),
            Codec.BOOL.optionalFieldOf("never_anchor", false).forGetter(BlockIntegrity::neverAnchor)
    ).apply(i, BlockIntegrity::new));
}
