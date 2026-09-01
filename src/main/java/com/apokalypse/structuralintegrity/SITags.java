package com.apokalypse.structuralintegrity;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

/**
 * Block tags this mod reads. Shipped in the jar, overridable by datapack, and
 * deliberately not config: these are properties of a material, the same way
 * naturalintegrityreg is.
 */
public final class SITags {
    private SITags() {}

    /**
     * Materials that shatter when spent no matter how little load they carry.
     *
     * holdSpentUpToNatural decides the fate of a spent block from its natural
     * integrity, and that one number is being asked to answer two questions that
     * are not the same question. Natural integrity means "how many blocks may hang
     * off me". Held-when-spent means "do I slump into rubble or do I break".
     * Soil happens to answer both the same way - dirt is weak AND it slumps - and
     * that coincidence is what made the threshold look sufficient in 0.7.1.
     *
     * Leaves are the counter-example that broke it. They are natural 1, below any
     * useful setting of the threshold, so a canopy left behind by a felled trunk
     * was pinned at failAt and hung in the air instead of dropping. The two ways
     * to fix that with the number alone are both lies: raising leaves to 3 says a
     * three-block leaf cantilever holds and triples their clump grant, so canopies
     * brace themselves and stop shedding at all; lowering the threshold to 1 drops
     * sand and gravel, both natural 2, out of the rubble band entirely.
     *
     * So the second question gets its own answer. A block in this tag always
     * breaks, whatever the threshold is set to and whatever its natural integrity
     * says - the tag is a veto on holding, not another number to compare.
     */
    public static final TagKey<Block> BREAKS_WHEN_SPENT = TagKey.create(
            Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath(StructuralIntegrity.MODID, "breaks_when_spent"));
}
