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
}
