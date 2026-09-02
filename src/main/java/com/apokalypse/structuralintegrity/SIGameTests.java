package com.apokalypse.structuralintegrity;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Gamma verification for {@link SIFall} stage 2/3: does a detached component
 * actually get handed to sable (stage 2), and does the resulting sub-level
 * actually revert back to blocks once it lands (stage 3)?
 *
 * Stage 3 is uncommitted and untested. The specific hypothesis this exercises:
 * {@link SIFall#checkForRevert} only calls {@code tryRevert} while the sub-level's
 * velocity is still ABOVE {@code MOVING_THRESHOLD_SQ} - if sable zeroes velocity
 * the same tick it snaps a resting body's pose into final alignment, that
 * above-threshold+aligned tick never occurs and a landed sub-level would never
 * revert, staying a physics object forever. Only a real tick-by-tick run against
 * sable's actual physics can show which of those happens; reading the source
 * cannot.
 *
 * Deliberately bypasses Stage 1 ({@link SIEvents}/{@link Integrity}): the loose
 * cluster's wbireg rows are set directly rather than produced by a real
 * place/break, so this isolates stage 2/3 from stage 1's own separate logic.
 */
@GameTestHolder(StructuralIntegrity.MODID)
@PrefixGameTestTemplate(false)
public final class SIGameTests {
    private SIGameTests() {}

    // 5x5 floor for the cluster to land on, well inside the 7x12x7 "empty" template.
    private static final int FLOOR_Y = 1;
    private static final int FLOOR_MIN = 1;
    private static final int FLOOR_MAX = 5;

    // 2x2x1 cluster floating well above the floor, connected to nothing.
    private static final BlockPos[] CLUSTER_LOCAL = {
            new BlockPos(2, 8, 2), new BlockPos(3, 8, 2),
            new BlockPos(2, 8, 3), new BlockPos(3, 8, 3),
    };

    // The "empty" template's own size (see make_empty_structure.py) - this test's world-space
    // footprint. The gametest server runs every mod's @GameTest methods concurrently in one
    // shared ServerLevel, tiled next to each other, so container.getAllSubLevels() returns
    // OTHER tests' sable objects too (sable's own PhysicsTest suite among them) - counting
    // those against this test's own pass condition means it can never succeed while any
    // neighboring test still has a sub-level, regardless of whether this test's own cluster
    // reverted correctly. A margin covers legitimate physics overshoot before it settles.
    private static final int TEMPLATE_SIZE_X = 7;
    private static final int TEMPLATE_SIZE_Y = 12;
    private static final int TEMPLATE_SIZE_Z = 7;
    private static final int BOUNDS_MARGIN = 4;

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void revertOnLanding(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        WbiReg reg = WbiReg.of(level);

        for (int x = FLOOR_MIN; x <= FLOOR_MAX; x++) {
            for (int z = FLOOR_MIN; z <= FLOOR_MAX; z++) {
                helper.setBlock(new BlockPos(x, FLOOR_Y, z), Blocks.STONE);
            }
        }

        List<BlockPos> clusterWorld = new ArrayList<>();
        for (BlockPos local : CLUSTER_LOCAL) {
            helper.setBlock(local, Blocks.STONE);
            BlockPos world = helper.absolutePos(local);
            clusterWorld.add(world);
            // Disturbed, not ground - an untouched stone block defaults to WbiReg.ANCHOR
            // (see Integrity#storedAt), which Integrity#collect treats as already-grounded
            // even for a block floating alone in the void. This is what a real break/
            // explosion disturb pass leaves behind once whatever held this up is gone.
            reg.set(world, Integrity.naturalOf(level, world, level.getBlockState(world)));
        }

        StructuralIntegrity.LOGGER.info(
                "[SI-TEST] revertOnLanding: floor y={} [{}..{}]x[{}..{}], cluster n={} at {}",
                FLOOR_Y, FLOOR_MIN, FLOOR_MAX, FLOOR_MIN, FLOOR_MAX, clusterWorld.size(), clusterWorld);

        // This test is about the LANDING half of the cycle: a piece that comes to rest
        // square stops being a physics object and becomes blocks again. The collapse
        // kick exists to stop pieces landing square, so it is turned off for this one
        // fall - with it on, the piece tumbles and correctly never re-aligns, which is
        // the behaviour collapseSpinsSubLevel is there to check.
        SIFall.overrideCollapseKickAt(clusterWorld.get(0), 0.0, 0.0);
        SIFall.queueFall(level, clusterWorld.get(0));

        // World-space AABB of this test's own "empty" structure instance, expanded by a
        // margin. helper.absolutePos already accounts for the structure's placement and
        // rotation, so min/max is taken per-axis rather than assuming corner order.
        BlockPos nearCorner = helper.absolutePos(BlockPos.ZERO);
        BlockPos farCorner = helper.absolutePos(
                new BlockPos(TEMPLATE_SIZE_X - 1, TEMPLATE_SIZE_Y - 1, TEMPLATE_SIZE_Z - 1));
        double minX = Math.min(nearCorner.getX(), farCorner.getX()) - BOUNDS_MARGIN;
        double maxX = Math.max(nearCorner.getX(), farCorner.getX()) + BOUNDS_MARGIN;
        double minY = Math.min(nearCorner.getY(), farCorner.getY()) - BOUNDS_MARGIN;
        double maxY = Math.max(nearCorner.getY(), farCorner.getY()) + BOUNDS_MARGIN;
        double minZ = Math.min(nearCorner.getZ(), farCorner.getZ()) - BOUNDS_MARGIN;
        double maxZ = Math.max(nearCorner.getZ(), farCorner.getZ()) + BOUNDS_MARGIN;

        // SIFall#worldAnchorOf, not pose.transformPosition(new Vector3d()) - see its javadoc.
        // Transforming the local origin returns a point millions of blocks from the sub-level,
        // so this filter matched nothing and the test reported "stage 2 never created a
        // sub-level" while the log plainly showed stage 2 creating one.
        java.util.function.Predicate<ServerSubLevel> ownedByThisTest = sub -> {
            var pos = SIFall.worldAnchorOf(sub);
            return pos.x >= minX && pos.x <= maxX
                    && pos.y >= minY && pos.y <= maxY
                    && pos.z >= minZ && pos.z <= maxZ;
        };

        AtomicBoolean everAssembled = new AtomicBoolean(false);
        AtomicBoolean censusLogged = new AtomicBoolean(false);

        helper.onEachTick(() -> {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            List<ServerSubLevel> subLevels = container == null ? List.of() : container.getAllSubLevels();
            List<ServerSubLevel> ownSubLevels = subLevels.stream().filter(ownedByThisTest).toList();
            if (!ownSubLevels.isEmpty()) {
                everAssembled.set(true);
            }
            for (ServerSubLevel sub : ownSubLevels) {
                // Same trap as the filter above, and it made this trace lie: with the
                // orientation still identity the origin transform's Y component happens to
                // read as a plausible world Y, so ticks 1-21 looked like a clean fall. The
                // moment the body tilted on landing, the plot-scale X/Z of -R*centreOfMass
                // leaked into Y and the trace showed y=27947 -> 40331 -> 22416 -> 10267,
                // which reads as the sub-level being flung into the sky and is nothing of
                // the sort - it decays exactly as the angular velocity decays.
                var pos = SIFall.worldAnchorOf(sub);
                StructuralIntegrity.LOGGER.info(
                        "[SI-TEST] tick={} subLevel pos=({},{},{}) linVelSq={} angVelSq={}",
                        helper.getTick(), pos.x, pos.y, pos.z,
                        sub.latestLinearVelocity.lengthSquared(), sub.latestAngularVelocity.lengthSquared());
            }
        });

        // Stage 2 must fire within a couple of ticks of queueFall - if it hasn't by tick
        // 20, the fall/assemble path itself is broken and stage 3 was never reachable.
        helper.runAtTickTime(20, () -> helper.assertTrue(everAssembled.get(),
                "stage 2 never created a sub-level - fall/assemble did not fire"));

        helper.succeedWhen(() -> {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            long ownSubLevels = container == null ? 0
                    : container.getAllSubLevels().stream().filter(ownedByThisTest).count();
            helper.assertTrue(everAssembled.get() && ownSubLevels == 0,
                    "waiting for revert: everAssembled=" + everAssembled.get() + " ownSubLevels=" + ownSubLevels);

            // The piece has landed and become blocks again. With
            // subLevelsFloatWhenReconverted off - the default since 0.7.0 - the landing
            // mints no ground, so every block that came back must still be tracked.
            // Anything above the floor reading as ANCHOR is SIFall's reg.clear() path
            // having run, which is exactly the behaviour being switched off: an anchor
            // never fails, so a collapse would be leaving permanent terrain behind it.
            // The floor itself is deliberately untouched and therefore ground, hence
            // starting above it.
            List<BlockPos> minted = new ArrayList<>();
            for (int x = 0; x < TEMPLATE_SIZE_X; x++) {
                for (int y = FLOOR_Y + 1; y < TEMPLATE_SIZE_Y; y++) {
                    for (int z = 0; z < TEMPLATE_SIZE_Z; z++) {
                        BlockPos world = helper.absolutePos(new BlockPos(x, y, z));
                        if (!level.getBlockState(world).isAir() && reg.isAnchor(world)) {
                            minted.add(world);
                        }
                    }
                }
            }
            if (censusLogged.compareAndSet(false, true)) {
                StructuralIntegrity.LOGGER.info(
                        "[SI-TEST] revertOnLanding landed-anchor census: {} block(s) above the"
                                + " floor read as ANCHOR after the revert {}",
                        minted.size(), minted);
            }
            helper.assertTrue(minted.isEmpty(),
                    "the landing minted ground: " + minted.size() + " block(s) above the floor"
                            + " read as ANCHOR " + minted + " - subLevelsFloatWhenReconverted"
                            + " must leave landed blocks tracked");
        });
    }

    /**
     * Not an assertion - a census. Every block in the registry resolved through the
     * real runtime pipeline (datamap rows, tags, collision shapes, hardness
     * derivation) and written to build/integrity-sweep.csv, one entry per block, so
     * the whole table can be read outside the game. Runs in the gametest server so
     * a live instance is never touched.
     */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void integritySweep(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(BlockPos.ZERO);
        Map<Integer, Integer> tiers = new TreeMap<>();
        StringBuilder csv = new StringBuilder("id,integrity,never_anchor,source,structural,hardness,volume,vol_derived\n");
        int vanilla = 0;
        for (Block block : BuiltInRegistries.BLOCK) {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
            if (!id.getNamespace().equals("minecraft")) {
                continue;
            }
            vanilla++;
            BlockState state = block.defaultBlockState();
            boolean structural = !(state.isAir() || block instanceof LiquidBlock
                    || block instanceof BushBlock);
            int nat = Integrity.naturalOf(level, pos, state);
            boolean never = Integrity.neverAnchor(level, pos, state);
            boolean hasRow = block.builtInRegistryHolder().getData(SIDataMaps.NATURAL) != null;
            float hardness = safeHardness(level, pos, state);
            double volume = shapeVolume(level, pos, state);
            csv.append(id.getPath()).append(',').append(nat).append(',').append(never)
                    .append(',').append(hasRow ? "datamap" : "derived").append(',')
                    .append(structural).append(',').append(hardness).append(',')
                    .append(String.format(java.util.Locale.ROOT, "%.5f", volume)).append(',')
                    .append(volumeDerived(hardness, volume)).append('\n');
            if (structural) {
                tiers.merge(nat, 1, Integer::sum);
            }
        }
        Path out = Path.of("..", "integrity-sweep.csv").toAbsolutePath().normalize();
        try {
            Files.writeString(out, csv.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        StructuralIntegrity.LOGGER.info("[SI] SWEEP {} vanilla blocks -> {} (structural tier -> count: {})",
                vanilla, out, tiers);
        helper.succeed();
    }

    // ---- 0.6.0: force on sub-levels ----

    /**
     * Hard enough that nothing else can account for it. A collapse topple is
     * collapseForce (1.5 m/s by default) and gravity only moves Y, so a body that
     * ends up travelling faster than a quarter of this along +X was pushed by
     * {@link SIForce#apply} and by nothing else in the mod.
     */
    private static final double TEST_PUSH = 40.0;

    /**
     * Well under what the default collapseTorque produces on a four-block cluster -
     * 2.5 against a mass of 8 is a torque impulse of 20, and this body's moment of
     * inertia about the tumble axis is a few kg m2, so several rad/s - and well over
     * anything solver noise can put on that particular axis.
     */
    private static final double SPIN_THRESHOLD = 0.05;

    /**
     * The kick this test applies to its own piece, independent of what the mod ships.
     *
     * collapseTorque is a feel setting - it has already gone from "spins hard" to
     * "does not spin at all" once - and whether the angular route still works is not
     * a matter of taste. These are the numbers that were shipped when the route was
     * fixed, kept here so the measurement survives any retune.
     */
    private static final double TEST_KICK_FORCE = 1.5;
    private static final double TEST_KICK_TORQUE = 2.5;

    /**
     * Does {@link SIForce#apply} actually reach sable's physics pipeline? A cluster
     * is dropped exactly as {@link #revertOnLanding} drops one, and the first tick
     * on which it exists as a sub-level it is shoved along +X. If the impulse lands,
     * the body's own reported linear velocity gains an X component it had no other
     * way to get.
     *
     * The sub-level is captured by reference the moment it is first seen, and
     * located by {@link SIForce#worldPositionOf} rather than
     * {@link SIFall#worldAnchorOf} - the pose's translation is the world position of
     * the centre of mass by definition, so it needs none of the frame assumptions
     * that make worldAnchorOf unreliable.
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void forcePushesSubLevel(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        WbiReg reg = WbiReg.of(level);

        List<BlockPos> clusterWorld = new ArrayList<>();
        for (BlockPos local : CLUSTER_LOCAL) {
            helper.setBlock(local, Blocks.STONE);
            BlockPos world = helper.absolutePos(local);
            clusterWorld.add(world);
            reg.set(world, Integrity.naturalOf(level, world, level.getBlockState(world)));
        }
        StructuralIntegrity.LOGGER.info("[SI-TEST] forcePushesSubLevel: cluster n={} at {}",
                clusterWorld.size(), clusterWorld);
        SIFall.queueFall(level, clusterWorld.get(0));

        BlockPos queuedAt = clusterWorld.get(0);
        AtomicReference<ServerSubLevel> own = new AtomicReference<>();
        AtomicBoolean pushed = new AtomicBoolean(false);
        AtomicBoolean moved = new AtomicBoolean(false);
        // Ticks since the sub-level was first seen. The whole question is whether a
        // body ignores an impulse only while it is brand new.
        int[] age = {-1};

        helper.onEachTick(() -> {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) {
                return;
            }
            if (own.get() == null) {
                // Matched on the anchor this test itself queued, never on a spatial
                // margin. Gametests are tiled a few blocks apart in one shared level,
                // so any generous margin reaches into the neighbours - a 24-block one
                // picked up sable's own assembly test, twenty blocks away, and a
                // 1370 N s impulse landed on its structure instead of this one's.
                for (ServerSubLevel sub : container.getAllSubLevels()) {
                    if (queuedAt.equals(SIFall.assembledAt(sub))) {
                        own.set(sub);
                        var pose = sub.logicalPose().position();
                        var bb = sub.boundingBox();
                        StructuralIntegrity.LOGGER.info(
                                "[SI-TEST] own sub-level assembledAt={} poseCoM=({},{},{}) "
                                        + "worldBounds=[{},{},{} .. {},{},{}]",
                                queuedAt, pose.x(), pose.y(), pose.z(),
                                bb.minX(), bb.minY(), bb.minZ(), bb.maxX(), bb.maxY(), bb.maxZ());
                        break;
                    }
                }
            }
            ServerSubLevel sub = own.get();
            if (sub == null) {
                return;
            }
            age[0]++;
            if (!pushed.get()) {
                if (SIForce.apply(sub, new Vec3(1.0, 0.0, 0.0), TEST_PUSH)) {
                    pushed.set(true);
                }
                return;
            }
            // Asked of the physics engine, not read off latestLinearVelocity. That
            // field is recomputed from the pose delta and reads a flat zero for these
            // bodies for their whole life, which is what made an earlier version of
            // this test report a working push as a failure.
            double vx = SIForce.linearVelocityOf(sub).x;
            StructuralIntegrity.LOGGER.info("[SI-TEST] age={} vx={} (mirror says {})",
                    age[0], vx, sub.latestLinearVelocity.x());
            if (vx > TEST_PUSH * 0.25) {
                moved.set(true);
            }
        });

        helper.runAtTickTime(30, () -> helper.assertTrue(pushed.get(),
                "no sub-level to push - fall/assemble did not fire, or the rigid body handle was null"));

        helper.succeedWhen(() -> helper.assertTrue(moved.get(),
                "impulse did not change the body's velocity: pushed=" + pushed.get()));
    }



    /**
     * The spec's rotational half: a piece that detaches through integrity loss must
     * come out of {@link SIFall} already turning, and turning the right way.
     *
     * Nothing is applied by this test. The cluster is dropped exactly as
     * {@link #forcePushesSubLevel} drops one and then only watched, so what is being
     * measured is the shipped {@code collapseTorque} path inside
     * {@code SIFall#assemble} rather than a call the test made itself.
     *
     * The assertion is on the component of the body's angular velocity ALONG its own
     * tumble axis, not on the raw magnitude. A falling body picks up spin from ground
     * contact too, and that spin has no reason to line up with the axis the collapse
     * chose; requiring the sign and the direction to match is what separates the
     * torque landing from the body simply bouncing.
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void collapseSpinsSubLevel(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        WbiReg reg = WbiReg.of(level);

        List<BlockPos> clusterWorld = new ArrayList<>();
        for (BlockPos local : CLUSTER_LOCAL) {
            helper.setBlock(local, Blocks.STONE);
            BlockPos world = helper.absolutePos(local);
            clusterWorld.add(world);
            reg.set(world, Integrity.naturalOf(level, world, level.getBlockState(world)));
        }
        BlockPos queuedAt = clusterWorld.get(0);
        SIFall.overrideCollapseKickAt(queuedAt, TEST_KICK_FORCE, TEST_KICK_TORQUE);
        SIFall.queueFall(level, queuedAt);

        // Recomputed here from the same inputs assemble() will use, so the test knows
        // which way the piece was told to roll without reading it back off the body.
        Vec3 axis = SIForce.tumbleAxis(queuedAt, clusterWorld).normalize();
        StructuralIntegrity.LOGGER.info(
                "[SI-TEST] collapseSpinsSubLevel: cluster n={} anchor={} expected tumble axis=({},{},{})",
                clusterWorld.size(), queuedAt, axis.x, axis.y, axis.z);

        AtomicReference<ServerSubLevel> own = new AtomicReference<>();
        AtomicBoolean seen = new AtomicBoolean(false);
        AtomicBoolean spun = new AtomicBoolean(false);
        int[] age = {-1};

        helper.onEachTick(() -> {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) {
                return;
            }
            if (own.get() == null) {
                for (ServerSubLevel sub : container.getAllSubLevels()) {
                    if (queuedAt.equals(SIFall.assembledAt(sub))) {
                        own.set(sub);
                        seen.set(true);
                        break;
                    }
                }
            }
            ServerSubLevel sub = own.get();
            if (sub == null) {
                return;
            }
            // Age, not tick: what matters is how long this body has existed, because a
            // body younger than its own mass properties is exactly the case that used
            // to swallow the spin silently.
            age[0]++;
            Vec3 w = SIForce.angularVelocityOf(sub);
            Vec3 v = SIForce.linearVelocityOf(sub);
            double along = w.dot(axis);
            StructuralIntegrity.LOGGER.info(
                    "[SI-TEST] age={} tick={} omega=({},{},{}) alongTumbleAxis={} v=({},{},{})",
                    age[0], helper.getTick(), w.x, w.y, w.z, along, v.x, v.y, v.z);
            if (along > SPIN_THRESHOLD) {
                spun.set(true);
            }
        });

        helper.runAtTickTime(30, () -> helper.assertTrue(seen.get(),
                "no sub-level was assembled - fall/assemble did not fire"));

        helper.succeedWhen(() -> helper.assertTrue(spun.get(),
                "the assembled sub-level is not turning about its tumble axis: seen=" + seen.get()));
    }

    /**
     * The other half of the force spec: a real detonation, through the real
     * {@code ExplosionEvent.Detonate} handler, moving a real sub-level.
     * {@link #forcePushesSubLevel} proves the impulse reaches sable; this proves the
     * explosion is wired to it and that the range and falloff arithmetic lands on
     * the right body.
     *
     * The charge is deliberately tiny - radius 1, so explosionForceRadius reaches
     * three blocks - and set to break nothing. Gametests share one level a few
     * blocks apart, and the shipped behaviour pushes every sub-level in reach
     * whoever owns it, so a default-sized blast here would reach into the
     * neighbouring tests and shove their structures around.
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void explosionPushesSubLevel(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        WbiReg reg = WbiReg.of(level);

        List<BlockPos> clusterWorld = new ArrayList<>();
        for (BlockPos local : CLUSTER_LOCAL) {
            helper.setBlock(local, Blocks.STONE);
            BlockPos world = helper.absolutePos(local);
            clusterWorld.add(world);
            reg.set(world, Integrity.naturalOf(level, world, level.getBlockState(world)));
        }
        BlockPos queuedAt = clusterWorld.get(0);
        SIFall.queueFall(level, queuedAt);

        AtomicReference<ServerSubLevel> own = new AtomicReference<>();
        AtomicBoolean detonated = new AtomicBoolean(false);
        AtomicBoolean moved = new AtomicBoolean(false);
        double[] baselineVx = {0.0};

        helper.onEachTick(() -> {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) {
                return;
            }
            if (own.get() == null) {
                for (ServerSubLevel sub : container.getAllSubLevels()) {
                    if (queuedAt.equals(SIFall.assembledAt(sub))) {
                        own.set(sub);
                        break;
                    }
                }
            }
            ServerSubLevel sub = own.get();
            if (sub == null) {
                return;
            }
            if (!detonated.get()) {
                Vec3 at = SIForce.worldPositionOf(sub);
                baselineVx[0] = SIForce.linearVelocityOf(sub).x;
                // Two blocks to the -X side, so anything the blast does shows up as +X.
                level.explode(null, at.x - 2.0, at.y, at.z, 1.0f, Level.ExplosionInteraction.NONE);
                detonated.set(true);
                StructuralIntegrity.LOGGER.info(
                        "[SI-TEST] explosionPushesSubLevel: charge at {} baselineVx={}",
                        at.x - 2.0, baselineVx[0]);
                return;
            }
            double delta = SIForce.linearVelocityOf(sub).x - baselineVx[0];
            StructuralIntegrity.LOGGER.info("[SI-TEST] tick={} post-blast vx delta={}",
                    helper.getTick(), delta);
            // explosionForce 8 m/s, falling off to two thirds one block outside the
            // hull. Nothing else in the mod can add to +X after assembly.
            if (delta > 2.0) {
                moved.set(true);
            }
        });

        helper.runAtTickTime(30, () -> helper.assertTrue(detonated.get(),
                "no sub-level existed to detonate next to - fall/assemble did not fire"));

        helper.succeedWhen(() -> helper.assertTrue(moved.get(),
                "the blast did not move the sub-level: detonated=" + detonated.get()));
    }

    // ---- 0.6.0: ground that stopped being ground ----

    // A 3x3x3 of stone with local corner (1,4,1): the 26-block shell all carry
    // wbireg rows, the block at the centre carries none and so reads as ground.
    private static final BlockPos ENCLOSE_MIN = new BlockPos(1, 4, 1);
    private static final BlockPos ENCLOSE_CORE = new BlockPos(2, 5, 2);
    private static final BlockPos ENCLOSE_LID = new BlockPos(2, 6, 2);

    /**
     * The enclosure walk, both answers, without waiting on a one-in-a-hundred roll -
     * which is why {@link SIEnclosure#check} is public separately from
     * {@link SIEnclosure#maybeCheck}.
     *
     * With a hole in the shell the core is open to the world and must stay ground;
     * with the hole plugged nothing untouched connects it to anything and it must
     * stop being ground. The same core, in the same place, in the same test, so the
     * only difference between the two answers is the one block.
     */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void enclosedGroundIsDemoted(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        WbiReg reg = WbiReg.of(level);

        BlockPos core = helper.absolutePos(ENCLOSE_CORE);
        BlockPos lid = helper.absolutePos(ENCLOSE_LID);

        for (int dx = 0; dx < 3; dx++) {
            for (int dy = 0; dy < 3; dy++) {
                for (int dz = 0; dz < 3; dz++) {
                    BlockPos local = ENCLOSE_MIN.offset(dx, dy, dz);
                    helper.setBlock(local, Blocks.STONE);
                    BlockPos world = helper.absolutePos(local);
                    if (local.equals(ENCLOSE_CORE)) {
                        // Untouched: no row at all, which is what reads as ground.
                        reg.clear(world);
                    } else {
                        reg.set(world, Integrity.naturalOf(level, world, level.getBlockState(world)));
                    }
                }
            }
        }
        helper.assertTrue(reg.isAnchor(core), "test setup: the core should start out untracked");

        // Open the lid. The core now touches air, so it is not enclosed by anything.
        helper.setBlock(ENCLOSE_LID, Blocks.AIR);
        reg.clear(lid);
        int open = SIEnclosure.check(level, reg, lid);
        helper.assertTrue(open == 0, "open shell: expected no demotion, got " + open);
        helper.assertTrue(reg.isAnchor(core), "open shell: the core stopped being ground anyway");

        // Plug it. Every face of the core is now a block with a wbireg row.
        helper.setBlock(ENCLOSE_LID, Blocks.STONE);
        reg.set(lid, Integrity.naturalOf(level, lid, level.getBlockState(lid)));
        int closed = SIEnclosure.check(level, reg, lid);
        helper.assertTrue(closed == 1, "sealed shell: expected exactly the core demoted, got " + closed);
        helper.assertTrue(!reg.isAnchor(core), "sealed shell: the core is still ground");

        StructuralIntegrity.LOGGER.info("[SI-TEST] enclosedGroundIsDemoted: open={} closed={}", open, closed);
        helper.succeed();
    }

    // ---- 0.6.3: what a sideways link costs, and what it hands over -----------
    // Two legs of the same material off the same ground: one straight up, one
    // straight out. Every claim the 0.6.3 rules make is a number here - what a
    // pillar costs its base per block, what a ledge costs its innermost block per
    // block, and what each new block is worth when it arrives.
    //
    // 0.6.3 made two claims and 0.7.10 keeps one of them. The COST is unchanged and
    // is the whole reason the second claim could go: a sideways link still bills
    // sidewaysLoadMultiplier, so the ledge's innermost block pays two points per
    // block placed while the pillar's base pays one. What arrives is now the same in
    // both legs, because charging the structure double AND handing the newcomer half
    // was one event billed to two accounts. This test is where both numbers sit side
    // by side, so it is the clearest place to see that only one of them moved.
    private static final int PILLAR_X = 1;
    private static final int PILLAR_Z = 1;
    private static final int LEDGE_Z = 4;
    private static final int LEDGE_Y = 6;
    /**
     * Blocks added after the founded first one, the same count in both legs.
     *
     * Three, not more, because the ledge must stay strictly inside the template. A
     * block on the boundary has a neighbour in the surrounding world, that neighbour
     * has never been touched, and untouched means ground - so the outermost block
     * comes out founded at full strength with nothing charged, which is exactly what
     * a four-step ledge did before this was pinned to the plot.
     */
    private static final int REACH_STEPS = 3;

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void sidewaysCostsDoubleAndInheritsInFull(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        WbiReg reg = WbiReg.of(level);
        Block mat = Blocks.STONE;

        // Ground, left untouched so every one of these reads as WbiReg.ANCHOR: it
        // takes load without ever being charged. Both legs stand on the same rock.
        for (int x = FLOOR_MIN; x <= FLOOR_MIN + 5; x++) {
            helper.setBlock(new BlockPos(x, FLOOR_Y, PILLAR_Z), mat);
            helper.setBlock(new BlockPos(x, FLOOR_Y, LEDGE_Z), mat);
        }
        // The cliff face the ledge springs from - untouched too, so also ground.
        for (int y = FLOOR_Y + 1; y <= LEDGE_Y + 1; y++) {
            helper.setBlock(new BlockPos(FLOOR_MIN, y, LEDGE_Z), mat);
        }

        int natural = Integrity.naturalOf(level, helper.absolutePos(BlockPos.ZERO),
                mat.defaultBlockState());

        // Leg one: straight up off the ground. Every block placed charges the base
        // once, so the base is the first thing to run out.
        BlockPos pillarBase = helper.absolutePos(new BlockPos(PILLAR_X, FLOOR_Y + 1, PILLAR_Z));
        int pillarTip = 0;
        for (int i = 0; i <= REACH_STEPS; i++) {
            BlockPos local = new BlockPos(PILLAR_X, FLOOR_Y + 1 + i, PILLAR_Z);
            helper.setBlock(local, mat);
            Integrity.Placed placed = Integrity.place(level, reg, helper.absolutePos(local));
            helper.assertTrue(placed != null, "pillar step " + i + " was not structural");
            pillarTip = placed.assigned();
            StructuralIntegrity.LOGGER.info("[SI-TEST] pillar step {} assigned={} base={} trace={}",
                    i, placed.assigned(), reg.get(pillarBase), placed.trace());
        }
        int pillarBaseLeft = reg.get(pillarBase);

        // Leg two: straight out off the same ground, high enough that there is
        // nothing underneath it. Every block placed charges the innermost twice.
        BlockPos ledgeInner = helper.absolutePos(new BlockPos(FLOOR_MIN + 1, LEDGE_Y, LEDGE_Z));
        int ledgeTip = 0;
        for (int i = 0; i <= REACH_STEPS; i++) {
            BlockPos local = new BlockPos(FLOOR_MIN + 1 + i, LEDGE_Y, LEDGE_Z);
            helper.setBlock(local, mat);
            Integrity.Placed placed = Integrity.place(level, reg, helper.absolutePos(local));
            helper.assertTrue(placed != null, "ledge step " + i + " was not structural");
            // Only the first block out is allowed to touch ground. If a later one
            // does, it has found the untouched world past the edge of the template
            // and the leg is no longer measuring a ledge at all.
            helper.assertTrue(i == 0 || !placed.onAnchor(),
                    "ledge step " + i + " founded on ground - it has reached outside the plot");
            ledgeTip = placed.assigned();
            StructuralIntegrity.LOGGER.info("[SI-TEST] ledge step {} assigned={} inner={} trace={}",
                    i, placed.assigned(), reg.get(ledgeInner), placed.trace());
        }
        int ledgeInnerLeft = reg.get(ledgeInner);

        int pillarSpent = natural - pillarBaseLeft;
        int ledgeSpent = natural - ledgeInnerLeft;
        // The same numbers said in blocks: how far each leg gets before its first
        // block runs out. This is the line the balance target is read off.
        double pillarReach = 1.0 + (double) natural * REACH_STEPS / Math.max(1, pillarSpent);
        double ledgeReach = 1.0 + (double) natural * REACH_STEPS / Math.max(1, ledgeSpent);
        StructuralIntegrity.LOGGER.info(
                "[SI-TEST] REACH natural={} over {} steps: pillar base {}->{} (spent {}), "
                        + "ledge inner {}->{} (spent {}); arriving tip pillar={} ledge={}; "
                        + "projected reach pillar={} ledge={} (ledge is {} of the pillar)",
                natural, REACH_STEPS, natural, pillarBaseLeft, pillarSpent,
                natural, ledgeInnerLeft, ledgeSpent, pillarTip, ledgeTip,
                String.format(java.util.Locale.ROOT, "%.1f", pillarReach),
                String.format(java.util.Locale.ROOT, "%.1f", ledgeReach),
                String.format(java.util.Locale.ROOT, "%.2fx", ledgeReach / pillarReach));

        helper.assertTrue(pillarSpent == REACH_STEPS,
                "a pillar costs its base one point per block: expected " + REACH_STEPS
                        + ", spent " + pillarSpent);
        helper.assertTrue(ledgeSpent == 2 * REACH_STEPS,
                "a ledge costs its innermost block two points per block: expected "
                        + (2 * REACH_STEPS) + ", spent " + ledgeSpent);
        helper.assertTrue(pillarTip == natural,
                "a block set on TOP inherits in full: expected " + natural
                        + ", got " + pillarTip);
        helper.assertTrue(ledgeTip == natural,
                "since 0.7.10 a block set on the SIDE inherits in full, the same as"
                        + " one set on TOP: expected " + natural + ", got " + ledgeTip);
        helper.assertTrue(ledgeTip != natural / 2 && natural / 2 > 0,
                "the fixture must be able to tell the rules apart: 0.6.3 through"
                        + " 0.7.9 gave " + (natural / 2) + " here");
        helper.assertTrue(ledgeSpent > pillarSpent,
                "the sideways leg must still cost more than the upright one - that"
                        + " charge is what replaced the halving, so if it ever goes"
                        + " the ledge becomes free: ledge spent " + ledgeSpent
                        + ", pillar spent " + pillarSpent);
        helper.succeed();
    }

    /**
     * Build the fixture both side-joint tests measure on: a run of ground, a cliff
     * rising off it, and one block ledged out from the cliff at {@link #LEDGE_Y} to
     * act as the HOST. Everything untouched is ground and reads as
     * {@link WbiReg#ANCHOR}, so the host is the first thing in the fixture that
     * carries a real row.
     *
     * The host is placed against the cliff, which is ground, so it founds at full
     * natural whichever face it touched - that exemption is what makes the host's
     * stored value equal to its own rating and keeps the guest's assertion about
     * the CAP rather than about wear the fixture happened to inflict.
     *
     * @return the host's assigned value, which must be its full natural
     */
    private static int buildSideJointHost(GameTestHelper helper, WbiReg reg, Block host) {
        ServerLevel level = helper.getLevel();
        for (int x = FLOOR_MIN; x <= FLOOR_MIN + 4; x++) {
            helper.setBlock(new BlockPos(x, FLOOR_Y, LEDGE_Z), Blocks.OBSIDIAN);
        }
        for (int y = FLOOR_Y + 1; y <= LEDGE_Y + 1; y++) {
            helper.setBlock(new BlockPos(FLOOR_MIN, y, LEDGE_Z), Blocks.OBSIDIAN);
        }
        BlockPos local = new BlockPos(FLOOR_MIN + 1, LEDGE_Y, LEDGE_Z);
        helper.setBlock(local, host);
        Integrity.Placed placed = Integrity.place(level, reg, helper.absolutePos(local));
        helper.assertTrue(placed != null, "side-joint host was not structural");
        helper.assertTrue(placed.onAnchor(),
                "side-joint host should have founded on the cliff, so its row is full natural");
        return placed.assigned();
    }

    /**
     * Place the GUEST one further out, so its only connection is a horizontal face
     * into the host, and report what it was assigned.
     */
    private static Integrity.Placed placeSideJointGuest(GameTestHelper helper, WbiReg reg,
                                                        Block guest) {
        BlockPos local = new BlockPos(FLOOR_MIN + 2, LEDGE_Y, LEDGE_Z);
        helper.setBlock(local, guest);
        Integrity.Placed placed = Integrity.place(helper.getLevel(), reg,
                helper.absolutePos(local));
        helper.assertTrue(placed != null, "side-joint guest was not structural");
        // If this founded it has reached the untouched world past the plot edge and
        // is measuring ground, not a joint.
        helper.assertTrue(!placed.onAnchor(),
                "side-joint guest founded on ground - it has reached outside the plot");
        return placed;
    }

    /**
     * 0.7.8: a side joint is worth half of the HOST's rating, not half of the
     * guest's own.
     *
     * Stone (32) hung on the side of an iron block (80). Half of iron is 40, which
     * is more than stone's own 32, so the guest's own material is what binds and it
     * arrives at a full 32.
     *
     * Under the rule this replaces the fraction was taken of the guest's own rating,
     * so the same stone arrived at 16. That number is asserted against directly:
     * a stone shelf bolted to iron is a better shelf than a stone shelf bolted to
     * stone, and before 0.7.8 the host had no say in it at all.
     *
     * 0.7.9 moved the fraction from the host's rating onto the host's stored value
     * and this test did not change, which is the point: the fixture founds its host
     * on the cliff, so the host is unworn and the two numbers are the same number.
     * That agreement on a fresh build is a property worth pinning rather than a
     * coincidence worth ignoring, so the test now asserts it directly - and it is
     * exactly why the divergence needs its own fixture below, with a host that has
     * been worn on purpose.
     */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void sideJointDoesNotPenaliseAWeakGuestOnAStrongHost(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        WbiReg reg = WbiReg.of(level);

        int hostStored = buildSideJointHost(helper, reg, Blocks.IRON_BLOCK);
        Integrity.Placed guest = placeSideJointGuest(helper, reg, Blocks.STONE);

        BlockPos probe = helper.absolutePos(BlockPos.ZERO);
        int natHost = Integrity.naturalOf(level, probe, Blocks.IRON_BLOCK.defaultBlockState());
        int natGuest = Integrity.naturalOf(level, probe, Blocks.STONE.defaultBlockState());

        StructuralIntegrity.LOGGER.info(
                "[SI-TEST] SIDE-JOINT weak-on-strong: host iron nat={} stored={}, "
                        + "guest stone nat={} assigned={} sideHost={} sideHostStored={}; "
                        + "0.7.10 expects the guest's own {} - unchanged from every "
                        + "earlier rule, which gave half-of-host={} (0.7.8/0.7.9, "
                        + "clipped by stone) and half-of-guest={} (pre-0.7.8)",
                natHost, hostStored, natGuest, guest.assigned(),
                guest.sideHost(), guest.sideHostStored(),
                natGuest, natHost / 2, natGuest / 2);

        helper.assertTrue(natHost >= 2 * natGuest,
                "fixture is not measuring anything: iron " + natHost + " must be at least"
                        + " twice stone " + natGuest + " for the two rules to differ");
        helper.assertTrue(guest.sideHost() == natHost,
                "the report must name the HOST's rating: expected " + natHost
                        + ", got " + guest.sideHost());
        helper.assertTrue(guest.assigned() == natGuest,
                "the host holds more than stone can carry, so stone's own " + natGuest
                        + " binds and it arrives full; got " + guest.assigned());
        helper.assertTrue(guest.assigned() > natGuest / 2,
                "the pre-0.7.8 rule halved the GUEST and would have given "
                        + (natGuest / 2) + "; got " + guest.assigned());
        helper.assertTrue(guest.assigned() == Math.min(natGuest, hostStored),
                "0.7.10 is one min over two numbers - the guest's own rating and what"
                        + " the host holds - with nothing else in it: expected "
                        + Math.min(natGuest, hostStored) + ", got " + guest.assigned());
        helper.assertTrue(guest.sideHostStored() == hostStored,
                "the report must also carry what the host still HELD: expected "
                        + hostStored + ", got " + guest.sideHostStored());
        helper.assertTrue(guest.sideHostStored() == guest.sideHost(),
                "an unworn host makes the 0.7.8 and 0.7.9 rules agree, and this"
                        + " fixture founds its host, so rating " + guest.sideHost()
                        + " and stored " + guest.sideHostStored() + " must match");
        helper.succeed();
    }

    /**
     * The direction the fraction actually cost something, and the case 0.7.10
     * changes: iron (80) hung on the side of stone (32) now arrives at 32, not 16.
     *
     * A joint is still only as good as the material it was made against - bolting a
     * strong block to a weak wall does not import the strong block's rating, and
     * the stone's 32 is still the ceiling. What is gone is the second cut on top of
     * that ceiling. 0.7.8 and 0.7.9 both halved the host's contribution before
     * applying it, so the guest arrived at 16 while the wall it hung on was sitting
     * on a full 32, and the missing 16 was charged to nobody and accounted for
     * nowhere.
     *
     * The sideways joint is not free, and it never was. The load chain bills it at
     * {@link SIConfig#sidewaysLoadMultiplier} - double, by default - to the sturdier
     * of the two blocks, which is a real cost taken out of the structure at the
     * moment of placement. Halving the newcomer as well was a second charge for one
     * event, and this test is where the two used to be visible as one number.
     */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void sideJointToAWeakHostGivesWhatTheHostHolds(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        WbiReg reg = WbiReg.of(level);

        int hostStored = buildSideJointHost(helper, reg, Blocks.STONE);
        Integrity.Placed guest = placeSideJointGuest(helper, reg, Blocks.IRON_BLOCK);

        BlockPos probe = helper.absolutePos(BlockPos.ZERO);
        int natHost = Integrity.naturalOf(level, probe, Blocks.STONE.defaultBlockState());
        int natGuest = Integrity.naturalOf(level, probe, Blocks.IRON_BLOCK.defaultBlockState());

        StructuralIntegrity.LOGGER.info(
                "[SI-TEST] SIDE-JOINT strong-on-weak: host stone nat={} stored={}, "
                        + "guest iron nat={} assigned={} sideHost={} sideHostStored={}; "
                        + "0.7.10 expects the host's whole {}, where 0.7.8/0.7.9 "
                        + "halved it to {}",
                natHost, hostStored, natGuest, guest.assigned(),
                guest.sideHost(), guest.sideHostStored(),
                hostStored, natHost / 2);

        helper.assertTrue(guest.sideHost() == natHost,
                "the report must name the HOST's rating: expected " + natHost
                        + ", got " + guest.sideHost());
        helper.assertTrue(guest.assigned() == hostStored,
                "a strong block on a weak wall takes the WALL's whole remaining "
                        + hostStored + ", not a fraction of it; got " + guest.assigned());
        helper.assertTrue(guest.assigned() != natHost / 2,
                "the fixture must be able to tell the rules apart: half of "
                        + natHost + " is " + (natHost / 2) + ", which 0.7.8 and 0.7.9"
                        + " would have given, and 0.7.10 gives " + guest.assigned());
        helper.assertTrue(guest.assigned() < natGuest,
                "the host must still bind - iron's own " + natGuest + " is not"
                        + " importable through a weak wall; got " + guest.assigned());
        helper.assertTrue(guest.sideHostStored() == hostStored
                        && guest.sideHostStored() == natHost,
                "unworn host: rating " + natHost + " and stored "
                        + guest.sideHostStored() + " must agree here, so this fixture"
                        + " isolates the fraction and nothing else");
        helper.succeed();
    }

    /** Where {@link #buildSideJointHost} puts the host, so a test can wear it. */
    private static BlockPos sideJointHostPos() {
        return new BlockPos(FLOOR_MIN + 1, LEDGE_Y, LEDGE_Z);
    }

    /**
     * A worn host hands over what it has, whole.
     *
     * Nothing above separates the rules, because founding the host on the cliff
     * leaves it at full natural and the fixture never wears it. So this one wears
     * the host deliberately - the row is set straight to 10, which is programming
     * the precondition rather than reproducing it - and then hangs a stone guest
     * off it. Iron rated 80 holding 10, factor 0.5:
     *
     *   0.7.8  read the RATING:      min(stone 32, floor(80 * 0.5), host stored 10) = 10
     *   0.7.9  halved what was LEFT: min(stone 32, floor(10 * 0.5))                =  5
     *   0.7.10 takes what is LEFT:   min(stone 32, 10)                             = 10
     *
     * 0.7.9 arrived at five by measuring the right block and then charging it twice
     * - once by the fraction and again by the chain, which had already billed the
     * sideways link. Ten is the whole of what the block being hung off actually
     * has, which is the only limit the host can honestly impose.
     *
     * That it agrees with 0.7.8's answer is a coincidence of this fixture, not a
     * revert: 0.7.8 reached 10 by halving a pristine rating and then clipping to the
     * stored value, and would still read the rating on a host worn to 30. The
     * assertion below is against the stored value directly.
     */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void sideJointTakesWhatAWornHostHasLeftWhole(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        WbiReg reg = WbiReg.of(level);

        int fresh = buildSideJointHost(helper, reg, Blocks.IRON_BLOCK);
        BlockPos hostAbs = helper.absolutePos(sideJointHostPos());
        final int worn = 10;
        reg.set(hostAbs, worn);

        Integrity.Placed guest = placeSideJointGuest(helper, reg, Blocks.STONE);

        BlockPos probe = helper.absolutePos(BlockPos.ZERO);
        int natHost = Integrity.naturalOf(level, probe, Blocks.IRON_BLOCK.defaultBlockState());
        int natGuest = Integrity.naturalOf(level, probe, Blocks.STONE.defaultBlockState());
        double f = SIConfig.sideInheritanceFactor();
        int expected = Math.min(natGuest, worn);
        int pre0710 = Math.min(natGuest, (int) Math.floor(worn * f));

        StructuralIntegrity.LOGGER.info(
                "[SI-TEST] SIDE-JOINT worn host: iron nat={} fresh={} worn to {}; "
                        + "guest stone nat={} assigned={} sideHost={} sideHostStored={}; "
                        + "0.7.10 expects {}, 0.7.9 would have halved it to {}",
                natHost, fresh, worn, natGuest, guest.assigned(),
                guest.sideHost(), guest.sideHostStored(), expected, pre0710);

        helper.assertTrue(pre0710 != expected,
                "fixture is not measuring anything: the fraction and its removal both"
                        + " answer " + expected + " here, so pick a wear level where"
                        + " they differ");
        helper.assertTrue(guest.sideHost() == natHost,
                "the report must still name the host's RATING: expected " + natHost
                        + ", got " + guest.sideHost());
        helper.assertTrue(guest.sideHostStored() == worn,
                "the report must name what the host HELD: expected " + worn
                        + ", got " + guest.sideHostStored());
        helper.assertTrue(guest.assigned() == expected,
                "the whole of what the host has left (" + expected + "), not half of"
                        + " it (" + pre0710 + "); got " + guest.assigned());
        helper.assertTrue(guest.assigned() == guest.sideHostStored(),
                "the host is the binding limit here, so the guest must arrive holding"
                        + " exactly what the host held: " + guest.sideHostStored()
                        + ", got " + guest.assigned());
        helper.succeed();
    }

    /**
     * The consequence 0.7.10 is being made for: a ledge run stops thinning out.
     *
     * 0.7.9 made this run read 40, 20, 10 and called the compounding its whole
     * justification - a cantilever that ended on its own, with no distance rule and
     * no config key. It was also the clearest picture of what the fraction was
     * doing, which is charging the STRUCTURE for a shape the load chain had already
     * charged it for. Three iron blocks off an iron wall are three ordinary
     * placements, and none of them is weaker than iron.
     *
     * So the run now reads flat at full natural, and the ledge still ends - just
     * for the reason the rest of the mod uses. Every placement runs the load chain
     * back down through the run into the wall and the foundation, and a sideways
     * link there bills double. The wall wears, the foundation wears, and a long
     * enough cantilever brings itself down from the ROOT, which is where a real one
     * fails. Nothing in that needs the newcomer to be punished on arrival.
     *
     * Each block is asserted on its ASSIGNED value, the number the rule produced at
     * placement time, not on its stored row afterwards. The rows keep moving as
     * later placements charge back down the run, and that charge is the load chain's
     * business rather than this rule's - which is exactly the separation 0.7.10
     * restores.
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void aLedgeRunNoLongerThinsOutOnItsOwn(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        WbiReg reg = WbiReg.of(level);

        int hostStored = buildSideJointHost(helper, reg, Blocks.IRON_BLOCK);
        double f = SIConfig.sideInheritanceFactor();
        int natGuest = Integrity.naturalOf(level, helper.absolutePos(BlockPos.ZERO),
                Blocks.IRON_BLOCK.defaultBlockState());

        List<Integer> assigned = new ArrayList<>();
        List<Integer> readOff = new ArrayList<>();
        int prev = hostStored;
        for (int step = 1; step <= 3; step++) {
            BlockPos local = new BlockPos(FLOOR_MIN + 1 + step, LEDGE_Y, LEDGE_Z);
            helper.setBlock(local, Blocks.IRON_BLOCK);
            Integrity.Placed p = Integrity.place(level, reg, helper.absolutePos(local));
            helper.assertTrue(p != null, "ledge step " + step + " was not structural");
            helper.assertTrue(!p.onAnchor(),
                    "ledge step " + step + " founded on ground - the run has left the plot");
            helper.assertTrue(p.sideHostStored() == prev,
                    "step " + step + " must read the block before it, which held " + prev
                            + "; it read " + p.sideHostStored());
            assigned.add(p.assigned());
            readOff.add(p.sideHostStored());
            prev = p.assigned();
        }

        List<Integer> halving = new ArrayList<>();
        int decay = hostStored;
        for (int step = 0; step < assigned.size(); step++) {
            decay = (int) Math.floor(decay * f);
            halving.add(decay);
        }
        StructuralIntegrity.LOGGER.info(
                "[SI-TEST] LEDGE RUN off an iron wall holding {}: assigned {} "
                        + "(each read off {}); 0.7.9 would have given {}",
                hostStored, assigned, readOff, halving);

        for (int step = 0; step < assigned.size(); step++) {
            int expect = Math.min(natGuest, readOff.get(step));
            helper.assertTrue(assigned.get(step) == expect,
                    "step " + (step + 1) + " must take the whole of what the block"
                            + " before it held, capped by its own rating: expected "
                            + expect + ", got " + assigned.get(step));
        }
        helper.assertTrue(assigned.get(2) == assigned.get(0),
                "the run must NOT thin out on its own any more. 0.7.9 halved it to "
                        + halving + "; got " + assigned);
        helper.assertTrue(assigned.get(2) > halving.get(2),
                "fixture is not measuring anything: the halving rule and its removal"
                        + " agree at " + assigned.get(2) + " on this run");
        helper.succeed();
    }

    /**
     * The edge that used to need a config key, and no longer needs one.
     *
     * A host holding ONE point had half of one to give, which rounds to nothing, so
     * whether the guest arrived alive came down to a floor at {@code failAt + 1} and
     * {@link SIConfig#sideJointNeverArrivesSpent} decided whether that floor applied.
     * With no fraction there is no rounding: one point is handed over as one point,
     * the guest arrives cracked but standing, and the key has nothing left to decide.
     * It stays declared and unread - see {@link SIConfig#sideInheritanceFactor} for
     * why an unread key is not a deleted key.
     *
     * A host holding NOTHING still gives nothing, and it always did. That answer was
     * never the key's to make and it is unchanged: {@code min(guest, 0)} is zero the
     * same way the skipped floor was zero. Both cases are asserted here because the
     * two used to be reached by different code paths and now are not, and a test that
     * stops covering the second one would not notice if only the first survived.
     */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void sideJointAgainstANearlySpentHost(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        WbiReg reg = WbiReg.of(level);

        buildSideJointHost(helper, reg, Blocks.IRON_BLOCK);
        BlockPos hostAbs = helper.absolutePos(sideJointHostPos());
        boolean floored = SIConfig.sideJointNeverArrivesSpent();
        int failAt = SIConfig.failAt();

        reg.set(hostAbs, failAt + 1);
        Integrity.Placed nearly = placeSideJointGuest(helper, reg, Blocks.STONE);
        // No fraction, so no rounding, so no floor: the one point survives the hand
        // over whatever sideJointNeverArrivesSpent is still set to.
        int expectNearly = failAt + 1;

        // Clear the guest's row and the block before the second case, so the second
        // placement is a placement and not a recompute of the first one's leftovers.
        BlockPos guestLocal = new BlockPos(FLOOR_MIN + 2, LEDGE_Y, LEDGE_Z);
        reg.clear(helper.absolutePos(guestLocal));
        helper.setBlock(guestLocal, Blocks.AIR);

        reg.set(hostAbs, failAt);
        Integrity.Placed spent = placeSideJointGuest(helper, reg, Blocks.STONE);

        StructuralIntegrity.LOGGER.info(
                "[SI-TEST] SIDE-JOINT spent host (failAt={}; sideJointNeverArrivesSpent={} "
                        + "is declared but unread since 0.7.10): "
                        + "host holding {} -> guest {} (expected {}); "
                        + "host holding {} -> guest {} (expected 0)",
                failAt, floored, failAt + 1, nearly.assigned(), expectNearly,
                failAt, spent.assigned());

        helper.assertTrue(nearly.assigned() == expectNearly,
                "a host holding " + (failAt + 1) + " hands that point over whole, so"
                        + " the guest arrives at " + expectNearly + " with the config"
                        + " key out of it entirely; got " + nearly.assigned());
        helper.assertTrue(spent.assigned() == 0,
                "a spent host has no fraction to give and the floor must not rescue"
                        + " it: expected 0, got " + spent.assigned());
        helper.succeed();
    }

    /**
     * Reading a value that moves does not make the side joint move with it.
     *
     * {@link Integrity#faceCap} is shared with {@link Integrity#recompute}, so a
     * repair pass re-reads a host that may have worn since the guest was placed, and
     * {@code fresh} can come back lower than what the guest already holds. It never
     * lands: recompute takes {@code max(before, fresh)} and only ever repairs upward.
     *
     * 0.7.10 does not change this and could not have: removing the fraction changes
     * how big {@code fresh} is, not whether a smaller {@code fresh} is allowed to
     * land. The contract outlived the rule it was written against, which is the
     * argument for having pinned it separately.
     *
     * So a worn host repairs its guest LESS than a sound one would, and never
     * demotes it. Lowering an already-placed block as its host degrades is a real
     * and arguably desirable behaviour - it is what "structures come apart as they
     * wear" would mean taken to its end - but it would stop the side joint being
     * settled at placement time, which is a change in kind rather than in number,
     * and it belongs to 0.8.0 rather than to a point release. Asserted here so that
     * if 0.8.0 does make that change, this test is what tells it the old contract
     * was deliberate.
     */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void recomputeNeverLowersAGuestAsItsHostWears(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        WbiReg reg = WbiReg.of(level);

        buildSideJointHost(helper, reg, Blocks.IRON_BLOCK);
        Integrity.Placed guest = placeSideJointGuest(helper, reg, Blocks.STONE);
        BlockPos guestAbs = helper.absolutePos(new BlockPos(FLOOR_MIN + 2, LEDGE_Y, LEDGE_Z));
        int held = reg.get(guestAbs);

        reg.set(helper.absolutePos(sideJointHostPos()), 4);
        Integrity.Recomputed r = Integrity.recompute(level, reg, guestAbs);

        StructuralIntegrity.LOGGER.info(
                "[SI-TEST] RECOMPUTE over a worn host: guest held {}, host worn to 4, "
                        + "fresh={} after={} changed={} (a lowering would show after<before)",
                held, r.fresh(), r.after(), r.changed());

        helper.assertTrue(r.fresh() < held,
                "the fixture must actually produce a lower fresh value or it proves"
                        + " nothing: held " + held + ", fresh " + r.fresh());
        helper.assertTrue(r.after() == held,
                "recompute repairs upward only: the guest must still hold " + held
                        + ", got " + r.after());
        helper.assertTrue(reg.get(guestAbs) == held,
                "the row itself must be untouched: expected " + held + ", got "
                        + reg.get(guestAbs));
        helper.succeed();
    }

    /**
     * A spent block stands, and stops carrying.
     *
     * This is the whole of the holding branch, which became the default in 0.6.6
     * and had no coverage: a block driven to failAt is left in the world
     * rather than destroyed, and what it was holding is expected to come away
     * around it. The block staying put is easy to see; the load actually shedding
     * is not, because it depends on every graph walk agreeing that rubble conducts
     * nothing. They did not agree before 0.7.0 - the sub-level walk skipped spent
     * blocks while both support finders were happy to choose one as a footing - so
     * this asserts the block is still there, that nothing picks it as support, and
     * that what sat on it has genuinely lost its route to ground.
     */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void spentBlockStandsAndShedsLoad(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        WbiReg reg = WbiReg.of(level);
        Block mat = Blocks.STONE;

        // Ground: never routed through Integrity.place, so it reads as WbiReg.ANCHOR.
        helper.setBlock(new BlockPos(3, FLOOR_Y, 3), mat);

        // isSpent is hard-wired to return false for a material the config destroys,
        // so under a threshold below this block's natural every assertion here would
        // be testing nothing and passing anyway. Say so out loud rather than letting
        // a config drift read as a green run.
        //
        // This is why run/config sets holdSpentUpToNatural above the shipped 2: the
        // test needs one material that is both HELD and strong enough to stand a four
        // block pillar, and at the shipped default no such material exists. Holding
        // stops at natural 2 and a natural 2 block cannot carry two of itself, so the
        // pillar would spend its own base while being built. The branch is what is
        // under test, not the number that selects it.
        BlockPos probe = helper.absolutePos(new BlockPos(3, FLOOR_Y, 3));
        helper.assertTrue(Integrity.holdsWhenSpent(level, probe),
                "this test needs a material that is held when spent, but " + mat
                        + " has natural integrity "
                        + Integrity.naturalOf(level, probe, level.getBlockState(probe))
                        + " and holdSpentUpToNatural is " + SIConfig.holdSpentUpToNatural()
                        + " - raise the config in run/config or pick a softer block");

        // A four block pillar on it, each charged in properly so it owns a row.
        List<BlockPos> pillar = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            BlockPos local = new BlockPos(3, FLOOR_Y + i, 3);
            helper.setBlock(local, mat);
            BlockPos abs = helper.absolutePos(local);
            helper.assertTrue(Integrity.place(level, reg, abs) != null,
                    "pillar block " + i + " was not structural");
            pillar.add(abs);
        }

        BlockPos spent = pillar.get(1);
        BlockPos above = pillar.get(2);
        BlockPos top = pillar.get(3);

        helper.assertTrue(Integrity.isConnectedToAnchor(level, reg, top, null),
                "the pillar was not grounded before anything was spent - bad fixture");

        // Exactly what SIFall does to a block it declines to destroy.
        reg.set(spent, SIConfig.failAt());

        StructuralIntegrity.LOGGER.info(
                "[SI-TEST] SPENT-SHED spent={} stored={} structural={} conducts={}"
                        + " supportOf(above)={} topGrounded={} blockStillThere={}",
                spent, reg.get(spent), Integrity.isStructural(level, spent, null),
                Integrity.conductsLoad(level, reg, spent, null),
                Integrity.supportOf(level, reg, above, null),
                Integrity.isConnectedToAnchor(level, reg, top, null),
                level.getBlockState(spent).getBlock() == mat);

        helper.assertTrue(level.getBlockState(spent).getBlock() == mat,
                "the spent block was destroyed - a held material must be left standing");
        helper.assertTrue(Integrity.isStructural(level, spent, null),
                "a spent block is still a block: isStructural must stay true");
        helper.assertTrue(!Integrity.conductsLoad(level, reg, spent, null),
                "a spent block must not conduct load");
        helper.assertTrue(!spent.equals(Integrity.supportOf(level, reg, above, null)),
                "the block above chose the spent block as its support");
        helper.assertTrue(!Integrity.isConnectedToAnchor(level, reg, top, null),
                "the pillar above the spent block still reaches ground - load did not shed");
        helper.succeed();
    }

    /**
     * The threshold actually discriminates: soil stands, structure shatters.
     *
     * spentBlockStandsAndShedsLoad covers the holding branch and nothing else, so
     * it would pass just as happily if holdSpentUpToNatural were still the boolean
     * it replaced in 0.7.1 - it only ever looks at one material. What is new is
     * that two blocks failing on the SAME tick can now meet different fates, which
     * is exactly the case SIFall got wrong by construction before: it read the
     * config once, above the worklist loop, so whatever the first block deserved
     * the whole pass got.
     *
     * So this spends one block on each side of the threshold at the same moment
     * and asserts they diverge. Both are set to failAt by hand and handed to
     * {@link SIFall#queueDestroy} exactly as SIEvents.enforce does when a row
     * reaches zero, which keeps the test on the real path rather than a seam.
     *
     * The two sit at opposite corners of the template deliberately. breakShockwave
     * is on in the test config, so the block that does break propagates wear to its
     * neighbours, and a soil block standing next to it would be surviving the
     * shockwave rather than proving anything about the threshold.
     */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void holdThresholdDiscriminatesByMaterial(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        WbiReg reg = WbiReg.of(level);
        int threshold = SIConfig.holdSpentUpToNatural();
        Block softMat = Blocks.DIRT;
        Block hardMat = Blocks.STONE_BRICKS;

        helper.setBlock(new BlockPos(1, FLOOR_Y, 1), softMat);
        helper.setBlock(new BlockPos(5, FLOOR_Y, 5), hardMat);
        helper.setBlock(new BlockPos(1, FLOOR_Y + 1, 1), softMat);
        helper.setBlock(new BlockPos(5, FLOOR_Y + 1, 5), hardMat);

        BlockPos soft = helper.absolutePos(new BlockPos(1, FLOOR_Y + 1, 1));
        BlockPos hard = helper.absolutePos(new BlockPos(5, FLOOR_Y + 1, 5));
        helper.assertTrue(Integrity.place(level, reg, soft) != null, "dirt was not structural");
        helper.assertTrue(Integrity.place(level, reg, hard) != null, "stone bricks were not structural");

        int softNat = Integrity.naturalOf(level, soft, level.getBlockState(soft));
        int hardNat = Integrity.naturalOf(level, hard, level.getBlockState(hard));
        helper.assertTrue(softNat <= threshold && hardNat > threshold,
                "this test needs one material on each side of holdSpentUpToNatural="
                        + threshold + ", but " + softMat + " is natural " + softNat
                        + " and " + hardMat + " is natural " + hardNat
                        + " - adjust the config in run/config or pick other blocks");

        // Spent by hand, then queued the way SIEvents.enforce queues a row that has
        // reached failAt. Same tick, same worklist, opposite outcomes.
        reg.set(soft, SIConfig.failAt());
        reg.set(hard, SIConfig.failAt());
        SIFall.queueDestroy(level, soft);
        SIFall.queueDestroy(level, hard);

        AtomicBoolean logged = new AtomicBoolean(false);
        helper.succeedWhen(() -> {
            boolean softStands = level.getBlockState(soft).getBlock() == softMat;
            boolean hardGone = level.getBlockState(hard).isAir();
            helper.assertTrue(softStands,
                    softMat + " is natural " + softNat + ", at or below holdSpentUpToNatural="
                            + threshold + ", so it must be held spent - it was destroyed");
            helper.assertTrue(hardGone,
                    hardMat + " is natural " + hardNat + ", above holdSpentUpToNatural="
                            + threshold + ", so it must be destroyed - it is still standing");
            if (logged.compareAndSet(false, true)) {
                StructuralIntegrity.LOGGER.info(
                        "[SI-TEST] HOLD-THRESHOLD threshold={} | {} nat={} stands={}"
                                + " | {} nat={} destroyed={}",
                        threshold, softMat, softNat, softStands, hardMat, hardNat, hardGone);
            }
        });
    }

    /**
     * A tagged material breaks even where the threshold would have held it.
     *
     * holdThresholdDiscriminatesByMaterial proves the NUMBER discriminates, by
     * putting one block on each side of it. That says nothing about the veto,
     * because the two materials it uses already disagree on natural integrity -
     * the threshold alone explains its result.
     *
     * So this puts both materials on the SAME side. Dirt and oak leaves are both
     * natural 1 and both never_anchor, and run/config sets holdSpentUpToNatural to
     * 32, so on the number alone both would be held and the test would fail. Only
     * #structuralintegrity:breaks_when_spent can separate them, which is the whole
     * claim. The tag membership is asserted directly as well, so a tag json that
     * silently failed to load reads as a missing tag rather than as a broken veto.
     *
     * The leaves are placed PERSISTENT deliberately. A decaying leaf block vanishes
     * on its own schedule, and a test that cannot tell decay from a break is not
     * testing anything.
     *
     * Opposite corners for the reason spentBlockStandsAndShedsLoad gives:
     * breakShockwave is on in the test config, and dirt standing next to a break
     * would be surviving the shockwave rather than proving it was held.
     */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void taggedMaterialBreaksInsideHoldBand(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        WbiReg reg = WbiReg.of(level);
        int threshold = SIConfig.holdSpentUpToNatural();
        Block heldMat = Blocks.DIRT;
        Block taggedMat = Blocks.OAK_LEAVES;
        BlockState taggedState = taggedMat.defaultBlockState()
                .setValue(LeavesBlock.PERSISTENT, true);

        helper.setBlock(new BlockPos(1, FLOOR_Y, 1), heldMat);
        helper.setBlock(new BlockPos(1, FLOOR_Y + 1, 1), heldMat);
        helper.setBlock(new BlockPos(5, FLOOR_Y, 5), taggedState);
        helper.setBlock(new BlockPos(5, FLOOR_Y + 1, 5), taggedState);

        BlockPos held = helper.absolutePos(new BlockPos(1, FLOOR_Y + 1, 1));
        BlockPos tagged = helper.absolutePos(new BlockPos(5, FLOOR_Y + 1, 5));
        helper.assertTrue(Integrity.place(level, reg, held) != null, "dirt was not structural");
        helper.assertTrue(Integrity.place(level, reg, tagged) != null, "leaves were not structural");

        int heldNat = Integrity.naturalOf(level, held, level.getBlockState(held));
        int taggedNat = Integrity.naturalOf(level, tagged, level.getBlockState(tagged));
        helper.assertTrue(heldNat <= threshold && taggedNat <= threshold,
                "this test needs BOTH materials inside holdSpentUpToNatural=" + threshold
                        + " so that only the tag can separate them, but " + heldMat
                        + " is natural " + heldNat + " and " + taggedMat + " is natural "
                        + taggedNat + " - adjust the config in run/config");
        helper.assertTrue(level.getBlockState(tagged).is(SITags.BREAKS_WHEN_SPENT),
                taggedMat + " is not in #structuralintegrity:breaks_when_spent - the tag"
                        + " json did not load, so this would be testing nothing");
        helper.assertTrue(!level.getBlockState(held).is(SITags.BREAKS_WHEN_SPENT),
                heldMat + " must NOT be in #structuralintegrity:breaks_when_spent");

        reg.set(held, SIConfig.failAt());
        reg.set(tagged, SIConfig.failAt());
        SIFall.queueDestroy(level, held);
        SIFall.queueDestroy(level, tagged);

        AtomicBoolean logged = new AtomicBoolean(false);
        helper.succeedWhen(() -> {
            boolean heldStands = level.getBlockState(held).getBlock() == heldMat;
            boolean taggedGone = level.getBlockState(tagged).isAir();
            helper.assertTrue(heldStands,
                    heldMat + " is natural " + heldNat + ", inside holdSpentUpToNatural="
                            + threshold + " and untagged, so it must be held - it was destroyed");
            helper.assertTrue(taggedGone,
                    taggedMat + " is natural " + taggedNat + ", inside holdSpentUpToNatural="
                            + threshold + ", so only the breaks_when_spent tag can destroy it"
                            + " - it is still standing, so the veto did not fire");
            if (logged.compareAndSet(false, true)) {
                StructuralIntegrity.LOGGER.info(
                        "[SI-TEST] BREAKS-WHEN-SPENT threshold={} | {} nat={} untagged stands={}"
                                + " | {} nat={} tagged destroyed={}",
                        threshold, heldMat, heldNat, heldStands,
                        taggedMat, taggedNat, taggedGone);
            }
        });
    }

    /**
     * The goggle read-out says the same thing the mod acts on.
     *
     * The number in the tooltip and the number that decides whether a block stands
     * are computed in two different classes - {@link Integrity.Result#integrity()}
     * on the server and {@link SIPayloads.Info#integrity()} over the wire - and they
     * have already drifted apart once. The payload went on subtracting the hanging
     * weight after the server stopped, so a perfectly healthy block read tens below
     * zero in the goggles while the thing that actually broke it, stored, sat
     * comfortably above failAt and was never shown at all. Nothing in the mod
     * couples the two, so this pins them.
     *
     * A cantilevered ledge is the shape that exposes it. The innermost block carries
     * a large ungrounded region on one face, so hangMax is big while stored is still
     * healthy - which is precisely where the two formulas disagree most.
     */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void goggleReadoutMatchesBreakCriterion(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        WbiReg reg = WbiReg.of(level);
        Block mat = Blocks.STONE;

        // Ground, and the cliff face the ledge springs from. Both left untouched so
        // they read as WbiReg.ANCHOR and the ledge has something real to hang off.
        for (int x = FLOOR_MIN; x <= FLOOR_MIN + 5; x++) {
            helper.setBlock(new BlockPos(x, FLOOR_Y, LEDGE_Z), mat);
        }
        for (int y = FLOOR_Y + 1; y <= LEDGE_Y + 1; y++) {
            helper.setBlock(new BlockPos(FLOOR_MIN, y, LEDGE_Z), mat);
        }

        BlockPos innerLocal = new BlockPos(FLOOR_MIN + 1, LEDGE_Y, LEDGE_Z);
        for (int i = 0; i <= REACH_STEPS; i++) {
            BlockPos local = new BlockPos(FLOOR_MIN + 1 + i, LEDGE_Y, LEDGE_Z);
            helper.setBlock(local, mat);
            helper.assertTrue(Integrity.place(level, reg, helper.absolutePos(local)) != null,
                    "ledge step " + i + " was not structural");
        }

        BlockPos inner = helper.absolutePos(innerLocal);
        Integrity.Result r = Integrity.compute(level, reg, inner);

        // Built exactly the way SIPayloads.onQuery builds it, so what is asserted is
        // the wire format the goggles actually read rather than a re-derivation of it.
        int flags = SIPayloads.FLAG_STRUCTURAL
                | (r.anchor() ? SIPayloads.FLAG_ANCHOR : 0)
                | (r.grounded() ? SIPayloads.FLAG_GROUNDED : 0);
        SIPayloads.Info info =
                new SIPayloads.Info(inner, r.natural(), r.stored(), r.hangMax(), flags, 0, 0, 0);

        StructuralIntegrity.LOGGER.info(
                "[SI-TEST] GOGGLE {}: natural={} stored={} hangMax={} hangSum={} grounded={}"
                        + " -> server={} goggles={} (pre-0.6.6 formula would have shown {})",
                innerLocal, r.natural(), r.stored(), r.hangMax(), r.hangSum(),
                r.grounded(), r.integrity(), info.integrity(), r.stored() - r.hangMax());

        helper.assertTrue(r.hangMax() > 0,
                "test shape is wrong: the inner ledge block carries no hanging region, so a"
                        + " hang-adjusted read-out would be indistinguishable from a correct one");
        helper.assertTrue(info.integrity() == r.integrity(),
                "goggles disagree with the server: tooltip would show " + info.integrity()
                        + ", the mod acts on " + r.integrity());
        helper.assertTrue(info.integrity() == r.stored(),
                "the read-out is not the break criterion: shows " + info.integrity()
                        + ", blocks break at stored <= " + SIConfig.failAt());
        helper.assertTrue(info.integrity() > SIConfig.failAt(),
                "a standing block read as already broken: " + info.integrity()
                        + " <= failAt " + SIConfig.failAt());
        helper.succeed();
    }

    /**
     * Diagnostic only - nothing in the running mod calls these. The sweep records
     * what a size-aware derivation WOULD say next to what the shipped
     * hardness-only derivation actually says, so the two can be compared across the
     * whole registry instead of argued about one block at a time.
     */
    private static float safeHardness(ServerLevel level, BlockPos pos, BlockState state) {
        try {
            return state.getDestroySpeed(level, pos);
        } catch (Exception e) {
            return -2.0f;
        }
    }

    /** Occupied fraction of the block cube, summed over the collision shape's boxes. */
    private static double shapeVolume(ServerLevel level, BlockPos pos, BlockState state) {
        try {
            var shape = state.getCollisionShape(level, pos);
            if (shape.isEmpty()) {
                return 0.0;
            }
            double[] total = {0.0};
            shape.forAllBoxes((x1, y1, z1, x2, y2, z2) ->
                    total[0] += (x2 - x1) * (y2 - y1) * (z2 - z1));
            return total[0];
        } catch (Exception e) {
            return -1.0;
        }
    }

    /**
     * The shipped formula with hardness multiplied by occupied volume before the
     * square root, so a full cube derives exactly what it derives today and a thin
     * shape falls off with how little of the cube it actually fills.
     */
    private static int volumeDerived(float hardness, double volume) {
        if (hardness < -1.5f || volume < 0) {
            return -1;
        }
        if (hardness < 0) {
            return 1024;
        }
        if (hardness == 0 || volume == 0) {
            return SIConfig.defaultFragileIntegrity();
        }
        double effective = hardness * volume;
        int derived = (int) Math.round(SIConfig.defaultIntegrity() * Math.sqrt(effective / 1.5));
        return Math.min(1024, Math.max(2, derived));
    }

    // =====================================================================
    // 0.7.3 - the jump shock
    // =====================================================================

    /**
     * A landing that the structure survives must leave it EXACTLY as it was.
     *
     * The order the test runs in. First a cantilevered ledge is built off a cliff,
     * because a ledge is the shape that pays the sideways doubling and so the shape
     * where a flat +1 restore would silently under-restore. Then every wbireg row
     * this test built is written down. Then the landing charge is run - the same
     * {@link Integrity#chain} call {@link SIJumpShock} makes - asking for the list
     * of rows it rewrote. Then that list is checked for at least one row that moved
     * by more than the single point asked for; if none did, the ledge is not
     * exercising the doubling and the rest of the test proves nothing, so that is an
     * assertion rather than an observation. Then the recorded amounts are handed
     * back, and every row must read exactly what it read before the landing.
     */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void jumpShockRestoresExactly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        WbiReg reg = WbiReg.of(level);
        Block mat = Blocks.STONE;

        // Ground under the cliff, left untouched so it reads as anchor.
        for (int x = FLOOR_MIN; x <= FLOOR_MIN + 5; x++) {
            helper.setBlock(new BlockPos(x, FLOOR_Y, LEDGE_Z), mat);
        }
        // The cliff face the ledge springs from.
        for (int y = FLOOR_Y + 1; y <= LEDGE_Y; y++) {
            BlockPos local = new BlockPos(FLOOR_MIN, y, LEDGE_Z);
            helper.setBlock(local, mat);
            Integrity.place(level, reg, helper.absolutePos(local));
        }
        // The ledge itself: every block out charges the innermost twice.
        List<BlockPos> ledge = new ArrayList<>();
        for (int i = 0; i <= REACH_STEPS; i++) {
            BlockPos local = new BlockPos(FLOOR_MIN + 1 + i, LEDGE_Y, LEDGE_Z);
            helper.setBlock(local, mat);
            BlockPos abs = helper.absolutePos(local);
            Integrity.Placed placed = Integrity.place(level, reg, abs);
            helper.assertTrue(placed != null, "ledge step " + i + " was not structural");
            ledge.add(abs);
        }

        // The tip of the ledge is what a player would land on.
        BlockPos landed = ledge.get(ledge.size() - 1);
        helper.assertTrue(!Integrity.isAnchor(level, reg, landed),
                "the landing block is ground - this test would measure nothing");

        Map<BlockPos, Integer> before = new TreeMap<>(
                java.util.Comparator.comparingLong(BlockPos::asLong));
        for (BlockPos p : ledge) {
            before.put(p, reg.get(p));
        }
        for (int y = FLOOR_Y + 1; y <= LEDGE_Y; y++) {
            BlockPos abs = helper.absolutePos(new BlockPos(FLOOR_MIN, y, LEDGE_Z));
            before.put(abs, reg.get(abs));
        }

        Integrity.Chained shock = Integrity.chain(level, reg, landed, null, null, -1, true);
        StructuralIntegrity.LOGGER.info("[SI-TEST] JUMP shock -1 touched {} row(s), chain {}: {}",
                shock.touched().size(), shock.count(), shock.trace());
        helper.assertTrue(!shock.touched().isEmpty(),
                "the landing charge changed no row at all");

        int worst = 0;
        for (Integrity.Touched t : shock.touched()) {
            StructuralIntegrity.LOGGER.info("[SI-TEST]   touched {},{},{} by {}",
                    t.pos().getX(), t.pos().getY(), t.pos().getZ(), t.applied());
            worst = Math.min(worst, t.applied());
        }
        helper.assertTrue(worst <= -2,
                "no row on this ledge was charged more than the -1 asked for (worst was " + worst
                        + ") - the sideways doubling is not being exercised, so this test cannot "
                        + "show that a flat restore would be wrong");

        Integrity.Restored restored = Integrity.restoreTouched(level, reg, shock.touched());
        StructuralIntegrity.LOGGER.info("[SI-TEST] JUMP recover gave back {} row(s), skipped {}: {}",
                restored.count(), restored.skipped(), restored.trace());

        for (Map.Entry<BlockPos, Integer> e : before.entrySet()) {
            int now = reg.get(e.getKey());
            helper.assertTrue(now == e.getValue(),
                    "row " + e.getKey().getX() + "," + e.getKey().getY() + "," + e.getKey().getZ()
                            + " was " + e.getValue() + " before the landing and is " + now
                            + " after the recovery - a survived landing must cost nothing");
        }
        StructuralIntegrity.LOGGER.info(
                "[SI-TEST] JUMP SHOCK no-op confirmed across {} row(s)", before.size());
        helper.succeed();
    }

    /**
     * A landing on a structure already one point from failing must spend it.
     *
     * The support under the landing block is driven down to one above {@code failAt}
     * by hand rather than by building something enormous, so the test states the
     * precondition it is testing instead of hoping some shape happens to produce it.
     * Then a single point of landing load is enough to run it out, and the chain must
     * report it failed - that report is the only thing between a weak floor and a
     * player walking across it forever.
     */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void jumpShockSpendsAnAlreadyWeakSupport(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        WbiReg reg = WbiReg.of(level);
        Block mat = Blocks.STONE;

        for (int x = FLOOR_MIN; x <= FLOOR_MIN + 3; x++) {
            helper.setBlock(new BlockPos(x, FLOOR_Y, PILLAR_Z), mat);
        }
        BlockPos supportLocal = new BlockPos(PILLAR_X, FLOOR_Y + 1, PILLAR_Z);
        BlockPos landedLocal = new BlockPos(PILLAR_X, FLOOR_Y + 2, PILLAR_Z);
        helper.setBlock(supportLocal, mat);
        helper.setBlock(landedLocal, mat);
        BlockPos support = helper.absolutePos(supportLocal);
        BlockPos landed = helper.absolutePos(landedLocal);
        Integrity.place(level, reg, support);
        Integrity.place(level, reg, landed);

        int failAt = SIConfig.failAt();
        reg.set(landed, failAt + 1);
        reg.set(support, failAt + 1);
        StructuralIntegrity.LOGGER.info(
                "[SI-TEST] JUMP weak-floor precondition: landed={} support={} failAt={}",
                reg.get(landed), reg.get(support), failAt);

        Integrity.Chained shock = Integrity.chain(level, reg, landed, null, null, -1, true);
        StructuralIntegrity.LOGGER.info(
                "[SI-TEST] JUMP shock on weak floor: {} failed, touched {}: {}",
                shock.failed().size(), shock.touched().size(), shock.trace());
        helper.assertTrue(!shock.failed().isEmpty(),
                "a landing on a support one point from failAt did not spend it");

        Integrity.Restored restored = Integrity.restoreTouched(level, reg, shock.touched());
        StructuralIntegrity.LOGGER.info("[SI-TEST] JUMP recover after a spend: {} back, {} skipped",
                restored.count(), restored.skipped());
        helper.succeed();
    }

    // ---- 0.7.4 -------------------------------------------------------------

    /**
     * The wrench repair, and the one line that makes it a repair rather than a
     * second placement.
     *
     * The damage being repaired is real and permanent without this. A stack of four
     * blocks is placed from the ground up, and each placement charges everything
     * below it, so the bottom block ends three points down and the one above it two.
     * Then the top block detaches the way {@link SIFall} detaches one - block gone,
     * row cleared - and every point it charged into the stack stays charged. The
     * stack is now carrying a block that is not there, and nothing in the mod ever
     * gives that back.
     *
     * The repair walks up, because it can only walk up: a block comes back no further
     * than its best neighbour currently stands, so the bottom block - which touches
     * ground and therefore re-derives at full natural - has to be mended before the
     * one above it has anything sound to inherit from. That order is the mechanic,
     * not an implementation detail, and it is what stops one click from healing a
     * tower.
     *
     * The assertion that matters is the last one. {@link Integrity#place} ends with
     * a {@code chain(-1)} that charges the support for agreeing to carry a new block;
     * {@link Integrity#recompute} deliberately omits it, and that omission is the
     * whole difference between the two methods. If it were ever put back - by a
     * refactor that "simplified" recompute into a call to place, say - every other
     * test here would still pass while repairing a wall quietly ground its own
     * foundation away, worst of all for a player patching from the top down.
     */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void wrenchRepairRestoresWithoutChargingTheSupport(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        WbiReg reg = WbiReg.of(level);
        Block mat = Blocks.STONE;

        // Ground for the stack to stand on. Untracked, so it reads as ANCHOR.
        helper.setBlock(new BlockPos(PILLAR_X, FLOOR_Y, PILLAR_Z), mat);

        BlockPos[] stack = new BlockPos[4];
        for (int i = 0; i < stack.length; i++) {
            BlockPos local = new BlockPos(PILLAR_X, FLOOR_Y + 1 + i, PILLAR_Z);
            helper.setBlock(local, mat);
            stack[i] = helper.absolutePos(local);
            Integrity.place(level, reg, stack[i]);
        }
        int natural = Integrity.naturalOf(level, stack[0], level.getBlockState(stack[0]));
        StructuralIntegrity.LOGGER.info(
                "[SI-TEST] WRENCH built: natural={} rows={},{},{},{}",
                natural, reg.get(stack[0]), reg.get(stack[1]), reg.get(stack[2]), reg.get(stack[3]));
        helper.assertTrue(reg.get(stack[0]) < natural,
                "the base was never charged by the blocks above it - nothing to repair");

        // The top block shears off, exactly as SIFall does it: gone from the world,
        // row cleared. Its charge stays behind in everything underneath.
        helper.setBlock(new BlockPos(PILLAR_X, FLOOR_Y + 4, PILLAR_Z), Blocks.AIR);
        reg.clear(stack[3]);
        int baseAfterLoss = reg.get(stack[0]);
        int secondAfterLoss = reg.get(stack[1]);
        StructuralIntegrity.LOGGER.info(
                "[SI-TEST] WRENCH after the top block left: base={} second={} third={} (natural {})",
                baseAfterLoss, secondAfterLoss, reg.get(stack[2]), natural);

        // Repairing the SECOND block first must do nothing: its best neighbour is the
        // still-damaged base, and a block cannot come back further than that.
        Integrity.Recomputed early = Integrity.recompute(level, reg, stack[1]);
        StructuralIntegrity.LOGGER.info("[SI-TEST] WRENCH top-down attempt: {}", early);
        helper.assertTrue(early != null && !early.changed(),
                "repairing above a damaged block healed it anyway - the inheritance rule is gone");

        // Bottom first. The base touches ground, so it re-derives at full natural.
        Integrity.Recomputed atBase = Integrity.recompute(level, reg, stack[0]);
        StructuralIntegrity.LOGGER.info("[SI-TEST] WRENCH base repair: {}", atBase);
        helper.assertTrue(atBase != null && atBase.founded(),
                "the base does not see the ground it is standing on");
        helper.assertValueEqual(reg.get(stack[0]), natural, "base integrity after repair");

        // Now the second block has something sound underneath and can follow.
        int baseBeforeSecond = reg.get(stack[0]);
        Integrity.Recomputed atSecond = Integrity.recompute(level, reg, stack[1]);
        StructuralIntegrity.LOGGER.info("[SI-TEST] WRENCH second repair: {}", atSecond);
        helper.assertTrue(atSecond != null && atSecond.changed(),
                "the second block did not recover once its support was sound");
        helper.assertValueEqual(reg.get(stack[1]), natural, "second block integrity after repair");

        // The assertion this test exists for.
        StructuralIntegrity.LOGGER.info(
                "[SI-TEST] WRENCH support check: base was {} before repairing the block above it, now {}",
                baseBeforeSecond, reg.get(stack[0]));
        helper.assertValueEqual(reg.get(stack[0]), baseBeforeSecond,
                "repairing a block charged the block underneath it - recompute is calling chain()");
        helper.succeed();
    }

    /**
     * A player's impact scales with how fast they were actually travelling.
     *
     * Two things, on one real sable body. First that the impulse is linear in the
     * player's world motion - twice the arrival speed is twice the impulse, and no
     * motion is no impulse - which is the claim {@code playerImpactMass} being a flat
     * number makes easy to misread as "every landing hits the same". It is the mass
     * that is constant, correctly, because it is the player's weight; the speed is
     * whatever they arrived at.
     *
     * Second that a strike actually reaches the physics engine, asked of the engine
     * through {@link SIForce#linearVelocityOf} rather than read off
     * {@code latestLinearVelocity} - that mirror field reads a flat zero for these
     * bodies for their whole life, which is what once made a working push report
     * itself as a failure.
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void playerImpactScalesWithArrivalSpeed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        WbiReg reg = WbiReg.of(level);

        List<BlockPos> clusterWorld = new ArrayList<>();
        for (BlockPos local : CLUSTER_LOCAL) {
            helper.setBlock(local, Blocks.STONE);
            BlockPos world = helper.absolutePos(local);
            clusterWorld.add(world);
            reg.set(world, Integrity.naturalOf(level, world, level.getBlockState(world)));
        }
        SIFall.queueFall(level, clusterWorld.get(0));
        BlockPos queuedAt = clusterWorld.get(0);

        AtomicReference<ServerSubLevel> own = new AtomicReference<>();
        AtomicBoolean scales = new AtomicBoolean(false);
        AtomicBoolean struck = new AtomicBoolean(false);
        AtomicBoolean moved = new AtomicBoolean(false);
        double[] before = {0.0};

        helper.onEachTick(() -> {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) {
                return;
            }
            if (own.get() == null) {
                // Matched on the anchor this test queued, never on a spatial margin:
                // gametests are tiled a few blocks apart in one shared level and any
                // generous margin reaches into a neighbour's structure.
                for (ServerSubLevel sub : container.getAllSubLevels()) {
                    if (queuedAt.equals(SIFall.assembledAt(sub))) {
                        own.set(sub);
                        break;
                    }
                }
            }
            ServerSubLevel sub = own.get();
            if (sub == null) {
                return;
            }

            if (!scales.get()) {
                Vec3 slow = new Vec3(0.0, -0.42, 0.0);
                Vec3 fast = slow.scale(2.0);
                double one = SIPlayerImpact.impulseOf(sub, slow).length();
                double two = SIPlayerImpact.impulseOf(sub, fast).length();
                double none = SIPlayerImpact.impulseOf(sub, Vec3.ZERO).length();
                StructuralIntegrity.LOGGER.info(
                        "[SI-TEST] IMPACT scaling: |slow|={} |fast|={} ratio={} |still|={} mass={}",
                        one, two, one > 0 ? two / one : Double.NaN, none,
                        SIConfig.playerImpactMass());
                helper.assertTrue(one > 0.0, "a landing at 0.42 b/t produced no impulse at all");
                helper.assertTrue(Math.abs(two - 2.0 * one) < 1.0e-6,
                        "impulse is not linear in arrival speed: " + one + " then " + two);
                helper.assertTrue(none < 1.0e-9,
                        "a player with no motion still delivered an impulse of " + none);
                scales.set(true);
            }

            if (!struck.get()) {
                before[0] = SIForce.linearVelocityOf(sub).y;
                // A hard landing straight down, on the body's own centre so the whole
                // impulse shows up as linear velocity rather than spin.
                SIPlayerImpact.strike(sub, Vec3.ZERO, new Vec3(0.0, -1.5, 0.0),
                        "TEST-LAND", null, 1.5);
                struck.set(true);
                return;
            }
            double after = SIForce.linearVelocityOf(sub).y;
            StructuralIntegrity.LOGGER.info("[SI-TEST] IMPACT vy {} -> {}", before[0], after);
            if (after < before[0] - 0.05) {
                moved.set(true);
            }
        });

        helper.runAtTickTime(40, () -> helper.assertTrue(scales.get(),
                "no sub-level was assembled - fall/assemble did not fire"));

        helper.succeedWhen(() -> helper.assertTrue(moved.get(),
                "a player landing on the body did not change its velocity: struck=" + struck.get()));
    }

    // ---- 0.7.5 -------------------------------------------------------------

    /**
     * A stronger material braces the weaker one that leans on it.
     *
     * The structure is a plank wall standing on a stone block standing on ground,
     * so the charge from the top plank walks plank -> plank -> stone and crosses
     * into a sturdier material once, in the middle of the walk rather than on its
     * first step. That placement matters: on the first step the block handing the
     * load over is the origin, which the chain never charges, so there is nothing
     * to hand back and the old absorb-and-stop behaviour stands there instead.
     *
     * What the rule does at the crossing is move one point, not add one. The plank
     * takes its -1 and is then handed it straight back, ending the pass unchanged;
     * the stone takes -2. So the pair loses exactly the two points it would have
     * lost anyway, and the second assertion here is the one that says so. A future
     * refactor that read the rule as "the strong block takes an extra -1" would
     * satisfy the first assertion and fail this one, and the mod would quietly grow
     * a new source of damage that scaled with how many material changes a builder
     * used.
     *
     * The third assertion is the reason the rule is written symmetrically at all.
     * {@link Integrity#chain} is one function serving placement (-1), breaking (+1)
     * and explosion relax, so a brace that fired only on the loss direction would
     * mean a place followed by a break did not cancel: every cycle would leave the
     * stone one point down and the plank one point up, and a player who built and
     * unbuilt in the same spot would grind their own foundation away without ever
     * seeing why.
     */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void strongerMaterialBracesTheWeakerOneAboveIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        WbiReg reg = WbiReg.of(level);

        // Ground, then one stone course, then two of planks. Untracked ground reads
        // as ANCHOR, so the chain has somewhere to end.
        helper.setBlock(new BlockPos(PILLAR_X, FLOOR_Y, PILLAR_Z), Blocks.STONE);

        BlockPos stone = helper.absolutePos(new BlockPos(PILLAR_X, FLOOR_Y + 1, PILLAR_Z));
        BlockPos lower = helper.absolutePos(new BlockPos(PILLAR_X, FLOOR_Y + 2, PILLAR_Z));
        BlockPos upper = helper.absolutePos(new BlockPos(PILLAR_X, FLOOR_Y + 3, PILLAR_Z));
        helper.setBlock(new BlockPos(PILLAR_X, FLOOR_Y + 1, PILLAR_Z), Blocks.STONE);
        helper.setBlock(new BlockPos(PILLAR_X, FLOOR_Y + 2, PILLAR_Z), Blocks.OAK_PLANKS);
        helper.setBlock(new BlockPos(PILLAR_X, FLOOR_Y + 3, PILLAR_Z), Blocks.OAK_PLANKS);
        Integrity.place(level, reg, stone);
        Integrity.place(level, reg, lower);
        Integrity.place(level, reg, upper);

        int stoneNatural = Integrity.naturalOf(level, stone, level.getBlockState(stone));
        int plankNatural = Integrity.naturalOf(level, lower, level.getBlockState(lower));
        helper.assertTrue(stoneNatural > plankNatural,
                "the test needs stone to outrank planks - natural_integrity.json changed");

        int stoneBefore = reg.get(stone);
        int lowerBefore = reg.get(lower);
        StructuralIntegrity.LOGGER.info(
                "[SI-TEST] BRACE built: plank natural={} stone natural={} rows plank={} stone={}",
                plankNatural, stoneNatural, lowerBefore, stoneBefore);

        // One charge, walking down from the lower plank. The origin is the plank
        // above it, so the first step is plank -> plank (equal calibre, nothing
        // special) and the crossing into stone happens on the second.
        Integrity.Chained charged = Integrity.chain(level, reg, lower, upper, null, -1);
        int stoneCharged = reg.get(stone);
        int lowerCharged = reg.get(lower);
        StructuralIntegrity.LOGGER.info(
                "[SI-TEST] BRACE charged: plank {}->{} stone {}->{} trace {}",
                lowerBefore, lowerCharged, stoneBefore, stoneCharged, charged.trace());

        helper.assertValueEqual(lowerCharged, lowerBefore,
                "the plank handing the load into stone should end the pass unchanged");
        helper.assertValueEqual(stoneCharged, stoneBefore - 2,
                "the stone bracing the plank should take two points, not one");
        helper.assertValueEqual((lowerCharged - lowerBefore) + (stoneCharged - stoneBefore), -2,
                "the brace moved a point, it did not create one - the pair must still total -2");

        // The same walk with the sign flipped, which is exactly what a break of the
        // block above would run. Both rows must land back where they started.
        Integrity.Chained relaxed = Integrity.chain(level, reg, lower, upper, null, 1);
        StructuralIntegrity.LOGGER.info(
                "[SI-TEST] BRACE relaxed: plank {}->{} stone {}->{} trace {}",
                lowerCharged, reg.get(lower), stoneCharged, reg.get(stone), relaxed.trace());

        helper.assertValueEqual(reg.get(stone), stoneBefore,
                "a relax did not give the stone back what the charge took - "
                        + "place/break no longer cancels and every cycle grinds the foundation");
        helper.assertValueEqual(reg.get(lower), lowerBefore,
                "a relax did not leave the plank where it started");

        helper.succeed();
    }

    /**
     * A material rise concentrates a point onto the foundation; it does not stop
     * there. Everything below the crossing still takes its ordinary -1.
     *
     * Up to 0.7.7 a rise into sturdier material could end the walk outright, traced
     * {@code !BOUNDARY}. It was unreachable on a default install - a braced crossing
     * was exempt and bracing was on by default - but where it was reachable it did
     * two contradictory things at once: it withheld the brace AND refused to let the
     * load travel down to the ground the foundation was standing on, so a foundation
     * behaved like a wall the load could not get past. 0.7.8 removed the stopper and
     * made the brace unconditional, and this test is what says so.
     *
     * The structure is three tracked courses on untracked ground: cobble, then stone,
     * then cobble, with a fourth cobble on top to be the origin. Walking down from
     * the upper cobble the chain crosses UP into stone on its second step and back
     * DOWN into cobble on its third, so one pass exercises both directions of a
     * material change and the ground below is the same calibre as the block resting
     * on it, which keeps a second brace from firing against the anchor and muddying
     * the arithmetic.
     *
     * The third assertion is the one that carries the change. The first two would
     * also pass on 0.7.7 with bracing left on - the brace itself is unaltered - so a
     * test that stopped at the crossing would report success against both versions
     * and prove nothing. The bottom cobble is BELOW the crossing, and it only moves
     * at all if the walk continued past the stone.
     */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void aMaterialRiseDoesNotInterruptTheChain(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        WbiReg reg = WbiReg.of(level);

        // Untracked cobble ground reads as ANCHOR, and matching the course above it
        // means that course has nothing sturdier to brace into - the only crossings
        // in this walk are the two being measured.
        helper.setBlock(new BlockPos(PILLAR_X, FLOOR_Y, PILLAR_Z), Blocks.COBBLESTONE);

        BlockPos bottom = helper.absolutePos(new BlockPos(PILLAR_X, FLOOR_Y + 1, PILLAR_Z));
        BlockPos strong = helper.absolutePos(new BlockPos(PILLAR_X, FLOOR_Y + 2, PILLAR_Z));
        BlockPos weak = helper.absolutePos(new BlockPos(PILLAR_X, FLOOR_Y + 3, PILLAR_Z));
        BlockPos origin = helper.absolutePos(new BlockPos(PILLAR_X, FLOOR_Y + 4, PILLAR_Z));
        helper.setBlock(new BlockPos(PILLAR_X, FLOOR_Y + 1, PILLAR_Z), Blocks.COBBLESTONE);
        helper.setBlock(new BlockPos(PILLAR_X, FLOOR_Y + 2, PILLAR_Z), Blocks.STONE);
        helper.setBlock(new BlockPos(PILLAR_X, FLOOR_Y + 3, PILLAR_Z), Blocks.COBBLESTONE);
        helper.setBlock(new BlockPos(PILLAR_X, FLOOR_Y + 4, PILLAR_Z), Blocks.COBBLESTONE);
        Integrity.place(level, reg, bottom);
        Integrity.place(level, reg, strong);
        Integrity.place(level, reg, weak);
        Integrity.place(level, reg, origin);

        int cobbleNatural = Integrity.naturalOf(level, weak, level.getBlockState(weak));
        int stoneNatural = Integrity.naturalOf(level, strong, level.getBlockState(strong));
        helper.assertTrue(stoneNatural > cobbleNatural,
                "the test needs stone to outrank cobblestone - natural_integrity.json changed");

        int weakBefore = reg.get(weak);
        int strongBefore = reg.get(strong);
        int bottomBefore = reg.get(bottom);
        StructuralIntegrity.LOGGER.info(
                "[SI-TEST] RISE built: cobble natural={} stone natural={} rows weak={} strong={} bottom={}",
                cobbleNatural, stoneNatural, weakBefore, strongBefore, bottomBefore);

        Integrity.Chained charged = Integrity.chain(level, reg, weak, origin, null, -1);
        int weakCharged = reg.get(weak);
        int strongCharged = reg.get(strong);
        int bottomCharged = reg.get(bottom);
        StructuralIntegrity.LOGGER.info(
                "[SI-TEST] RISE charged: weak {}->{} strong {}->{} bottom {}->{} count={} trace {}",
                weakBefore, weakCharged, strongBefore, strongCharged,
                bottomBefore, bottomCharged, charged.count(), charged.trace());

        helper.assertValueEqual(weakCharged, weakBefore,
                "the cobble handing the load into stone should end the pass unchanged");
        helper.assertValueEqual(strongCharged, strongBefore - 2,
                "the stone braced into should take two points, not one");
        helper.assertValueEqual(bottomCharged, bottomBefore - 1,
                "the block BELOW the crossing took nothing - the rise stopped the walk, "
                        + "which is exactly what 0.7.8 removed");
        helper.assertValueEqual(
                (weakCharged - weakBefore) + (strongCharged - strongBefore)
                        + (bottomCharged - bottomBefore), -3,
                "three blocks were walked, so three points should have been spent - "
                        + "the brace moves a point, it does not create or destroy one");
        helper.assertValueEqual(charged.count(), 3,
                "the walk should have charged all three tracked courses");

        // Both crossings have to be legible in the trace. Diagnosing the 0.7.7
        // attachment defect from a live log was only possible because every material
        // change tagged itself, and a silent crossing costs that outright.
        helper.assertTrue(charged.trace().contains("!RISE"),
                "the crossing into stone left no !RISE tag: " + charged.trace());
        helper.assertTrue(charged.trace().contains("!FALL"),
                "the crossing back into cobble left no !FALL tag: " + charged.trace());

        // The same walk with the sign flipped - what a break of the origin runs.
        // All three rows must land back where they started, or a build/unbuild cycle
        // grinds the structure down and the fall is silently charging something.
        Integrity.Chained relaxed = Integrity.chain(level, reg, weak, origin, null, 1);
        StructuralIntegrity.LOGGER.info(
                "[SI-TEST] RISE relaxed: weak {}->{} strong {}->{} bottom {}->{} trace {}",
                weakCharged, reg.get(weak), strongCharged, reg.get(strong),
                bottomCharged, reg.get(bottom), relaxed.trace());

        helper.assertValueEqual(reg.get(weak), weakBefore,
                "a relax did not leave the cobble above the crossing where it started");
        helper.assertValueEqual(reg.get(strong), strongBefore,
                "a relax did not give the stone back what the charge took");
        helper.assertValueEqual(reg.get(bottom), bottomBefore,
                "a relax did not give the block below the crossing back what the charge took");

        helper.succeed();
    }

    // ---- 0.7.6 -------------------------------------------------------------

    /**
     * The goggles report the footing, and the footing is found by the real walk.
     *
     * The structure is the user's own example: a stone brick foundation carrying a
     * plank wall. Looking at a plank, the Integrity row says 20 / 20 and is telling
     * the truth about that plank, which is exactly the problem - the support chain
     * has no falloff, so every plank above pays into the same stone course, and the
     * stone is the block that fails while the planks stay pristine. The Structure
     * row is that stone: its row, against its own natural rating.
     *
     * The second half is a drift alarm. {@link Integrity#probe} is
     * {@link Integrity#chain} itself run dry rather than a shorter routine that
     * follows supports down, and the whole justification for that is that a
     * read-out built from the physics cannot contradict the physics. That only
     * holds while the two walks really do cover the same blocks, so this runs the
     * probe and then runs a real charge from the same block and compares the step
     * counts. If someone later changes where a load goes, or dry mode stops early
     * because it has nothing to write, the number on a player's HUD would go on
     * looking authoritative while pointing at the wrong block - and this fails
     * instead.
     */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void goggleStructureRowNamesTheFooting(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        WbiReg reg = WbiReg.of(level);

        // Ground, then one stone brick course, then three of planks. Untracked
        // ground reads as ANCHOR, so the walk has somewhere to end.
        helper.setBlock(new BlockPos(PILLAR_X, FLOOR_Y, PILLAR_Z), Blocks.STONE);

        BlockPos footing = helper.absolutePos(new BlockPos(PILLAR_X, FLOOR_Y + 1, PILLAR_Z));
        helper.setBlock(new BlockPos(PILLAR_X, FLOOR_Y + 1, PILLAR_Z), Blocks.STONE_BRICKS);
        Integrity.place(level, reg, footing);

        BlockPos top = null;
        for (int i = 2; i <= 4; i++) {
            helper.setBlock(new BlockPos(PILLAR_X, FLOOR_Y + i, PILLAR_Z), Blocks.OAK_PLANKS);
            top = helper.absolutePos(new BlockPos(PILLAR_X, FLOOR_Y + i, PILLAR_Z));
            Integrity.place(level, reg, top);
        }

        int footingNatural = Integrity.naturalOf(level, footing, level.getBlockState(footing));
        int plankNatural = Integrity.naturalOf(level, top, level.getBlockState(top));
        helper.assertTrue(footingNatural > plankNatural,
                "the test needs stone bricks to outrank planks - natural_integrity.json changed");

        Integrity.Probe probe = Integrity.probe(level, reg, top);
        StructuralIntegrity.LOGGER.info(
                "[SI-TEST] PROBE from {} depth={} last={} {}/{} capped={} trace {}",
                top, probe.depth(), probe.last(), probe.stored(), probe.natural(),
                probe.capped(), probe.trace());

        helper.assertTrue(probe.found(), "the probe found no chain under a wall standing on stone");
        helper.assertFalse(probe.capped(), "a four-block chain should not hit maxLoadPath");
        helper.assertTrue(probe.last().equals(footing),
                "the Structure row must name the stone brick footing, not " + probe.last());
        helper.assertValueEqual(probe.natural(), footingNatural,
                "the Structure row's denominator is the FOOTING's rating, not the plank's");
        helper.assertValueEqual(probe.stored(), reg.get(footing),
                "the Structure row's value is the footing's row as it stands");
        helper.assertValueEqual(probe.path().get(0), top,
                "the walk starts at the block being looked at, not at its support");

        // A dry walk must not change anything - that is the entire contract of the
        // read-out, and a stray write here would damage a building by looking at it.
        int footingBefore = reg.get(footing);
        int topBefore = reg.get(top);
        Integrity.probe(level, reg, top);
        helper.assertValueEqual(reg.get(footing), footingBefore,
                "the probe wrote to the footing - reading a block must never charge it");
        helper.assertValueEqual(reg.get(top), topBefore,
                "the probe wrote to the block it started from");

        // The drift alarm. Same start, same direction, one dry and one real.
        Integrity.Chained charged = Integrity.chain(level, reg, top, null, null, -1);
        StructuralIntegrity.LOGGER.info(
                "[SI-TEST] PROBE drift: dry depth={} real count={} trace {}",
                probe.depth(), charged.count(), charged.trace());
        helper.assertValueEqual(probe.depth(), charged.count(),
                "the dry walk and the real charge covered different blocks - the goggle "
                        + "read-out has drifted from the physics it claims to report");

        // And put it back, so the test leaves the structure as it found it.
        Integrity.chain(level, reg, top, null, null, 1);

        helper.succeed();
    }

    // ---- 0.7.11: deepslate outranks stone, cut or uncut ----------------------
    /**
     * Deepslate is the sounder rock and the datamap has always said so - 36 against
     * stone's 32. Cut either one into bricks, though, and the order flipped. Cut
     * them into stairs and the distinction vanished outright.
     *
     * Neither was a decision. Both were accidents of HOW a value gets found. Stone
     * bricks carry a hand-written row at 40, while deepslate bricks carried no row
     * at all, so naturalOf fell through to the hardness curve - defaultIntegrity *
     * sqrt(hardness / 1.5) - which at the live default of 24 lands on 37. That is
     * above the 24 stone bricks would have derived and below the 40 they were
     * lifted to, so the hand-written uplift on one side quietly overtook the real
     * hardness advantage on the other. The stairs were worse still: #minecraft:
     * stairs is a single flat 20 for every stair in the game, so deepslate brick
     * stairs and stone brick stairs were literally the same block.
     *
     * 0.7.11 gives deepslate the two rows it was missing. The ratio they carry is
     * NOT the rock's own 36/32. Cut into the brick family, deepslate is worth a
     * flat fifth more than stone, 6/5, and that is a choice rather than a
     * derivation. The reason to make it is that a fifth lands WHOLE on both rows
     * where the rock ratio does not: 40 * 6/5 = 48 and 20 * 6/5 = 24, both exact,
     * against 45 and a 22.5 that had to be rounded up.
     *
     * So this asserts the ratio, never the values. One fraction, applied to each
     * stone counterpart, with the division required to come out even - which is
     * the justification for 6/5 made executable instead of merely claimed in a
     * comment. Written that way the test has an opinion about the future: move
     * stone bricks or stone brick stairs and it names the deepslate rows that have
     * to move with them, rather than mismatching a constant nobody can trace back
     * to a reason.
     *
     * One invariant sits underneath the choice. The cut ratio must never be MEANER
     * than the rock ratio, because cutting deepslate into a shape should not cost
     * it the advantage it already has as raw stone. 6/5 clears 36/32 today. The
     * day someone re-tunes the raw rock past a fifth, that assert is what says the
     * brick rows have quietly become the weaker link.
     *
     * Nothing is placed. naturalOf answers from the block state, and asking it
     * directly is the whole point: this is a test about the DATA, so a fixture that
     * built a structure first would be able to fail for reasons that have nothing
     * to do with which row won.
     */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void deepslateOutranksStoneInBricksAndStairs(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos probe = helper.absolutePos(BlockPos.ZERO);

        int stone = Integrity.naturalOf(level, probe, Blocks.STONE.defaultBlockState());
        int deepslate = Integrity.naturalOf(level, probe, Blocks.DEEPSLATE.defaultBlockState());
        int stoneBricks = Integrity.naturalOf(level, probe, Blocks.STONE_BRICKS.defaultBlockState());
        int deepBricks = Integrity.naturalOf(level, probe, Blocks.DEEPSLATE_BRICKS.defaultBlockState());
        int stoneStairs = Integrity.naturalOf(level, probe, Blocks.STONE_BRICK_STAIRS.defaultBlockState());
        int deepStairs = Integrity.naturalOf(level, probe, Blocks.DEEPSLATE_BRICK_STAIRS.defaultBlockState());

        // One fraction, both rows: deepslate cut into the brick family is worth a
        // fifth more than stone. Not the rock's own 36/32 - that cannot divide 20
        // evenly, and landing whole on BOTH rows is the entire reason 6/5 was
        // picked over it. These two checks are that reason made executable.
        final int CUT_NUM = 6, CUT_DEN = 5;
        helper.assertTrue(stoneBricks * CUT_NUM % CUT_DEN == 0,
                "the cut ratio " + CUT_NUM + "/" + CUT_DEN + " has to divide stone"
                        + " bricks evenly, and " + stoneBricks + " * " + CUT_NUM
                        + " / " + CUT_DEN + " does not come out whole - either pick"
                        + " a fraction that does, or accept a rounded deepslate row"
                        + " and say so here");
        helper.assertTrue(stoneStairs * CUT_NUM % CUT_DEN == 0,
                "the cut ratio " + CUT_NUM + "/" + CUT_DEN + " has to divide stone"
                        + " brick stairs evenly, and " + stoneStairs + " * " + CUT_NUM
                        + " / " + CUT_DEN + " does not come out whole");
        int wantBricks = stoneBricks * CUT_NUM / CUT_DEN;
        int wantStairs = stoneStairs * CUT_NUM / CUT_DEN;

        StructuralIntegrity.LOGGER.info(
                "[SI-TEST] DEEPSLATE RANK rock {}/{} = {} | cut {}/{} = {}"
                        + " | bricks stone={} deepslate={} (want {})"
                        + " | stairs stone={} deepslate={} (want {})"
                        + " | 0.7.10 gave bricks 37, under stone's 40, and stairs 20,"
                        + " tied with stone's 20",
                deepslate, stone, (float) deepslate / stone,
                CUT_NUM, CUT_DEN, (float) CUT_NUM / CUT_DEN,
                stoneBricks, deepBricks, wantBricks,
                stoneStairs, deepStairs, wantStairs);

        helper.assertTrue(deepslate > stone,
                "the whole family rests on deepslate outranking stone at the raw rock,"
                        + " and it does not: stone " + stone + ", deepslate " + deepslate);
        helper.assertTrue(CUT_NUM * stone >= CUT_DEN * deepslate,
                "the cut family must not be meaner than the rock it is cut from, but"
                        + " cut ratio " + CUT_NUM + "/" + CUT_DEN + " now sits under"
                        + " rock ratio " + deepslate + "/" + stone + " - cutting"
                        + " deepslate into a shape would cost it the advantage it"
                        + " already has as raw stone");
        helper.assertTrue(deepBricks > stoneBricks,
                "deepslate bricks must outrank stone bricks: stone " + stoneBricks
                        + ", deepslate " + deepBricks + " (0.7.10 had this backwards"
                        + " at 40 against 37, because deepslate bricks had no row and"
                        + " fell through to the hardness curve)");
        helper.assertTrue(deepStairs > stoneStairs,
                "deepslate brick stairs must outrank stone brick stairs: stone "
                        + stoneStairs + ", deepslate " + deepStairs + " (0.7.10 gave"
                        + " both 20 from the flat #minecraft:stairs entry)");
        helper.assertValueEqual(deepBricks, wantBricks,
                "deepslate bricks are stone bricks scaled by the cut ratio " + CUT_NUM
                        + "/" + CUT_DEN + " - if this reads 37 the row did not load"
                        + " and the hardness curve answered instead");
        helper.assertValueEqual(deepStairs, wantStairs,
                "deepslate brick stairs are stone brick stairs scaled by the cut ratio "
                        + CUT_NUM + "/" + CUT_DEN + " - if this reads 20 the row did"
                        + " not load and flat #minecraft:stairs answered instead");

        helper.succeed();
    }
}
