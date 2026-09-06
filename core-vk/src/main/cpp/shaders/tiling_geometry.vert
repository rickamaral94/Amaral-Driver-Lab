#version 450

// Workload 2, subpass 0. Procedural geometry spread across the whole render
// target so the binner has real work to do: a driver that mis-decides GMEM
// versus system memory shows up here and nowhere in workload 1.

layout(push_constant) uniform Push {
    uint frameIndex;
    uint drawIndex;
} push;

layout(location = 0) out vec3 vColor;
layout(location = 1) out vec3 vNormal;

const float GOLDEN = 2.39996323;

void main() {
    uint triangle = uint(gl_VertexIndex) / 3u;
    uint corner = uint(gl_VertexIndex) % 3u;

    // Phyllotaxis placement: even coverage of the target without a vertex buffer,
    // and no two triangles landing in the same tile.
    float index = float(triangle + push.drawIndex * 1024u);
    float angle = index * GOLDEN;
    float radius = sqrt(index) * 0.021;
    vec2 centre = vec2(cos(angle), sin(angle)) * min(radius, 0.96);

    float size = 0.014 + 0.010 * fract(index * 0.37);
    float spin = angle + float(push.frameIndex) * 0.0031;
    float cornerAngle = spin + float(corner) * 2.0943951;
    vec2 offset = vec2(cos(cornerAngle), sin(cornerAngle)) * size;

    // Depth varies per triangle so early-z and the depth attachment both matter.
    float depth = fract(index * 0.618);

    vColor = vec3(fract(index * 0.113), fract(index * 0.271), fract(index * 0.577));
    vNormal = normalize(vec3(offset, 0.35));

    gl_Position = vec4(centre + offset, depth, 1.0);
}
