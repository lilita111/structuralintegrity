package com.apokalypse.structuralintegrity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
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

    /**
     * How far a placement's charge is allowed to descend before it is abandoned.
     *
     * This is a chain, not a fill, so it is bounded by the height of the structure
     * rather than by its mass - a couple of hundred is already past bedrock. Reaching
     * it means the support graph has led somewhere unreasonable and the pass gives up
     * rather than walking forever; it is reported, never silent.
     */
    public static final int MAX_LOAD_PATH = 256;
    /** Default for a solid block with no row in naturalintegrityreg. */
    public static final int DEFAULT_INTEGRITY = 8;
    /** Default for a block with no collision shape - a torch, a flower, a rail. */
    public static final int DEFAULT_FRAGILE = 1;
    /**
     * A block degraded to this has nothing left and fails outright. 1 is the
     * cracked state - loaded to its last point, still standing.
     */
    public static final int FAIL_AT = 0;

    private static final Direction[] DIRS = Direction.values();

    // =====================================================================
    // Placement - the only thing that writes an integrity value
    // =====================================================================

    /**
     * What a placement did. Reported, never guessed at from the log.
     *
     * @param support   the neighbour the block was built on, or null if it had none
     * @param supportAt that neighbour's integrity before the placement charged it
     * @param failed    every block the charge pass drove to {@link #FAIL_AT}
     * @param degraded  how many blocks the pass reduced, failed ones included
     */
    public record Placed(
            BlockPos pos, int natural, int assigned,
            @Nullable BlockPos support, int supportAt,
            boolean onAnchor, List<BlockPos> failed, int degraded, boolean capped,
            String trace
    ) {}

    /**
     * What one {@link #chain} pass did.
     *
     * @param trace the path it walked, {@code x,y,z=value} per step, for the log
     */
    public record Chained(int count, List<BlockPos> failed, boolean capped, String trace) {}

    /**
     * What holds a block up: the neighbour with the most left in it.
     *
     * The single definition of support in the mod. {@link #place} asks it what the
     * new block inherits from, {@link #chain} asks it where the load goes next, and
     * because both ask the same question the answer cannot disagree between them.
     *
     * Gravity decides, not strength. One structural neighbour is support with
     * nothing to compare against - the load goes along it wherever it points, UP
     * included. With more than one, the most downward-facing wins outright: DOWN
     * if it is there, otherwise a sideways neighbour, otherwise UP. Several
     * sideways candidates have no physical reason to prefer one over another, so
     * the pick is random rather than a fixed enumeration order that would make
     * every collapse lean the same compass direction.
     *
     * Stored strength does not vote. It used to - strongest neighbour won - but
     * that let a strong side wall pull the load sideways off a weak footing, and
     * pressure does not do that: it goes down through whatever is underneath,
     * and whether the footing can take it is the footing's problem.
     *
     * @param exclude positions the chain has already been through, or null
     * @return the supporting neighbour, or null if nothing structural touches it
     */
    @Nullable
    public static BlockPos supportOf(ServerLevel level, WbiReg reg, BlockPos pos,
                                     @Nullable Set<BlockPos> exclude) {
        if (!SIConfig.gravityFirstChain()) {
            return strongestSupportOf(level, reg, pos, exclude);
        }
        BlockPos down = null;
        BlockPos up = null;
        List<BlockPos> sideways = new ArrayList<>(4);
        for (Direction d : DIRS) {
            BlockPos n = pos.relative(d).immutable();
            if (exclude != null && exclude.contains(n)) {
                continue;
            }
            if (!isStructural(level, n, null)) {
                continue;
            }
            if (d == Direction.DOWN) {
                down = n;
            } else if (d == Direction.UP) {
                up = n;
            } else {
                sideways.add(n);
            }
        }
        if (down != null) {
            return down;
        }
        if (!sideways.isEmpty()) {
            return sideways.size() == 1 ? sideways.get(0)
                    : sideways.get(level.getRandom().nextInt(sideways.size()));
        }
        return up;
    }

    /**
     * The pre-0.5.2 rule, kept behind {@code gravityFirstChain=false}: the neighbour
     * with the most stored integrity carries the load, ties going DOWN first because
     * of {@link Direction} enumeration order.
     */
    @Nullable
    private static BlockPos strongestSupportOf(ServerLevel level, WbiReg reg, BlockPos pos,
                                               @Nullable Set<BlockPos> exclude) {
        BlockPos best = null;
        int bestAt = Integer.MIN_VALUE;
        for (Direction d : DIRS) {
            BlockPos n = pos.relative(d).immutable();
            if (exclude != null && exclude.contains(n)) {
                continue;
            }
            if (!isStructural(level, n, null)) {
                continue;
            }
            int at = storedAt(level, reg, n);
            if (at > bestAt) {
                bestAt = at;
                best = n;
            }
        }
        return best;
    }

    /**
     * The one integrity function: applies {@code delta} to every block of the
     * support chain. Placing charges the chain with -1; breaking runs the same
     * walk with +1, because a removed load lets the chain relax; an explosion
     * relaxes it by its shockwave delta instead.
     *
     * The walk is the same either way. From {@code start} the pass applies the
     * delta, then asks {@link #supportOf} what holds THAT up and repeats, so it
     * travels the chain the weight actually travels: block, its footing, that
     * footing's footing, down to ground. Ground ends it - rock takes the load and
     * passes none on - and so does a block driven to {@link #FAIL_AT}, which is
     * not carrying anything any more and therefore has nothing to hand down.
     *
     * The sign picks the clamp. Charging (delta &lt; 0) floors at failAt - a
     * block cannot be worse than spent - and the first block driven there snaps
     * the chain. Relaxing (delta &gt; 0) caps at each block's own natural
     * integrity - it never heals past what it is - and a value already above
     * natural (a clump grant) is left where it stands, not cut down. Nothing can
     * fail from gaining, so a positive walk never snaps.
     *
     * There is no falloff along the chain: every block in it pays the same delta.
     * That is what makes the base of a pillar fail first - it is on the path of every
     * placement above it, so it is charged once per block, while the tip is charged
     * once in its life.
     *
     * This is a chain and not a flood, and the difference is the whole behaviour. A
     * flood charges every block connected to the support, which means a placement on
     * a beach charges the beach; and because a flood has to pick a visit order, which
     * block failed depended on {@link Direction} enumeration order rather than on
     * anything physical - DOWN being first, the spread dived into the soil under the
     * support instead of travelling along what was built, so a sideways run failed one
     * block behind the tip while a pillar, having only one structural neighbour,
     * behaved correctly. Following supports removes the choice, and with it the
     * asymmetry: there is exactly one next block at every step.
     *
     * Iterative, not literally recursive - {@link #MAX_LOAD_PATH} deep would overflow
     * the stack. The cap is reported, never silent.
     *
     * @param origin   the block whose place or break caused the walk, excluded from
     *                 the path because it is the load rather than any part of what
     *                 carries it; also the held-up side when the chain snaps on the
     *                 first step
     * @param excluded further positions the walk must not enter, or null - an
     *                 explosion passes every position the blast is about to take,
     *                 so the shockwave lands on survivors only
     * @return the side(s) of the snap the config chose to break, for the caller to
     *         destroy - possibly empty even when the chain snapped
     */
    public static Chained chain(ServerLevel level, WbiReg reg, BlockPos start,
                                @Nullable BlockPos origin, @Nullable Set<BlockPos> excluded,
                                int delta) {
        List<BlockPos> failed = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>();
        StringBuilder trace = new StringBuilder();
        if (origin != null) {
            visited.add(origin.immutable());
        }
        if (excluded != null) {
            for (BlockPos p : excluded) {
                visited.add(p.immutable());
            }
        }

        BlockPos cur = start == null ? null : start.immutable();
        BlockPos prev = origin == null ? null : origin.immutable();
        int count = 0;
        boolean capped = false;
        int maxLoadPath = SIConfig.maxLoadPath();
        int failAt = SIConfig.failAt();

        while (cur != null) {
            if (count >= maxLoadPath) {
                capped = true;
                break;
            }
            // Ground is where the load was always going. It is not reduced.
            if (!isStructural(level, cur, null) || isAnchor(level, reg, cur)) {
                break;
            }
            // A support graph can close a loop. Arriving twice means the chain has
            // nowhere left to descend to, so it ends here rather than circling.
            if (!visited.add(cur)) {
                break;
            }

            int stored = storedAt(level, reg, cur);
            int now;
            if (delta < 0) {
                // A block cannot be worse than spent. Below FAIL_AT the number is
                // meaningless - it is already queued for destruction - and it only
                // makes the report harder to read.
                now = Math.max(failAt, stored + delta);
            } else {
                now = Math.min(stored + delta,
                        Math.max(stored, naturalOf(level, cur, level.getBlockState(cur))));
            }
            if (now != stored) {
                reg.set(cur, now);
            }
            count++;
            if (trace.length() > 0) {
                trace.append(" -> ");
            }
            trace.append(cur.getX()).append(',').append(cur.getY()).append(',')
                    .append(cur.getZ()).append('=').append(now);

            if (delta < 0 && now <= failAt) {
                trace.append("!SNAP");
                snap(cur, prev, failed, trace);
                break;
            }

            prev = cur;
            cur = supportOf(level, reg, cur, visited);
        }
        return new Chained(count, failed, capped, trace.toString());
    }

    /**
     * The chain has snapped at {@code spent} - really simply, the block whose
     * wbireg value ran out. A snap has two sides: the spent block is the weaker
     * side, because running out first is what being weaker means here, and
     * {@code above} - the block it was holding up, the placed block itself when
     * the snap lands on the first step - is the stronger side. Config chooses
     * which side gives way: weaker, stronger, both, or neither.
     */
    private static void snap(BlockPos spent, @Nullable BlockPos above,
                             List<BlockPos> failed, StringBuilder trace) {
        BlockPos weaker = spent;
        BlockPos stronger = above;
        if (SIConfig.breakWeakerBlock()) {
            failed.add(weaker);
            trace.append(" weaker@").append(weaker.getX()).append(',').append(weaker.getY())
                    .append(',').append(weaker.getZ()).append("!FAIL");
        }
        if (SIConfig.breakStrongerBlock() && stronger != null) {
            failed.add(stronger);
            trace.append(" stronger@").append(stronger.getX()).append(',').append(stronger.getY())
                    .append(',').append(stronger.getZ()).append("!FAIL");
        }
    }

    /**
     * How many neighbours of {@code pos} are structural and the same block as
     * {@code matchBlock}.
     */
    private static int countSameTypeNeighbors(ServerLevel level, BlockPos pos, Block matchBlock) {
        int n = 0;
        for (Direction d : DIRS) {
            BlockPos p = pos.relative(d).immutable();
            if (!isStructural(level, p, null)) {
                continue;
            }
            if (level.getBlockState(p).getBlock() == matchBlock) {
                n++;
            }
        }
        return n;
    }

    /**
     * The value a block enters wbireg with. Clumps stay together: a block sitting
     * in threshold-many same-type neighbours gets its clump grant - its natural
     * integrity added on top of {@code base}, or, with {@code clumpAddsOnTop}
     * off, just its max natural. The grant is paid here - at row creation - and
     * only here, so it is paid once; from then on the chain takes its -1 from the
     * block like from any other, and the grant wears away instead of renewing.
     */
    private static int initialValue(ServerLevel level, BlockPos pos, int base) {
        int threshold = SIConfig.clumpBracingThreshold();
        if (threshold <= 0) {
            return base;
        }
        BlockState state = level.getBlockState(pos);
        if (countSameTypeNeighbors(level, pos, state.getBlock()) < threshold) {
            return base;
        }
        int natural = naturalOf(level, pos, state);
        int value = SIConfig.clumpAddsOnTop() ? base + natural : Math.max(base, natural);
        StructuralIntegrity.LOGGER.info("[SI] clump init {},{},{}: {} -> {} (natural {})",
                pos.getX(), pos.getY(), pos.getZ(), base, value, natural);
        return value;
    }

    /**
     * Assign the placed block its integrity from the block it was built on.
     *
     * <pre>
     *   touching ground   assigned = natural                        nothing charged
     *   otherwise         assigned = min(natural, best connection)  chain(support, -1) charges
     * </pre>
     *
     * The new block does not arrive weaker than what it stands on - it arrives equal
     * to it, capped at its own natural, because a block is only ever as sound as its
     * footing. What the placement costs is paid underneath, by {@link #chain}.
     *
     * This is the reverse of the obvious reading, and it is the physical one: weight
     * travels down. The block at the bottom of a pillar is charged once for every
     * block above it, so it is the first to fail, and it fails while the tip is still
     * near full. A support with no wbireg row is ground - it is charged nothing and
     * passes nothing on, so founding on rock is free.
     *
     * A block driven to {@link #FAIL_AT} is destroyed by the caller next tick.
     * Whatever it was holding is then floating, and goes the way every disconnected
     * block goes: {@link #isConnectedToAnchor} is false and it becomes a sublevel.
     *
     * The support follows gravity - below first, then sideways, then above - so a
     * block bridging out from a cliff face is still held by the cliff, but a block
     * with anything at all underneath is held by that, whatever a side wall offers.
     */
    @Nullable
    public static Placed place(ServerLevel level, WbiReg reg, BlockPos pos) {
        if (!isStructural(level, pos, null)) {
            return null;
        }
        int natural = naturalOf(level, pos, level.getBlockState(pos));

        BlockPos support = supportOf(level, reg, pos, null);

        if (support == null) {
            // Placed touching nothing structural. It supports itself and no more.
            reg.set(pos, 0);
            return new Placed(pos.immutable(), natural, 0, null, 0, false, List.of(), 0, false, "");
        }

        // The value comes from the strongest connection, not blindly from below -
        // the block underneath may be the weakest thing touching us, and a block
        // held by a strong wall is as sound as that wall. Only the CHARGE follows
        // gravity; where strength is inherited from is a separate question.
        boolean founded = false;
        int best = Integer.MIN_VALUE;
        for (Direction dir : DIRS) {
            BlockPos n = pos.relative(dir).immutable();
            if (!isStructural(level, n, null)) {
                continue;
            }
            int at = storedAt(level, reg, n);
            if (at == WbiReg.ANCHOR) {
                founded = true;
                break;
            }
            if (at > best) {
                best = at;
            }
        }

        if (founded) {
            // Touching ground anywhere. Full strength, and nothing is charged -
            // rock takes the load and passes none on.
            int assigned = initialValue(level, pos, natural);
            reg.set(pos, assigned);
            return new Placed(pos.immutable(), natural, assigned,
                    support.immutable(), WbiReg.ANCHOR, true, List.of(), 0, false, "");
        }

        // Inherit the best footing, then charge the structure the load rests on.
        int assigned = initialValue(level, pos, Math.min(natural, best));
        reg.set(pos, assigned);
        Chained d = chain(level, reg, support, pos, null, -1);
        return new Placed(pos.immutable(), natural, assigned,
                support.immutable(), best, false, d.failed(), d.count(), d.capped(), d.trace());
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
            if (!isStructural(level, n, ghost) || !isAnchor(level, reg, n)) {
                continue;
            }
            reg.set(n, initialValue(level, n, naturalOf(level, n, level.getBlockState(n))));
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
        /**
         * What the block has left, and the only thing that decides whether it stands.
         *
         * Once the placement cost moved into the stored value this became the whole
         * of it. The hang terms below used to be subtracted here as well, which
         * charged every placement twice: {@link #chain} took a point off the
         * support permanently, and then this took another off for the same weight
         * still sitting on it. A pillar therefore failed at half the height its
         * material said it should.
         */
        public int integrity() {
            return stored;
        }

        /**
         * Dead weight on the worst single face. Reported, not charged - see
         * {@link #integrity()} for why.
         */
        public int integrityMax() {
            return stored - hangMax;
        }

        /** Dead weight summed over every face. Reported, not charged. */
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
            int v = integrity();
            int failAt = SIConfig.failAt();
            if (v <= failAt) {
                return "BREAK";
            }
            if (v == failAt + 1) {
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
        if (!isStructural(level, seed, ghost) || isAnchor(level, reg, seed)) {
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
            if (isAnchor(level, reg, p)) {
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

        // The cap stopped the walk mid-frontier, before every discovered position was
        // dequeued and checked. Those positions cost nothing further to look at - they
        // are already in hand, just unexamined - so a component that is actually
        // grounded one hop past the cap is still reported grounded instead of a false
        // DETACHED. Ground reachable only by expanding further stays undetermined,
        // which is what the cap means.
        for (BlockPos p : queue) {
            if (isAnchor(level, reg, p)) {
                return new Component(List.of(), true, false);
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
        int reading = storedAt(level, reg, pos);
        boolean anchor = reading == WbiReg.ANCHOR;
        int stored = anchor ? natural : reading;

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

        while (!queue.isEmpty() && out.count < SIConfig.maxRegion()) {
            BlockPos p = queue.poll();
            if (isAnchor(level, reg, p)) {
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
        // Same cap-boundary check as collect(): the remaining queue is already
        // discovered, just unexamined, so a region grounded one hop past the cap is
        // still reported grounded rather than a false hang.
        if (out.capped && !out.grounded) {
            for (BlockPos p : queue) {
                if (isAnchor(level, reg, p)) {
                    out.grounded = true;
                    break;
                }
            }
        }
        return out;
    }

    /**
     * The wbireg reading for a position, and the only place anchor-ness is decided.
     *
     * Ground is "no row". Some blocks may never mean that: loose material and growth
     * hold themselves up and nothing else. Rather than carve out a second kind of
     * ground, such a block is simply given the row it should have had, at its own
     * natural, the first time the solver looks at it - after which it is an ordinary
     * tracked block and every rule below applies to it unchanged.
     *
     * This is the air rule one step in. Air says "not part of the structure";
     * never-anchor says "part of the structure, never the thing holding it up".
     *
     * @return the stored value, or {@link WbiReg#ANCHOR} if this position is ground
     */
    public static int storedAt(ServerLevel level, WbiReg reg, BlockPos pos) {
        int v = reg.get(pos);
        if (v != WbiReg.ANCHOR) {
            return v;
        }
        BlockState state = level.getBlockState(pos);
        BlockIntegrity row = state.getBlock().builtInRegistryHolder().getData(SIDataMaps.NATURAL);
        int natural = naturalOf(level, pos, state);
        // Ground is the permissive default - but nothing with no collision shape can
        // hold anything up, so it never gets to be ground either way; that is the same
        // floor naturalOf already gave it below. Listed rows still decide for themselves,
        // unless the config's defaultAnchorBlocks forces anchor status back on.
        boolean neverAnchor = row != null ? row.neverAnchor()
                : natural == SIConfig.defaultFragileIntegrity();
        if (neverAnchor && SIConfig.defaultAnchorBlocks().contains(state.getBlock())) {
            neverAnchor = false;
        }
        if (!neverAnchor) {
            return WbiReg.ANCHOR;
        }
        int init = initialValue(level, pos, natural);
        reg.set(pos, init);
        return init;
    }

    /** True when this position is ground: untouched, and allowed to be. */
    public static boolean isAnchor(ServerLevel level, WbiReg reg, BlockPos pos) {
        return storedAt(level, reg, pos) == WbiReg.ANCHOR;
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
        return state.getCollisionShape(level, pos).isEmpty()
                ? SIConfig.defaultFragileIntegrity() : SIConfig.defaultIntegrity();
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
        if (state.isAir() || state.getBlock() instanceof LiquidBlock) {
            return false;
        }
        return !SIConfig.nonStructuralBlocks().contains(state.getBlock());
    }
}
