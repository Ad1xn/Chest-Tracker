package dev.adrian.chesttracker.mixin.client.compat;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Applies the compatibility mixins only when the mod they target is installed.
 *
 * <p>Without this, a mixin naming a class that is not present is an error at
 * load: Mixin cannot know the absence is expected. ShulkerBoxTooltip is not a
 * dependency and most installs will not have it, so "not present" is the normal
 * case rather than the exceptional one.
 *
 * <p>Keyed on mod id rather than on whether the class resolves, because a mod
 * that is present but has moved its internals should fail loudly during
 * development rather than silently doing nothing.
 */
public final class ChestTrackerCompatPlugin implements IMixinConfigPlugin {

    /** Which mod each mixin needs, by simple class name. */
    private static String requiredModFor(String mixinClassName) {
        if (mixinClassName.endsWith("PreviewRendererMixin")) return "shulkerboxtooltip";
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        String required = requiredModFor(mixinClassName);
        return required == null || FabricLoader.getInstance().isModLoaded(required);
    }

    @Override
    public void onLoad(String mixinPackage) {}

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
    public void preApply(String targetClassName, ClassNode targetClass,
                         String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass,
                          String mixinClassName, IMixinInfo mixinInfo) {}
}
