package com.apokalypse.structuralintegrity.mixin;

import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * The goggle mixin is compat code, and an ungated compat mixin is a hard crash the
 * moment its target is not installed. This gates it on Create actually being in the
 * mod list, checked through {@link LoadingModList} because mixin plugins run long
 * before ModList exists.
 */
public class SIMixinPlugin implements IMixinConfigPlugin {

    private static final String CREATE = "create";
    private boolean createPresent;

    @Override
    public void onLoad(String mixinPackage) {
        createPresent = LoadingModList.get().getModFileById(CREATE) != null;
        System.out.println("[SI] mixin plugin: create " + (createPresent ? "present" : "absent")
                + ", goggle overlay mixin " + (createPresent ? "enabled" : "skipped"));
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.endsWith("GoggleOverlayMixin")) {
            return createPresent;
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
