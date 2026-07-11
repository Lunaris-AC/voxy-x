package me.cortex.voxy.client.core.rendering.section.backend.meshlet;

import me.cortex.voxy.client.RenderStatistics;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlVertexArray;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.model.ModelStore;
import me.cortex.voxy.client.core.rendering.section.backend.AbstractSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICViewport;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicSectionGeometryData;
import me.cortex.voxy.client.core.rendering.section.IUsesMeshlets;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.client.core.rendering.util.LightMapHelper;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.client.core.util.GPUTiming;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.lwjgl.opengl.NVMeshShader;
import org.lwjgl.system.MemoryUtil;

import java.util.List;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL30.glBindBufferBase;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL31.GL_UNIFORM_BUFFER;
import static org.lwjgl.opengl.GL33.glBindSampler;
import static org.lwjgl.opengl.GL40C.GL_DRAW_INDIRECT_BUFFER;
import static org.lwjgl.opengl.GL40C.glDrawElementsIndirect;
import static org.lwjgl.opengl.GL42.glMemoryBarrier;
import static org.lwjgl.opengl.GL43.*;
import static org.lwjgl.opengl.GL45.glBindTextureUnit;
import static org.lwjgl.opengl.NVRepresentativeFragmentTest.GL_REPRESENTATIVE_FRAGMENT_TEST_NV;

public class MeshShaderSectionRenderer extends AbstractSectionRenderer<MDICViewport, BasicSectionGeometryData> implements IUsesMeshlets {

    public static final Factory<MDICViewport, BasicSectionGeometryData> FACTORY = AbstractSectionRenderer.Factory.create(MeshShaderSectionRenderer.class);

    public static final int OPAQUE_DRAW_COUNT = 400_000;
    public static final int TRANSLUCENT_DRAW_COUNT = 100_000;
    public static final int TEMPORAL_DRAW_COUNT = 100_000;

    public static final int TRANSLUCENT_OFFSET = OPAQUE_DRAW_COUNT;
    public static final int TEMPORAL_OFFSET = TRANSLUCENT_OFFSET + TRANSLUCENT_DRAW_COUNT;
    public static final int TRANSLUCENT_WRITE_BASE = 1024;
    private static final int STATISTICS_BUFFER_BINDING = 8;

    private final Shader prepShader;
    private final Shader cullShader;
    private final Shader commandGenShader;
    private final Shader prefixSumShader;
    private final Shader translucentGenShader;

    private final Shader terrainShader;
    private final Shader translucentTerrainShader;
    private final Shader meshPrepShader;
    private final GlBuffer meshTasksIndirectBuffer;

    private final GlBuffer uniform = new GlBuffer(1024).zero();
    private final GlBuffer distanceCountBuffer = new GlBuffer(1024*4+TRANSLUCENT_DRAW_COUNT*4).zero();
    private final GlBuffer statisticsBuffer = new GlBuffer(1024).zero();

    private final AbstractRenderPipeline pipeline;

    public MeshShaderSectionRenderer(AbstractRenderPipeline pipeline, ModelStore modelStore, BasicSectionGeometryData geometryData) {
        super(modelStore, geometryData);
        this.pipeline = pipeline;

        // Compile Command Generation, Prep, Culling, PrefixSum, and TranslucentGen Compute shaders
        this.prepShader = Shader.make()
                .add(ShaderType.COMPUTE, "voxy:lod/gl46/prep.comp")
                .compile();

        if (this.pipeline.hasTAA()) {
            this.cullShader = Shader.make()
                    .addSource(ShaderType.VERTEX, ShaderLoader.parse("voxy:lod/gl46/cull/raster.vert") + "\n\n\n\n" + pipeline.taaFunction("getTAA"))
                    .define("TAA")
                    .add(ShaderType.FRAGMENT, "voxy:lod/gl46/cull/raster.frag")
                    .compile();
        } else {
            this.cullShader = Shader.make()
                    .add(ShaderType.VERTEX, "voxy:lod/gl46/cull/raster.vert")
                    .add(ShaderType.FRAGMENT, "voxy:lod/gl46/cull/raster.frag")
                    .compile();
        }

        this.commandGenShader = Shader.make()
                .define("TRANSLUCENT_WRITE_BASE", 1024)
                .define("TEMPORAL_OFFSET", TEMPORAL_OFFSET)
                .define("TRANSLUCENT_DISTANCE_BUFFER_BINDING", 7)
                .defineIf("HAS_STATISTICS", RenderStatistics.enabled)
                .defineIf("STATISTICS_BUFFER_BINDING", RenderStatistics.enabled, STATISTICS_BUFFER_BINDING)
                .add(ShaderType.COMPUTE, "voxy:lod/gl46/cmdgen.comp")
                .compile();

        this.prefixSumShader = Shader.make()
                .add(ShaderType.COMPUTE, Capabilities.INSTANCE.subgroup ? "voxy:util/prefixsum/inital3.comp" : "voxy:util/prefixsum/simple.comp")
                .define("IO_BUFFER", 0)
                .compile();

        this.translucentGenShader = Shader.make()
                .add(ShaderType.COMPUTE, "voxy:lod/gl46/buildtranslucents.comp")
                .define("TRANSLUCENT_WRITE_BASE", 1024)
                .define("TRANSLUCENT_DISTANCE_BUFFER_BINDING", 5)
                .define("TRANSLUCENT_OFFSET", TRANSLUCENT_OFFSET)
                .compile();

        this.meshPrepShader = Shader.make()
                .add(ShaderType.COMPUTE, "voxy:lod/gl46/meshlet/meshprep.comp")
                .compile();

        this.meshTasksIndirectBuffer = new GlBuffer(3 * 8).zero();

        // Compile Task + Mesh + Fragment terrain shaders
        String taskSource = ShaderLoader.parse("voxy:lod/gl46/meshlet/task.glsl");
        String meshSource = ShaderLoader.parse("voxy:lod/gl46/meshlet/mesh.glsl");
        String taskFusedSource = ShaderLoader.parse("voxy:lod/gl46/meshlet/task_fused.glsl");
        String meshFusedSource = ShaderLoader.parse("voxy:lod/gl46/meshlet/mesh_fused.glsl");
        String taa = pipeline.taaFunction("taaShift");
        String meshSourceWithTaa = meshSource;
        String meshFusedSourceWithTaa = meshFusedSource;
        if (taa != null) {
            meshSourceWithTaa += "\n" + taa;
            meshFusedSourceWithTaa += "\n" + taa;
        }

        var opaqueBuilder = Shader.make()
                .defineIf("TAA_PATCH", taa != null)
                .defineIf("DEBUG_RENDER", false)
                .addSource(ShaderType.TASK, taskFusedSource)
                .addSource(ShaderType.MESH, meshFusedSourceWithTaa);

        var translucentBuilder = Shader.make()
                .defineIf("TAA_PATCH", taa != null)
                .defineIf("DEBUG_RENDER", false)
                .addSource(ShaderType.TASK, taskSource)
                .addSource(ShaderType.MESH, meshSourceWithTaa);

        addDirectionalFaceTint(opaqueBuilder, Minecraft.getInstance().level);
        addDirectionalFaceTint(translucentBuilder, Minecraft.getInstance().level);

        String frag = ShaderLoader.parse("voxy:lod/gl46/quads.frag");
        frag = frag.replace("\r\n", "\n");
        String targetInput = "layout(location = 0) in flat uvec4 interData;\n" +
                             "#ifndef USE_NV_BARRY\n" +
                             "layout(location = 1) in vec2 uv;\n" +
                             "#endif";
        String blockInput = "layout(location = 0) in VertexOut {\n" +
                            "    flat uvec4 interData;\n" +
                            "#ifndef USE_NV_BARRY\n" +
                            "    vec2 uv;\n" +
                            "#endif\n" +
                            "};";
        frag = frag.replace(targetInput, blockInput);

        String opaqueFrag = pipeline.patchOpaqueShader(this, frag);
        opaqueFrag = opaqueFrag == null ? frag : opaqueFrag;
        this.terrainShader = tryCompilePatchedOrNormal(opaqueBuilder, opaqueFrag, frag);

        String translucentFrag = pipeline.patchTranslucentShader(this, frag);
        translucentFrag = translucentFrag == null ? frag : translucentFrag;
        this.translucentTerrainShader = tryCompilePatchedOrNormal(translucentBuilder.define("TRANSLUCENT"), translucentFrag, frag);
    }

    private void uploadUniformBuffer(MDICViewport viewport) {
        long ptr = UploadStream.INSTANCE.upload(this.uniform, 0, 1024);

        var mat = new Matrix4f(viewport.MVP);
        mat.translate(-viewport.innerTranslation.x, -viewport.innerTranslation.y, -viewport.innerTranslation.z);
        mat.getToAddress(ptr); ptr += 4*4*4;

        viewport.section.getToAddress(ptr); ptr += 4*3;

        if (viewport.frameId < 0) {
            Logger.error("Frame ID negative, wrapping around");
            viewport.frameId &= 0x7fffffff;
        }
        MemoryUtil.memPutInt(ptr, viewport.frameId & 0x7fffffff); ptr += 4;
        viewport.innerTranslation.getToAddress(ptr); ptr += 4*3;

        UploadStream.INSTANCE.commit();
    }

    private void bindRenderingBuffers(MDICViewport viewport) {
        glBindBufferBase(GL_UNIFORM_BUFFER, 0, this.uniform.id);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, this.geometryManager.getGeometryBuffer().id);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, this.geometryManager.getMetadataBuffer().id);
        this.modelStore.bind(3, 4, 0);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 5, viewport.positionScratchBuffer.id);

        // Bind new SSBOs required by Task/Mesh shaders
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 6, viewport.indirectLookupBuffer.id);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 7, viewport.drawCallBuffer.id);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 8, viewport.drawCountCallBuffer.id);

        LightMapHelper.bind(1);
        glBindTextureUnit(2, viewport.depthBoundingBuffer.getDepthTex().id);
    }

    private void renderTerrain(MDICViewport viewport, long indirectOffset, long drawCountOffset, int maxDrawCount, int drawType) {
        glDisable(GL_CULL_FACE);
        glDisable(GL_BLEND);
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        this.terrainShader.bind();

        // Bind location uniforms to notify task shader of command offsets
        glUniform1ui(0, (int) (indirectOffset / 20));
        glUniform1ui(1, drawType);

        glBindVertexArray(GlVertexArray.STATIC_VAO);
        this.pipeline.setupAndBindOpaque(viewport);
        this.bindRenderingBuffers(viewport);

        glMemoryBarrier(GL_COMMAND_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT);
        glProvokingVertex(GL_FIRST_VERTEX_CONVENTION);

        if (VoxyClient.getOcclusionDebugState() == 3) {
            glPolygonMode(GL_FRONT_AND_BACK, GL_LINE);
        }

        // Call glDrawMeshTasksIndirectNV instead of glDrawMeshTasksNV!
        long meshIndirectOffset = 0;
        if (drawType == 1) {
            meshIndirectOffset = 8;
        } else if (drawType == 2) {
            meshIndirectOffset = 16;
        }
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, this.meshTasksIndirectBuffer.id);
        NVMeshShader.glDrawMeshTasksIndirectNV(meshIndirectOffset);
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, 0);

        if (VoxyClient.getOcclusionDebugState() == 3) {
            glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
        }

        glEnable(GL_CULL_FACE);
        glBindVertexArray(0);
        glBindSampler(0, 0);
        glBindTextureUnit(0, 0);
        glBindSampler(1, 0);
        glBindTextureUnit(1, 0);
    }

    @Override
    public void renderOpaque(MDICViewport viewport) {
        if (this.geometryManager.getSectionCount() == 0) return;

        this.uploadUniformBuffer(viewport);
        this.renderTerrain(viewport, 0, 4*3, OPAQUE_DRAW_COUNT, 0);
    }

    @Override
    public void renderTranslucent(MDICViewport viewport) {
        if (this.geometryManager.getSectionCount() == 0) return;

        glEnable(GL_BLEND);
        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);

        glDisable(GL_CULL_FACE);
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        this.translucentTerrainShader.bind();

        glUniform1ui(0, TRANSLUCENT_OFFSET);
        glUniform1ui(1, 1); // 1 = Translucent

        glBindVertexArray(GlVertexArray.STATIC_VAO);
        this.pipeline.setupAndBindTranslucent(viewport);
        this.bindRenderingBuffers(viewport);

        glMemoryBarrier(GL_COMMAND_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT);
        glProvokingVertex(GL_FIRST_VERTEX_CONVENTION);

        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, this.meshTasksIndirectBuffer.id);
        NVMeshShader.glDrawMeshTasksIndirectNV(8); // Offset 8 for Translucent
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, 0);

        glEnable(GL_CULL_FACE);
        glBindVertexArray(0);
        glBindSampler(0, 0);
        glBindTextureUnit(0, 0);
        glBindSampler(1, 0);
        glBindTextureUnit(1, 0);

        glDisable(GL_BLEND);
    }

    @Override
    public void renderTemporal(MDICViewport viewport) {
        // this.terrainShader is always compiled from the "fused" task/mesh
        // source (task_fused.glsl/mesh_fused.glsl), which reads the full
        // section list straight from indirectLookupBuffer without ever
        // consulting commandOffset/drawType (unlike the non-fused task.glsl
        // used for translucency, which explicitly uses them to select a
        // subset). Since the fused shader doesn't distinguish "temporal"
        // sections from the rest, calling renderTerrain again here redrew
        // the exact same terrain renderOpaque() already drew this frame -
        // doubling the mesh-shader dispatch cost and producing visual
        // corruption from the terrain being drawn twice with a drawType
        // the shader can't actually act on. renderOpaque() already covers
        // everything the fused pipeline needs in one pass.
    }

    /**
     * CPU wall time (ns) of the last buildDrawCalls call's sections, for
     * the renderOpaque spike log (a CPU stall in here means a GL call is
     * blocking on the driver, e.g. an implicit sync on a busy buffer).
     * Indices: 0 uniformUpload, 1 prepDispatch, 2 occlusionTest,
     * 3 cmdGen, 4 translucencySort, 5 meshPrep.
     */
    public static final long[] BUILD_SPIKE_NS = new long[6];

    @Override
    public void buildDrawCalls(MDICViewport viewport) {
        if (this.geometryManager.getSectionCount() == 0) return;
        long bt0 = System.nanoTime();
        this.uploadUniformBuffer(viewport);
        long bt1 = System.nanoTime();
        BUILD_SPIKE_NS[0] = bt1 - bt0;

        {// Dispatch prep
            this.prepShader.bind();
            glBindBufferBase(GL_UNIFORM_BUFFER, 0, this.uniform.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, viewport.drawCountCallBuffer.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, viewport.getRenderList().id);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
            glDispatchCompute(1, 1, 1);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        }
        BUILD_SPIKE_NS[1] = System.nanoTime() - bt1;
        bt1 = System.nanoTime();

        GPUTiming.INSTANCE.marker("OT");
        {// Test occlusion
            this.cullShader.bind();
            if (this.pipeline.hasTAA()) this.pipeline.bindUniforms();
            if (Capabilities.INSTANCE.repFragTest) {
                glEnable(GL_REPRESENTATIVE_FRAGMENT_TEST_NV);
            }
            glBindVertexArray(GlVertexArray.STATIC_VAO);
            glBindBufferBase(GL_UNIFORM_BUFFER, 0, this.uniform.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, this.geometryManager.getMetadataBuffer().id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, viewport.visibilityBuffer.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, viewport.indirectLookupBuffer.id);
            glBindBuffer(GL_DRAW_INDIRECT_BUFFER, viewport.drawCountCallBuffer.id);
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer.INSTANCE.id());
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(GL_LEQUAL);
            glColorMask(false, false, false, false);
            glDepthMask(false);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_COMMAND_BARRIER_BIT);
            glDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_BYTE, 6*4);
            glDepthMask(true);
            glColorMask(true, true, true, true);
            glDisable(GL_DEPTH_TEST);
            if (Capabilities.INSTANCE.repFragTest) {
                glDisable(GL_REPRESENTATIVE_FRAGMENT_TEST_NV);
            }
        }
        BUILD_SPIKE_NS[2] = System.nanoTime() - bt1;
        bt1 = System.nanoTime();

        GPUTiming.INSTANCE.marker("CG");
        {// Generate the commands
            this.distanceCountBuffer.zeroRange(0, 1024*4);
            this.commandGenShader.bind();
            glBindBufferBase(GL_UNIFORM_BUFFER, 0, this.uniform.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, viewport.drawCallBuffer.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, viewport.drawCountCallBuffer.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, this.geometryManager.getMetadataBuffer().id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 4, viewport.visibilityBuffer.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 5, viewport.indirectLookupBuffer.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 6, viewport.positionScratchBuffer.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 7, this.distanceCountBuffer.id);

            if (RenderStatistics.enabled) {
                this.statisticsBuffer.zero();
                glBindBufferBase(GL_SHADER_STORAGE_BUFFER, STATISTICS_BUFFER_BINDING, this.statisticsBuffer.id);
            }

            glBindBuffer(GL_DISPATCH_INDIRECT_BUFFER, viewport.drawCountCallBuffer.id);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
            glDispatchComputeIndirect(0);
            glMemoryBarrier(GL_COMMAND_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT);

            if (RenderStatistics.enabled) {
                DownloadStream.INSTANCE.download(this.statisticsBuffer, down -> {
                    final int LAYERS = WorldEngine.MAX_LOD_LAYER+1;
                    for (int i = 0; i < LAYERS; i++) {
                        RenderStatistics.visibleSections[i] = MemoryUtil.memGetInt(down.address+i*4L);
                    }
                    for (int i = 0; i < LAYERS; i++) {
                        RenderStatistics.quadCount[i] = MemoryUtil.memGetInt(down.address+LAYERS*4L+i*4L);
                    }
                });
            }
        }
        BUILD_SPIKE_NS[3] = System.nanoTime() - bt1;
        bt1 = System.nanoTime();

        GPUTiming.INSTANCE.marker("TS");
        {// Do translucency sorting
            this.prefixSumShader.bind();
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, this.distanceCountBuffer.id);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
            glDispatchCompute(1, 1, 1);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);

            this.translucentGenShader.bind();
            glBindBufferBase(GL_UNIFORM_BUFFER, 0, this.uniform.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, viewport.drawCallBuffer.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, viewport.drawCountCallBuffer.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, this.geometryManager.getMetadataBuffer().id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 4, viewport.indirectLookupBuffer.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 5, this.distanceCountBuffer.id);

            glBindBuffer(GL_DISPATCH_INDIRECT_BUFFER, viewport.drawCountCallBuffer.id);
            glMemoryBarrier(GL_COMMAND_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT | GL_UNIFORM_BARRIER_BIT);
            glDispatchComputeIndirect(0);
            glMemoryBarrier(GL_COMMAND_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT);
        }
        BUILD_SPIKE_NS[4] = System.nanoTime() - bt1;
        bt1 = System.nanoTime();

        GPUTiming.INSTANCE.marker("MP");
        {// Dispatch mesh prep
            this.meshPrepShader.bind();
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, viewport.drawCountCallBuffer.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, this.meshTasksIndirectBuffer.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, viewport.getRenderList().id);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
            glDispatchCompute(1, 1, 1);
            glMemoryBarrier(GL_COMMAND_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT);
        }
        BUILD_SPIKE_NS[5] = System.nanoTime() - bt1;
    }

    @Override
    public void addDebug(List<String> lines) {
        super.addDebug(lines);
    }

    @Override
    public MDICViewport createViewport() {
        return new MDICViewport(this.geometryManager.getMaxSectionCount());
    }

    @Override
    public void free() {
        this.uniform.free();
        this.distanceCountBuffer.free();
        this.translucentTerrainShader.free();
        this.terrainShader.free();
        this.commandGenShader.free();
        this.cullShader.free();
        this.prepShader.free();
        this.translucentGenShader.free();
        this.prefixSumShader.free();
        this.statisticsBuffer.free();
        this.meshPrepShader.free();
        this.meshTasksIndirectBuffer.free();
    }
}
