package me.cortex.voxy.client.mixin.minecraft;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import me.cortex.voxy.client.config.VoxyConfig;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = {GameRenderer.class}, priority = 1100)
public class MixinGameRenderer {
    @WrapMethod(method = "getDepthFar()F")
    public float getDepthFar(Operation<Float> original) {
        if (VoxyConfig.CONFIG.isRenderingEnabled()) {
            return Math.max(original.call(), VoxyConfig.CONFIG.sectionRenderDistance * 32F * 4F);
        }
        return original.call();
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void ip_frameStart(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        me.cortex.voxy.client.core.util.FrameCounter.frame++;
    }
}
