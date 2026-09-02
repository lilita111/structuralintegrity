package com.apokalypse.structuralintegrity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BushBlock;
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
     * rather than by its mass - 1024 covers the full build height with room for a
     * winding path. Reaching it means the support graph has led somewhere unreasonable
     * and the pass gives up rather than walking forever; it is reported, never silent.
     */
    public static final int MAX_LOAD_PATH = 1024;
    /** Default for a solid block with no row in naturalintegrityreg. */
    public static final int DEFAULT_INTEGRITY = 24;
    /** Default for a block with no collision shape - a torch, a flower, a rail. */
    public static final int DEFAULT_FRAGILE = 1;
    /**
     * A block degraded to this has nothing left and fails outright. 1 is the
     * cracked state - loaded to its last point, still standing.
     */
    public static final int FAIL_AT = 0;
    /**
     * Default for {@link SIConfig#holdSpentUpToNatural}. Holds soil and nothing
     * else: dirt is natural 1, sand and gravel are 2, and the next material up
     * is grass_block at 4.
     */
    public static final int HOLD_SPENT_UP_TO_NATURAL = 2;

    private static final Direction[] DIRS = Direction.values();

    // =====================================================================
    // Placement - the only thing that writes an integrity value
    // =====================================================================

    /**
     * What a placement did. Reported, never guessed at from the log.
     *
     * @param support   the neighbour the block was built on, or null if it had none
     * @param supportAt that neighbour's integrity before the placement charged it
     * @param sideHost  the natural RATING of the block the side cap was taken from,
     *                  when the winning connection came in through a horizontal
     *                  face, 0 otherwise. Reported but no longer used to compute
     *                  anything: since 0.7.9 the fraction comes off sideHostStored.
     *                  It stays in the report because the rating names the material
     *                  and the stored value alone does not, and a log that cannot
     *                  say what the host was made of cannot be read back later.
     * @param sideHostStored  what that host still HELD, the value the fraction was
     *                  actually taken of, 0 when no horizontal face won. The two
     *                  numbers agree on a fresh host and separate as it wears,
     *                  which is exactly the case a single number cannot report -
     *                  0.7.8 and 0.7.9 are indistinguishable in a log that prints
     *                  only one of them.
     * @param failed    every block the charge pass drove to {@link #FAIL_AT}
     * @param degraded  how many blocks the pass reduced, failed ones included
     */
    public record Placed(
            BlockPos pos, int natural, int assigned,
            @Nullable BlockPos support, int supportAt, int sideHost, int sideHostStored,
            boolean onAnchor, List<BlockPos> failed, int degraded, boolean capped,
            String trace
    ) {}

    /**
     * What one {@link #chain} pass did.
     *
     * @param trace the path it walked, {@code x,y,z=value} per step, for the log
     */
    public record Chained(int count, List<BlockPos> failed, boolean capped, String trace,
                          List<Touched> touched) {}

    /**
     * One block a {@link #chain} pass actually rewrote, and by how much.
     *
     * {@code applied} is the SIGNED difference the row really moved by, measured
     * after every clamp the walk applies - not the delta that was asked for. A
     * sideways link doubles the charge, a material boundary hands the whole deficit
     * across, and the floor at {@code failAt} truncates whatever is left; so the
     * block a caller charged "-1" may have moved -2, or -1 when it asked for -2.
     * Recording the real movement is what lets {@link #restoreTouched} put the
     * structure back exactly as it was rather than approximately.
     *
     * Only rows whose stored value CHANGED are recorded. A block the walk visited
     * but left untouched - already at natural on a relax, already spent on a charge
     * - has nothing to give back.
     */
    public record Touched(BlockPos pos, int applied) {}

    /** What one {@link #restoreTouched} pass gave back. */
    public record Restored(int count, int skipped, String trace) {}

    /**
     * Where a block's load ends up, read without changing anything.
     *
     * The support chain has no falloff - every block above pays the same amount
     * into the same footing - so the block at the BOTTOM of the walk is the one
     * that fails first, and it is the only number that says whether a structure is
     * in danger. A pristine plank reading 20/20 tells a builder nothing while the
     * stone course under it sits one point from going.
     *
     * @param last    the last block the walk applied to before it exited - the
     *                footing, or null if the start was not part of a chain at all
     * @param stored  that block's wbireg row as it stands now
     * @param natural that block's material rating, the denominator to show it against
     * @param path    every block the walk covered, in order, start first
     * @param capped  the walk hit {@link SIConfig#maxLoadPath} and gave up, so
     *                {@code last} is where it stopped rather than a real footing
     */
    public record Probe(@Nullable BlockPos last, int stored, int natural,
                        List<BlockPos> path, boolean capped, String trace) {

        /** How many blocks deep the chain ran. */
        public int depth() {
            return path.size();
        }

        /** Whether the walk found anything at all to report. */
        public boolean found() {
            return last != null;
        }
    }

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
     * the pick is scattered by position rather than following a fixed enumeration
     * order that would make every collapse lean the same compass direction.
     *
     * Scattered, NOT re-rolled. A RandomSource here breaks the invariant above:
     * the same block would answer one neighbour to place() and another to chain(),
     * inheriting its strength through one wall and spending its load through the
     * other, and the same structure would collapse differently every evaluation.
     * Hashing the position gives the same spread across a wall - adjacent blocks
     * pick differently - while any one block always answers the same way.
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
            if (!conductsLoad(level, reg, n, null)) {
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
                    : sideways.get(Math.floorMod(scatter(pos), sideways.size()));
        }
        return up;
    }

    /**
     * A stable per-position scatter for the sideways tie-break - a cheap integer hash
     * of the coordinates, not a random draw, so the choice varies from block to block
     * but never varies for the same block between calls.
     *
     * Package-private rather than private because {@link SIForce#tumbleAxis} needs
     * the same property for a different question - which way a piece whose mass sits
     * squarely over the failure should fall - and two hashes that must agree on
     * "stable per position" are better as one.
     */
    static int scatter(BlockPos pos) {
        int h = pos.getX() * 0x9E3779B9 ^ pos.getY() * 0x85EBCA6B ^ pos.getZ() * 0xC2B2AE35;
        h ^= h >>> 15;
        h *= 0x2545F491;
        h ^= h >>> 13;
        return h;
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
            if (!conductsLoad(level, reg, n, null)) {
                continue;
            }
            int at = peekAt(level, reg, n);
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
     * Material matters at the crossings, and only there. The step where the walk
     * passes INTO a sturdier material - the next block's natural integrity above
     * the previous block's - is a BRACE: one point of the loss moves off the
     * weaker block and onto the sturdier one. The weaker block is handed its point
     * back, so it ends the pass unchanged; the sturdier one takes an extra point,
     * so a -1 becomes -2; and the walk carries on past the crossing. The pair is
     * conserved, which is what keeps a place and its later break cancelling
     * exactly, and the relax mirrors it - the sturdier block gains two, the weaker
     * gives one back. If a clamp stops the sturdier block from taking its extra
     * point, no point moved, so nothing is refunded either. Unconditional since
     * 0.7.8: this IS the rule for a rise, not one of two ways a rise could be
     * scored, so no config turns it off. It does not stand on the FIRST step of a
     * walk, where the block handing the load over is the origin - the load itself,
     * never charged by the chain - so there is nothing to refund and no transfer
     * to make.
     *
     * The crossing never interrupts the walk. Past the sturdier block the chain
     * carries on into whatever holds IT up, every one of those blocks taking the
     * plain delta as {@code compute()} says. A rise concentrates one point onto the
     * foundation; it does not terminate at it.
     *
     * Into a WEAKER material, since 0.7.8, nothing happens at all: the crossing takes
     * the plain delta like any other step, and the walk carries on. The rule is
     * deliberately one-sided - a rise into sturdier material is an event, a fall into
     * weaker material is not, because a structure built out of weak material, or
     * standing on it, already runs out and falls on the plain delta alone.
     *
     * Both directions still TAG themselves in the trace, {@code !RISE} and
     * {@code !FALL}, even though only the rise is charged. A crossing that leaves no
     * mark is a crossing nobody can find in a log afterwards, and finding them is how
     * the 0.7.7 defect below was diagnosed at all.
     *
     * Until 0.7.8 the first block of the weaker material took the previous block's
     * whole remaining deficit instead of the plain delta, and a zero deficit ended
     * the walk outright, traced !ABSORB. It was removed because it was a second
     * penalty for something the ordinary arithmetic already says. Weak materials
     * have low natural ratings, so a structure built out of them, or standing on
     * them, runs out and falls on the plain delta alone - no boundary rule needed
     * to make that happen. What the transfer actually did was charge whatever hung
     * off a structure the structure's entire accumulated wear, in one step, every
     * pass: {@code prevDeficit} is a running total measured from the row's entry
     * value, and charging the weaker block never discharged it, so the same debt was
     * re-billed on every pass that crossed the joint. In one 0.7.7 session that was
     * 28 of 36 failures, with single steps as large as 45, and it made an attachment
     * to a worn structure unsurvivable however sound its own material was.
     *
     * The rise had a matching stopper, traced {@code !BOUNDARY}, that ended the walk
     * on the sturdier block. It only ever fired with {@code strongerMaterialBraces}
     * off - a braced crossing was already exempt from it - so on a default install it
     * was unreachable, and where it was reachable it contradicted the rule it was
     * supposed to serve: it withheld the -2/+0 transfer AND refused to let the load
     * reach the ground the foundation was standing on. Removed in 0.7.8 along with
     * the gate that was the only way to reach it.
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
        return chain(level, reg, start, origin, excluded, delta, false);
    }

    /**
     * {@link #chain} that can hand back the blocks it rewrote.
     *
     * @param returnList when true, {@link Chained#touched()} lists every row the
     *                   walk changed together with the signed amount it moved by,
     *                   for a caller that means to undo the pass later. When false
     *                   the list comes back empty and nothing is recorded, so the
     *                   existing callers pay nothing for the feature.
     */
    public static Chained chain(ServerLevel level, WbiReg reg, BlockPos start,
                                @Nullable BlockPos origin, @Nullable Set<BlockPos> excluded,
                                int delta, boolean returnList) {
        return chain(level, reg, start, origin, excluded, delta, returnList, false, null);
    }

    /**
     * {@link #chain} with the two knobs only {@link #probe} needs.
     *
     * @param dry  make every decision the walk would make and apply NONE of them:
     *             no row is written, no block is charged, no point is braced. This
     *             is what keeps the goggle read-out and the physics one
     *             implementation rather than two that agree until one is edited.
     * @param path when given, every block the walk applied to, in order
     */
    private static Chained chain(ServerLevel level, WbiReg reg, BlockPos start,
                                 @Nullable BlockPos origin, @Nullable Set<BlockPos> excluded,
                                 int delta, boolean returnList, boolean dry,
                                 @Nullable List<BlockPos> path) {
        List<Touched> touched = returnList ? new ArrayList<>() : null;
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
        // Neither materialBoundaryStops nor strongerMaterialBraces is read here any
        // more. The brace IS the rule for a material rise rather than one option for
        // scoring one, so it is not switchable, and with the !BOUNDARY stopper gone
        // materialBoundaryStops had nothing left to gate. Both keys stay defined in
        // SIConfig on purpose: a build that stops declaring a key does not leave the
        // key alone in an existing TOML, it drops it, and the value silently reverts
        // on the next downgrade. Keeping them declared and unread costs two lines and
        // makes 0.7.8 -> 0.7.7 a version change instead of a config wipe.
        // The material the delta arrives FROM - the placed or removed block itself
        // on the first step. Every caller runs before removal (BreakEvent and
        // Detonate fire with the blocks still present), so its nireg is readable;
        // a null origin starts the walk with no boundary to cross.
        int prevNatural = origin != null && isStructural(level, origin, null)
                ? naturalOf(level, origin, level.getBlockState(origin)) : 0;
        // Set when the link just crossed was sideways AND this block is the sturdier
        // material of the two, so the doubled cost was deferred onto it rather than
        // taken by the block that handed the load over.
        boolean owedSideways = false;
        // Set when the block above decided, on its own iteration, to hand its point
        // of the loss down to this one because this one is sturdier material.
        boolean bracedIn = false;
        // 0.7.8 removed prevDeficit and the weaker-material transfer it fed. Nothing
        // in the walk now carries state from one block to the next except which
        // material was handed FROM and the two one-point transfer flags above, so a
        // step's cost no longer depends on how worn the rest of the chain is.

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

            int natural = naturalOf(level, cur, level.getBlockState(cur));
            // Which way the material changed on the way in. Neither flag decides
            // anything: a rise is scored by the brace, which the block ABOVE settled
            // on its own iteration looking down and left in bracedIn, and a fall is
            // not scored at all. They exist to be reported. A crossing that leaves no
            // mark in the trace is a crossing nobody can find in a log afterwards,
            // and the 0.7.7 attachment defect was only diagnosable because every
            // crossing tagged itself - so both keep tagging themselves now that
            // neither carries a penalty of its own.
            boolean rising = prevNatural > 0 && natural > prevNatural;
            boolean falling = prevNatural > 0 && natural < prevNatural;
            // Where the load leaves this block, worked out BEFORE the charge is,
            // because a sideways link costs one of its two blocks double. Safe to
            // ask this early: cur is already in visited, and nothing between here
            // and the bottom of the loop moves a neighbour's value.
            BlockPos next = supportOf(level, reg, cur, visited);
            int nextNatural = next == null ? 0
                    : naturalOf(level, next, level.getBlockState(next));
            // The brace, decided HERE - before this block is charged - rather than one
            // step later once the walk has arrived on the sturdier block. That timing
            // IS the fix. Charging a block and then handing a point back means the
            // refund can subtract a point the charge never delivered: on a relax, a
            // block already at its natural rating has its +1 clamped away to nothing
            // and the refund still takes 1, so every place/break cycle grinds the wall
            // down. Deciding first, the weaker block simply is not charged, so there is
            // no half of a transfer left for a clamp to eat and the relax mirrors the
            // charge by construction instead of by a guard enumerating clamp cases.
            boolean bracesDown = next != null && nextNatural > natural;
            boolean owes = owedSideways;
            boolean owedNext = false;
            if (next != null && next.getY() == cur.getY()) {
                // The doubled loss always lands on the sturdier MATERIAL of the two,
                // which is what forces a builder to reach for a better block as soon
                // as they span rather than stack. A tie goes to the block handing the
                // load on - the uniform-material case, and the one the reach numbers
                // are tuned against. A block already owing from the link it arrived
                // over does not owe twice; one sideways link, one doubling.
                if (natural >= nextNatural) {
                    owes = true;
                } else {
                    owedNext = true;
                }
            }

            // Every step takes the plain delta. A dry walk takes nothing - it is
            // here to find out WHERE the load goes, not to move it.
            int applied = dry ? 0 : delta;
            if (!dry && owes && (delta < 0 || SIConfig.sidewaysMultiplierOnRestore())) {
                applied = scaleSideways(applied);
            }

            int stored = storedAt(level, reg, cur);

            // The braced point: one point of the loss, in whichever direction the
            // pass is going, moved off the weaker block and onto the sturdier one it
            // rests on. Applied AFTER the sideways doubling on purpose - the doubling
            // scales a load, this moves a fixed point between two blocks, and scaling
            // it would stop the pair balancing.
            //
            // Exactly one movement per block per pass, and that is what makes the pair
            // reversible. A block bracing down is not charged rather than charged and
            // refunded, so no clamp can eat half a transfer. A block that is braced
            // into from above AND braces down below receives one and passes one on,
            // paying its own delta - so the strongest material in the run takes the
            // concentrated loss, which is what a foundation is for.
            int step = delta < 0 ? -1 : 1;
            int carried = dry ? 0
                    : (bracedIn ? step : 0) + (bracesDown ? -step : 0);

            // A block cannot be worse than spent, and cannot heal past what it is.
            // Below FAIL_AT the number is meaningless - already queued for
            // destruction - and it only makes the report harder to read.
            int now = clampToRange(stored + applied + carried, delta, failAt, stored, natural);
            if (!dry && now != stored) {
                reg.set(cur, now);
                if (touched != null) {
                    touched.add(new Touched(cur, now - stored));
                }
            }
            count++;
            if (path != null) {
                path.add(cur);
            }
            if (trace.length() > 0) {
                trace.append(" -> ");
            }
            trace.append(cur.getX()).append(',').append(cur.getY()).append(',')
                    .append(cur.getZ()).append('=').append(now);
            if (owes) {
                trace.append("(side").append(applied).append(')');
            }
            if (carried != 0) {
                // No second write to report any more - each block moves once, so the
                // trace says which direction this block's point went rather than
                // naming a row that was edited twice in one pass.
                trace.append(bracedIn ? "(braced-in " : "(braces-down ")
                        .append(carried).append(')');
            }
            // The material change itself, named after the value it produced so the
            // two can be read against each other. !RISE is expected to sit next to a
            // (braced-in) tag and show a block two down rather than one; !FALL is
            // expected to look exactly like an ordinary step, and if it ever does not,
            // something has started charging the fall again.
            if (rising || falling) {
                trace.append(rising ? "!RISE(" : "!FALL(").append(prevNatural)
                        .append('/').append(natural).append(')');
            }

            if (delta < 0 && now <= failAt) {
                trace.append("!SNAP");
                snap(cur, prev, failed, trace);
                // The config said only the weaker side breaks - but the splinter
                // rule would wear the stronger side anyway when both are the same
                // material, breaking it in the same pass. Shield it.
                if (prev != null && !SIConfig.breakStrongerBlock()) {
                    SIFall.protectFromSplinter(level, prev);
                }
                break;
            }
            owedSideways = owedNext;
            bracedIn = bracesDown;
            prevNatural = natural;
            prev = cur;
            cur = next;
        }
        return new Chained(count, failed, capped, trace.toString(),
                touched == null ? List.of() : touched);
    }

    /**
     * Follow a block's support chain to the bottom without touching anything, and
     * report what is standing there.
     *
     * This is {@link #chain} itself in dry mode, not a second walk that resembles
     * it. Every decision the real charge makes is made here - which neighbour holds
     * the block up, where ground ends the descent, the loop guard, the path cap,
     * and the material crossings including the brace - and none of them are
     * applied. Anything that changes the physics changes this read-out in the same
     * commit, which is the only way a read-out stays true.
     *
     * Walked with delta -1 because the sign selects which exits apply, and the
     * question being asked - where does this block's load end up, and what is
     * holding it there - is the charge direction. No origin: the block being looked
     * at is part of the structure being measured, not a load arriving on top of it,
     * so it is the first step of the walk rather than excluded from it.
     */
    public static Probe probe(ServerLevel level, WbiReg reg, BlockPos start) {
        List<BlockPos> path = new ArrayList<>();
        Chained walked = chain(level, reg, start, null, null, -1, false, true, path);
        if (path.isEmpty()) {
            return new Probe(null, 0, 0, List.of(), walked.capped(), walked.trace());
        }
        BlockPos last = path.get(path.size() - 1);
        return new Probe(last, storedAt(level, reg, last),
                naturalOf(level, last, level.getBlockState(last)),
                List.copyOf(path), walked.capped(), walked.trace());
    }

    /**
     * The one clamp both directions of a {@link #chain} step go through: a charge
     * cannot drive a block below {@code failAt}, and a relax cannot heal one past
     * its own natural integrity - though a value already above natural, a clump
     * grant, is left where it stands rather than cut down to fit.
     */
    private static int clampToRange(int value, int delta, int failAt, int stored, int natural) {
        return delta < 0 ? Math.max(failAt, value)
                : Math.min(value, Math.max(stored, natural));
    }

    /**
     * Give back exactly what a {@code returnList} {@link #chain} pass took.
     *
     * Each row moves by the negation of the amount recorded for it, so a block the
     * walk charged -2 gets +2 and one it charged -1 gets +1. A flat +1 per position
     * would be wrong here and wrong in a way that only shows up over hours of play:
     * the sideways doubling means a block carrying a span is charged twice per pass,
     * so a flat restore would leave one point of permanent wear behind every single
     * time, and a player walking around their own house would eventually collapse it
     * without ever touching a block.
     *
     * A row is skipped when its block is gone or has become ground since the charge -
     * the shock may well have knocked it down, and there is nothing to hand back to a
     * hole. The natural cap still applies, so a restore can never overshoot into
     * strengthening a structure past what its material allows.
     */
    public static Restored restoreTouched(ServerLevel level, WbiReg reg, List<Touched> touched) {
        StringBuilder trace = new StringBuilder();
        int count = 0;
        int skipped = 0;
        for (Touched t : touched) {
            BlockPos pos = t.pos();
            if (t.applied() == 0) {
                continue;
            }
            if (!isStructural(level, pos, null) || isAnchor(level, reg, pos)) {
                skipped++;
                continue;
            }
            int natural = naturalOf(level, pos, level.getBlockState(pos));
            int stored = storedAt(level, reg, pos);
            int now = Math.min(stored - t.applied(), Math.max(stored, natural));
            if (now == stored) {
                skipped++;
                continue;
            }
            reg.set(pos, now);
            count++;
            if (trace.length() > 0) {
                trace.append(" -> ");
            }
            trace.append(pos.getX()).append(',').append(pos.getY()).append(',')
                    .append(pos.getZ()).append('=').append(now)
                    .append("(+").append(-t.applied()).append(')');
        }
        return new Restored(count, skipped, trace.toString());
    }

    /**
     * A charge crossing a sideways link, scaled by
     * {@link SIConfig#sidewaysLoadMultiplier}.
     *
     * Load handed straight down costs the plain amount. Load handed sideways costs
     * double, and the doubled amount is billed to whichever of the link's two blocks
     * is the sturdier material - not to the one doing the handing. That is what puts
     * the cost of spanning on the good block: a floor or a roof reaching out over
     * open air wears its own beams out, so building storeys means using better
     * material than pillaring does.
     *
     * The consequence for reach is the point of the whole rule: at the default 2.0 a
     * player can build out about half as far as they can build up in the same
     * material, because every block placed along a ledge charges the innermost block
     * twice over while every block placed on a pillar charges the bottom one once.
     *
     * The magnitude is scaled and the sign kept, and it never rounds down to nothing
     * - a charge that was going to cost something still costs at least that much.
     */
    private static int scaleSideways(int applied) {
        if (applied == 0) {
            return 0;
        }
        double m = SIConfig.sidewaysLoadMultiplier();
        int magnitude = Math.max(Math.abs(applied), (int) Math.round(Math.abs(applied) * m));
        return applied < 0 ? -magnitude : magnitude;
    }

    /**
     * The most integrity a block may inherit through a side face, rather than by
     * sitting on top of its support.
     *
     * A side joint is the weaker joint, so the block arrives holding a fraction of
     * {@link SIConfig#sideInheritanceFactor} - half by default - of what the block
     * it was stuck TO still has left. Two separate questions were settled here one
     * version apart, and they are worth keeping apart while reading this.
     *
     * WHICH block is measured was settled in 0.7.8: the host, not the guest. A
     * joint is a property of what is being joined TO, not of what is being hung on
     * it, so a shelf bolted to deepslate is a better shelf than the same shelf
     * bolted to planks. Before 0.7.8 the fraction came off the placed block's own
     * rating and the host only ever entered as a separate {@code min} afterwards.
     *
     * WHAT is measured on that host was settled in 0.7.9: its stored value, not its
     * natural rating. Natural integrity says what a material is CAPABLE of; stored
     * integrity says what this particular block still has to GIVE. A side
     * attachment is carried entirely by the block it hangs off, so what it can
     * inherit is bounded by what that block actually has, not by what a pristine
     * example of the same stone would have had. Reading the rating let a wall one
     * point from failing hand out half of a full stone rating, which is the same
     * shape as both 0.7.7 defects - a number derived from the material where it
     * should have been read from the state.
     *
     * So the host's stored value stops being a ceiling applied after the fraction
     * and becomes the thing the fraction is taken OF. The two forms agree exactly
     * on a fresh, unloaded host and diverge the moment it has taken any load: a
     * stone wall worn from 32 down to 10 hands a guest 16 under the 0.7.8 form and
     * 5 under this one.
     *
     * This DOES make a ledge decay outward, which the 0.7.8 form deliberately did
     * not, and that reversal is the point of the change rather than a side effect
     * of it. Natural integrity is a constant per material, so measuring it capped
     * every block along a uniform run at the same half of the same rating - 16, 16,
     * 16, 16. Stored value is not constant, so the run now halves at every step out
     * - 16, 8, 4, 2 - and a cantilever thins to nothing on its own, with no distance
     * rule, no length limit and no config key. The compounding the 0.7.8 note called
     * a trap is the behaviour 0.7.9 wants.
     *
     * Whether the result is floored so a side block never arrives already spent is
     * {@link SIConfig#sideJointNeverArrivesSpent}. It only bites against a host that
     * is nearly spent itself, where half of what little is left rounds away to
     * nothing: floored, a failing wall still carries a one-point shelf; unfloored,
     * attaching to it fails the placement outright. Both are defensible, the choice
     * is not the mod's to make silently, and floored is the default because it is
     * what 0.7.8 did.
     */
    private static int sideInheritanceCap(int hostStored) {
        double f = SIConfig.sideInheritanceFactor();
        if (f >= 1.0) {
            return hostStored;
        }
        int failAt = SIConfig.failAt();
        if (hostStored <= failAt) {
            // The host is spent. There is no fraction of nothing to hand over, and
            // flooring here would let a dead wall grant a live attachment - the one
            // outcome neither side of the config question above wants.
            return hostStored;
        }
        int share = (int) Math.floor(hostStored * f);
        return SIConfig.sideJointNeverArrivesSpent() ? Math.max(failAt + 1, share) : share;
    }

    /**
     * How much of a neighbour's strength may travel through the face it is reached
     * through, expressed as a ceiling on the placed block's assigned value.
     *
     * The one definition of that question, asked by both {@link #place} and
     * {@link #recompute}. They differ in how they find the neighbour - place scans
     * all six faces for the strongest, recompute asks {@link #supportOf} for the one
     * that carries the load - but what a face is worth once found is the same
     * question with the same answer, and it is not worth two copies free to drift.
     *
     * Through a vertical face the block inherits as much as the host still holds,
     * capped at its own natural: sitting on something is the sound joint, and the
     * only ceilings are what the host has left and the material itself. Through a
     * horizontal face what the host has left is first cut to
     * {@link #sideInheritanceCap}, and the block's own natural still applies,
     * because no joint however good makes a block sounder than the stuff it is
     * made of.
     *
     * Since 0.7.9 this returns the COMPLETE ceiling and its callers use what they
     * are handed. Both used to take a further {@code min} against the host's stored
     * value afterwards, which was right while the fraction came off the host's
     * RATING - the stored value was a genuine second, independent limit - and is
     * wrong now that the fraction comes off that very stored value. Applied twice,
     * the outer clamp is a no-op that reads like a rule, and a no-op that reads
     * like a rule is the thing someone later reinstates as a real one.
     *
     * It no longer needs the level or the neighbour's position either: the host
     * enters purely as the number it currently holds. Only {@link #place} still
     * looks the host's material up, and only to name it in the report.
     */
    private static int faceCap(int hostStored, int ownNatural, boolean horizontal) {
        if (!horizontal) {
            return Math.min(ownNatural, hostStored);
        }
        return Math.min(ownNatural, sideInheritanceCap(hostStored));
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
     *
     * @param creating true when the caller is about to write this value as a new
     *                 row - the clump-init line logs then and only then. A pure
     *                 peek asks the same question and logs nothing, because
     *                 nothing happened.
     */
    private static int initialValue(ServerLevel level, BlockPos pos, int base, boolean creating) {
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
        if (creating) {
            StructuralIntegrity.LOGGER.info("[SI] clump init {},{},{}: {} -> {} (natural {})",
                    pos.getX(), pos.getY(), pos.getZ(), base, value, natural);
        }
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
     * to it, capped by {@link #faceCap} for the face it came in through, because a
     * block is only ever as sound as its footing and as the joint to it. What the
     * placement costs is paid underneath, by {@link #chain}.
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
            reg.setEntry(pos, 0);
            return new Placed(pos.immutable(), natural, 0, null, 0, 0, 0, false, List.of(), 0, false, "");
        }

        // The value comes from the strongest connection, not blindly from below -
        // the block underneath may be the weakest thing touching us, and a block
        // held by a strong wall is as sound as that wall. Only the CHARGE follows
        // gravity; where strength is inherited from is a separate question.
        boolean founded = false;
        int best = Integer.MIN_VALUE;
        // The host material the WINNING face's cap was derived from, or 0 when that
        // face was vertical, or nothing won. Carried out to the report so the line
        // shows the rating the cap came from and not only the cap's effect - the
        // whole point of 0.7.8 is which block that number is read off, and a report
        // that shows the result alone cannot tell the two rules apart.
        int sideHost = 0;
        int sideHostStored = 0;
        for (Direction dir : DIRS) {
            BlockPos n = pos.relative(dir).immutable();
            if (!isStructural(level, n, null)) {
                continue;
            }
            int at = peekAt(level, reg, n);
            if (at == WbiReg.ANCHOR) {
                founded = true;
                break;
            }
            // Seated on top of its support a block arrives as sound as that support,
            // capped at its own natural. Stuck to the SIDE of one it arrives capped
            // at a fraction of THAT NEIGHBOUR's rating instead - a joint is only as
            // good as the material it was made against - and at its own natural too,
            // because a good joint does not make a block sounder than its material.
            boolean horizontal = dir.getAxis().isHorizontal();
            // faceCap returns the whole ceiling since 0.7.9, the host's remaining
            // strength included, so there is no second clamp to apply here. The old
            // min(cap, at) was correct while the side fraction came off the host's
            // rating; now that it comes off `at` itself, clamping again would be
            // arithmetic that never changes an answer and reads like it might.
            int candidate = faceCap(at, natural, horizontal);
            if (candidate > best) {
                best = candidate;
                // Both halves of the report, and only for a horizontal win. The
                // rating names the material, the stored value is what the fraction
                // was taken of, and a log carrying one without the other cannot
                // distinguish this rule from the one it replaced.
                sideHost = horizontal ? naturalOf(level, n, level.getBlockState(n)) : 0;
                sideHostStored = horizontal ? at : 0;
            }
        }

        if (founded) {
            // Touching ground anywhere. Full strength, and nothing is charged -
            // rock takes the load and passes none on. Ground is exempt from the
            // side-face cap as well, and that exemption is what makes a ledge run
            // out at half a pillar's reach rather than a quarter of it: the first
            // block out from the ground stands at full strength, and every block
            // placed after it charges that one double.
            int assigned = initialValue(level, pos, natural, true);
            reg.setEntry(pos, assigned);
            return new Placed(pos.immutable(), natural, assigned,
                    support.immutable(), WbiReg.ANCHOR, 0, 0, true, List.of(), 0, false, "");
        }

        // Inherit the best footing, then charge the structure the load rests on.
        // The per-face cap is already folded into best, so there is nothing left to
        // clamp here. A support that is structural but somehow contributed nothing
        // falls back to the block's own natural rather than to MIN_VALUE.
        int assigned = initialValue(level, pos,
                best == Integer.MIN_VALUE ? natural : best, true);
        reg.setEntry(pos, assigned);
        Chained d = chain(level, reg, support, pos, null, -1);
        return new Placed(pos.immutable(), natural, assigned,
                support.immutable(), best, sideHost, sideHostStored,
                false, d.failed(), d.count(), d.capped(), d.trace());
    }

    /**
     * What a {@link #recompute} did, or would have done.
     *
     * @param natural  the block's own material limit
     * @param before   the row as it stood
     * @param fresh    what the same block would be assigned if it were placed here now
     * @param after    the row as it now stands - {@code max(before, fresh)}
     * @param founded  true when a neighbour is ground, so the block re-derives at full natural
     * @param best     the strongest connection found, or {@link WbiReg#ANCHOR} when founded
     */
    public record Recomputed(BlockPos pos, int natural, int before, int fresh, int after,
                             boolean founded, int best, boolean changed) {}

    /**
     * Re-derive a block's integrity from the blocks around it as they stand now,
     * and charge nobody for it.
     *
     * Why anything needs repairing at all. When part of a building shears off and
     * becomes a sub-level, {@link SIFall} clears the rows of the blocks that left,
     * and they fly away. Every point those blocks charged into the wall they were
     * hanging from when they were PLACED stays charged - {@link #place} spends it
     * through {@link #chain} at build time, and nothing anywhere gives it back. So
     * the wall is left carrying a load that is no longer there, permanently weaker
     * than the same wall freshly built, and the next thing built on it inherits
     * that weakness. Doing this automatically the moment a piece detaches was the
     * alternative and is worse: a collapse would silently repair its own damage and
     * a building could never be worn down at all.
     *
     * What it computes, in order. First the block's own natural limit, because
     * nothing here can exceed it. Then {@link #supportOf} - the same routine
     * {@link #place} and {@link #chain} ask - for the one neighbour that is holding
     * this block up. A support reading {@link WbiReg#ANCHOR} is ground and settles
     * it outright at full natural; any other support hands over what it currently
     * holds, capped by {@link #faceCap} - at {@code natural} when it is reached
     * through a vertical face, and at {@link #sideInheritanceCap} of the SUPPORT's
     * STORED value since 0.7.9, formerly of the SUPPORT's
     * own rating through a horizontal one; and no support at
     * all hands over nothing. Then the row moves to the better of what it holds and
     * what that says - never down, because a repair that could damage is not a
     * repair, and a block standing stronger than its support warrants has earned
     * that from something this pass cannot see.
     *
     * The two deliberate differences from {@link #place}, both of which are the
     * whole design:
     *
     * <p>No {@link #chain} call. A placement charges the support underneath for
     * agreeing to carry a new block; that charge was paid once, when the block was
     * first placed, and it is still on the books. Charging it again would mean
     * repairing a block damages what it stands on, and a player patching a wall
     * from the top down would grind its foundation to nothing.
     *
     * <p>No {@link #initialValue} call, so no clump grant. The grant is a one-time
     * payment made at row creation and worn away thereafter, and re-paying it on
     * every click would turn any tool into an integrity fountain: click a stone
     * block in a stone wall repeatedly and it would climb without limit.
     *
     * The consequence for play, which falls out of asking supportOf rather than
     * being written: a block can only be repaired as far as the thing HOLDING IT UP
     * currently stands, so a damaged tower cannot be fixed from the top - the block
     * above is not what a block rests on, and now it is not what a block inherits
     * from either. Repair the tower at the bottom, where it meets ground, and each
     * block up it then has something sound underneath to inherit from. Mending a
     * building is a walk up it.
     *
     * @return null when the position holds nothing structural; otherwise a report,
     *         with {@code changed} false when there was nothing to give back
     */
    @Nullable
    public static Recomputed recompute(ServerLevel level, WbiReg reg, BlockPos pos) {
        if (!isStructural(level, pos, null)) {
            return null;
        }
        int natural = naturalOf(level, pos, level.getBlockState(pos));

        // Ground repairs to nothing because ground was never damaged: an untouched
        // position has no row, and a row-less position reads as ANCHOR forever.
        if (isAnchor(level, reg, pos)) {
            return new Recomputed(pos.immutable(), natural, WbiReg.ANCHOR, WbiReg.ANCHOR,
                    WbiReg.ANCHOR, true, WbiReg.ANCHOR, false);
        }

        int before = reg.get(pos);

        // One question, asked of the one routine that answers it everywhere else.
        // The six-way scan this replaces gave UP the same full-natural cap as DOWN,
        // so a block inherited from the block above it as freely as from the one
        // below and a damaged tower healed downwards from its tip. Capping UP would
        // have fixed that symptom and left a second, private definition of what
        // holds a block up sitting next to the real one, free to drift from it.
        BlockPos support = supportOf(level, reg, pos, null);

        boolean founded = false;
        int best = Integer.MIN_VALUE;
        if (support != null) {
            int at = peekAt(level, reg, support);
            if (at == WbiReg.ANCHOR) {
                founded = true;
            } else {
                // Which face the support was reached through still decides the cap.
                // supportOf answers WHICH neighbour carries the load; this answers
                // how much of its strength travels through that particular face, and
                // those are separate questions with separate answers. Asked through
                // faceCap, the same routine place() asks, so the side rule cannot
                // mean one thing on placement and another on repair.
                // Same complete-ceiling contract as place(): no outer clamp.
                //
                // Worth being explicit about what this does NOT do. Reading the
                // fraction off a value that moves means a repair pass re-reads a
                // host that may have worn since the guest was placed, so `fresh`
                // can come back lower than what the guest already holds. It never
                // takes effect: `after` is max(before, fresh) a few lines down, and
                // recompute only ever repairs upward. A worn host therefore repairs
                // its guest LESS than a sound one would, and never demotes it.
                // Lowering an already-placed block as its host degrades is a real
                // and arguably desirable behaviour, but it is a change in kind - the
                // side joint would stop being settled at placement time - and it
                // belongs to the 0.8.0 rewrite, not to a point release.
                best = faceCap(at, natural, support.getY() == pos.getY());
            }
        }

        // Three outcomes, where the old form had two. Ground repairs to full natural.
        // A tracked support repairs as far as that support currently stands. Nothing
        // holding the block up repairs to nothing - the old form folded that case in
        // with ground and granted full natural, which under a six-way scan almost
        // never came up and under supportOf is ordinary: every neighbour spent, or
        // the supports mined out. Left alone it would make clearing the ground around
        // a block the cheapest repair in the mod.
        int fresh = founded ? natural
                : best == Integer.MIN_VALUE ? before
                : Math.min(best, natural);
        int after = Math.max(before, fresh);
        if (after != before) {
            // set, not setEntry: the row moves, but the value it ENTERED tracking
            // with is history and chain() still measures its sideways deficit
            // against that. A repair is not a second birth.
            reg.set(pos, after);
        }
        return new Recomputed(pos.immutable(), natural, before, fresh, after,
                founded, founded ? WbiReg.ANCHOR : best, after != before);
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
            reg.setEntry(n, initialValue(level, n, naturalOf(level, n, level.getBlockState(n)), true));
            out.add(n.immutable());
        }
        return out;
    }

    /**
     * Disturb one position rather than a mined block's neighbours: the same entry
     * write, at the same natural value, for a block that has been shown by some
     * other means to no longer be ground. {@link SIEnclosure} uses it for a lump of
     * rock that nothing untouched connects to any more.
     *
     * @return false if the position was already tracked or is not structural
     */
    public static boolean disturbAt(ServerLevel level, WbiReg reg, BlockPos pos) {
        if (!isStructural(level, pos, null) || !isAnchor(level, reg, pos)) {
            return false;
        }
        reg.setEntry(pos, initialValue(level, pos, naturalOf(level, pos, level.getBlockState(pos)), true));
        return true;
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
        if (!conductsLoad(level, reg, seed, ghost) || isAnchor(level, reg, seed)) {
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
                if (!conductsLoad(level, reg, n, ghost)) {
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
        int reading = peekAt(level, reg, pos);
        boolean anchor = reading == WbiReg.ANCHOR;
        int stored = anchor ? natural : reading;

        Set<BlockPos> visited = new HashSet<>();
        Map<BlockPos, Integer> regionOf = new HashMap<>();
        List<Region> regions = new ArrayList<>();
        List<Face> faces = new ArrayList<>(6);
        boolean capped = false;

        for (Direction d : DIRS) {
            BlockPos n = pos.relative(d);
            if (!conductsLoad(level, reg, n, ghost)) {
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
                if (n.equals(origin) || !conductsLoad(level, reg, n, ghost)) {
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
     * May this block never be ground? Ground is the permissive default - but
     * nothing with no collision shape can hold anything up, so it never gets to be
     * ground either way; that is the same floor naturalOf already gave it. Listed
     * rows still decide for themselves, unless the config's defaultAnchorBlocks
     * forces anchor status back on.
     */
    static boolean neverAnchor(ServerLevel level, BlockPos pos, BlockState state) {
        BlockIntegrity row = state.getBlock().builtInRegistryHolder().getData(SIDataMaps.NATURAL);
        boolean never = row != null ? row.neverAnchor()
                : naturalOf(level, pos, state) == SIConfig.defaultFragileIntegrity();
        return never && !SIConfig.defaultAnchorBlocks().contains(state.getBlock());
    }

    /**
     * The wbireg reading for a position, for the WRITE path - {@link #chain} and
     * everything else that is about to modify the row it asks for.
     *
     * Ground is "no row". Some blocks may never mean that: loose material and growth
     * hold themselves up and nothing else. Rather than carve out a second kind of
     * ground, such a block is given the row it should have had, at its own natural,
     * the first time the solver WRITES near it - after which it is an ordinary
     * tracked block and every rule below applies to it unchanged. Reads must not do
     * this - a goggle query or a region flood that materialised rows changed the
     * world by looking at it, and the numbers drifted with observation order; they
     * go through {@link #peekAt} instead.
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
        if (!neverAnchor(level, pos, state)) {
            return WbiReg.ANCHOR;
        }
        int init = initialValue(level, pos, naturalOf(level, pos, state), true);
        reg.setEntry(pos, init);
        return init;
    }

    /**
     * {@link #storedAt} without the side effect: exactly what it would answer, with
     * no row created and nothing logged. Every read path asks here - the goggle
     * read-out, the region floods, the placement's best-connection scan - because a
     * read must not write. An untracked never-anchor block answers with the value
     * it WOULD enter tracking with, computed against its neighbours as they stand
     * now - live, not frozen at whatever the clump census said the first time
     * something happened to look.
     */
    public static int peekAt(ServerLevel level, WbiReg reg, BlockPos pos) {
        int v = reg.get(pos);
        if (v != WbiReg.ANCHOR) {
            return v;
        }
        BlockState state = level.getBlockState(pos);
        if (!neverAnchor(level, pos, state)) {
            return WbiReg.ANCHOR;
        }
        return initialValue(level, pos, naturalOf(level, pos, state), false);
    }

    /** True when this position is ground: untouched, and allowed to be. A pure read. */
    public static boolean isAnchor(ServerLevel level, WbiReg reg, BlockPos pos) {
        return peekAt(level, reg, pos) == WbiReg.ANCHOR;
    }

    /**
     * Does this material stand when spent, or break outright?
     *
     * The single answer to a question three places ask separately: {@link SIFall}
     * deciding whether to destroy a block that just failed, {@link #isSpent}
     * deciding whether a block still standing at failAt conducts anything, and
     * SIEvents deciding what to report. If those three disagreed a block would be
     * held by one and treated as gone by another - the same split
     * {@link #conductsLoad} closed in 0.7.0, one level further down.
     *
     * This reads naturalintegrityreg by block id, so it is a property of the
     * MATERIAL and not of this position's wear: a stone block holds or breaks
     * identically whether it is fresh or one point from failing. Since 0.7.1 the
     * config is a threshold rather than a switch, so soil can linger as rubble
     * while everything structural above it still shatters.
     *
     * Since 0.7.2 the threshold is not the only voice. It answers "how much load
     * does this material carry", and that turned out to be a different question
     * from "does this material slump or shatter" - see {@link SITags#BREAKS_WHEN_SPENT}
     * for the leaves case that separated them. Two vetoes now sit above the
     * number and both win when they fire.
     */
    public static boolean holdsWhenSpent(ServerLevel level, BlockPos pos) {
        return dispositionWhenSpent(level, pos) == SpentDisposition.HELD;
    }

    /**
     * What becomes of a block sitting at failAt, and why.
     *
     * An enum rather than a reason string because {@link #isSpent} sits under
     * {@link #conductsLoad} and asks this on every step of every graph walk. The
     * log site formats it; the hot path allocates nothing.
     */
    public enum SpentDisposition {
        /** Held in place at failAt, still standing, carrying nothing. */
        HELD,
        /** Natural integrity is above holdSpentUpToNatural - destroyed, as always. */
        ABOVE_THRESHOLD,
        /** Inside the band but in {@link SITags#BREAKS_WHEN_SPENT}, which vetoes holding. */
        TAGGED_BREAKS,
        /** Inside the band but has no collision shape, and breakFragileWhenSpent is on. */
        FRAGILE
    }

    /**
     * The threshold is asked first, and deliberately so. For a block with a data
     * map row {@link #naturalOf} returns on a single holder read, and the answer
     * settles the overwhelming majority of positions - anything structural sits
     * far above the band. Only a block that WOULD be held pays for the two vetoes
     * below it, and only the second of those costs a collision shape query.
     */
    public static SpentDisposition dispositionWhenSpent(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (naturalOf(level, pos, state) > SIConfig.holdSpentUpToNatural()) {
            return SpentDisposition.ABOVE_THRESHOLD;
        }
        if (state.is(SITags.BREAKS_WHEN_SPENT)) {
            return SpentDisposition.TAGGED_BREAKS;
        }
        if (SIConfig.breakFragileWhenSpent() && state.getCollisionShape(level, pos).isEmpty()) {
            return SpentDisposition.FRAGILE;
        }
        return SpentDisposition.HELD;
    }

    /**
     * True when the block is standing but no longer carries anything: it reached
     * failAt and its material is one {@link #holdsWhenSpent} keeps rather than
     * destroys. False for a material that breaks instead, because such a block is
     * gone rather than spent.
     *
     * The two raw row reads come first deliberately. This sits under
     * {@link #conductsLoad} and so runs on every step of every graph walk, while
     * {@link #holdsWhenSpent} costs a blockstate fetch and a data map lookup. Nearly
     * every position asked is an anchor or is still above failAt, and those bail
     * out on a hashmap read before the expensive question is reached. Never
     * materialises a row.
     */
    public static boolean isSpent(ServerLevel level, WbiReg reg, BlockPos pos) {
        return !reg.isAnchor(pos)
                && reg.get(pos) <= SIConfig.failAt()
                && holdsWhenSpent(level, pos);
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
        // Config wildcard patterns - explicit intent, so they also beat the
        // fragile (no-collision) classification below.
        int pattern = SIPatterns.lookup(state.getBlock());
        if (pattern != SIPatterns.NO_MATCH) {
            return pattern;
        }
        if (state.getCollisionShape(level, pos).isEmpty()) {
            return SIConfig.defaultFragileIntegrity();
        }
        if (!SIConfig.deriveIntegrityFromHardness()) {
            return SIConfig.defaultIntegrity();
        }
        // No row: derive the entry from the block's own hardness, normalised so
        // stone's 1.5 lands exactly on defaultIntegrity. Sub-linear (sqrt) so the
        // very hard end does not run away; unbreakable blocks derive the ceiling.
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0) {
            return 1024;
        }
        if (hardness == 0) {
            return SIConfig.defaultFragileIntegrity();
        }
        int derived = Math.round(SIConfig.defaultIntegrity() * (float) Math.sqrt(hardness / 1.5f));
        return Math.min(1024, Math.max(2, derived));
    }

    public static boolean hasRow(BlockState state) {
        return state.getBlock().builtInRegistryHolder().getData(SIDataMaps.NATURAL) != null;
    }

    /**
     * Does load travel through this block?
     *
     * The question every graph walk in this class is really asking, and it is not
     * the same as {@link #isStructural}. That one is about MATERIAL: air, fluids and
     * plants are outside the system and always were. This one is about STATE - a
     * block can be perfectly solid and still carry nothing, because integrity loss
     * has already spent it.
     *
     * A spent block (holdSpentUpToNatural kept it at failAt instead of destroying
     * it) stands, fills its space and keeps a room sealed, but it is rubble:
     * nothing reaches ground through it and nothing hangs off it. That is the
     * whole point of leaving it there rather than deleting it - the structure it
     * was holding comes down around a block that is still visibly present. Which
     * materials do this is {@link #holdsWhenSpent}, a threshold on natural
     * integrity rather than the world-wide switch it was before 0.7.1.
     *
     * This is one method because the rule used to be written three different ways.
     * {@link #chain} enforced it by breaking the walk the moment a block hit
     * failAt, {@link #collect} enforced it with an inline {@link #isSpent} test,
     * and {@link #fill}, {@link #compute}'s face scan and both support finders did
     * not enforce it at all - so a spent block stopped conducting for sub-level
     * detection while still being chosen as something's footing. One predicate now
     * answers it everywhere the walk asks.
     *
     * Deliberately NOT used for the material questions, which have their own
     * answers. A spent block still walls in a region for {@link SIEnclosure}, still
     * splinters as a same-type neighbour of a break, still needs its fall checked
     * by {@link SIFall}, and is still a legal thing to build against: a block set
     * on one inherits its zero and is spent in turn, which is the correct cascade
     * and wants no special case.
     */
    public static boolean conductsLoad(ServerLevel level, WbiReg reg, BlockPos pos,
                                       @Nullable BlockPos ghost) {
        return isStructural(level, pos, ghost) && !isSpent(level, reg, pos);
    }

    /**
     * Is this block part of the system at all? A question about MATERIAL only -
     * {@link #conductsLoad} is the one that also asks whether the block still has
     * anything left to give, and is what the load-bearing walks want.
     *
     * Air, fluids and plants are outside the system - not structure, carrying no
     * load and transmitting none. Plants are anything growing: {@link BushBlock}
     * covers flowers, saplings, crops, grass and mushrooms - a class, not a list,
     * so modded blocks extending it are covered too. Leaves are IN the system:
     * untouched they read as ground like any untracked block, and they track at
     * their hardness-derived integrity once the solver writes to them. A torch is
     * NOT excluded, it is integrity 1:
     * place a block on one and the torch is at 0 and the block it was carrying
     * has nowhere to sit. The config's nonStructuralBlocks adds to this.
     */
    public static boolean isStructural(ServerLevel level, BlockPos pos, @Nullable BlockPos ghost) {
        if (ghost != null && ghost.equals(pos)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();
        if (state.isAir() || block instanceof LiquidBlock
                || block instanceof BushBlock) {
            return false;
        }
        return !SIConfig.nonStructuralBlocks().contains(block);
    }
}
