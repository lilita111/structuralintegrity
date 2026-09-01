package com.apokalypse.structuralintegrity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Ground that stopped being ground.
 *
 * A position with no wbireg row reads as an anchor - untouched, immovable,
 * whatever rests on it is grounded. That is right for real terrain and wrong for
 * terrain that has been mined out from underneath. Disturbance spreads exactly
 * one block, deliberately, so that carving a tunnel through a mountain leaves
 * the rock behind the wall still grounded. The same rule means carving out
 * everything AROUND a lump of rock leaves its core still grounded too, and the
 * lump hangs in the air forever because nothing in the system has any reason to
 * look at it again.
 *
 * This is the reason to look. From a block that just broke, walk outward through
 * untouched blocks only. If that walk closes - if every face of the region it
 * covers is a block that already has a wbireg row - then no untouched block
 * connects this lump to the rest of the world, and calling it ground is a
 * bookkeeping accident. Give every block in it a row at its natural value,
 * exactly as {@link Integrity#disturb} does for a mined block's neighbours, and
 * the ordinary fall machinery takes it from there.
 *
 * The walk gives up, leaving the region alone, if it:
 *   - reaches a block on the true-ground list (deepslate by default) - deep rock
 *     is ground whatever has been built around it;
 *   - touches air, fluid or anything else non-structural, which means the region
 *     is open to the world and is not enclosed by anything;
 *   - grows past the configured cap, which is what open terrain does.
 *
 * Every one of those is a "leave it as ground" answer, so the expensive case is
 * the one that fails fastest: a break at the surface hits air within a step or
 * two. It still costs something, which is why it runs on a roll rather than on
 * every break.
 */
public final class SIEnclosure {
    private SIEnclosure() {}

    /**
     * Roll for it, and run the walk if the roll comes up.
     *
     * @param origin the position that just broke - player-mined or integrity-crushed
     */
    public static void maybeCheck(ServerLevel level, WbiReg reg, BlockPos origin) {
        int chance = SIConfig.enclosureCheckChance();
        if (chance <= 0 || level.random.nextInt(chance) != 0) {
            return;
        }
        check(level, reg, origin);
    }

    /**
     * The walk itself, without the roll. Separate so a test can call it directly
     * instead of hoping for a one-in-a-hundred.
     *
     * @return how many blocks stopped being ground
     */
    public static int check(ServerLevel level, WbiReg reg, BlockPos origin) {
        Set<BlockPos> seen = new HashSet<>();
        int demoted = 0;

        // The broken block is gone, so the untouched rock it was against is one
        // step out. Each neighbour can belong to a different region; each gets
        // its own walk, and a region already covered is skipped.
        for (Direction d : Direction.values()) {
            BlockPos start = origin.relative(d);
            if (seen.contains(start) || !isUntouched(level, reg, start)) {
                continue;
            }
            demoted += walk(level, reg, start, seen);
        }
        return demoted;
    }

    /** True if this position is structural and has never been disturbed. */
    private static boolean isUntouched(ServerLevel level, WbiReg reg, BlockPos pos) {
        return Integrity.isStructural(level, pos, null) && reg.isAnchor(pos);
    }

    private static int walk(ServerLevel level, WbiReg reg, BlockPos start, Set<BlockPos> seen) {
        Set<Block> trueGround = SIConfig.enclosureGroundBlocks();
        int cap = SIConfig.enclosureMaxRegion();

        List<BlockPos> region = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> work = new ArrayDeque<>();
        work.add(start.immutable());
        visited.add(start.immutable());

        String reason = null;
        while (!work.isEmpty()) {
            BlockPos pos = work.poll();

            if (trueGround.contains(level.getBlockState(pos).getBlock())) {
                reason = "reached true ground at " + fmt(pos);
                break;
            }
            region.add(pos);
            if (region.size() > cap) {
                reason = "larger than enclosureMaxRegion=" + cap;
                break;
            }

            for (Direction d : Direction.values()) {
                BlockPos n = pos.relative(d);
                if (!Integrity.isStructural(level, n, null)) {
                    // Air, fluid, a plant, anything the system ignores. The region
                    // has an open face, so nothing encloses it.
                    reason = "open face at " + fmt(n);
                    work.clear();
                    break;
                }
                if (!reg.isAnchor(n)) {
                    continue; // a tracked block - this is the wall we are hoping for
                }
                if (visited.add(n.immutable())) {
                    work.add(n.immutable());
                }
            }
        }

        // Everything walked is off the table either way: if the region is enclosed
        // it is being demoted, and if it is not, re-walking it from another face of
        // the same break would reach the same answer.
        seen.addAll(visited);

        if (reason != null) {
            StructuralIntegrity.LOGGER.info("[SI] enclosure check from {} - still ground, {} (n={})",
                    fmt(start), reason, region.size());
            return 0;
        }

        StructuralIntegrity.LOGGER.info("[SI] ENCLOSED region of {} untouched blocks from {} "
                        + "is walled in by tracked blocks - no longer ground",
                region.size(), fmt(start));
        for (BlockPos p : region) {
            Integrity.disturbAt(level, reg, p);
        }
        // The lump can now be found to be detached, but only if something looks.
        for (BlockPos p : region) {
            SIFall.queueFall(level, p);
        }
        return region.size();
    }

    private static String fmt(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }
}
