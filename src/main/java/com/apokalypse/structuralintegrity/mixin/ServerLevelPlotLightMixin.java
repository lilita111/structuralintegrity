package com.apokalypse.structuralintegrity.mixin;

import com.apokalypse.structuralintegrity.SISableLight;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Turn sky light back on for freshly created sable plot chunks.
 *
 * The reasoning, and the reason this lives here rather than in sable, is written
 * out in {@link SISableLight}. The short version: sable clears a new plot chunk's
 * light-correct flag and then feeds that same flag to the light engine, so every
 * newly assembled sub-level ships all-zero sky data and renders in permanent
 * shadow, while a sub-level reloaded from disk lights correctly because its flag
 * came back true from NBT.
 *
 * Patched from outside because the sable the pack actually runs is upstream
 * 2.0.5, ahead of our own fork, and rebuilding sable to carry a one-argument fix
 * would downgrade the pack. {@code sableexplosionfix} sets the precedent for
 * reaching into sable from a separate mod.
 *
 * The class is named by string and {@link SIMixinPlugin} refuses the mixin when
 * sable is absent, so the mod still builds and runs without it. {@code remap} is
 * off for the same reason as {@link GoggleOverlayMixin}: the target is a mod
 * class, and NeoForge 1.21.1 runs on official names anyway.
 */
@Mixin(targets = "dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot", remap = false)
public class ServerLevelPlotLightMixin {

    @WrapOperation(
            method = "initializeLight",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/lighting/LevelLightEngine;setLightEnabled"
                            + "(Lnet/minecraft/world/level/ChunkPos;Z)V"
            )
    )
    private void structuralintegrity$forceSkyLight(LevelLightEngine engine, ChunkPos pos,
                                                   boolean lightCorrect,
                                                   Operation<Void> original) {
        original.call(engine, pos, SISableLight.skyLightEnabled(pos, lightCorrect));
    }
}
