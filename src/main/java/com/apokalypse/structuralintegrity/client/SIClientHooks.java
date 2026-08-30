package com.apokalypse.structuralintegrity.client;

import com.apokalypse.structuralintegrity.SIClientCache;
import com.apokalypse.structuralintegrity.SIPayloads;
import com.apokalypse.structuralintegrity.StructuralIntegrity;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Client half of the goggle read-out.
 *
 * Ask the server about the block under the crosshair, but only while wearing
 * Create's goggles, and only when the answer could have changed: a different
 * position, or {@link #REFRESH_TICKS} since the last one. The tooltip itself is
 * appended by the mixin, which reads whatever answer arrived last.
 *
 * Goggles are matched by registry id rather than by calling into Create, so this
 * class compiles and loads with Create absent - only the mixin needs Create, and
 * that is gated separately.
 */
@EventBusSubscriber(modid = StructuralIntegrity.MODID, value = Dist.CLIENT)
public final class SIClientHooks {
    private SIClientHooks() {}

    public static final ResourceLocation GOGGLES =
            ResourceLocation.fromNamespaceAndPath("create", "goggles");

    /** A stale answer is still shown, it is just refreshed this often. */
    public static final int REFRESH_TICKS = 20;

    @Nullable
    private static BlockPos lastAsked;
    private static int cooldown;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.getConnection() == null) {
            lastAsked = null;
            SIClientCache.clear();
            return;
        }
        if (!isWearingGoggles(mc.player)) {
            lastAsked = null;
            return;
        }
        if (!(mc.hitResult instanceof BlockHitResult hit) || mc.hitResult.getType() != HitResult.Type.BLOCK) {
            lastAsked = null;
            return;
        }

        BlockPos pos = hit.getBlockPos();
        if (cooldown > 0) {
            cooldown--;
        }
        if (pos.equals(lastAsked) && cooldown > 0) {
            return;
        }
        lastAsked = pos.immutable();
        cooldown = REFRESH_TICKS;
        PacketDistributor.sendToServer(new SIPayloads.Query(lastAsked));
    }

    /**
     * Create's own check consults curios and trinkets too; this only reads the head
     * slot, which is the case that matters and needs no Create on the classpath.
     */
    public static boolean isWearingGoggles(Player player) {
        ItemStack head = player.getItemBySlot(EquipmentSlot.HEAD);
        return !head.isEmpty() && GOGGLES.equals(BuiltInRegistries.ITEM.getKey(head.getItem()));
    }

    /**
     * Called from the mixin, once per frame, for the hovered position. Adds nothing
     * unless the server has already answered about that exact position - a missing
     * answer leaves Create's tooltip exactly as it was.
     */
    public static void addGoggleLines(List<Component> tooltip, BlockPos pos) {
        SIPayloads.Info info = SIClientCache.get(pos);
        if (info == null || !info.structural()) {
            return;
        }
        if (!tooltip.isEmpty()) {
            tooltip.add(Component.empty());
        }
        tooltip.add(Component.literal("Structural Integrity").withStyle(ChatFormatting.GRAY));

        if (info.anchor()) {
            tooltip.add(line("Integrity", "∞", ChatFormatting.AQUA)
                    .append(Component.literal(" (ground)").withStyle(ChatFormatting.DARK_GRAY)));
            tooltip.add(line("Natural", String.valueOf(info.natural()), ChatFormatting.DARK_GRAY));
            return;
        }

        int value = info.integrity();
        ChatFormatting colour = !info.grounded() ? ChatFormatting.RED
                : value <= 0 ? ChatFormatting.RED
                : value == 1 ? ChatFormatting.GOLD
                : ChatFormatting.WHITE;
        tooltip.add(line("Integrity", value + " / " + info.natural(), colour));

        if (info.hang() > 0) {
            tooltip.add(line("Hanging", info.hang() + " block" + (info.hang() == 1 ? "" : "s"),
                    ChatFormatting.YELLOW));
        }
        if (!info.grounded()) {
            tooltip.add(Component.literal(" Not connected to ground")
                    .withStyle(ChatFormatting.RED));
        } else if (value <= 0) {
            tooltip.add(Component.literal(" Overloaded").withStyle(ChatFormatting.RED));
        } else if (value == 1) {
            tooltip.add(Component.literal(" About to fail").withStyle(ChatFormatting.GOLD));
        }
    }

    private static MutableComponent line(String label, String value, ChatFormatting colour) {
        return Component.literal(" " + label + " ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(value).withStyle(colour));
    }
}
