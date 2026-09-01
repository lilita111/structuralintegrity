package com.apokalypse.structuralintegrity;

import net.minecraft.world.level.ChunkPos;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Why a falling roof renders at midnight in the middle of the afternoon.
 *
 * The order it happens in. First sable creates a plot chunk for a new sub-level
 * and clears its light-correct flag - {@code addChunkHolder} does
 * {@code chunk.setLightCorrect(false)} - which is reasonable on its own, because
 * the chunk genuinely has no correct light yet. Then, one call later, it lights
 * that chunk, and the lighting step asks the light engine to honour the very flag
 * that was just cleared: {@code setLightEnabled(pos, chunk.isLightCorrect())},
 * which is {@code false} for every chunk of every freshly assembled sub-level. A
 * light engine with lighting disabled for a chunk holds no sky data for it.
 *
 * Then the sub-level is sent to whoever is tracking it. The chunk packet carries
 * the plot engine's sky layers, and because the engine was told not to light this
 * chunk they are empty, so the packet marks those sections in its empty-sky mask.
 * The client obligingly materialises a real, all-zero sky layer for each one. That
 * is the difference that matters: MISSING sky data reads as full daylight, but
 * PRESENT and zero reads as pitch dark, and the client now has the second. Then
 * the mesh builder bakes that zero into every vertex of the piece, and no amount
 * of the day/night cycle moving afterwards can lift a value that is baked in.
 *
 * The evidence that this is a slip rather than a design decision is sable's own
 * load path: a sub-level read back from disk does
 * {@code chunk.setLightCorrect(chunkTag.getBoolean("isLightOn"))} BEFORE lighting,
 * so a saved piece arrives with the flag true and lights correctly. Reloaded
 * sub-levels have always looked right; only freshly assembled ones are dark. This
 * makes a fresh plot behave the way a reloaded one already does.
 */
public final class SISableLight {
    private SISableLight() {}

    /** Reported for the first few chunks and then thinned out - an assembly is many chunks. */
    private static final AtomicLong FORCED = new AtomicLong();
    private static final long LOUD_UNTIL = 10;
    private static final long THEN_EVERY = 200;

    /**
     * What sable should have passed to {@code setLightEnabled}.
     *
     * Only ever turns lighting ON, and only for the case it was going to be off:
     * a chunk that already reports its light as correct is left exactly alone, so
     * the reload path - the one that already works - is not touched.
     */
    public static boolean skyLightEnabled(ChunkPos pos, boolean lightCorrect) {
        if (lightCorrect || !SIConfig.fixSubLevelSkyLight()) {
            return lightCorrect;
        }
        long n = FORCED.incrementAndGet();
        if (n <= LOUD_UNTIL || n % THEN_EVERY == 0) {
            StructuralIntegrity.LOGGER.info(
                    "[SI] SABLE LIGHT plot chunk {},{} was created with sky light disabled "
                            + "(isLightCorrect=false); forcing it on so the piece is not rendered "
                            + "under a permanent night [{} so far]",
                    pos.x, pos.z, n);
        }
        return true;
    }

    /** How many plot chunks have had their sky light forced on. For the gametests. */
    public static long forcedCount() {
        return FORCED.get();
    }
}
