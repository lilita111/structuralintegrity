package com.apokalypse.structuralintegrity;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

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
        StringBuilder csv = new StringBuilder("id,integrity,never_anchor,source,structural\n");
        int vanilla = 0;
        for (Block block : BuiltInRegistries.BLOCK) {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
            if (!id.getNamespace().equals("minecraft")) {
                continue;
            }
            vanilla++;
            BlockState state = block.defaultBlockState();
            boolean structural = !(state.isAir() || block instanceof LiquidBlock
                    || block instanceof BushBlock || block instanceof LeavesBlock);
            int nat = Integrity.naturalOf(level, pos, state);
            boolean never = Integrity.neverAnchor(level, pos, state);
            boolean hasRow = block.builtInRegistryHolder().getData(SIDataMaps.NATURAL) != null;
            csv.append(id.getPath()).append(',').append(nat).append(',').append(never)
                    .append(',').append(hasRow ? "datamap" : "derived").append(',')
                    .append(structural).append('\n');
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
}
