package com.apokalypse.structuralintegrity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Stage 1 trigger points. A player's own place/break is run through the full
 * evaluate-and-report function below - vanilla neighbour updates, redstone,
 * fluids and pistons are ignored. Explosions are not a player update and are not
 * evaluated or reported the same way, but they remove blocks exactly as
 * mining does, so they get the same disturb-then-seed treatment break does,
 * batched over every position the blast is about to take.
 *
 * place : the placed block inherits its support's integrity, and the load then
 *         descends the support chain from that support to ground, charging one
 *         point per block, before the placed block and its neighbours are
 *         evaluated. The whole chain is printed, so what the maths did is
 *         readable from the log without guessing.
 * break : run the chain in reverse first - the destroyed block's load comes
 *         off, +1 per block from its support toward ground - then drop the
 *         broken row, disturb the neighbours out of ground, and evaluate them.
 * explode : same reverse-then-drop-and-disturb as break, for every position
 *         the explosion is about to remove - no per-origin evaluation, there
 *         is no single origin.
 *
 * For every HANG face found, the neighbour across that face is the break
 * candidate and is run through the function in turn. One step, never a cascade -
 * the block the player touched is never the one that breaks.
 *
 * Writes wbireg rows. Changes no block in the world.
 */
public final class SIEvents {
    private SIEvents() {}

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        WbiReg reg = WbiReg.of(level);
        Integrity.Placed placed = Integrity.place(level, reg, event.getPos());
        Entity placer = event.getEntity();
        run(level, reg, "PLACE", event.getPos(), null, placed, List.of(),
                placer instanceof Player p ? p : null);

        // The degrade pass can drive several blocks to zero at once, anywhere in the
        // structure. They are removed next tick, and whatever they were holding is
        // then floating - which the fall pass picks up on its own.
        if (placed != null) {
            for (BlockPos failed : placed.failed()) {
                SIFall.queueDestroy(level, failed);
            }
        }
        SIFall.seedAround(level, event.getPos(), true);
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        WbiReg reg = WbiReg.of(level);
        BlockPos pos = event.getPos();
        // The load this block was adding disappears with it: the same chain the
        // placement charged runs again from its support, +1 per block this time.
        // Before disturb, so the walk still sees ground as ground.
        Integrity.Degraded restored = null;
        if (SIConfig.reverseIntegrityOnBreak() && Integrity.isStructural(level, pos, null)) {
            BlockPos support = Integrity.supportOf(level, reg, pos, null);
            if (support != null) {
                restored = Integrity.restore(level, reg, support, Set.of(pos.immutable()));
            }
        }
        // BreakEvent fires before removal: solve the world as it will be.
        reg.clear(pos);
        List<BlockPos> disturbed = Integrity.disturb(level, reg, pos, pos);
        run(level, reg, "BREAK", pos, pos, null, disturbed, event.getPlayer());
        if (restored != null && restored.count() > 0) {
            StructuralIntegrity.LOGGER.info("[SI]   restore chain +1 x{}: {}{}",
                    restored.count(), restored.trace(), restored.capped() ? " (capped)" : "");
        }

        // Stage 2. Deliberately not solved here: this fires while the broken block
        // is still in the world, so the component that matters does not exist yet.
        SIFall.seedAround(level, pos, false);
    }

    /**
     * Detonate fires once per explosion with every position it is about to take,
     * still present in the world - the same "solve it before it's gone" timing
     * BreakEvent gives a single mined block. Without this, a blast never disturbs
     * anything: whatever it undercuts either hangs there forever (nothing here ever
     * queues a fall check for it) or, if it happens to be a vanilla gravity block
     * like sand or gravel, drops on its own the ordinary way - never as the one
     * connected sub-level the rest of the wall should come down as.
     */
    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        List<BlockPos> affected = event.getAffectedBlocks();
        if (affected.isEmpty()) {
            return;
        }
        WbiReg reg = WbiReg.of(level);
        Set<BlockPos> gone = new HashSet<>(affected.size());
        for (BlockPos pos : affected) {
            gone.add(pos.immutable());
        }
        // Reverse the chains before anything is cleared, with the whole blast
        // excluded from the walks - a +1 must not land on a block that is about
        // to be removed anyway, it belongs to the survivors past it.
        int restored = 0;
        if (SIConfig.reverseIntegrityOnBreak()) {
            for (BlockPos p : gone) {
                if (!Integrity.isStructural(level, p, null)) {
                    continue;
                }
                BlockPos support = Integrity.supportOf(level, reg, p, gone);
                if (support != null) {
                    restored += Integrity.restore(level, reg, support, gone).count();
                }
            }
        }
        for (BlockPos p : gone) {
            reg.clear(p);
            Integrity.disturb(level, reg, p, p);
            SIFall.seedAround(level, p, false);
        }
        StructuralIntegrity.LOGGER.info("[SI] EXPLOSION {} blocks -> restored +1 across {} chain blocks, disturbed and seeded for fall-check",
                affected.size(), restored);
    }

    private static void run(ServerLevel level, WbiReg reg, String trigger, BlockPos origin,
                            @Nullable BlockPos ghost, @Nullable Integrity.Placed placed,
                            List<BlockPos> disturbed, @Nullable Player player) {
        long t0 = System.nanoTime();

        // The blocks a player update touches: the block itself (if it still exists)
        // and everything that was connected to it.
        Set<BlockPos> targets = new LinkedHashSet<>();
        if (ghost == null && Integrity.isStructural(level, origin, null)) {
            targets.add(origin.immutable());
        }
        for (Direction d : Direction.values()) {
            BlockPos n = origin.relative(d);
            if (Integrity.isStructural(level, n, ghost)) {
                targets.add(n.immutable());
            }
        }

        List<Integrity.Result> primary = new ArrayList<>();
        List<Integrity.Result> candidates = new ArrayList<>();
        Set<BlockPos> seen = new LinkedHashSet<>();

        for (BlockPos t : targets) {
            Integrity.Result r = Integrity.compute(level, reg, t, ghost);
            if (!r.structural()) {
                continue;
            }
            primary.add(r);
            // Hand off across each hanging face: that neighbour is what breaks.
            for (Integrity.Face f : r.faces()) {
                if (f.grounded() || f.count() <= 1) {
                    continue;
                }
                BlockPos c = t.relative(f.dir());
                if (targets.contains(c) || !seen.add(c)) {
                    continue;
                }
                Integrity.Result cr = Integrity.compute(level, reg, c, ghost);
                if (cr.structural()) {
                    candidates.add(cr);
                }
            }
        }

        long micros = (System.nanoTime() - t0) / 1000L;

        StructuralIntegrity.LOGGER.info("[SI] {} at {} | evaluated={} candidates={} wbireg={} in {}us",
                trigger, fmt(origin), primary.size(), candidates.size(), reg.size(), micros);
        if (placed != null) {
            StructuralIntegrity.LOGGER.info("[SI]   {}", placeLine(placed));
        }
        if (!disturbed.isEmpty()) {
            StringBuilder sb = new StringBuilder("[SI]   disturbed out of ground:");
            for (BlockPos p : disturbed) {
                sb.append(' ').append(fmt(p)).append('(').append(reg.get(p)).append(')');
            }
            StructuralIntegrity.LOGGER.info(sb.toString());
        }
        for (Integrity.Result r : primary) {
            logResult("  ", r);
        }
        for (Integrity.Result r : candidates) {
            logResult("  -> ", r);
        }

        if (player == null) {
            return;
        }
        StringBuilder chat = new StringBuilder("[SI] ").append(trigger);
        if (placed != null) {
            chat.append(' ').append(placeLine(placed));
        }
        Integrity.Result worst = worst(primary, candidates);
        if (worst != null) {
            chat.append(" | worst ").append(oneLine(worst));
        }
        chat.append(" | ").append(micros).append("us wbireg=").append(reg.size());
        player.sendSystemMessage(Component.literal(chat.toString()));
    }

    private static String placeLine(Integrity.Placed p) {
        StringBuilder sb = new StringBuilder("placed nat=").append(p.natural())
                .append(" -> stored=").append(p.assigned());
        if (p.support() == null) {
            sb.append(" (no support)");
        } else if (p.onAnchor()) {
            sb.append(" on GROUND ").append(fmt(p.support()));
        } else {
            sb.append(" on ").append(fmt(p.support()))
                    .append(" (support=").append(p.supportAt()).append(')');
        }
        if (p.degraded() > 0) {
            sb.append(" load[").append(p.degraded()).append("] ").append(p.trace());
            if (p.capped()) {
                sb.append(" CAPPED(").append(Integrity.MAX_LOAD_PATH).append(')');
            }
        }
        if (!p.failed().isEmpty()) {
            sb.append(" FAILED");
            for (BlockPos f : p.failed()) {
                sb.append(' ').append(fmt(f));
            }
            sb.append(" (spent; destroyed next tick)");
        }
        return sb.toString();
    }

    private static void logResult(String indent, Integrity.Result r) {
        StringBuilder sb = new StringBuilder();
        sb.append("[SI] ").append(indent).append(oneLine(r));
        if (r.capped()) {
            sb.append(" CAPPED(").append(Integrity.MAX_REGION).append(')');
        }
        if (!Integrity.hasRow(r.state())) {
            sb.append(" [no naturalintegrityreg row, defaulted to ").append(r.natural()).append(']');
        }
        StructuralIntegrity.LOGGER.info(sb.toString());

        for (Integrity.Face f : r.faces()) {
            if (f.count() == 0 && !f.grounded()) {
                continue; // air or non-structural: nothing to say
            }
            StructuralIntegrity.LOGGER.info("[SI] {}    face {} {}", indent, pad(f.dir()),
                    f.grounded()
                            ? "GROUND" + (f.shared() ? " (shared)" : "")
                            : "HANG x=" + f.count() + (f.shared() ? " (shared)" : "")
                              + " -> candidate " + fmt(r.pos().relative(f.dir())));
        }
    }

    private static String oneLine(Integrity.Result r) {
        return fmt(r.pos()) + " " + key(r)
                + " nat=" + r.natural()
                + " stored=" + (r.anchor() ? "GROUND" : String.valueOf(r.stored()))
                + " hang=" + r.hangMax() + "/" + r.hangSum()
                + " -> " + r.integrity()
                + " (hang-adj " + r.integrityMax() + "/" + r.integritySum() + ")"
                + " " + r.verdict();
    }

    @Nullable
    private static Integrity.Result worst(List<Integrity.Result> a, List<Integrity.Result> b) {
        Integrity.Result best = null;
        for (List<Integrity.Result> list : List.of(a, b)) {
            for (Integrity.Result r : list) {
                if (r.anchor()) {
                    continue; // ground never fails; it would win every comparison
                }
                if (best == null || r.integrity() < best.integrity()) {
                    best = r;
                }
            }
        }
        return best;
    }

    private static String key(Integrity.Result r) {
        return BuiltInRegistries.BLOCK.getKey(r.state().getBlock()).toString();
    }

    private static String pad(Direction d) {
        return String.format("%-5s", d.getName());
    }

    private static String fmt(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }
}
