package com.apokalypse.structuralintegrity.mixin;

import com.apokalypse.structuralintegrity.SISableLight;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Report what a sable plot's light engine holds the instant sable finishes lighting
 * a chunk.
 *
 * This used to force sky light on, on the theory written out in
 * {@link SISableLight}. That theory was disproved in 0.7.4 - sable calls
 * {@code propagateLightSources} two calls after the {@code setLightEnabled} this
 * mixin was overriding, and vanilla's propagate re-enables light unconditionally as
 * its first statement, so the override was undone before it could matter and the
 * mixin was a no-op the whole time it shipped. Sub-levels are still dark. Rather
 * than delete the hook and lose the access, it now reads instead of writes.
 *
 * The injection point moved with the purpose. {@code initializeLight} was the right
 * place to change the flag and the wrong place to read the result, because
 * {@code correctLight} runs after it and changes everything that matters. The RETURN
 * of {@code lightChunk} is after all three steps - {@code initializeLightSources},
 * {@code initializeLight}, {@code correctLight} - so what is read here is the
 * finished server-side state of the chunk. If the sky layers are already zero at
 * this point the bug is in sable or in vanilla's engine; if they are correct here,
 * the darkness enters later, on the wire or on the client, and the next instrument
 * belongs there.
 *
 * Patched from outside because the sable the pack actually runs is upstream 2.0.5,
 * ahead of our own fork, and rebuilding sable to carry an instrument would downgrade
 * the pack. {@code sableexplosionfix} sets the precedent for reaching into sable
 * from a separate mod.
 *
 * The class is named by string and {@link SIMixinPlugin} refuses the mixin when
 * sable is absent, so the mod still builds and runs without it. {@code remap} is off
 * for the same reason as {@link GoggleOverlayMixin}: the target is a mod class, and
 * NeoForge 1.21.1 runs on official names anyway.
 */
@Mixin(targets = "dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot", remap = false)
public abstract class ServerLevelPlotLightMixin {

    /**
     * The plot's own light engine - a separate {@link LevelLightEngine} from the
     * host level's, built in the plot's constructor. Shadowed through the public
     * getter rather than the protected field so this mixin needs no knowledge of
     * sable's field name.
     */
    @Shadow
    public abstract LevelLightEngine getLightEngine();

    @Inject(method = "lightChunk", at = @At("RETURN"))
    private void structuralintegrity$reportLight(LevelChunk chunk, CallbackInfo ci) {
        SISableLight.report(chunk.getPos(), getLightEngine(), chunk);
    }
}
