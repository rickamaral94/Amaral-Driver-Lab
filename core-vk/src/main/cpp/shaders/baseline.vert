#version 450

// Workload 1 draws a fullscreen triangle and then a quad over it. Geometry comes
// from gl_VertexIndex rather than a vertex buffer, so the scene is identical on
// every device and every run without any host-side data to get out of sync.

layout(push_constant) uniform Push {
    uint frameIndex;
    uint drawIndex;
} push;

layout(location = 0) out vec2 vUv;
layout(location = 1) out vec3 vTint;

void main() {
    // Oversized triangle covering the viewport: (-1,-1), (3,-1), (-1,3).
    vec2 position = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2) * 2.0 - 1.0;
    vUv = position * 0.5 + 0.5;

    // A deterministic per-draw tint so consecutive draws are not identical work.
    float phase = float(push.drawIndex) * 0.61803398875 + float(push.frameIndex) * 0.017453;
    vTint = vec3(fract(phase), fract(phase * 1.7), fract(phase * 2.3));

    gl_Position = vec4(position, 0.0, 1.0);
}
