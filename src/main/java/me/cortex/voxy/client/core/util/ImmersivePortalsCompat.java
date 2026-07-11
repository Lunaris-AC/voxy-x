package me.cortex.voxy.client.core.util;

import me.cortex.voxy.common.Logger;

import java.lang.reflect.Field;

/**
 * Reflective, best-effort bridge to Immersive Portals' per-portal-view
 * identity (SodiumInterface.currentPortalViewToken), without a hard compile
 * dependency (voxy runs via Sinytra Connector; imm_ptl is a native NeoForge
 * mod and may not always be present on the classpath). Resolves to a no-op
 * (always null) if the class isn't found, so voxy behaves exactly as before
 * when imm_ptl isn't installed.
 */
public class ImmersivePortalsCompat {
    private static final Field TOKEN_FIELD = resolveTokenField();

    private static Field resolveTokenField() {
        try {
            Class<?> cls = Class.forName("qouteall.imm_ptl.core.compat.sodium_compatibility.SodiumInterface");
            Field f = cls.getField("currentPortalViewToken");
            Logger.info("Immersive Portals detected - per-portal-view viewport isolation enabled");
            return f;
        } catch (Throwable t) {
            Logger.info("Immersive Portals not detected (" + t.getClass().getSimpleName() + ") - single-viewport mode");
            return null;
        }
    }

    /**
     * Identity of the portal view currently being rendered, stable across
     * frames for the same (destination dimension + rendering description)
     * portal view (see imm_ptl's SodiumContextStore), null while the outer
     * (non-portal) view renders. Null whenever imm_ptl isn't installed.
     */
    public static Object getCurrentPortalViewToken() {
        if (TOKEN_FIELD == null) {
            return null;
        }
        try {
            return TOKEN_FIELD.get(null);
        } catch (Throwable t) {
            return null;
        }
    }
}
