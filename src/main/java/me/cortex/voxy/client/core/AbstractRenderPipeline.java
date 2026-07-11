package me.cortex.voxy.client.core;

import me.cortex.voxy.client.RenderStatistics;
import me.cortex.voxy.client.TimingStatistics;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.rendering.post.FullscreenBlit;
import me.cortex.voxy.client.core.rendering.section.backend.AbstractSectionRenderer;
import me.cortex.voxy.client.core.rendering.util.DepthFramebuffer;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.client.core.util.GPUTiming;
import me.cortex.voxy.common.util.TrackedObject;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.util.List;
import java.util.function.BooleanSupplier;

import static org.lwjgl.opengl.GL11C.GL_ALWAYS;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11C.GL_EQUAL;
import static org.lwjgl.opengl.GL11C.GL_KEEP;
import static org.lwjgl.opengl.GL11C.GL_REPLACE;
import static org.lwjgl.opengl.GL11C.GL_STENCIL_TEST;
import static org.lwjgl.opengl.GL11C.glColorMask;
import static org.lwjgl.opengl.GL11C.glDisable;
import static org.lwjgl.opengl.GL11C.glEnable;
import static org.lwjgl.opengl.GL11C.glStencilFunc;
import static org.lwjgl.opengl.GL11C.glStencilMask;
import static org.lwjgl.opengl.GL11C.glStencilOp;
import static org.lwjgl.opengl.GL30C.GL_DEPTH24_STENCIL8;
import static org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.glBindFramebuffer;
import static org.lwjgl.opengl.GL42.GL_LEQUAL;
import static org.lwjgl.opengl.GL42.GL_NOTEQUAL;
import static org.lwjgl.opengl.GL42.glDepthFunc;
import static org.lwjgl.opengl.GL42.*;
import static org.lwjgl.opengl.GL45.glClearNamedFramebufferfi;
import static org.lwjgl.opengl.GL45.glGetNamedFramebufferAttachmentParameteri;
import static org.lwjgl.opengl.GL45C.glBindTextureUnit;

public abstract class AbstractRenderPipeline extends TrackedObject {
    private final BooleanSupplier frexStillHasWork;

    private final AsyncNodeManager nodeManager;
    private final NodeCleaner nodeCleaner;
    private final HierarchicalOcclusionTraverser traversal;

    protected AbstractSectionRenderer<?,?> sectionRenderer;

    private final FullscreenBlit depthStencilSetup = new FullscreenBlit("voxy:post/fullscreen2.vert", "voxy:post/setup_stencil_depth.frag");

    public final DepthFramebuffer fb = new DepthFramebuffer(GL_DEPTH24_STENCIL8);

    protected final boolean deferTranslucency;

    private static final int DEPTH_SAMPLER = glGenSamplers();
    static {
        glSamplerParameteri(DEPTH_SAMPLER, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glSamplerParameteri(DEPTH_SAMPLER, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    }

    protected AbstractRenderPipeline(AsyncNodeManager nodeManager, NodeCleaner nodeCleaner, HierarchicalOcclusionTraverser traversal, BooleanSupplier frexSupplier, boolean deferTranslucency) {
        this.frexStillHasWork = frexSupplier;
        this.nodeManager = nodeManager;
        this.nodeCleaner = nodeCleaner;
        this.traversal = traversal;
        this.deferTranslucency = deferTranslucency;
    }

    //Allows pipelines to configure model baking system
    public void setupExtraModelBakeryData(ModelBakerySubsystem modelService) {}

    public final void setSectionRenderer(AbstractSectionRenderer<?,?> sectionRenderer) {//Stupid java ordering not allowing something pre super
        if (this.sectionRenderer != null) throw new IllegalStateException();
        this.sectionRenderer = sectionRenderer;
    }

    //Called before the pipeline starts running, used to update uniforms etc
    public void preSetup(Viewport<?> viewport) {

    }

    protected abstract int setup(Viewport<?> viewport, int sourceFramebuffer, int srcWidth, int srcHeight);
    protected abstract void postOpaquePreTranslucent(Viewport<?> viewport, int sourceFrameBuffer);
    protected void finish(Viewport<?> viewport, int sourceFrameBuffer, int srcWidth, int srcHeight) {
        glDisable(GL_STENCIL_TEST);
        glBindFramebuffer(GL_FRAMEBUFFER, sourceFrameBuffer);
    }

    /**
     * CPU wall time (ns) of the last runPipeline call's stages, for the
     * renderOpaque spike log. Render thread only. Indices:
     * 0 setup, 1 renderOpaque, 2 innerPrimaryWork total, 3 buildDrawCalls,
     * 4 temporal+postOpaquePrep, 5 postOpaquePreTranslucent(ssao),
     * 6 translucent, 7 finish, then innerPrimaryWork detail:
     * 8 hiZ, 9 downloadTick, 10 nodeMgrTick, 11 nodeCleanTick, 12 traversal.
     */
    public static final long[] SPIKE_STAGE_NS = new long[13];

    public void runPipeline(Viewport<?> viewport, int sourceFrameBuffer, int srcWidth, int srcHeight) {
        long t0 = System.nanoTime();
        int depthTexture = this.setup(viewport, sourceFrameBuffer, srcWidth, srcHeight);
        long t1 = System.nanoTime();

        var rs = ((AbstractSectionRenderer)this.sectionRenderer);
        GPUTiming.INSTANCE.marker("RO");
        rs.renderOpaque(viewport);
        long t2 = System.nanoTime();
        var occlusionDebug = VoxyClient.getOcclusionDebugState();
        if (occlusionDebug==0) {
            GPUTiming.INSTANCE.marker("I");
            this.innerPrimaryWork(viewport, depthTexture);
            GPUTiming.INSTANCE.marker();
        }
        long t3 = System.nanoTime();

        if (occlusionDebug<=1) {
            TimingStatistics.G.start();
            rs.buildDrawCalls(viewport);
            TimingStatistics.G.stop();
        }
        long t4 = System.nanoTime();

        GPUTiming.INSTANCE.marker("TP");
        rs.renderTemporal(viewport);

        rs.postOpaquePreperation(viewport);
        long t5 = System.nanoTime();

        this.postOpaquePreTranslucent(viewport, sourceFrameBuffer);
        GPUTiming.INSTANCE.marker("RT");
        long t6 = System.nanoTime();

        if (!this.deferTranslucency) {
            rs.renderTranslucent(viewport);
        }
        GPUTiming.INSTANCE.marker();
        long t7 = System.nanoTime();

        this.finish(viewport, sourceFrameBuffer, srcWidth, srcHeight);
        glBindFramebuffer(GL_FRAMEBUFFER, sourceFrameBuffer);
        long t8 = System.nanoTime();

        SPIKE_STAGE_NS[0] = t1 - t0;
        SPIKE_STAGE_NS[1] = t2 - t1;
        SPIKE_STAGE_NS[2] = t3 - t2;
        SPIKE_STAGE_NS[3] = t4 - t3;
        SPIKE_STAGE_NS[4] = t5 - t4;
        SPIKE_STAGE_NS[5] = t6 - t5;
        SPIKE_STAGE_NS[6] = t7 - t6;
        SPIKE_STAGE_NS[7] = t8 - t7;
    }

    protected void initDepthStencil(int sourceFrameBuffer, int targetFb, int srcWidth, int srcHeight, int width, int height) {
        glClearNamedFramebufferfi(targetFb, GL_DEPTH_STENCIL, 0, 1.0f, 1);
        // using blit to copy depth from mismatched depth formats is not portable so instead a full screen pass is performed for a depth copy
        // the mismatched formats in this case is the d32 to d24s8
        glBindFramebuffer(GL30.GL_FRAMEBUFFER, targetFb);

        //If pixel passes, update stencil to 0 and set depth to 0
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_ALWAYS);

        glEnable(GL_STENCIL_TEST);
        glStencilOp(GL_KEEP, GL_KEEP, GL_REPLACE);
        glStencilFunc(GL_ALWAYS, 0, 0xFF);
        glStencilMask(0xFF);


        this.depthStencilSetup.bind();
        int depthTexture = glGetNamedFramebufferAttachmentParameteri(sourceFrameBuffer, GL_DEPTH_ATTACHMENT, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        glBindTextureUnit(0, depthTexture);
        glBindSampler(0, DEPTH_SAMPLER);
        glUniform2f(1,((float)width)/srcWidth, ((float)height)/srcHeight);
        glDepthMask(true);
        glColorMask(false,false,false,false);
        this.depthStencilSetup.blit();


        glDepthFunc(GL_LEQUAL);
        glColorMask(true,true,true,true);

        //Make voxy terrain render only where there isnt mc terrain
        glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
        glStencilFunc(GL_EQUAL, 1, 0xFF);
    }

    private static final long SCRATCH = MemoryUtil.nmemAlloc(4*4*4);
    protected static void transformBlitDepth(FullscreenBlit blitShader, int srcDepthTex, int dstFB, Viewport<?> viewport, Matrix4f targetTransform) {
        // The composite must run under the CALLER's stencil state, not with
        // the test force-disabled: when Immersive Portals renders a portal
        // view, dstFB is the main framebuffer with an active GL_EQUAL
        // stencil clip restricting all drawing to the portal's screen shape.
        // Disabling the test here (the old behavior, whose comment admitted
        // the pass becomes "corrupted" with a stencil attachment present)
        // wrote this fullscreen color+depth composite across the entire
        // screen, replacing the outer view with the portal camera's terrain.
        // Without a stencil-using caller the captured state is "disabled"
        // and this is identical to the old code.
        me.cortex.voxy.client.core.rendering.util.CallerStencilState.INSTANCE.apply();
        glBindFramebuffer(GL30.GL_FRAMEBUFFER, dstFB);

        blitShader.bind();
        glBindTextureUnit(0, srcDepthTex);
        new Matrix4f(viewport.MVP).invert().getToAddress(SCRATCH);
        nglUniformMatrix4fv(1, 1, false, SCRATCH);//inverse fromProjection
        targetTransform.getToAddress(SCRATCH);//new Matrix4f(tooProjection).mul(vp.modelView).get(data);
        nglUniformMatrix4fv(2, 1, false, SCRATCH);//tooProjection

        glEnable(GL_DEPTH_TEST);
        blitShader.blit();
        glDisable(GL_STENCIL_TEST);
        glDisable(GL_DEPTH_TEST);
    }

    protected void innerPrimaryWork(Viewport<?> viewport, int depthBuffer) {

        long t0 = System.nanoTime();
        //Compute the mip chain
        viewport.hiZBuffer.buildMipChain(depthBuffer, viewport.width, viewport.height);
        long t1 = System.nanoTime();
        SPIKE_STAGE_NS[8] = t1 - t0;
        SPIKE_STAGE_NS[9] = 0;
        SPIKE_STAGE_NS[10] = 0;
        SPIKE_STAGE_NS[11] = 0;
        SPIKE_STAGE_NS[12] = 0;

        do {
            TimingStatistics.main.stop();
            TimingStatistics.dynamic.start();

            TimingStatistics.D.start();
            //Tick download stream
            DownloadStream.INSTANCE.tick();
            TimingStatistics.D.stop();
            long t2 = System.nanoTime();
            SPIKE_STAGE_NS[9] += t2 - t1;

            this.nodeManager.tick(this.traversal.getNodeBuffer(), this.nodeCleaner);
            //glFlush();
            long t3 = System.nanoTime();
            SPIKE_STAGE_NS[10] += t3 - t2;

            this.nodeCleaner.tick(this.traversal.getNodeBuffer());//Probably do this here??
            long t4 = System.nanoTime();
            SPIKE_STAGE_NS[11] += t4 - t3;

            TimingStatistics.dynamic.stop();
            TimingStatistics.main.start();

            glMemoryBarrier(GL_FRAMEBUFFER_BARRIER_BIT | GL_PIXEL_BUFFER_BARRIER_BIT);

            TimingStatistics.F.start();
            this.traversal.doTraversal(viewport);
            TimingStatistics.F.stop();
            t1 = System.nanoTime();
            SPIKE_STAGE_NS[12] += t1 - t4;
        } while (this.frexStillHasWork.getAsBoolean());
    }

    @Override
    protected void free0() {
        this.fb.free();
        this.sectionRenderer.free();
        this.depthStencilSetup.delete();
        super.free0();
    }

    public void addDebug(List<String> debug) {
        this.sectionRenderer.addDebug(debug);
        this.traversal.addDebug(debug);
        RenderStatistics.addDebug(debug);
    }

    //Binds the framebuffer and any other bindings needed for rendering
    public abstract void setupAndBindOpaque(Viewport<?> viewport);
    public abstract void setupAndBindTranslucent(Viewport<?> viewport);


    public void bindUniforms() {
        this.bindUniforms(-1);
    }

    public void bindUniforms(int index) {
    }

    public boolean hasTAA() {
        return false;
    }

    //null means no function, otherwise return the taa injection function
    public String taaFunction(String functionName) {
        return this.taaFunction(-1, functionName);
    }

    public String taaFunction(int uboBindingPoint, String functionName) {
        return null;
    }

    //null means dont transform the shader
    public String patchOpaqueShader(AbstractSectionRenderer<?,?> renderer, String input) {
        return null;
    }

    //Returning null means apply the same patch as the opaque
    public String patchTranslucentShader(AbstractSectionRenderer<?,?> renderer, String input) {
        return null;
    }

    //Null means no scaling factor
    public float[] getRenderScalingFactor() {return null;}

}
