package com.apokalypse.structuralintegrity.mixin;

import com.apokalypse.structuralintegrity.client.SIClientHooks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/**
 * Create's goggle overlay has no registration hook - every addon that adds a line
 * for a block without a block entity injects here. This is the same call Create Big
 * Cannons wraps for its armour inspector, and MixinExtras chains wrappers on one
 * target, so both can hold it at once.
 *
 * The class is named by string: nothing here references a Create type, so the mod
 * builds and runs with Create absent. {@link SIMixinPlugin} keeps the mixin itself
 * from being applied in that case.
 *
 * Appending to the tooltip is what makes it show. Create renders whenever the list
 * is non-empty, so a plain stone block gets an overlay it would never otherwise
 * have - which is the point.
 */
@Mixin(targets = "com.simibubi.create.content.equipment.goggles.GoggleOverlayRenderer", remap = false)
public class GoggleOverlayMixin {

    @WrapOperation(
            method = "renderOverlay",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/ClientLevel;getBlockState"
                            + "(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
                    ordinal = 0
            )
    )
    private static BlockState structuralintegrity$addIntegrity(
            ClientLevel level, BlockPos pos, Operation<BlockState> original,
            @Local List<Component> tooltip) {
        BlockState state = original.call(level, pos);
        try {
            SIClientHooks.addGoggleLines(tooltip, pos);
        } catch (Throwable t) {
            // A read-out is never worth taking the HUD down with it.
            com.apokalypse.structuralintegrity.StructuralIntegrity.LOGGER
                    .error("[SI] goggle overlay line failed at {}", pos, t);
        }
        return state;
    }
}
