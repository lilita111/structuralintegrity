package com.apokalypse.structuralintegrity;

import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stage 2 - the fall. Stage 1 decided what is disconnected; this turns that
 * verdict into a physical object.
 *
 * Nothing happens inside the place or break event itself. Those only leave seeds
 * here, and the work runs on the next server tick, for two reasons: BreakEvent
 * fires while the broken block is still in the world, so the region solved during
 * the event is not the region that exists afterwards; and assembling a sub-level
 * rewrites the chunk the event is still walking.
 *
 * A seed is any position that might have just lost its way to ground. On the tick,
 * each seed grows into its connected component - stopping at ground, exactly as
 * {@link Integrity#compute} does - and a component that never reaches ground is
 * handed to sable as a sub-level. Seeds landing in a component already handled this
 * tick are dropped, so one collapse assembles once no matter how many faces were
 * disturbed.
 */
public final class SIFall {
    private SIFall() {}

    /**
     * Refuse to assemble anything larger. This is a physics object, not a region
     * edit: past a few hundred blocks the honest answer is that the structure is
     * load-bearing terrain and should stay where it is. Always logged, never silent.
     */
    public static final int MAX_ASSEMBLY = 512;

    private static final Map<ServerLevel, LinkedHashSet<BlockPos>> PENDING_FALL = new HashMap<>();
    private static final Map<ServerLevel, LinkedHashSet<BlockPos>> PENDING_DESTROY = new HashMap<>();

    /**
     * Positions an assembly has already been attempted at, and the tick it happened.
     *
     * A successful assembly is supposed to empty the world of those blocks. When it
     * does not, the next fall pass finds the same blocks detached and assembles them
     * again, and again, once per pass forever - which is what the log showed, the same
     * anchor and the same size 0.8s apart. This is the brake: an anchor that has just
     * been through assembly is left alone long enough for the world to settle.
     */
    private static final Map<ServerLevel, Map<BlockPos, Long>> RECENT_ASSEMBLY = new HashMap<>();

    /** How long an anchor is held off after an assembly attempt. */
    public static final int ASSEMBLY_COOLDOWN_TICKS = 40;

    /** Guards against a re-entrant assembly triggering itself through block updates. */
    private static boolean running;

    // =====================================================================
    // Queueing - called from the event handlers, does no work
    // =====================================================================

    /** This position may have just been cut loose. Check it next tick. */
    public static void queueFall(ServerLevel level, BlockPos pos) {
        if (running) {
            return;
        }
        PENDING_FALL.computeIfAbsent(level, l -> new LinkedHashSet<>()).add(pos.immutable());
    }

    /**
     * A support that was crushed by a placement. It is removed next tick, before the
     * fall pass, so whatever it was holding is seen in its true state.
     */
    public static void queueDestroy(ServerLevel level, BlockPos pos) {
        if (running) {
            return;
        }
        PENDING_DESTROY.computeIfAbsent(level, l -> new LinkedHashSet<>()).add(pos.immutable());
    }

    // =====================================================================
    // The tick pass
    // =====================================================================

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (PENDING_DESTROY.isEmpty() && PENDING_FALL.isEmpty()) {
            return;
        }

        Map<ServerLevel, LinkedHashSet<BlockPos>> destroy = new HashMap<>(PENDING_DESTROY);
        Map<ServerLevel, LinkedHashSet<BlockPos>> fall = new HashMap<>(PENDING_FALL);
        PENDING_DESTROY.clear();
        PENDING_FALL.clear();

        running = true;
        try {
            for (Map.Entry<ServerLevel, LinkedHashSet<BlockPos>> e : destroy.entrySet()) {
                runDestroy(e.getKey(), e.getValue(), fall);
            }
            for (Map.Entry<ServerLevel, LinkedHashSet<BlockPos>> e : fall.entrySet()) {
                runFall(e.getKey(), e.getValue());
            }
        } finally {
            running = false;
        }
    }

    /**
     * Remove crushed supports. The pillar below is deliberately not disturbed - a
     * crush is not mining, and the spec is explicit that the pillar keeps standing
     * and only loses its top.
     */
    private static void runDestroy(ServerLevel level, Set<BlockPos> positions,
                                   Map<ServerLevel, LinkedHashSet<BlockPos>> fall) {
        WbiReg reg = WbiReg.of(level);
        for (BlockPos pos : positions) {
            if (!Integrity.isStructural(level, pos, null)) {
                continue; // already gone
            }
            boolean ok = level.destroyBlock(pos, true);
            reg.clear(pos);
            StructuralIntegrity.LOGGER.info("[SI] CRUSH destroy {} {} wbireg={}",
                    fmt(pos), ok ? "removed" : "FAILED", reg.size());
            // Whatever it was carrying has just lost its support.
            for (Direction d : Direction.values()) {
                BlockPos n = pos.relative(d);
                if (Integrity.isStructural(level, n, null)) {
                    fall.computeIfAbsent(level, l -> new LinkedHashSet<>()).add(n.immutable());
                }
            }
        }
    }

    private static void runFall(ServerLevel level, Set<BlockPos> seeds) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            StructuralIntegrity.LOGGER.warn("[SI] fall skipped in {}: sable has no sub-level container here",
                    level.dimension().location());
            return;
        }

        WbiReg reg = WbiReg.of(level);
        Set<BlockPos> handled = new HashSet<>();
        int checked = 0;
        int fell = 0;

        for (BlockPos seed : seeds) {
            if (handled.contains(seed)) {
                continue;
            }
            checked++;
            long t0 = System.nanoTime();
            Integrity.Component comp = Integrity.collect(level, reg, seed, null, MAX_ASSEMBLY);
            long micros = (System.nanoTime() - t0) / 1000L;

            if (comp.grounded()) {
                StructuralIntegrity.LOGGER.info("[SI] fall check {} GROUNDED in {}us", fmt(seed), micros);
                continue;
            }
            if (comp.blocks().isEmpty()) {
                continue; // seed was air, ground, or fluid by the time we got here
            }
            handled.addAll(comp.blocks());

            if (comp.capped()) {
                StructuralIntegrity.LOGGER.warn(
                        "[SI] fall check {} DETACHED but larger than maxAssembly={} - left in place ({}us)",
                        fmt(seed), MAX_ASSEMBLY, micros);
                continue;
            }

            StructuralIntegrity.LOGGER.info("[SI] fall check {} DETACHED n={} in {}us -> assembling",
                    fmt(seed), comp.blocks().size(), micros);
            if (assemble(level, reg, seed, comp.blocks())) {
                fell++;
            }
        }

        if (checked > 0) {
            StructuralIntegrity.LOGGER.info("[SI] fall pass in {}: seeds={} checked={} assembled={} wbireg={}",
                    level.dimension().location(), seeds.size(), checked, fell, reg.size());
        }
    }

    /**
     * Hand the component to sable. The bounds are the component's own box grown by
     * one, which is what sable's own assembly commands pass - it is the volume that
     * tracking points and riding entities are swept out of, not the block set.
     */
    private static boolean assemble(ServerLevel level, WbiReg reg, BlockPos anchor, List<BlockPos> blocks) {
        Map<BlockPos, Long> recent = RECENT_ASSEMBLY.computeIfAbsent(level, l -> new HashMap<>());
        long now = level.getGameTime();
        recent.entrySet().removeIf(e -> now - e.getValue() > ASSEMBLY_COOLDOWN_TICKS);

        Long last = recent.get(anchor.immutable());
        if (last != null) {
            StructuralIntegrity.LOGGER.warn(
                    "[SI] assembly at {} SUPPRESSED, attempted {} ticks ago and the blocks are still here",
                    fmt(anchor), now - last);
            return false;
        }
        recent.put(anchor.immutable(), now);

        BoundingBox3i bounds = BoundingBox3i.from(blocks);
        bounds.set(bounds.minX - 1, bounds.minY - 1, bounds.minZ - 1,
                bounds.maxX + 1, bounds.maxY + 1, bounds.maxZ + 1);

        ServerSubLevel subLevel;
        try {
            subLevel = SubLevelAssemblyHelper.assembleBlocks(level, anchor, blocks, bounds);
        } catch (Throwable t) {
            StructuralIntegrity.LOGGER.error("[SI] assembly FAILED at {} for {} blocks",
                    fmt(anchor), blocks.size(), t);
            return false;
        }

        if (subLevel.getMassTracker().isInvalid()) {
            StructuralIntegrity.LOGGER.warn("[SI] assembled at {} but sable reports no mass - nothing moved",
                    fmt(anchor));
            return false;
        }

        // Sable reporting mass is not the same as the world having given the blocks
        // up. Measure it: if they are still standing, the assembly did not happen and
        // saying it did is what makes the next pass do it all over again.
        int left = 0;
        BlockPos first = null;
        for (BlockPos p : blocks) {
            if (!level.getBlockState(p).isAir()) {
                if (first == null) {
                    first = p.immutable();
                }
                left++;
            }
        }
        if (left > 0) {
            StructuralIntegrity.LOGGER.error(
                    "[SI] assembly at {} claimed {} blocks but {} are STILL IN THE WORLD, first {} - "
                            + "rows left alone, position held off {} ticks",
                    fmt(anchor), blocks.size(), left, fmt(first), ASSEMBLY_COOLDOWN_TICKS);
            return false;
        }

        // The blocks are no longer at these positions; their rows mean nothing now.
        for (BlockPos p : blocks) {
            reg.clear(p);
        }

        StructuralIntegrity.LOGGER.info("[SI] SUBLEVEL created at {} n={} bounds=[{},{},{} .. {},{},{}]",
                fmt(anchor), blocks.size(),
                bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ);
        return true;
    }

    // =====================================================================

    /** Seed the block itself and everything that touched it. */
    public static void seedAround(ServerLevel level, BlockPos origin, boolean includeOrigin) {
        List<BlockPos> seeds = new ArrayList<>(7);
        if (includeOrigin && Integrity.isStructural(level, origin, null)) {
            seeds.add(origin);
        }
        for (Direction d : Direction.values()) {
            BlockPos n = origin.relative(d);
            if (Integrity.isStructural(level, n, null)) {
                seeds.add(n);
            }
        }
        for (BlockPos p : seeds) {
            queueFall(level, p);
        }
    }

    private static String fmt(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }
}
