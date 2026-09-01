package com.apokalypse.structuralintegrity.mixin;

import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Every mixin in this mod is compat code, and an ungated compat mixin is a hard
 * crash the moment its target is not installed. Each is gated on the mod it
 * patches actually being in the mod list, checked through {@link LoadingModList}
 * because mixin plugins run long before ModList exists.
 *
 * The goggle overlay needs Create; the plot lighting fix needs sable.
 */
public class SIMixinPlugin implements IMixinConfigPlugin {

    private static final String CREATE = "create";
    private static final String SABLE = "sable";
    private boolean createPresent;
    private boolean sablePresent;

    @Override
    public void onLoad(String mixinPackage) {
        createPresent = LoadingModList.get().getModFileById(CREATE) != null;
        sablePresent = LoadingModList.get().getModFileById(SABLE) != null;
        System.out.println("[SI] mixin plugin: create " + (createPresent ? "present" : "absent")
                + ", goggle overlay mixin " + (createPresent ? "enabled" : "skipped"));
        System.out.println("[SI] mixin plugin: sable " + (sablePresent ? "present" : "absent")
                + ", plot sky light fix " + (sablePresent ? "enabled" : "skipped"));
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.endsWith("GoggleOverlayMixin")) {
            return createPresent;
        }
        if (mixinClassName.endsWith("ServerLevelPlotLightMixin")) {
            return sablePresent;
        }
        return true;
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo info) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo info) {}
}
