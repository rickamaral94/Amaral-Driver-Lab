#version 450

layout(push_constant) uniform PushConstants {
    float time_seconds;
    uint scene_kind;
    vec2 resolution;
} pc;

layout(location = 0) out vec2 out_uv;
layout(location = 1) flat out uint out_instance;
layout(location = 2) out float out_light;

const vec2 kQuad[6] = vec2[](
    vec2(-1.0, -1.0), vec2(1.0, -1.0), vec2(-1.0, 1.0),
    vec2(-1.0, 1.0), vec2(1.0, -1.0), vec2(1.0, 1.0)
);

void main() {
    uint index = gl_InstanceIndex;
    uint columns = 16u;
    uint rows = 9u;
    uint x = index % columns;
    uint y = (index / columns) % rows;

    vec2 local = kQuad[gl_VertexIndex];
    vec2 cell = vec2((float(x) + 0.5) / float(columns),
                     (float(y) + 0.5) / float(rows));
    vec2 center = cell * 2.0 - 1.0;
    center.y = -center.y;

    float phase = float(index) * 0.173 + pc.time_seconds * (0.55 + float(index % 5u) * 0.07);
    float angle = phase + sin(phase * 0.37) * 0.4;
    mat2 rotation = mat2(cos(angle), -sin(angle), sin(angle), cos(angle));

    float base_scale = pc.scene_kind == 0u ? 0.072 : (pc.scene_kind == 1u ? 0.078 : 0.083);
    float pulse = 0.82 + 0.18 * sin(phase * 1.9 + float(y));
    vec2 scale = vec2(base_scale * pulse, base_scale * 1.55 * pulse);
    vec2 offset = vec2(sin(phase * 0.73), cos(phase * 0.61)) * 0.018;
    if (pc.scene_kind == 2u) {
        offset += vec2(sin(pc.time_seconds * 0.8 + float(y)),
                       cos(pc.time_seconds * 0.6 + float(x))) * 0.025;
    }

    vec2 position = center + offset + rotation * (local * scale);
    float depth_wave = 0.48 + 0.46 * sin(float(index) * 0.39 + pc.time_seconds * 0.71);
    float depth = depth_wave * 1.8 - 0.9;

    gl_Position = vec4(position, depth, 1.0);
    out_uv = local * 0.5 + 0.5;
    out_instance = index;
    out_light = 0.45 + 0.55 * max(0.0, cos(angle) * 0.7 + sin(angle * 0.6) * 0.3);
}
