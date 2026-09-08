#version 450

// Deliberately cheap. Workload 1 measures submit and present overhead, so the
// fragment cost must stay far below the driver cost it is meant to expose.

layout(location = 0) in vec2 vUv;
layout(location = 1) in vec3 vTint;

layout(location = 0) out vec4 outColor;

void main() {
    float ring = sin(vUv.x * 12.0) * cos(vUv.y * 12.0);
    outColor = vec4(vTint * (0.5 + 0.5 * ring), 1.0);
}
