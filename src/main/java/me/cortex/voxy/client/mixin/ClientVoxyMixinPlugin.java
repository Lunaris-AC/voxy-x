package me.cortex.voxy.client.mixin;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class ClientVoxyMixinPlugin implements IMixinConfigPlugin {
    private static boolean valkyrienSkiesInstalled;
    private static boolean nvidiumInstalled;

    @Override
    public void onLoad(String mixinPackage) {
        valkyrienSkiesInstalled = FabricLoader.getInstance().isModLoaded("valkyrienskies");
        nvidiumInstalled = FabricLoader.getInstance().isModLoaded("nvidium");
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return true; }

    @Override public List<String> getMixins() {
        List<String> mixins = new ArrayList<>();
        if (valkyrienSkiesInstalled && !nvidiumInstalled) {
            mixins.add("sodium.MixinSodiumWorldRendererVS");
        } else {
            mixins.add("sodium.MixinDefaultChunkRenderer");
        }

        // MixinShaderLoader (Connector-only) is gone: it worked around a
        // classloader bug in Sodium 0.6's getShaderSource that Sodium 0.8
        // already fixes internally (ShaderLoader.class.getClassLoader()
        // instead of the caller's classloader).
        return mixins;
    }

    @Override
    public String getRefMapperConfig() { return null; }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}