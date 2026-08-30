package com.apokalypse.structuralintegrity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The standard class function. Every block a player updates is run through
 * {@link #compute}, and nothing else is.
 *
 * <pre>
 *   integrity = stored - hang
 * </pre>
 *
 * stored - this position's row in wbireg. Written once, at placement, by
 *          {@link #place}: a block sits one below whatever it was built on,
 *          capped at its own natural. Never recomputed from geometry.
 * hang   - blocks connected through a face whose region cannot reach ground on
 *          its own. Those blocks are hanging off this one. Evaluated lazily, only
 *          when a player touches something adjacent.
 *
 * Ground is not a block list. A position with no wbireg row reads as maximum + 1
 * ({@link WbiReg#ANCHOR}): immovable, and where a fill stops.
 *
 * One traversal, not six: a global visited set labels each face's neighbour with
 * the region it lands in, so two faces opening into the same region share a fill
 * instead of repeating it.
 *
 * {@link #compute} is read-only. {@link #place} and {@link #disturb} write wbireg
 * rows and nothing else - no block in the world is changed by this stage.
 */
public final class Integrity {
    private Integrity() {}

    /** Hard cap on one region fill. Reaching it is always reported, never silent. */
    public static final int MAX_REGION = 2048;
    /** Default for a solid block with no row in naturalintegrityreg. */
    public static final int DEFAULT_INTEGRITY = 8;
    /** Default for a block with no collision shape - a torch, a flower, a rail. */
    public static final int DEFAULT_FRAGILE = 1;
    /** A support this low has nothing left to give: it is crushed, not reduced. */
    public static final int CRUSH_AT = 1;

    private static final Direction[] DIRS = Direction.values();

    // =====================================================================
    // Placement - the only thing that writes an integrity value
    // =====================================================================

    /**
     * What a placement did. Reported, never guessed at from the log.
     *
     * @param support   the neighbour the block was built on, or null if it had none
     * @param supportAt that neighbour's integrity, unchanged by the placement
     * @param crushed   the support, when it was at {@link #CRUSH_AT} and failed
     */
    public record Placed(
            BlockPos pos, int natural, int assigned,
            @Nullable BlockPos support, int supportAt,
            boolean onAnchor, @Nullable BlockPos crushed
    ) {}

    /**
     * Assign the placed block its integrity from the block it was built on.
     *
     * <pre>
     *   on ground   assigned = natural                    (a full row, not an anchor)
     *   otherwise   assigned = min(natural, support - 1)
     *   support 1   assigned = 1, and the support is crushed
     * </pre>
     *
     * A support with no wbireg row is ground, worth maximum + 1, so the placed block
     * starts at its own natural. Every block after that is one below the one it was
     * built on, so natural integrity reads directly as pillar height: the Nth block
     * of a material with natural N is at 1.
     *
     * The support itself is never reduced. Carrying load is the hang term's job, and
     * charging here as well would count the same weight twice; it also let the first
     * block of a pillar drop below its natural, which it must not do.
     *
     * Build one higher than that and the support is at {@link #CRUSH_AT}: it has
     * nothing left to give, so it fails outright and the new block takes 1. Nothing
     * below it is touched - the pillar does not fall, it loses its top. The new block
     * is then floating with a gap under it, so it goes the way every disconnected
     * block goes: {@link #isConnectedToAnchor} is false and it becomes a sublevel.
     *
     * The support is the best neighbour in any direction, not just below, so
     * building out from a cliff face costs the same as building up from it.
     */
    @Nullable
    public static Placed place(ServerLevel level, WbiReg reg, BlockPos pos) {
        if (!isStructural(level, pos, null)) {
            return null;
        }
        int natural = naturalOf(level, pos, level.getBlockState(pos));

        BlockPos support = null;
        int best = Integer.MIN_VALUE;
        for (Direction d : DIRS) {
            BlockPos n = pos.relative(d);
            if (!isStructural(level, n, null)) {
                continue;
            }
            int v = reg.get(n);
            if (v > best) {
                best = v;
                support = n;
            }
        }

        if (support == null) {
            // Placed touching nothing structural. It supports itself and no more.
            reg.set(pos, 0);
            return new Placed(pos.immutable(), natural, 0, null, 0, false, null);
        }

        if (best == WbiReg.ANCHOR) {
            reg.set(pos, natural);
            return new Placed(pos.immutable(), natural, natural,
                    support.immutable(), best, true, null);
        }

        if (best <= CRUSH_AT) {
            reg.set(pos, CRUSH_AT);
            return new Placed(pos.immutable(), natural, CRUSH_AT,
                    support.immutable(), best, false, support.immutable());
        }

        int assigned = Math.min(natural, best - 1);
        reg.set(pos, assigned);
        return new Placed(pos.immutable(), natural, assigned,
                support.immutable(), best, false, null);
    }

    /**
     * Mining does not damage integrity, but it does disturb the ground: the mined
     * block's structural neighbours stop being anchors and enter wbireg at their
     * natural value - tracked, undamaged, no longer ground.
     *
     * This is what makes carving matter. Disturbance spreads exactly one block, so
     * a mass stays grounded as long as it keeps an untouched core: carve something
     * thin and every block in it is non-anchor, carve a tunnel through something
     * thick and the rock behind the wall is still ground.
     *
     * @return the positions that were newly disturbed
     */
    public static List<BlockPos> disturb(ServerLevel level, WbiReg reg, BlockPos pos,
                                         @Nullable BlockPos ghost) {
        List<BlockPos> out = new ArrayList<>(6);
        for (Direction d : DIRS) {
            BlockPos n = pos.relative(d);
            if (!isStructural(level, n, ghost) || !reg.isAnchor(n)) {
                continue;
            }
            reg.set(n, naturalOf(level, n, level.getBlockState(n)));
            out.add(n.immutable());
        }
        return out;
    }

    // =====================================================================
    // Evaluation - read-only
    // =====================================================================

    private record Region(int count, boolean grounded) {}

    public record Face(Direction dir, int count, boolean grounded, boolean shared) {}

    public record Result(
            boolean structural,
            BlockPos pos,
            BlockState state,
            int natural,
            int stored,
            boolean anchor,
            List<Face> faces,
            int hangMax,
            int hangSum,
            boolean grounded,
            boolean capped,
            long micros
    ) {
        /** Literal spec reading: the worst single face binds. */
        public int integrityMax() {
            return stored - hangMax;
        }

        /** Physical reading: load hung off several faces adds up. */
        public int integritySum() {
            return stored - hangSum;
        }

        public String verdict() {
            if (anchor) {
                return "GROUND";
            }
            if (!grounded) {
                return "FLOATING";
            }
            int v = integrityMax();
            if (v <= 0) {
                return "BREAK";
            }
            if (v == 1) {
                return "CRACK";
            }
            return "OK";
        }
    }

    public static Result compute(ServerLevel level, WbiReg reg, BlockPos pos) {
        return compute(level, reg, pos, null);
    }

    /**
     * A connected component and whether it can reach ground.
     *
     * @param blocks  every position in the component, empty when it is grounded -
     *                a grounded component is not collected, the fill stops the
     *                moment it proves ground is reachable
     * @param capped  the component is larger than the limit it was collected under,
     *                so {@code blocks} is a prefix and not the whole thing
     */
    public record Component(List<BlockPos> blocks, boolean grounded, boolean capped) {}

    /**
     * Enumerate the component containing {@code seed}, which is what stage 2 needs
     * and {@link #compute} does not give: compute counts a region to size the hang
     * term, this one names every block in it so it can be assembled.
     *
     * Same traversal rules as {@link #fill}: faces only, air and fluids are gaps,
     * and a position with no wbireg row is ground - reaching one ends the search
     * immediately, because a component that touches ground is not going anywhere
     * and its contents are of no interest.
     */
    public static Component collect(ServerLevel level, WbiReg reg, BlockPos seed,
                                    @Nullable BlockPos ghost, int max) {
        if (!isStructural(level, seed, ghost) || reg.isAnchor(seed)) {
            return new Component(List.of(), true, false);
        }

        List<BlockPos> out = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        BlockPos start = seed.immutable();
        visited.add(start);
        queue.add(start);

        while (!queue.isEmpty() && out.size() < max) {
            BlockPos p = queue.poll();
            if (reg.isAnchor(p)) {
                return new Component(List.of(), true, false);
            }
            out.add(p);
            for (Direction dir : DIRS) {
                BlockPos n = p.relative(dir);
                if (!isStructural(level, n, ghost)) {
                    continue;
                }
                BlockPos ni = n.immutable();
                if (visited.add(ni)) {
                    queue.add(ni);
                }
            }
        }
        return new Component(out, false, !queue.isEmpty());
    }

    /**
     * The one predicate stage 2 turns into a fall: can this position reach ground
     * through connected blocks at all? False means the whole region it belongs to
     * is a sublevel, whatever its stored integrity says.
     */
    public static boolean isConnectedToAnchor(ServerLevel level, WbiReg reg, BlockPos pos,
                                              @Nullable BlockPos ghost) {
        return compute(level, reg, pos, ghost).grounded();
    }

    /**
     * @param ghost a position to treat as air - the block being broken, which is
     *              still present in the world when BreakEvent fires
     */
    public static Result compute(ServerLevel level, WbiReg reg, BlockPos pos, @Nullable BlockPos ghost) {
        long t0 = System.nanoTime();

        BlockState state = level.getBlockState(pos);
        if (!isStructural(level, pos, ghost)) {
            return new Result(false, pos.immutable(), state, 0, 0, false,
                    List.of(), 0, 0, false, false, (System.nanoTime() - t0) / 1000L);
        }

        int natural = naturalOf(level, pos, state);
        boolean anchor = reg.isAnchor(pos);
        int stored = anchor ? natural : reg.get(pos);

        Set<BlockPos> visited = new HashSet<>();
        Map<BlockPos, Integer> regionOf = new HashMap<>();
        List<Region> regions = new ArrayList<>();
        List<Face> faces = new ArrayList<>(6);
        boolean capped = false;

        for (Direction d : DIRS) {
            BlockPos n = pos.relative(d);
            if (!isStructural(level, n, ghost)) {
                faces.add(new Face(d, 0, false, false));
                continue;
            }
            Integer existing = regionOf.get(n);
            if (existing != null) {
                Region r = regions.get(existing);
                faces.add(new Face(d, r.count(), r.grounded(), true));
                continue;
            }
            int idx = regions.size();
            Fill fill = fill(level, reg, n, pos, ghost, idx, visited, regionOf);
            regions.add(new Region(fill.count, fill.grounded));
            capped |= fill.capped;
            faces.add(new Face(d, fill.count, fill.grounded, false));
        }

        int hangMax = 0;
        int hangSum = 0;
        boolean grounded = anchor;
        for (Region r : regions) {
            if (r.grounded()) {
                grounded = true;
            } else {
                hangSum += r.count();
                if (r.count() > hangMax) {
                    hangMax = r.count();
                }
            }
        }

        return new Result(true, pos.immutable(), state, natural, stored, anchor, faces,
                hangMax, hangSum, grounded, capped, (System.nanoTime() - t0) / 1000L);
    }

    // ---------------------------------------------------------------------

    private static final class Fill {
        int count;
        boolean grounded;
        boolean capped;
    }

    /**
     * Flood fill one region, starting at {@code start} and never crossing {@code origin}.
     * Anchors are reached but not counted and not expanded through - they are where
     * the structure ends and the world begins.
     */
    private static Fill fill(ServerLevel level, WbiReg reg, BlockPos start, BlockPos origin,
                             @Nullable BlockPos ghost, int idx,
                             Set<BlockPos> visited, Map<BlockPos, Integer> regionOf) {
        Fill out = new Fill();
        Deque<BlockPos> queue = new ArrayDeque<>();

        visited.add(start);
        regionOf.put(start, idx);
        queue.add(start);

        while (!queue.isEmpty() && out.count < MAX_REGION) {
            BlockPos p = queue.poll();
            if (reg.isAnchor(p)) {
                out.grounded = true;
                continue; // ground: do not count, do not expand through
            }
            out.count++;
            for (Direction dir : DIRS) {
                BlockPos n = p.relative(dir);
                if (n.equals(origin) || !isStructural(level, n, ghost)) {
                    continue;
                }
                if (visited.add(n)) {
                    regionOf.put(n, idx);
                    queue.add(n);
                }
            }
        }
        out.capped = !queue.isEmpty();
        return out;
    }

    /**
     * naturalintegrityreg lookup. A block with no row falls back to a rule, not a
     * list: something with no collision shape holds one block, anything else holds
     * {@link #DEFAULT_INTEGRITY}.
     */
    public static int naturalOf(ServerLevel level, BlockPos pos, BlockState state) {
        BlockIntegrity row = state.getBlock().builtInRegistryHolder().getData(SIDataMaps.NATURAL);
        if (row != null) {
            return row.integrity();
        }
        return state.getCollisionShape(level, pos).isEmpty() ? DEFAULT_FRAGILE : DEFAULT_INTEGRITY;
    }

    public static boolean hasRow(BlockState state) {
        return state.getBlock().builtInRegistryHolder().getData(SIDataMaps.NATURAL) != null;
    }

    /**
     * Air and fluids are the only things outside the system. A torch is not
     * excluded, it is integrity 1: place a block on one and the torch is at 0 and
     * the block it was carrying has nowhere to sit. No special-case list.
     */
    public static boolean isStructural(ServerLevel level, BlockPos pos, @Nullable BlockPos ghost) {
        if (ghost != null && ghost.equals(pos)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        return !state.isAir() && !(state.getBlock() instanceof LiquidBlock);
    }
}
