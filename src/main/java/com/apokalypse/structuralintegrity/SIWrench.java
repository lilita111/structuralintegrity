package com.apokalypse.structuralintegrity;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Mending a building by hand, with a wrench.
 *
 * Why this exists at all. Damage in this mod outlives its cause. When a block is
 * placed, {@link Integrity#place} charges the structure underneath for carrying
 * it, and that charge is permanent - it is what makes a tall thing fail at its
 * base rather than its tip. That is right while the block is still there. It stops
 * being right the moment the block leaves: when part of a building shears off and
 * {@link SIFall} assembles it into a sub-level, the rows of the departed blocks are
 * cleared and the piece flies away, but every point they charged into the wall they
 * hung from stays charged. The wall goes on carrying a load that is not there. A
 * building that has survived one collapse is permanently weaker than the same
 * building freshly built, and everything raised on it afterwards inherits that.
 *
 * Returning it automatically the instant a piece detaches was the obvious
 * alternative and is the worse game: a collapse would quietly undo its own damage,
 * and nothing could ever be worn down. Making it a deliberate act with a tool in
 * hand costs the player the walk, keeps the damage visible until someone deals with
 * it, and gives the wrench - an item that already means "adjust the building" to
 * anyone playing Create - something to do on ordinary blocks.
 *
 * What happens when the click lands, in order. First the item in hand is matched by
 * registry id against {@code wrenchRepairItem}, which is {@code create:wrench} by
 * default; matching by id and not by type is what lets this mod carry the feature
 * without a compile dependency on Create, exactly as {@code GoggleOverlayMixin}
 * names its target by string. Then the block is checked against the two things
 * Create's own wrench does, and skipped if it is either of them - see
 * {@link #createWouldHandle}. Then the block is recalculated by
 * {@link Integrity#recompute}, which re-derives what a block placed here now would
 * be assigned and charges nobody for it. Then the player is told what changed, on
 * the action bar, because a repair that reports nothing is indistinguishable from a
 * repair that did not happen.
 *
 * With {@code wrenchRepairRadius} above zero the same thing runs over a cube, and
 * the order it runs in matters: lowest blocks first. A block can only be repaired
 * as far as its best neighbour currently stands, so mending the bottom of a wall
 * first gives the course above it something sound to inherit from, and one click
 * does what a careful walk up the wall would.
 */
public final class SIWrench {
    private SIWrench() {}

    /**
     * Create's marker interface for blocks its own wrench acts on, or null when
     * Create is absent. Looked up by name once, for the same reason the item is
     * matched by id: this mod does not compile against Create and must run without
     * it. A failed lookup is the normal case in a pack with no Create in it.
     */
    private static final Class<?> IWRENCHABLE = findClass(
            "com.simibubi.create.content.equipment.wrench.IWrenchable");

    /**
     * The tag Create's wrench uses to decide what a sneaking player can pick up
     * whole. Named rather than referenced for the same reason; a tag that no mod
     * defines simply matches nothing.
     */
    private static final TagKey<Block> WRENCH_PICKUP = TagKey.create(
            Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("create", "wrench_pickup"));

    private static Class<?> findClass(String name) {
        try {
            return Class.forName(name, false, SIWrench.class.getClassLoader());
        } catch (Throwable ignored) {
            return null;
        }
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!SIConfig.wrenchRepairEnabled()) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Player player = event.getEntity();
        ItemStack held = event.getItemStack();
        if (held.isEmpty() || !matchesTool(held)) {
            return;
        }
        if (SIConfig.wrenchRepairRequiresSneak() != player.isShiftKeyDown()) {
            return;
        }

        BlockPos clicked = event.getPos();
        if (createWouldHandle(level.getBlockState(clicked), player.isShiftKeyDown())) {
            // Create's wrench has its own job on this block. Rotating a machine is
            // what the player asked for; quietly replacing that with a repair would
            // be the mod stealing an interaction it does not own.
            return;
        }

        int radius = SIConfig.wrenchRepairRadius();
        WbiReg reg = WbiReg.of(level);
        List<BlockPos> targets = targetsAround(clicked, radius);

        int repaired = 0;
        int gained = 0;
        int alreadySound = 0;
        Integrity.Recomputed atClicked = null;
        for (BlockPos pos : targets) {
            Integrity.Recomputed r = Integrity.recompute(level, reg, pos);
            if (r == null) {
                continue;
            }
            if (pos.equals(clicked)) {
                atClicked = r;
            }
            if (r.before() == WbiReg.ANCHOR) {
                continue; // ground: nothing was ever taken from it
            }
            if (r.changed()) {
                repaired++;
                gained += r.after() - r.before();
                StructuralIntegrity.LOGGER.info(
                        "[SI] WRENCH REPAIR {},{},{} {}: {} -> {} (fresh {} nat {} {})",
                        pos.getX(), pos.getY(), pos.getZ(),
                        level.getBlockState(pos).getBlock().getDescriptionId(),
                        r.before(), r.after(), r.fresh(), r.natural(),
                        r.founded() ? "founded on ground" : "best connection " + r.best());
            } else {
                alreadySound++;
            }
        }

        StructuralIntegrity.LOGGER.info(
                "[SI] WRENCH {} clicked {},{},{} radius={}: {} block(s) considered, "
                        + "{} repaired for {} point(s), {} already sound",
                player.getGameProfile().getName(),
                clicked.getX(), clicked.getY(), clicked.getZ(), radius,
                targets.size(), repaired, gained, alreadySound);

        if (repaired > 0) {
            level.playSound(null, clicked, SoundEvents.ANVIL_USE, SoundSource.BLOCKS, 0.35F, 1.6F);
        }
        player.displayClientMessage(message(atClicked, repaired, gained, alreadySound, radius), true);

        // Taken whether or not anything moved: the player aimed a repair at this
        // block and got an answer, and letting the click fall through afterwards
        // would place a block out of the off hand on top of the reply.
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    /**
     * Is this the repair tool? Matched by registry id, so the mod neither compiles
     * against nor requires Create, and a pack can point the feature at any item.
     */
    private static boolean matchesTool(ItemStack stack) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id != null && id.toString().equals(SIConfig.wrenchRepairItem());
    }

    /**
     * Would Create's wrench already be doing something here?
     *
     * Two cases, both taken from Create's own {@code WrenchItem.useOn}. A block
     * implementing {@code IWrenchable} is rotated by a plain click and picked up by
     * a sneaking one, so both clicks are spoken for. Any other block in the
     * {@code create:wrench_pickup} tag is picked up by a sneaking click only.
     * Everything else - which is nearly every block a building is made of - falls
     * through Create's handler to {@code super.useOn} and does nothing at all,
     * which is the gap this feature lives in.
     */
    private static boolean createWouldHandle(BlockState state, boolean sneaking) {
        if (IWRENCHABLE != null && IWRENCHABLE.isInstance(state.getBlock())) {
            return true;
        }
        return sneaking && state.is(WRENCH_PICKUP);
    }

    /**
     * The block clicked, plus its cube out to {@code radius}, lowest first.
     *
     * Sorted by height and then by distance because repair inherits: a block comes
     * back only as far as its best neighbour currently stands, so the course below
     * has to be mended before the course above can gain anything from it. Sorting
     * here rather than relying on iteration order makes that a stated rule instead
     * of an accident of how BlockPos.betweenClosed happens to walk.
     */
    private static List<BlockPos> targetsAround(BlockPos clicked, int radius) {
        if (radius <= 0) {
            return List.of(clicked);
        }
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(
                clicked.offset(-radius, -radius, -radius),
                clicked.offset(radius, radius, radius))) {
            out.add(p.immutable());
        }
        out.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY)
                .thenComparingDouble(p -> p.distSqr(clicked)));
        return out;
    }

    /**
     * What the player is told, on the action bar.
     *
     * The single-block case names the numbers, because one click on one block
     * should say exactly what that block was and is. The radius case counts, because
     * a list of forty rows is not a message.
     */
    private static Component message(Integrity.Recomputed atClicked, int repaired, int gained,
                                     int alreadySound, int radius) {
        if (radius > 0) {
            if (repaired == 0) {
                return Component.literal("Nothing to repair here - " + alreadySound
                        + " block(s) already sound").withStyle(ChatFormatting.GRAY);
            }
            return Component.literal("Repaired " + repaired + " block(s), +" + gained
                    + " integrity").withStyle(ChatFormatting.GREEN);
        }
        if (atClicked == null) {
            return Component.literal("Not a block integrity tracks")
                    .withStyle(ChatFormatting.GRAY);
        }
        if (atClicked.before() == WbiReg.ANCHOR) {
            return Component.literal("This is ground - it was never carrying anything")
                    .withStyle(ChatFormatting.GRAY);
        }
        if (!atClicked.changed()) {
            return Component.literal("Already sound at " + atClicked.after() + "/"
                    + atClicked.natural()).withStyle(ChatFormatting.GRAY);
        }
        return Component.literal("Integrity " + atClicked.before() + " -> " + atClicked.after()
                + "/" + atClicked.natural()).withStyle(ChatFormatting.GREEN);
    }
}
