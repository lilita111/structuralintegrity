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

            if (REST_TICKS.merge(subLevel, 1, Integer::sum) > SIConfig.restCheckTicks()) {
                continue;
            }

            tryRevert(level, reg, subLevel);
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

    private static void tryRevert(ServerLevel level, WbiReg reg, ServerSubLevel subLevel) {
        Pose3d pose = subLevel.logicalPose();
        LevelPlot plot = subLevel.getPlot();
        BlockPos localAnchor = plot.getCenterBlock();

        Vector3d worldAnchor = worldAnchorOf(subLevel);

        Vector3d up = pose.orientation().transform(new Vector3d(0, 1, 0));
        Integer angle = alignedYawAngle(pose.orientation());
        boolean nearCenter = isNearBlockCenter(worldAnchor);

        // TEMP DEBUG (SIGameTests stage 3 investigation) - remove once revert is confirmed working.
        StructuralIntegrity.LOGGER.info(
                "[SI-DEBUG] tryRevert subLevel={} upDist={} angle={} worldAnchor=({},{},{}) fracs=({},{},{}) nearCenter={}",
                System.identityHashCode(subLevel), up.distance(0, 1, 0), angle,
                worldAnchor.x, worldAnchor.y, worldAnchor.z,
                worldAnchor.x - Math.floor(worldAnchor.x),
                worldAnchor.y - Math.floor(worldAnchor.y),
                worldAnchor.z - Math.floor(worldAnchor.z),
                nearCenter);

        if (angle == null || !nearCenter) {
            return;
        }

        BlockPos targetAnchor = BlockPos.containing(worldAnchor.x, worldAnchor.y, worldAnchor.z);
        revert(level, reg, subLevel, plot, localAnchor, targetAnchor, angle);
    }

    /**
     * Checks the sub-level's orientation against the four quarter-turns using the exact
     * same {@link Vec3#yRot} operation {@link SubLevelAssemblyHelper.AssemblyTransform}
     * itself applies - so whatever sign convention the physics engine's quaternion uses,
     * the angle this returns is guaranteed to mean what AssemblyTransform thinks it means.
     *
     * @return 0-3, or null if not aligned to a quarter-turn (or tipped off of pure yaw)
     */
    private static Integer alignedYawAngle(Quaterniondc orientation) {
        double eps = SIConfig.snapOrientationEpsilon();
        Vector3d up = orientation.transform(new Vector3d(0, 1, 0));
        if (up.distance(0, 1, 0) > eps) {
            return null; // pitched or rolled - vanilla block states can't represent this anyway
        }

        Vector3d facing = orientation.transform(new Vector3d(1, 0, 0));
        Vec3 facingWorld = new Vec3(facing.x, facing.y, facing.z);

        for (int angle = 0; angle < 4; angle++) {
            Vec3 candidate = new Vec3(1, 0, 0).yRot((float) (angle * Math.PI / 2.0));
            if (facingWorld.distanceTo(candidate) <= eps) {
                return angle;
            }
        }
        return null;
    }

    private static boolean isNearBlockCenter(Vector3d pos) {
        return isNearHalf(pos.x) && isNearHalf(pos.y) && isNearHalf(pos.z);
    }

    private static boolean isNearHalf(double coord) {
        double frac = coord - Math.floor(coord);
        return Math.abs(frac - 0.5) <= SIConfig.snapPositionEpsilon();
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
            // A landing only mints ground where it touches ground: a landed block
            // becomes an anchor (row cleared) only if a neighbour OUTSIDE the landed
            // set already reads as an anchor - checked with the pure peek, and before
            // any clearing, so landed blocks can never anchor each other. The rest
            // re-enter tracked at natural, and the landing is re-checked next tick.
            Set<BlockPos> dests = new HashSet<>(blocks.size());
            for (BlockPos p : blocks) {
                dests.add(transform.apply(p));
            }
            int anchored = 0;
            for (BlockPos dest : dests) {
                boolean touchesAnchor = false;
                for (Direction d : Direction.values()) {
                    BlockPos n = dest.relative(d);
                    if (!dests.contains(n) && Integrity.isAnchor(level, reg, n)) {
                        touchesAnchor = true;
                        break;
                    }
                }
                if (touchesAnchor) {
                    reg.clear(dest);
                    anchored++;
                } else {
                    reg.set(dest, Integrity.naturalOf(level, dest, level.getBlockState(dest)));
                }
            }
            StructuralIntegrity.LOGGER.info("[SI] revert anchoring: {}/{} landed blocks touch existing ground",
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
                reg.set(dest, Integrity.naturalOf(level, dest, level.getBlockState(dest)));
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
