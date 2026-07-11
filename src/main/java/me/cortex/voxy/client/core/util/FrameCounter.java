package me.cortex.voxy.client.core.util;

/**
 * Incremented exactly once per actual rendered frame (see
 * MixinGameRenderer's HEAD injection on GameRenderer.render). Used to detect
 * when VoxyRenderSystem.renderOpaque() is invoked more than once within the
 * same frame - which happens whenever a portal shows another view of the
 * same dimension Voxy is already rendering (Immersive Portals reuses the
 * same LevelRenderer/VoxyRenderSystem for same-dimension portal views), so
 * per-frame-only work (like renderDistanceTracker's LOD streaming update)
 * isn't repeated with a different camera position mid-frame.
 */
public class FrameCounter {
    public static volatile int frame = 0;
}
