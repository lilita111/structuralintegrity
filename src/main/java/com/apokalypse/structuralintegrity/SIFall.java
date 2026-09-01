package com.apokalypse.structuralintegrity;

import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

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
     * The stronger side of each snap this tick, when config says only the weaker
     * breaks. Splinter wear skips these: without the shield, a same-material
     * stronger block is adjacent to the breaking weaker one, usually worn by the
     * same chain, and the splinter -1 breaks it in the same pass - an uncontrolled
     * breakStrongerBlock=true. Cleared with the pending maps every tick.
     */
    private static final Map<ServerLevel, Set<BlockPos>> SPLINTER_PROTECTED = new HashMap<>();

    /** Shield this position from splinter wear during the next destroy pass. */
    public static void protectFromSplinter(ServerLevel level, BlockPos pos) {
        if (running) {
            return;
        }
        SPLINTER_PROTECTED.computeIfAbsent(level, l -> new HashSet<>()).add(pos.immutable());
    }

    /**
     * Sub-levels this mod itself assembled, as opposed to any other sub-level that happens
     * to exist in the same {@link ServerLevel} - sable is a shared physics engine, and
     * {@link ServerSubLevelContainer#getAllSubLevels} returns every sub-level in the level,
     * including ones other mods (or sable's own gametest suite) created for entirely
     * unrelated reasons. {@link #checkForRevert} must never touch those: forcing one back
     * into blocks while its owner is still mid-flight with it is exactly what was crashing
     * sable's own {@code PhysicsTest.testSnag} with "Body has been removed" - this mod was
     * reverting a rigid body sable's own test was still actively applying impulses to.
     * A {@link WeakHashMap}-backed set so entries fall out once the sub-level is gone.
     */
    private static final Set<ServerSubLevel> OWNED_SUB_LEVELS =
            Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * The world position each owned sub-level was assembled at. Recorded because it
     * is the one world-space fact about a sub-level this mod knows for certain - it
     * came from block positions, not from a pose - and a test that has to pick its
     * own sub-level out of a level shared with every other test in the run has
     * nothing else to match on. It goes stale the moment the body moves, so it
     * identifies a sub-level rather than locating one.
     */
    private static final Map<ServerSubLevel, BlockPos> ASSEMBLED_AT = new WeakHashMap<>();

    /** True if this mod assembled this sub-level, as opposed to some other mod. */
    public static boolean isOwned(ServerSubLevel subLevel) {
        return OWNED_SUB_LEVELS.contains(subLevel);
    }

    /** Where this mod assembled this sub-level, or null if it did not assemble it. */
    @Nullable
    public static BlockPos assembledAt(ServerSubLevel subLevel) {
        return ASSEMBLED_AT.get(subLevel);
    }

    /**
     * Collapse kick to use at a given anchor instead of the configured one:
     * {@code {force, torque}}.
     *
     * A test seam, and the only one in this file. The two halves of the fall cycle
     * want opposite settings - the spin test needs a definite kick to measure, the
     * revert test needs none at all so its piece can land square - and neither should
     * break when the shipped default is retuned, which it has been more than once.
     * Each test pins the numbers it is actually about.
     *
     * Keyed by anchor rather than held as a global switch on purpose. The gametest
     * server runs every test concurrently in one shared level, so a global override
     * set by one test would silently reach into another running beside it. An anchor
     * belongs to exactly one test.
     *
     * Consumed on use, so it can never leak into a second collapse at the same spot.
     */
    private static final Map<BlockPos, double[]> KICK_OVERRIDES = new HashMap<>();

    /** Test-only. See {@link #KICK_OVERRIDES}. Pass zeroes to suppress the kick. */
    public static void overrideCollapseKickAt(BlockPos anchor, double force, double torque) {
        KICK_OVERRIDES.put(anchor.immutable(), new double[] {force, torque});
    }

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

    /**
     * Blocks a shockwave spent. They are queued for the next destroy pass, and
     * this marker is what stops them emitting a wave of their own when it runs -
     * one wave per original break, across ticks. Consumed as each is destroyed.
     */
    private static final Map<ServerLevel, Set<BlockPos>> WAVE_BROKEN = new HashMap<>();

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
        Map<ServerLevel, Set<BlockPos>> shielded = new HashMap<>(SPLINTER_PROTECTED);
        PENDING_DESTROY.clear();
        PENDING_FALL.clear();
        SPLINTER_PROTECTED.clear();

        // Original integrity breaks this pass, per level - shockwave candidates.
        // The wave is deferred: whether it fires depends on what the fall pass
        // finds, which has not run yet at destroy time.
        Map<ServerLevel, List<BlockPos>> waveOrigins = new HashMap<>();

        Map<ServerLevel, Integer> assembled = new HashMap<>();
        running = true;
        try {
            for (Map.Entry<ServerLevel, LinkedHashSet<BlockPos>> e : destroy.entrySet()) {
                runDestroy(e.getKey(), e.getValue(), fall,
                        shielded.getOrDefault(e.getKey(), Set.of()), waveOrigins);
            }
            for (Map.Entry<ServerLevel, LinkedHashSet<BlockPos>> e : fall.entrySet()) {
                assembled.put(e.getKey(), runFall(e.getKey(), e.getValue()));
            }
        } finally {
            running = false;
        }

        // The shockwave fires only when the break detached nothing: a sub-level
        // carried the energy away. Runs after `running` drops so wave-spent
        // blocks queue into the NEXT destroy pass, marked so they never emit.
        for (Map.Entry<ServerLevel, List<BlockPos>> e : waveOrigins.entrySet()) {
            ServerLevel level = e.getKey();
            int subLevels = assembled.getOrDefault(level, 0);
            if (subLevels > 0) {
                StructuralIntegrity.LOGGER.info(
                        "[SI] SHOCKWAVE suppressed in {}: {} detached component(s) this pass",
                        level.dimension().location(), subLevels);
                continue;
            }
            WbiReg reg = WbiReg.of(level);
            for (BlockPos origin : e.getValue()) {
                runShockwave(level, reg, origin, SIConfig.failAt(),
                        shielded.getOrDefault(level, Set.of()));
            }
        }
    }

    /**
     * Remove crushed supports. The pillar below is deliberately not disturbed - a
     * crush is not mining, and the spec is explicit that the pillar keeps standing
     * and only loses its top.
     */
    private static void runDestroy(ServerLevel level, Set<BlockPos> positions,
                                   Map<ServerLevel, LinkedHashSet<BlockPos>> fall,
                                   Set<BlockPos> shielded,
                                   Map<ServerLevel, List<BlockPos>> waveOrigins) {
        WbiReg reg = WbiReg.of(level);
        // Worklist, not a plain loop: wear can spend further blocks, and those
        // must be destroyed in this same pass.
        java.util.ArrayDeque<BlockPos> work = new java.util.ArrayDeque<>(positions);
        Set<BlockPos> done = new HashSet<>();
        // Blocks a previous pass's shockwave spent: they break here, but they
        // are not original breaks and must not become wave origins themselves.
        Set<BlockPos> waveBroken = WAVE_BROKEN.computeIfAbsent(level, l -> new HashSet<>());
        int failAt = SIConfig.failAt();
        boolean shockwave = SIConfig.breakShockwave();
        while (!work.isEmpty()) {
            BlockPos pos = work.poll();
            if (!done.add(pos) || !Integrity.isStructural(level, pos, null)) {
                continue; // already handled or already gone
            }
            // Asked per position, not once for the pass: holdSpentUpToNatural is a
            // threshold on the material, and this worklist mixes them freely - a
            // collapse that spends a dirt block and the stone lintel above it holds
            // the first and destroys the second in the same loop.
            Integrity.SpentDisposition disp = Integrity.dispositionWhenSpent(level, pos);
            if (disp == Integrity.SpentDisposition.HELD) {
                // The block stays in the world, pinned spent. It stops conducting
                // support (collect() treats spent rows as gaps), so whatever it was
                // holding detaches around it. No break happened, so no splintering.
                reg.set(pos, failAt);
                StructuralIntegrity.LOGGER.info("[SI] SPENT {} held in place, load shed", fmt(pos));
                for (Direction d : Direction.values()) {
                    BlockPos n = pos.relative(d);
                    if (Integrity.isStructural(level, n, null)) {
                        fall.computeIfAbsent(level, l -> new LinkedHashSet<>()).add(n.immutable());
                    }
                }
                continue;
            }
            // Says so out loud when a veto is what destroyed this block, because the
            // number in the config said hold and the block broke anyway. Silence here
            // through 0.6.6-0.7.1 is what let floating leaves read as correct.
            if (disp != Integrity.SpentDisposition.ABOVE_THRESHOLD) {
                StructuralIntegrity.LOGGER.info(
                        "[SI] SPENT {} {} nat={} inside hold band (<={}) but breaks: {}",
                        fmt(pos), level.getBlockState(pos).getBlock(),
                        Integrity.naturalOf(level, pos, level.getBlockState(pos)),
                        SIConfig.holdSpentUpToNatural(), disp);
            }
            var broken = level.getBlockState(pos).getBlock();
            boolean ok = level.destroyBlock(pos, true);
            reg.clear(pos);
            StructuralIntegrity.LOGGER.info("[SI] CRUSH destroy {} {} wbireg={}",
                    fmt(pos), ok ? "removed" : "FAILED", reg.size());
            // Broken indirectly, by integrity. Same roll as a player's own break.
            SIEnclosure.maybeCheck(level, reg, pos);
            if (shockwave) {
                // Deferred: recorded here, fired (or suppressed) after the fall
                // pass has said whether this break detached anything.
                if (!waveBroken.remove(pos)) {
                    waveOrigins.computeIfAbsent(level, l -> new ArrayList<>()).add(pos.immutable());
                }
                for (Direction d : Direction.values()) {
                    BlockPos n = pos.relative(d);
                    if (Integrity.isStructural(level, n, null)) {
                        // Whatever it was carrying has just lost its support.
                        fall.computeIfAbsent(level, l -> new LinkedHashSet<>()).add(n.immutable());
                    }
                }
                continue;
            }
            for (Direction d : Direction.values()) {
                BlockPos n = pos.relative(d);
                if (!Integrity.isStructural(level, n, null)) {
                    continue;
                }
                // Splintering: a breaking block wears same-type neighbours by 1.
                // Ground is exempt - an anchor has no row to wear and a neighbour
                // breaking is not a disturbance that materialises one. Wear only,
                // never an entry write.
                if (level.getBlockState(n).getBlock() == broken && !reg.isAnchor(n)
                        && !shielded.contains(n)) {
                    // With breakStrongerBlock=false only the snapped weaker block may
                    // break: splinter wear weakens neighbours but can never finish
                    // them, so the floor is one above failAt and no cascade starts.
                    int floor = SIConfig.breakStrongerBlock() ? failAt : failAt + 1;
                    // A row already at or under the floor is left alone - the clamp
                    // must never raise a spent row back above failAt.
                    int stored = reg.get(n);
                    int now = Math.max(Math.min(stored, floor), stored - 1);
                    reg.set(n, now);
                    if (now <= failAt) {
                        StructuralIntegrity.LOGGER.info("[SI] SPLINTER {} spent by {} breaking",
                                fmt(n), fmt(pos));
                        work.add(n.immutable());
                        continue;
                    }
                }
                // Whatever it was carrying has just lost its support.
                fall.computeIfAbsent(level, l -> new LinkedHashSet<>()).add(n.immutable());
            }
        }
    }

    /**
     * The shockwave: one original integrity break wears the whole connected
     * structure by 1. The flood starts at the broken block's neighbours (the
     * block itself is already air), travels faces across structural blocks, does
     * not expand through ground - an anchor is where the structure ends - and
     * gives up at the maxRegion cap, reported.
     *
     * Wear only, never an entry write: an untracked block conducts the wave but
     * takes nothing from it, and so does a block whose natural integrity is 1 -
     * torches, leaves, loose material are transparent to it. Snap-shielded
     * positions are skipped for the same reason they are shielded from splinter.
     *
     * The wave may break: a row driven to failAt is queued for the NEXT destroy
     * pass, marked wave-broken so it emits no wave of its own when it goes.
     */
    private static void runShockwave(ServerLevel level, WbiReg reg, BlockPos origin, int failAt,
                                     Set<BlockPos> shielded) {
        Set<BlockPos> visited = new HashSet<>();
        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        for (Direction d : Direction.values()) {
            BlockPos n = origin.relative(d).immutable();
            if (Integrity.isStructural(level, n, null) && visited.add(n)) {
                queue.add(n);
            }
        }

        int maxRegion = SIConfig.maxRegion();
        int worn = 0;
        int broke = 0;
        while (!queue.isEmpty() && visited.size() < maxRegion) {
            BlockPos p = queue.poll();
            boolean anchor = reg.isAnchor(p);
            if (!anchor && !shielded.contains(p)
                    && Integrity.naturalOf(level, p, level.getBlockState(p)) > 1) {
                int stored = reg.get(p);
                int now = Math.max(failAt, stored - 1);
                if (now != stored) {
                    reg.set(p, now);
                    worn++;
                }
                if (now <= failAt) {
                    broke++;
                    WAVE_BROKEN.computeIfAbsent(level, l -> new HashSet<>()).add(p);
                    queueDestroy(level, p);
                }
            }
            if (anchor) {
                continue; // ground: the wave lands in it and goes no further
            }
            for (Direction d : Direction.values()) {
                BlockPos n = p.relative(d).immutable();
                if (Integrity.isStructural(level, n, null) && visited.add(n)) {
                    queue.add(n);
                }
            }
        }
        StructuralIntegrity.LOGGER.info("[SI] SHOCKWAVE from {} reached {} blocks, wore {}, broke {}{}",
                fmt(origin), visited.size(), worn, broke,
                visited.size() >= maxRegion ? " (CAPPED)" : "");
    }

    /** @return how many detached components this pass found - assembled or not */
    private static int runFall(ServerLevel level, Set<BlockPos> seeds) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            StructuralIntegrity.LOGGER.warn("[SI] fall skipped in {}: sable has no sub-level container here",
                    level.dimension().location());
            return 0;
        }

        WbiReg reg = WbiReg.of(level);
        Set<BlockPos> handled = new HashSet<>();
        int checked = 0;
        int detached = 0;
        int fell = 0;

        for (BlockPos seed : seeds) {
            if (handled.contains(seed)) {
                continue;
            }
            checked++;
            long t0 = System.nanoTime();
            Integrity.Component comp = Integrity.collect(level, reg, seed, null, SIConfig.maxAssemblySize());
            long micros = (System.nanoTime() - t0) / 1000L;

            if (comp.grounded()) {
                StructuralIntegrity.LOGGER.info("[SI] fall check {} GROUNDED in {}us", fmt(seed), micros);
                continue;
            }
            if (comp.blocks().isEmpty()) {
                continue; // seed was air, ground, or fluid by the time we got here
            }
            handled.addAll(comp.blocks());
            detached++;

            if (comp.capped()) {
                StructuralIntegrity.LOGGER.warn(
                        "[SI] fall check {} DETACHED but larger than maxAssembly={} - left in place ({}us)",
                        fmt(seed), SIConfig.maxAssemblySize(), micros);
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
        return detached;
    }

    /**
     * Hand the component to sable. The bounds are the component's own box grown by
     * one, which is what sable's own assembly commands pass - it is the volume that
     * tracking points and riding entities are swept out of, not the block set.
     */
    private static boolean assemble(ServerLevel level, WbiReg reg, BlockPos anchor, List<BlockPos> blocks) {
        Map<BlockPos, Long> recent = RECENT_ASSEMBLY.computeIfAbsent(level, l -> new HashMap<>());
        long now = level.getGameTime();
        int cooldown = SIConfig.assemblyCooldownTicks();
        recent.entrySet().removeIf(e -> now - e.getValue() > cooldown);

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
                    fmt(anchor), blocks.size(), left, fmt(first), SIConfig.assemblyCooldownTicks());
            return false;
        }

        // The blocks are no longer at these positions; their rows mean nothing now.
        for (BlockPos p : blocks) {
            reg.clear(p);
        }

        OWNED_SUB_LEVELS.add(subLevel);
        ASSEMBLED_AT.put(subLevel, anchor.immutable());

        // The piece has just lost whatever was under it. Send it away from the failure
        // so it topples instead of sinking straight down in place, and set it turning
        // about the axis that carries its top over the way it is already leaning -
        // masonry that shears off a wall rolls, only a launched block travels without
        // spinning. Both at once, and by SIForce#kick rather than an impulse: the body
        // was assembled microseconds ago and has no mass properties yet, so an impulse
        // divides by zero inverse mass and arrives as nothing. Every piece this mod has
        // ever dropped fell straight down for that reason, landed square, and was
        // reverted to ordinary blocks by the alignment check the tick after.
        double[] override = KICK_OVERRIDES.remove(anchor);
        double kickForce = override != null ? override[0] : SIConfig.collapseForce();
        double kickTorque = override != null ? override[1] : SIConfig.collapseTorque();
        if (override != null) {
            StructuralIntegrity.LOGGER.info("[SI] collapse kick overridden at {}: force={} torque={} (test)",
                    fmt(anchor), kickForce, kickTorque);
        }
        SIForce.kick(subLevel,
                SIForce.toppleDirection(anchor, blocks), kickForce,
                SIForce.tumbleAxis(anchor, blocks), kickTorque);

        StructuralIntegrity.LOGGER.info("[SI] SUBLEVEL created at {} n={} bounds=[{},{},{} .. {},{},{}]",
                fmt(anchor), blocks.size(),
                bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ);
        return true;
    }

    // =====================================================================
    // Stage 3 - the landing. A sub-level whose pose has settled back onto the
    // grid stops being a physics object and becomes regular blocks again.
    // =====================================================================

    /** Below this speed (in each of linear/angular, squared) a sub-level counts as at rest. */
    private static final double MOVING_THRESHOLD_SQ = 1.0e-4;

    /**
     * How far a transformed anchor may sit from a block center and still count as
     * "in place". Loosened from 0.02: a body sable reports at rest can still be
     * fractionally off grid center from float drift in the physics solve, which was
     * enough to permanently miss the old tolerance and leave a landed sub-level
     * never reverting to blocks.
     */
    private static final double POSITION_EPSILON = 0.2;

    /** How far a rotated unit axis may miss its target and still count as grid-aligned. */
    private static final double ORIENTATION_EPSILON = 0.2;

    /**
     * Block-state rotation matching each of {@link SubLevelAssemblyHelper.AssemblyTransform}'s
     * angles, which are COUNTER-clockwise quarter turns: its position math is
     * {@link Vec3#yRot}, and yRot(+90 degrees) sends NORTH (0,0,-1) to (-1,0,0), which is WEST.
     * Index 1 is therefore COUNTERCLOCKWISE_90 and index 3 CLOCKWISE_90; pairing the angle
     * with the opposite Rotation lands stairs and other facing blocks mirrored about the
     * fall axis while the geometry itself is placed correctly.
     */
    private static final Rotation[] ROTATION_FOR_ANGLE =
            {Rotation.NONE, Rotation.COUNTERCLOCKWISE_90, Rotation.CLOCKWISE_180, Rotation.CLOCKWISE_90};

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        checkForRevert(level);
    }

    /**
     * Sub-levels that have been above {@link #MOVING_THRESHOLD_SQ} at least once since this
     * mod assembled them. A body is born perfectly aligned - sable places it at exactly the
     * blocks it was built from, so its anchor sits dead on a block centre with an identity
     * orientation - which means an alignment check on a sub-level that has never moved
     * always passes and simply undoes the fall the tick after it started. Only a body that
     * has actually travelled somewhere can meaningfully be said to have landed.
     */
    private static final Set<ServerSubLevel> HAS_MOVED =
            Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * Consecutive ticks each sub-level has been at rest. The alignment check runs only within
     * {@link #REST_CHECK_TICKS} of coming to rest: the engine can zero a landed body's
     * velocity a tick or two before its pose finishes snapping into place, so testing only the
     * moving-to-resting transition tick can miss the alignment, while testing every tick
     * forever makes a body parked permanently off-grid cost pose math for the rest of the
     * session. Both maps are {@link WeakHashMap}-backed so entries for removed or reverted
     * sub-levels fall out on their own.
     */
    private static final Map<ServerSubLevel, Integer> REST_TICKS = new WeakHashMap<>();

    /**
     * How long after coming to rest a sub-level stays eligible to revert. One second.
     */
    private static final int REST_CHECK_TICKS = 20;

    private static void checkForRevert(ServerLevel level) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return;
        }

        WbiReg reg = WbiReg.of(level);
        // copy: reverting removes from the container we'd otherwise be iterating
        for (ServerSubLevel subLevel : new ArrayList<>(container.getAllSubLevels())) {
            if (subLevel.isRemoved() || !OWNED_SUB_LEVELS.contains(subLevel)) {
                continue;
            }

            boolean moving = subLevel.latestLinearVelocity.lengthSquared() >= MOVING_THRESHOLD_SQ
                    || subLevel.latestAngularVelocity.lengthSquared() >= MOVING_THRESHOLD_SQ;

            if (moving) {
                HAS_MOVED.add(subLevel);
                REST_TICKS.remove(subLevel);
                continue;
            }

            // Still sitting where it was assembled - not a landing, so there is nothing to
            // revert. Reverting here would put the blocks back exactly where the fall pass
            // just took them from, and the next pass would detach them again.
            if (!HAS_MOVED.contains(subLevel)) {
                continue;
            }

            int rest = REST_TICKS.merge(subLevel, 1, Integer::sum);
            if (rest <= SIConfig.restCheckTicks()) {
                tryRevert(level, reg, subLevel, false);
                continue;
            }

            // Second chance. Fires on exactly one tick - the counter climbs by one
            // per pass and is dropped the moment the body moves again, so a body
            // that is nudged and settles somewhere new gets a fresh window and a
            // fresh assist rather than an assist per tick forever.
            int assist = SIConfig.snapAssistTicks();
            if (assist > 0 && rest == SIConfig.restCheckTicks() + assist) {
                tryRevert(level, reg, subLevel, true);
            }
        }
    }

    /**
     * World position of a sub-level's plot anchor: the point that sits exactly on a block
     * centre when, and only when, the sub-level is aligned to the world grid.
     *
     * The trap this exists to close: {@code pose.transformPosition(new Vector3d())} looks like
     * "where is this sub-level in the world" and is not. {@code Pose3dc#transformPosition} is
     * {@code world = orientation * ((local - rotationPoint) * scale) + position}, and sable
     * sets {@code rotationPoint} to the centre of mass expressed in PLOT coordinates - plot-grid
     * coordinates millions of blocks out, not an offset from the plot anchor. Handing it the
     * local origin therefore yields {@code -R * centreOfMass + position}, a point with no
     * geometric meaning that is essentially never near a block centre. The local point has to
     * be the plot anchor's own centre.
     */
    public static Vector3d worldAnchorOf(ServerSubLevel subLevel) {
        BlockPos localAnchor = subLevel.getPlot().getCenterBlock();
        Vector3d worldAnchor = new Vector3d(
                localAnchor.getX() + 0.5, localAnchor.getY() + 0.5, localAnchor.getZ() + 0.5);
        subLevel.logicalPose().transformPosition(worldAnchor);
        return worldAnchor;
    }

    /**
     * Turn a sub-level back into blocks if it is close enough to the grid to round.
     *
     * @param lenient the second pass, run once after {@code snapAssistTicks} of the
     *                body not moving at all. It widens the position and yaw
     *                tolerances only. It does NOT widen the up-vector test and it
     *                does not touch the body: {@link #revert} has always placed
     *                blocks at the nearest block centre and the nearest quarter
     *                turn, so what the tolerances decide is whether that rounding
     *                is honest, and after three seconds of stillness a wider
     *                rounding is still honest.
     */
    private static void tryRevert(ServerLevel level, WbiReg reg, ServerSubLevel subLevel,
                                  boolean lenient) {
        Pose3d pose = subLevel.logicalPose();
        LevelPlot plot = subLevel.getPlot();
        BlockPos localAnchor = plot.getCenterBlock();

        Vector3d worldAnchor = worldAnchorOf(subLevel);

        double posEps = lenient ? SIConfig.snapAssistPositionEpsilon()
                : SIConfig.snapPositionEpsilon();
        double yawEps = lenient ? SIConfig.snapAssistOrientationEpsilon()
                : SIConfig.snapOrientationEpsilon();

        Integer angle = alignedYawAngle(pose.orientation(), yawEps);
        boolean nearCenter = isNearBlockCenter(worldAnchor, posEps);

        if (angle == null || !nearCenter) {
            if (lenient) {
                // The only place the actual numbers are ever visible. Without this
                // a piece that never reverts is indistinguishable from a piece the
                // pass never looked at, and the tolerances stay guesses.
                StructuralIntegrity.LOGGER.info(
                        "[SI] SNAP ASSIST declined after {} still tick(s): {} - {}",
                        SIConfig.restCheckTicks() + SIConfig.snapAssistTicks(),
                        angle == null ? "orientation" : "position",
                        describeAlignment(pose, worldAnchor));
            }
            return;
        }

        if (lenient) {
            StructuralIntegrity.LOGGER.info(
                    "[SI] SNAP ASSIST took after {} still tick(s): rounding to yaw {} at {},{},{} - {}",
                    SIConfig.restCheckTicks() + SIConfig.snapAssistTicks(), angle,
                    (int) Math.floor(worldAnchor.x), (int) Math.floor(worldAnchor.y), (int) Math.floor(worldAnchor.z),
                    describeAlignment(pose, worldAnchor));
        }

        BlockPos targetAnchor = BlockPos.containing(worldAnchor.x, worldAnchor.y, worldAnchor.z);
        revert(level, reg, subLevel, plot, localAnchor, targetAnchor, angle);
    }

    /**
     * How far off the grid a body actually is, in the same units the epsilons are
     * written in: the up-vector's distance from straight up, the facing vector's
     * distance from the nearest quarter turn, and how far each coordinate sits from
     * the centre of the block it is in.
     */
    private static String describeAlignment(Pose3d pose, Vector3d worldAnchor) {
        Vector3d up = pose.orientation().transform(new Vector3d(0, 1, 0));
        Vector3d facing = pose.orientation().transform(new Vector3d(1, 0, 0));
        Vec3 facingWorld = new Vec3(facing.x, facing.y, facing.z);
        double bestYaw = Double.MAX_VALUE;
        int bestAngle = -1;
        for (int a = 0; a < 4; a++) {
            double d = facingWorld.distanceTo(new Vec3(1, 0, 0).yRot((float) (a * Math.PI / 2.0)));
            if (d < bestYaw) {
                bestYaw = d;
                bestAngle = a;
            }
        }
        return String.format(
                "up=%.3f (allow %.3f, never widened) yaw=%.3f to quarter-turn %d (allow %.3f) "
                        + "offset=%.3f,%.3f,%.3f (allow %.3f)",
                up.distance(0, 1, 0), SIConfig.snapOrientationEpsilon(),
                bestYaw, bestAngle, SIConfig.snapAssistOrientationEpsilon(),
                offsetFromCentre(worldAnchor.x), offsetFromCentre(worldAnchor.y),
                offsetFromCentre(worldAnchor.z), SIConfig.snapAssistPositionEpsilon());
    }

    private static double offsetFromCentre(double coord) {
        return Math.abs((coord - Math.floor(coord)) - 0.5);
    }

    /**
     * Checks the sub-level's orientation against the four quarter-turns using the exact
     * same {@link Vec3#yRot} operation {@link SubLevelAssemblyHelper.AssemblyTransform}
     * itself applies - so whatever sign convention the physics engine's quaternion uses,
     * the angle this returns is guaranteed to mean what AssemblyTransform thinks it means.
     *
     * @return 0-3, or null if not aligned to a quarter-turn (or tipped off of pure yaw)
     */
    private static Integer alignedYawAngle(Quaterniondc orientation, double yawEps) {
        Vector3d up = orientation.transform(new Vector3d(0, 1, 0));
        // Never widened, not even by the assist pass: a body lying on its side or
        // tipped onto a corner cannot be expressed as block states at all, and
        // rounding that away would stand a toppled wall back up.
        if (up.distance(0, 1, 0) > SIConfig.snapOrientationEpsilon()) {
            return null; // pitched or rolled - vanilla block states can't represent this anyway
        }

        Vector3d facing = orientation.transform(new Vector3d(1, 0, 0));
        Vec3 facingWorld = new Vec3(facing.x, facing.y, facing.z);

        for (int angle = 0; angle < 4; angle++) {
            Vec3 candidate = new Vec3(1, 0, 0).yRot((float) (angle * Math.PI / 2.0));
            if (facingWorld.distanceTo(candidate) <= yawEps) {
                return angle;
            }
        }
        return null;
    }

    private static boolean isNearBlockCenter(Vector3d pos, double eps) {
        return isNearHalf(pos.x, eps) && isNearHalf(pos.y, eps) && isNearHalf(pos.z, eps);
    }

    private static boolean isNearHalf(double coord, double eps) {
        double frac = coord - Math.floor(coord);
        return Math.abs(frac - 0.5) <= eps;
    }

    /**
     * Moves every block currently in the plot back into the world at the aligned anchor,
     * the exact mirror of {@link #assemble}: same {@link SubLevelAssemblyHelper#moveBlocks},
     * anchors swapped, and {@link WbiReg#clear} on the destinations that touch existing
     * ground. Clearing a destination row is also the entire "set as anchor" step - a cleared row
     * reads as {@link WbiReg#ANCHOR} until {@link Integrity#storedAt} re-derives it from
     * whatever block actually landed there, anchor-eligible or not.
     */
    private static void revert(ServerLevel level, WbiReg reg, ServerSubLevel subLevel, LevelPlot plot,
                                BlockPos localAnchor, BlockPos targetAnchor, int angle) {
        BoundingBox3ic bounds = plot.getBoundingBox();
        if (bounds.volume() <= 0) {
            return;
        }

        List<BlockPos> blocks = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(bounds.minX(), bounds.minY(), bounds.minZ(),
                bounds.maxX(), bounds.maxY(), bounds.maxZ())) {
            if (!level.getBlockState(p).isAir()) {
                blocks.add(p.immutable());
            }
        }
        if (blocks.isEmpty()) {
            return;
        }

        SubLevelAssemblyHelper.AssemblyTransform transform = new SubLevelAssemblyHelper.AssemblyTransform(
                localAnchor, targetAnchor, angle, ROTATION_FOR_ANGLE[angle], level);

        // Make sure the world can actually take these blocks back before destroying the plot's copy.
        for (BlockPos p : blocks) {
            BlockPos dest = transform.apply(p);
            if (!level.getBlockState(dest).isAir()) {
                StructuralIntegrity.LOGGER.warn(
                        "[SI] revert at {} SUPPRESSED, destination {} is occupied", fmt(targetAnchor), fmt(dest));
                return;
            }
        }

        SubLevelAssemblyHelper.moveBlocks(level, transform, blocks);

        if (SIConfig.subLevelsFloatWhenReconverted()) {
            // A landing mints ground where it touches the standing world: a landed
            // block becomes an anchor (row cleared) if a neighbour OUTSIDE the landed
            // set is any pre-existing structural block - checked before any clearing,
            // so landed blocks can never anchor each other. The rest re-enter tracked
            // at natural, and the landing is re-checked next tick.
            Set<BlockPos> dests = new HashSet<>(blocks.size());
            for (BlockPos p : blocks) {
                dests.add(transform.apply(p));
            }
            int anchored = 0;
            for (BlockPos dest : dests) {
                boolean touchesWorld = false;
                for (Direction d : Direction.values()) {
                    BlockPos n = dest.relative(d);
                    if (!dests.contains(n) && Integrity.isStructural(level, n, null)) {
                        touchesWorld = true;
                        break;
                    }
                }
                if (touchesWorld) {
                    reg.clear(dest);
                    anchored++;
                } else {
                    reg.setEntry(dest, Integrity.naturalOf(level, dest, level.getBlockState(dest)));
                }
            }
            StructuralIntegrity.LOGGER.info("[SI] revert anchoring: {}/{} landed blocks touch the standing world",
                    anchored, dests.size());
            if (anchored < dests.size()) {
                queueFall(level, targetAnchor);
            }
        } else {
            // The landing does not mint new ground. Every landed block re-enters tracked
            // at its own natural, and the anchor is re-checked next tick - a landing that
            // cannot reach real ground from where it stopped falls again.
            for (BlockPos p : blocks) {
                BlockPos dest = transform.apply(p);
                reg.setEntry(dest, Integrity.naturalOf(level, dest, level.getBlockState(dest)));
            }
            queueFall(level, targetAnchor);
        }

        // No explicit removal: emptying the plot is the removal. Every one of the block
        // clears above reaches SableCommonEvents#handleBlockChange, which recomputes the
        // plot's bounding box; once the last block is gone the box is empty and
        // ServerSubLevel#onPlotBoundsChanged calls markRemoved, so the container drops the
        // sub-level on its next processSubLevelRemovals pass. Calling ServerSubLevel#onRemove
        // here instead - as this did - runs the removal CALLBACK without any of the
        // bookkeeping SubLevelContainer#removeSubLevel does around it, leaving the sub-level
        // in getAllSubLevels and its plot slot occupied forever.
        StructuralIntegrity.LOGGER.info("[SI] REVERTED sub-level to {} n={} angle={}deg sableMarkedRemoved={}",
                fmt(targetAnchor), blocks.size(), angle * 90, subLevel.isRemoved());
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
