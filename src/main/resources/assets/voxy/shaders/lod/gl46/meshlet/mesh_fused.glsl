#version 460 core
#extension GL_NV_mesh_shader : require
#extension GL_ARB_gpu_shader_int64 : enable

#define QUAD_BUFFER_BINDING 1
#define MODEL_BUFFER_BINDING 3
#define MODEL_COLOUR_BUFFER_BINDING 4
#define LIGHTING_SAMPLER_BINDING 1

#import <voxy:lod/quad_format.glsl>
#import <voxy:lod/block_model.glsl>
#import <voxy:lod/gl46/bindings.glsl>
#import <voxy:lod/quad_util.glsl>

#define SECTION_METADATA_BUFFER_BINDING 2
#import <voxy:lod/section.glsl>

// Binding 2: Section metadata buffer
layout(binding = 2, std430) readonly restrict buffer SectionBuffer {
    SectionMeta sectionData[];
};

layout(local_size_x = 16) in;
layout(max_vertices = 64, max_primitives = 32) out;
layout(triangles) out;

#ifdef USE_SINGLE_TRI
#define USE_NV_BARRY
#endif

layout(location = 0) out VertexOut {
    flat uvec4 interData;
    #ifndef USE_NV_BARRY
    vec2 uv;
    #endif
} OUT[];

struct FaceRange {
    uint quadStart;
    uint quadCount;
};

// Task payload from the task shader
taskNV in TaskPayload {
    uint sectionId;
    uint drawId;
    FaceRange ranges[7];
    uint rangeCount;
} taskIn;

vec2 taaShift();

void main() {
    taaOffset = taaShift();

    uint localId = gl_LocalInvocationID.x;

    // Find which face range this workgroup is processing
    uint workgroupOffset = 0;
    uint targetRange = 999;
    uint localWorkgroupIndex = 0;
    for (uint i = 0; i < taskIn.rangeCount; i++) {
        uint faceWorkgroups = (taskIn.ranges[i].quadCount + 15) / 16;
        if (gl_WorkGroupID.x >= workgroupOffset && gl_WorkGroupID.x < workgroupOffset + faceWorkgroups) {
            targetRange = i;
            localWorkgroupIndex = gl_WorkGroupID.x - workgroupOffset;
            break;
        }
        workgroupOffset += faceWorkgroups;
    }

    if (targetRange == 999) {
        if (localId == 0) {
            gl_PrimitiveCountNV = 0;
        }
        return;
    }

    uint activeQuads = min(taskIn.ranges[targetRange].quadCount - localWorkgroupIndex * 16, 16);

    SectionMeta section = sectionData[taskIn.sectionId];
    uvec2 pos = extractRawPos(section);

    if (localId < activeQuads) {
        uint quadIdx = taskIn.ranges[targetRange].quadStart + localWorkgroupIndex * 16 + localId;
        Quad q = quadData[quadIdx];

        QuadData quad;
        setupQuad(quad, q, pos, false);

        uint face = extractFace(q);
        uint modelId = extractStateId(q);
        BlockModel model = modelData[modelId];
        uint faceData = model.faceData[face];
        ivec2 quadSize = extractSize(q);
        uint lodLevel = getLoDLevel(pos);

        uvec4 attributeData;
        attributeData.x = makeQuadFlags(faceData, modelId, quadSize, model, face);
        attributeData.yzw = makeRemainingAttributes(model, q, lodLevel, face);

        uint vBase = localId * 4;

        for (uint c = 0; c < 4; c++) {
            uint vIdx = vBase + c;
            gl_MeshVerticesNV[vIdx].gl_Position = getQuadCornerPos(quad, c);
            #ifndef USE_NV_BARRY
            OUT[vIdx].uv = getCornerUV(quad, c);
            #endif
            OUT[vIdx].interData = attributeData;
        }

        uint triBase = localId * 2;
        gl_PrimitiveIndicesNV[triBase * 3 + 0] = vBase + 1;
        gl_PrimitiveIndicesNV[triBase * 3 + 1] = vBase + 2;
        gl_PrimitiveIndicesNV[triBase * 3 + 2] = vBase + 0;

        gl_PrimitiveIndicesNV[triBase * 3 + 3] = vBase + 1;
        gl_PrimitiveIndicesNV[triBase * 3 + 4] = vBase + 3;
        gl_PrimitiveIndicesNV[triBase * 3 + 5] = vBase + 2;
    }

    if (localId == 0) {
        gl_PrimitiveCountNV = activeQuads * 2;
    }
}

#ifndef TAA_PATCH
vec2 taaShift() { return vec2(0.0); }
#endif
