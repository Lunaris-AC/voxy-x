#version 460 core
#extension GL_NV_mesh_shader : require
#extension GL_ARB_gpu_shader_int64 : enable

layout(local_size_x = 1) in;

layout(binding = 0, std140) uniform SceneUniform {
    mat4 MVP;
    ivec3 baseSectionPos;
    uint frameId;
    vec3 cameraSubPos;
};

// Not read by this shader (the fused task/mesh path doesn't need a per-draw
// command offset or draw-type split), but MeshShaderSectionRenderer.renderTerrain
// uploads to locations 0/1 unconditionally regardless of which task shader
// variant is bound. Without these declared here, the driver is free to assign
// locations 0/1 to unrelated uniforms in the linked program (e.g. a bindless
// sampler in mesh_fused.glsl/quads.frag), causing a type mismatch at upload.
layout(location = 0) uniform uint commandOffset;
layout(location = 1) uniform uint drawType;

#import <voxy:lod/section.glsl>

// Binding 2: Section metadata buffer
layout(binding = 2, std430) readonly restrict buffer SectionBuffer {
    SectionMeta sectionData[];
};

// Binding 6: Indirect section lookup buffer (render list from traversal)
layout(binding = 6, std430) readonly restrict buffer TaskIndirectSectionLookup {
    uint sectionCount;
    uint indirectLookup[];
};

struct FaceRange {
    uint quadStart;
    uint quadCount;
};

// Task payload sent to mesh shader workgroups
taskNV out TaskPayload {
    uint sectionId;
    uint drawId;
    FaceRange ranges[7];
    uint rangeCount;
} taskOut;

void main() {
    uint drawIndex = gl_WorkGroupID.x;

    if (drawIndex >= sectionCount) {
        gl_TaskCountNV = 0;
        return;
    }

    uint sectionId = indirectLookup[drawIndex];
    SectionMeta meta = sectionData[sectionId];

    uint detail = extractDetail(meta);
    ivec3 ipos = extractPosition(meta);

    uvec4 counts = meta.b;
    ivec3 relative = ipos - (baseSectionPos >> detail);

    // Compute backface culling mask (facing direction test)
    uint msk = 0;
    msk |= uint(((counts.y      & 0xFFFFu) != 0) && (relative.y > -1)) << 0; // Down
    msk |= uint((((counts.y >> 16) & 0xFFFFu) != 0) && (relative.y < 1))  << 1; // Up
    msk |= uint(((counts.z      & 0xFFFFu) != 0) && (relative.z > -1)) << 2; // North
    msk |= uint((((counts.z >> 16) & 0xFFFFu) != 0) && (relative.z < 1))  << 3; // South
    msk |= uint(((counts.w      & 0xFFFFu) != 0) && (relative.x > -1)) << 4; // West
    msk |= uint((((counts.w >> 16) & 0xFFFFu) != 0) && (relative.x < 1))  << 5; // East

    msk |= uint(((counts.x >> 16) & 0xFFFFu) != 0) << 6; // Double-sided (always visible)

    uint rangeCount = 0;
    uint totalMeshletGroups = 0;
    
    // Quads in the buffer start after translucent quads
    uint ptr = extractQuadStart(meta) + (counts.x & 0xFFFFu);

    // 1. Double-sided quads
    uint count = (counts.x >> 16) & 0xFFFFu;
    if (count != 0) {
        taskOut.ranges[rangeCount++] = FaceRange(ptr, count);
        totalMeshletGroups += (count + 15) / 16;
    }
    ptr += count;

    // 2. Down quads
    count = counts.y & 0xFFFFu;
    if (count != 0 && (msk & (1u << 0)) != 0) {
        taskOut.ranges[rangeCount++] = FaceRange(ptr, count);
        totalMeshletGroups += (count + 15) / 16;
    }
    ptr += count;

    // 3. Up quads
    count = (counts.y >> 16) & 0xFFFFu;
    if (count != 0 && (msk & (1u << 1)) != 0) {
        taskOut.ranges[rangeCount++] = FaceRange(ptr, count);
        totalMeshletGroups += (count + 15) / 16;
    }
    ptr += count;

    // 4. North quads
    count = counts.z & 0xFFFFu;
    if (count != 0 && (msk & (1u << 2)) != 0) {
        taskOut.ranges[rangeCount++] = FaceRange(ptr, count);
        totalMeshletGroups += (count + 15) / 16;
    }
    ptr += count;

    // 5. South quads
    count = (counts.z >> 16) & 0xFFFFu;
    if (count != 0 && (msk & (1u << 3)) != 0) {
        taskOut.ranges[rangeCount++] = FaceRange(ptr, count);
        totalMeshletGroups += (count + 15) / 16;
    }
    ptr += count;

    // 6. West quads
    count = counts.w & 0xFFFFu;
    if (count != 0 && (msk & (1u << 4)) != 0) {
        taskOut.ranges[rangeCount++] = FaceRange(ptr, count);
        totalMeshletGroups += (count + 15) / 16;
    }
    ptr += count;

    // 7. East quads
    count = (counts.w >> 16) & 0xFFFFu;
    if (count != 0 && (msk & (1u << 5)) != 0) {
        taskOut.ranges[rangeCount++] = FaceRange(ptr, count);
        totalMeshletGroups += (count + 15) / 16;
    }
    ptr += count;

    if (totalMeshletGroups == 0) {
        gl_TaskCountNV = 0;
        return;
    }

    taskOut.sectionId = sectionId;
    taskOut.drawId = drawIndex;
    taskOut.rangeCount = rangeCount;

    gl_TaskCountNV = totalMeshletGroups;
}
