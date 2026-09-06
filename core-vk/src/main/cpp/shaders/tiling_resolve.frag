#version 450

// Workload 2, subpass 1.
//
// Subpass 0 renders 4x multisampled and resolves into attachment 2; this subpass
// reads that resolved image through an input attachment. That is the pattern a
// tiler is supposed to keep entirely in on-chip memory, and the one where a wrong
// binning decision costs a full round trip through system memory.
//
// The input is single-sampled on purpose: reading the multisampled attachment
// directly would need subpassInputMS and a per-sample loop, which measures the
// shader rather than the binner.

layout(input_attachment_index = 0, set = 0, binding = 0) uniform subpassInput inColor;

layout(push_constant) uniform Push {
    uint frameIndex;
    uint drawIndex;
} push;

layout(location = 0) out vec4 outColor;

void main() {
    vec4 colour = subpassLoad(inColor);

    // Luminance-driven tone shift. Cheap, but it does consume the input, so the
    // driver cannot treat the resolve target as write-only and skip the store.
    float luma = dot(colour.rgb, vec3(0.2126, 0.7152, 0.0722));
    float phase = float(push.frameIndex) * 0.0009;
    vec3 shifted = mix(colour.rgb, vec3(0.05, 0.07, 0.12), clamp(1.0 - luma, 0.0, 1.0) * 0.6);
    outColor = vec4(shifted * (0.9 + 0.1 * sin(phase + luma * 6.28318)), 1.0);
}
