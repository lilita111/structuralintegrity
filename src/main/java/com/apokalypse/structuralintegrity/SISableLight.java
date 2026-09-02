package com.apokalypse.structuralintegrity;

import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Why a falling roof renders at midnight in the middle of the afternoon - and what
 * is left of that question after the first answer turned out to be wrong.
 *
 * The symptom has been the same since 0.3: a piece of building that shears off and
 * becomes a sable sub-level is drawn in permanent shadow, whatever the time of day,
 * while the identical structure reloaded from disk lights normally. Something about
 * a FRESHLY assembled piece leaves it dark and a reloaded one does not.
 *
 * The theory this class used to implement. Sable creates a plot chunk with its
 * light-correct flag cleared - {@code addChunkHolder} does
 * {@code chunk.setLightCorrect(false)} - and then, in {@code initializeLight}, hands
 * that same flag straight to the light engine as
 * {@code setLightEnabled(pos, chunk.isLightCorrect())}. A light engine told not to
 * light a chunk holds no sky data for it, the chunk packet then marks those sections
 * in its empty-sky mask, and the client materialises a real all-zero sky layer for
 * each one - and that distinction is the whole difference, because MISSING sky data
 * reads as full daylight while PRESENT-and-zero reads as pitch dark. The load path
 * looked like the control that proved it: a saved sub-level does
 * {@code chunk.setLightCorrect(chunkTag.getBoolean("isLightOn"))} before lighting,
 * comes back true, and lights correctly.
 *
 * Why it is wrong, established in 0.7.4 by reading the two ends against each other.
 * Sable's {@code lightChunk} is three calls: {@code initializeLightSources}, then
 * {@code initializeLight} - which contains the {@code setLightEnabled} above - and
 * then {@code correctLight}, whose first act is {@code propagateLightSources(pos)}.
 * And vanilla's {@code BlockLightEngine.propagateLightSources} and
 * {@code SkyLightEngine.propagateLightSources} BOTH open with
 * {@code setLightEnabled(chunkPos, true)}, unconditionally, before doing anything
 * else. So the flag the old fix overrode is turned back on by sable itself, two
 * calls later, on every single chunk. Forcing it on early changed nothing, which is
 * exactly consistent with sub-levels still being dark while the fix was shipped.
 *
 * What this class does now. Static reading of both codebases is exhausted; the
 * remaining question is empirical and has one honest form: at the moment sable
 * finishes lighting a plot chunk, what does the plot's own light engine actually
 * hold? This reports that, per section, in three facts that are each separately
 * capable of explaining a dark piece - whether light is on for the section at all,
 * whether a sky layer exists for it, and whether that layer is present but zero.
 * The last one is the smoking gun the old theory predicted and never verified.
 *
 * Reading the report. Each chunk prints one line per light layer, one character per
 * section, from {@code getMinLightSection()} upward:
 *
 * <pre>
 *   -  light is OFF for this section - the engine holds nothing and says so
 *   .  light on, no data layer - sent as "empty", which a client reads as FULL light
 *   0  light on, layer present and entirely zero - a client reads this as DARK
 *   F  light on, layer present and entirely 15 - full brightness
 *   ~  light on, layer present, mixed values - normal lighting
 * </pre>
 *
 * A row of {@code 0} on the sky line is the bug, sitting where the theory said it
 * would. A row of {@code ~} or {@code F} on the sky line means the server plot is
 * lit correctly and the darkness is entering later - in what the packet carries, in
 * what the client engine does with it, or in the mesh builder - and the next
 * instrument goes on the client side of that boundary.
 */
public final class SISableLight {
    private SISableLight() {}

    /** Reported for the first few chunks and then thinned out - an assembly is many chunks. */
    private static final AtomicLong SEEN = new AtomicLong();
    private static final long LOUD_UNTIL = 12;
    private static final long THEN_EVERY = 200;

    /**
     * Print what the plot's light engine holds for a chunk sable has just finished
     * lighting.
     *
     * Called at the return of {@code ServerLevelPlot.lightChunk}, which is after
     * {@code correctLight} has run {@code propagateLightSources} and set the chunk's
     * light-correct flag - so this is the finished state, not an intermediate one.
     * Anything wrong here is wrong on the server before a single packet is written.
     */
    public static void report(ChunkPos pos, LevelLightEngine engine, LevelChunk chunk) {
        if (!SIConfig.reportSubLevelLight()) {
            return;
        }
        long n = SEEN.incrementAndGet();
        if (n > LOUD_UNTIL && n % THEN_EVERY != 0) {
            return;
        }
        try {
            int min = engine.getMinLightSection();
            int max = engine.getMaxLightSection();
            StructuralIntegrity.LOGGER.info(
                    "[SI] SABLE LIGHT plot chunk {},{} lit: lightCorrect={} sections=[{}..{}) "
                            + "sky={} block={} [chunk {} of this run]",
                    pos.x, pos.z, chunk.isLightCorrect(), min, max,
                    layerString(engine, pos, min, max, LightLayer.SKY),
                    layerString(engine, pos, min, max, LightLayer.BLOCK),
                    n);
        } catch (Throwable t) {
            // A report is never worth taking a sub-level down for. If a future sable
            // hands us an engine shaped differently, say so once and stay quiet.
            StructuralIntegrity.LOGGER.warn("[SI] SABLE LIGHT report failed for chunk {},{}: {}",
                    pos.x, pos.z, t.toString());
        }
    }

    /** One character per section, low to high. Legend is in the class doc. */
    private static String layerString(LevelLightEngine engine, ChunkPos pos,
                                      int min, int max, LightLayer layer) {
        LayerLightEventListener listener = engine.getLayerListener(layer);
        StringBuilder sb = new StringBuilder(Math.max(0, max - min));
        for (int y = min; y < max; y++) {
            SectionPos section = SectionPos.of(pos, y);
            if (!engine.lightOnInSection(section)) {
                sb.append('-');
                continue;
            }
            DataLayer data = listener.getDataLayerData(section);
            if (data == null || data.isEmpty()) {
                // isEmpty means the backing array was never allocated. The chunk
                // packet writes this section into its empty mask, and a client reads
                // an empty SKY mask entry as FULL daylight - the opposite of the bug.
                sb.append('.');
            } else if (data.isDefinitelyFilledWith(0)) {
                sb.append('0');
            } else if (data.isDefinitelyFilledWith(15)) {
                sb.append('F');
            } else {
                sb.append('~');
            }
        }
        return sb.toString();
    }

    /** How many plot chunks have been examined. For the gametests. */
    public static long reportedCount() {
        return SEEN.get();
    }
}
