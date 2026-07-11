package me.cortex.voxy.client.core.rendering;

import me.cortex.voxy.client.core.util.FrameCounter;
import me.cortex.voxy.client.core.util.ImmersivePortalsCompat;
import me.cortex.voxy.client.core.util.IrisUtil;
import net.fabricmc.loader.api.FabricLoader;
import org.vivecraft.api.client.VRRenderingAPI;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import static org.vivecraft.api.client.data.RenderPass.VANILLA;

public class ViewportSelector <T extends Viewport<?>> {
    public static final boolean VIVECRAFT_INSTALLED = FabricLoader.getInstance().isModLoaded("vivecraft");

    /**
     * Frames after which an unused extra viewport (portal view, VR pass,
     * iris shadow pass) gets deleted. Mirrors imm_ptl's SodiumContextStore
     * eviction age, so a portal-view viewport sticks around exactly as long
     * as imm_ptl keeps its matching SodiumRenderingContext alive.
     */
    private static final int EVICTION_AGE_FRAMES = 240;

    private final Supplier<T> creator;
    private final T defaultViewport;
    private final Map<Object, Entry> extraViewports = new HashMap<>();

    private int lastEvictionFrame = -1;

    private final class Entry {
        final T viewport;
        int lastUsedFrame;

        Entry(T viewport) {
            this.viewport = viewport;
            this.lastUsedFrame = FrameCounter.frame;
        }
    }

    public ViewportSelector(Supplier<T> viewportCreator) {
        this.creator = viewportCreator;
        this.defaultViewport = viewportCreator.get();
    }

    private T getOrCreate(Object holder) {
        evictUnused();
        Entry entry = this.extraViewports.get(holder);
        if (entry == null) {
            entry = new Entry(this.creator.get());
            this.extraViewports.put(holder, entry);
        } else {
            entry.lastUsedFrame = FrameCounter.frame;
        }
        return entry.viewport;
    }

    private void evictUnused() {
        int currentFrame = FrameCounter.frame;
        if (currentFrame == this.lastEvictionFrame) {
            return;
        }
        this.lastEvictionFrame = currentFrame;

        this.extraViewports.entrySet().removeIf(e -> {
            boolean stale = currentFrame - e.getValue().lastUsedFrame > EVICTION_AGE_FRAMES
                    || currentFrame < e.getValue().lastUsedFrame; // frame counter reset
            if (stale) {
                e.getValue().viewport.delete();
            }
            return stale;
        });
    }

    private T getVivecraftViewport() {
        var pass = VRRenderingAPI.instance().getCurrentRenderPass();
        if (pass == null || pass == VANILLA) {
            return null;
        }
        return this.getOrCreate(pass);
    }

    private static final Object IRIS_SHADOW_OBJECT = new Object();
    public T getViewport() {
        T viewport = null;
        if (viewport == null && VIVECRAFT_INSTALLED) {
            viewport = getVivecraftViewport();
        }

        if (viewport == null && IrisUtil.irisShadowActive()) {
            viewport = this.getOrCreate(IRIS_SHADOW_OBJECT);
        }

        if (viewport == null) {
            // Immersive Portals reuses the same LevelRenderer/VoxyRenderSystem
            // instance to render a portal's nested view of the same
            // dimension, just with a different camera - within one frame,
            // this method gets called once for the main view and again for
            // each portal view. Without keying by the portal's view token,
            // the second call would hand back this.defaultViewport, whose
            // MVP/camera/GPU buffers the first call's still in-flight GPU
            // work may still be reading, corrupting both views' geometry.
            Object portalViewToken = ImmersivePortalsCompat.getCurrentPortalViewToken();
            viewport = (portalViewToken != null)
                    ? this.getOrCreate(portalViewToken)
                    : this.defaultViewport;
        }
        return viewport;
    }

    public void free() {
        this.defaultViewport.delete();
        this.extraViewports.values().forEach(e -> e.viewport.delete());
        this.extraViewports.clear();
    }
}
