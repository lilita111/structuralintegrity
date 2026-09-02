package com.apokalypse.structuralintegrity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A person is a load. Landing on a floor charges the structure that carries it,
 * exactly as placing a block there would, and the charge comes back off a second
 * later.
 *
 * The order, in full. First a player lands: {@link LivingFallEvent} fires with the
 * distance they fell, which is about 1.25 for a standing jump and 0 for walking
 * along flat ground, so the fall distance alone separates a jump from a stroll.
 * Then the block under their feet is looked up, and it only counts if it is a
 * block integrity actually tracks - landing on the ground, on a tree, on anything
 * that never entered wbireg, is landing on something that was never holding
 * itself up in the first place and there is nothing to load. Then the load is sent
 * down the support chain with {@link Integrity#chain}, negative, the same call a
 * placement makes, so a span pays the sideways doubling and a pillar pays once per
 * block and none of that logic is duplicated here. If that charge spends a block
 * outright, the chain reports it failed and it is queued for destruction the same
 * way a crushed support is - this is where a floor gives way under someone.
 *
 * Then, and this is the part that makes it a shock rather than wear, the exact
 * list of rows the chain rewrote is kept, and after {@code jumpShockRecoveryTicks}
 * every one of them is given back precisely what it lost. Not a flat +1 per block:
 * the sideways doubling means a block carrying a span is charged two points by a
 * one-point load, so handing back one point would leave a point of permanent damage
 * behind every single landing, and a player pacing around their own hallway would
 * bring the house down over an afternoon without ever touching a block. Giving back
 * the recorded amount makes a survived landing a true no-op.
 *
 * The blocks that failed are NOT restored, because they are gone. Their rows were
 * floored at {@code failAt} and then destroyed; the restore pass sees a hole or a
 * fresh untracked block and skips it.
 */
public final class SIJumpShock {
    private SIJumpShock() {}

    /**
     * A landing waiting to be handed back, with the tick it is due on.
     *
     * Recorded per landing rather than merged into one set: two players on two
     * floors have nothing to do with each other, and one player bouncing on the
     * spot stacks two charges that each expire on their own schedule. The sum
     * always comes back, because every pass restores exactly what it took.
     */
    private record Pending(ServerLevel level, BlockPos landed, long dueTick,
                           List<Integrity.Touched> touched) {}

    private static final Deque<Pending> PENDING = new ArrayDeque<>();

    @SubscribeEvent
    public static void onLand(LivingFallEvent event) {
        if (!SIConfig.jumpShockEnabled()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        if (event.getDistance() < SIConfig.jumpShockMinFallDistance()) {
            return;
        }
        int load = SIConfig.jumpShockLoad();
        if (load <= 0) {
            return;
        }

        // The block actually underfoot, not the one the player's feet are inside.
        BlockPos landed = player.getOnPos();
        WbiReg reg = WbiReg.of(level);
        // Untracked is ground: it holds itself up and cannot be loaded. This is the
        // whole "registered in wbireg" test - an anchor row and a missing row read
        // the same way here, and both mean there is nothing structural underfoot.
        if (!Integrity.isStructural(level, landed, null) || Integrity.isAnchor(level, reg, landed)) {
            return;
        }

        Integrity.Chained shock = Integrity.chain(level, reg, landed, null, null, -load, true);
        StructuralIntegrity.LOGGER.info(
                "[SI] JUMP SHOCK {} landed on {} at {},{},{} after {} blocks: -{} through {} block(s){}{} : {}",
                player.getGameProfile().getName(),
                level.getBlockState(landed).getBlock().getDescriptionId(),
                landed.getX(), landed.getY(), landed.getZ(),
                String.format(java.util.Locale.ROOT, "%.2f", event.getDistance()),
                load, shock.count(),
                shock.capped() ? " (CAPPED)" : "",
                shock.failed().isEmpty() ? "" : " SPENT " + shock.failed().size(),
                shock.trace());

        for (BlockPos failed : shock.failed()) {
            SIFall.queueDestroy(level, failed);
        }

        if (shock.touched().isEmpty()) {
            StructuralIntegrity.LOGGER.info(
                    "[SI]   nothing to give back - the chain changed no row");
            return;
        }
        long due = level.getGameTime() + SIConfig.jumpShockRecoveryTicks();
        PENDING.add(new Pending(level, landed.immutable(), due, shock.touched()));
        StructuralIntegrity.LOGGER.info(
                "[SI]   holding {} row(s) for {} tick(s), due at game time {}",
                shock.touched().size(), SIConfig.jumpShockRecoveryTicks(), due);
    }

    /**
     * Hand back every landing whose second is up.
     *
     * Deliberately drains by due time rather than in insertion order, because
     * {@code jumpShockRecoveryTicks} can be edited between two landings and the
     * later one would then be due first.
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (PENDING.isEmpty()) {
            return;
        }
        List<Pending> due = null;
        for (Pending p : PENDING) {
            if (p.level().getGameTime() >= p.dueTick()) {
                if (due == null) {
                    due = new ArrayList<>();
                }
                due.add(p);
            }
        }
        if (due == null) {
            return;
        }
        PENDING.removeAll(due);
        for (Pending p : due) {
            WbiReg reg = WbiReg.of(p.level());
            Integrity.Restored restored = Integrity.restoreTouched(p.level(), reg, p.touched());
            StructuralIntegrity.LOGGER.info(
                    "[SI] JUMP RECOVER {},{},{}: gave back {} of {} row(s){} : {}",
                    p.landed().getX(), p.landed().getY(), p.landed().getZ(),
                    restored.count(), p.touched().size(),
                    restored.skipped() == 0 ? "" : ", " + restored.skipped()
                            + " gone or already whole",
                    restored.trace());
        }
    }

    /**
     * Drop everything pending when the server stops.
     *
     * The queue holds live {@link ServerLevel} references, and in single player the
     * server stops every time the player quits to the menu. Without this, a landing
     * left mid-recovery would sit in the queue across that boundary and then hand
     * its points back into a level that no longer exists - or, worse, tick down
     * against the NEXT world's clock and write rows into it.
     */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        clear();
    }

    /** Drop everything pending. */
    public static void clear() {
        if (!PENDING.isEmpty()) {
            StructuralIntegrity.LOGGER.info("[SI] JUMP SHOCK dropped {} pending recovery/recoveries",
                    PENDING.size());
            PENDING.clear();
        }
    }

    /** How many landings are waiting to be handed back. For the gametests. */
    public static int pendingCount() {
        return PENDING.size();
    }
}
