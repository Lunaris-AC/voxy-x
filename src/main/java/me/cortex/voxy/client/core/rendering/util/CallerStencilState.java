package me.cortex.voxy.client.core.rendering.util;

import static org.lwjgl.opengl.GL11C.*;

/**
 * Snapshot of the caller's stencil context state, captured when
 * VoxyRenderSystem.renderOpaque is entered and re-applied wherever voxy
 * draws into the caller's framebuffer (the final composite blit) and when
 * handing GL state back to the caller.
 *
 * Stencil func/op/mask are global context state - only the stencil BUFFER
 * belongs to the framebuffer. Immersive Portals clips every draw of a
 * portal view to the portal's screen shape via GL_EQUAL stencil testing on
 * the main framebuffer. Unconditionally disabling the stencil test (what
 * this code did before, see the old comment in transformBlitDepth admitting
 * the composite becomes "corrupted" when the destination has a stencil
 * attachment) made voxy's fullscreen composite - and everything the caller
 * drew after voxy within the same view - cover the whole screen with the
 * portal camera's terrain instead of staying inside the portal shape.
 *
 * Without Immersive Portals (or outside portal views) the captured state is
 * simply "stencil disabled", making apply() equivalent to the old behavior.
 */
public final class CallerStencilState {
    private boolean enabled;
    private int func;
    private int ref;
    private int valueMask;
    private int writeMask;
    private int fail;
    private int zfail;
    private int zpass;

    public void capture() {
        this.enabled = glIsEnabled(GL_STENCIL_TEST);
        this.func = glGetInteger(GL_STENCIL_FUNC);
        this.ref = glGetInteger(GL_STENCIL_REF);
        this.valueMask = glGetInteger(GL_STENCIL_VALUE_MASK);
        this.writeMask = glGetInteger(GL_STENCIL_WRITEMASK);
        this.fail = glGetInteger(GL_STENCIL_FAIL);
        this.zfail = glGetInteger(GL_STENCIL_PASS_DEPTH_FAIL);
        this.zpass = glGetInteger(GL_STENCIL_PASS_DEPTH_PASS);
    }

    public void apply() {
        if (this.enabled) {
            glEnable(GL_STENCIL_TEST);
        } else {
            glDisable(GL_STENCIL_TEST);
        }
        glStencilFunc(this.func, this.ref, this.valueMask);
        glStencilOp(this.fail, this.zfail, this.zpass);
        glStencilMask(this.writeMask);
    }

    @Override
    public String toString() {
        return String.format("stencil[enabled=%s func=0x%X ref=%d valueMask=0x%X writeMask=0x%X]",
            this.enabled, this.func, this.ref, this.valueMask, this.writeMask);
    }

    // Single instance is enough: renderOpaque captures at entry and every
    // consumer (composite blit, state handback) runs before the next
    // renderOpaque call, including nested portal views which each get their
    // own renderOpaque invocation and thus their own capture.
    public static final CallerStencilState INSTANCE = new CallerStencilState();
}
